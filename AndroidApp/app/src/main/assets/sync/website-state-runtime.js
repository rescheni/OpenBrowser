(function websiteStateRuntime() {
  'use strict';
  const LIMIT = 2 * 1024 * 1024, MAX_ROWS = 10000;
  const fail = message => { throw new Error(message); };
  function bounded(value) { if (new TextEncoder().encode(JSON.stringify(value)).length > LIMIT) fail('网站存储快照超过 2 MB'); return value; }
  // Tagged values preserve structured-clone types. Unsupported/cyclic values fail the
  // whole snapshot rather than silently changing application data.
  async function encode(v, seen = new Set(), depth = 0) {
    if (depth > 64) fail('网站存储嵌套过深');
    if (v === null || typeof v === 'string' || typeof v === 'boolean') return ['primitive', v];
    if (typeof v === 'number') return ['number', Number.isNaN(v) ? 'NaN' : v === Infinity ? 'Infinity' : v === -Infinity ? '-Infinity' : Object.is(v, -0) ? '-0' : v];
    if (v === undefined) return ['undefined'];
    if (typeof v === 'bigint') return ['bigint', String(v)];
    if (typeof v !== 'object' || seen.has(v)) fail('网站存储含不支持或循环引用的值');
    seen.add(v);
    const next = x => encode(x, seen, depth + 1);
    try {
      if (v instanceof Date) return ['date', v.toISOString()];
      if ((v instanceof ArrayBuffer || ArrayBuffer.isView(v)) && v.byteLength > LIMIT) fail('网站存储二进制值超过容量限制');
      if (v instanceof ArrayBuffer) return ['buffer', Array.from(new Uint8Array(v))];
      if (ArrayBuffer.isView(v)) return ['view', v.constructor.name, Array.from(new Uint8Array(v.buffer, v.byteOffset, v.byteLength))];
      if (typeof Blob !== 'undefined' && v instanceof Blob) {
        if (v.size > LIMIT) fail('网站存储二进制值超过容量限制');
        if (typeof File !== 'undefined' && v instanceof File) return ['file', v.name, v.type, v.lastModified, Array.from(new Uint8Array(await v.arrayBuffer()))];
        return ['blob', v.type, Array.from(new Uint8Array(await v.arrayBuffer()))];
      }
      if (v instanceof Map) { const a = []; for (const [k, value] of v) a.push([await next(k), await next(value)]); return ['map', a]; }
      if (v instanceof Set) { const a = []; for (const item of v) a.push(await next(item)); return ['set', a]; }
      if (Array.isArray(v)) { const a = []; for (let i = 0; i < v.length; i++) a.push(i in v ? await next(v[i]) : ['hole']); return ['array', a]; }
      if (Object.getPrototypeOf(v) !== Object.prototype && Object.getPrototypeOf(v) !== null) fail('网站存储含不支持的类型');
      const a = []; for (const k of Object.keys(v).sort()) a.push([k, await next(v[k])]); return ['object', a];
    } finally { seen.delete(v); }
  }
  function decode(v, depth = 0) {
    if (depth > 64 || !Array.isArray(v)) fail('网站存储编码无效');
    const next = x => decode(x, depth + 1);
    switch (v[0]) {
      case 'primitive': if (v[1] !== null && !['string', 'boolean'].includes(typeof v[1])) fail('网站存储值无效'); return v[1];
      case 'undefined': return undefined;
      case 'number': { if(typeof v[1] === 'number')return v[1];const numbers={NaN:NaN,Infinity,'-Infinity':-Infinity,'-0':-0};if(!Object.prototype.hasOwnProperty.call(numbers,v[1]))fail('网站存储数字无效');return numbers[v[1]]; }
      case 'bigint': return BigInt(v[1]);
      case 'date': { const d = new Date(v[1]); if (!Number.isFinite(d.getTime())) fail('网站存储日期无效'); return d; }
      case 'buffer': return bytes(v[1]).buffer;
      case 'view': { const b = bytes(v[2]).buffer; const types = {Int8Array, Uint8Array, Uint8ClampedArray, Int16Array, Uint16Array, Int32Array, Uint32Array, Float32Array, Float64Array, DataView, BigInt64Array, BigUint64Array}; const C = Object.prototype.hasOwnProperty.call(types,v[1]) ? types[v[1]] : null; if (!C) fail('网站存储二进制类型无效'); return new C(b); }
      case 'blob': return new Blob([bytes(v[2])], {type: v[1]});
      case 'file': return new File([bytes(v[4])], v[1], {type: v[2], lastModified: v[3]});
      case 'array': { const a = new Array(v[1].length); v[1].forEach((x, i) => { if (x[0] !== 'hole') a[i] = next(x); }); return a; }
      case 'object': { const o = {}; for (const [k, x] of v[1]) Object.defineProperty(o, k, {value: next(x), writable: true, enumerable: true, configurable: true}); return o; }
      case 'map': return new Map(v[1].map(([k, x]) => [next(k), next(x)]));
      case 'set': return new Set(v[1].map(next));
      default: fail('网站存储编码无效');
    }
  }
  function bytes(a) { if (!Array.isArray(a) || a.length > LIMIT || a.some(x => !Number.isInteger(x) || x < 0 || x > 255)) fail('网站存储二进制值无效'); return new Uint8Array(a); }
  function request(r) { return new Promise((resolve, reject) => { r.onsuccess = () => resolve(r.result); r.onerror = () => reject(new Error('网站存储读取失败')); r.onblocked = () => reject(new Error('网站存储被其他页面占用，请关闭页面重试')); }); }
  async function capture() {
    if (!/^https?:$/.test(location.protocol) || typeof indexedDB.databases !== 'function') fail('当前网页或内核不支持网站存储同步');
    const result = {origin: location.origin, localStorage: [], databases: []};
    for (let i = 0; i < localStorage.length; i++) { const k = localStorage.key(i); result.localStorage.push([k, localStorage.getItem(k)]); }
    result.localStorage.sort((a,b) => a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0);
    let count = 0;
    const names = (await indexedDB.databases()).filter(x => x.name != null).sort((a,b) => a.name < b.name ? -1 : a.name > b.name ? 1 : 0);
    if (names.length > 50) fail('网站数据库过多');
    for (const info of names) {
      const db = await request(indexedDB.open(info.name));
      try {
        const item = {name: db.name, version: db.version, stores: []};
        // Read all stores in one readonly transaction, scheduling every cursor before
        // awaiting. Blob serialization happens afterwards, outside the transaction.
        const raw = await new Promise((resolve, reject) => {
          if (!db.objectStoreNames.length) { resolve([]); return; }
          const tx = db.transaction(Array.from(db.objectStoreNames), 'readonly'), stores = [];
          tx.onabort = tx.onerror = () => reject(new Error('网站存储读取失败'));
          tx.oncomplete = () => resolve(stores);
          for (const name of Array.from(db.objectStoreNames).sort()) {
            const s = tx.objectStore(name), rows = [], indexes = Array.from(s.indexNames).sort().map(n => { const i = s.index(n); return {name: n, keyPath: i.keyPath, unique: i.unique, multiEntry: i.multiEntry}; });
            stores.push({name, keyPath: s.keyPath, autoIncrement: s.autoIncrement, indexes, rows});
            const cursor = s.openCursor();
            cursor.onsuccess = () => { const c = cursor.result; if (!c) return; if (++count > MAX_ROWS) { tx.abort(); return; } rows.push({key: c.primaryKey, value: c.value}); c.continue(); };
          }
        });
        for (const s of raw) { const rows = []; for (const row of s.rows) { rows.push({key: await encode(row.key), value: await encode(row.value)}); bounded(rows); } item.stores.push({...s, rows}); }
        result.databases.push(item); bounded(result);
      } finally { db.close(); }
    }
    return bounded(result);
  }
  function validate(snapshot) {
    bounded(snapshot);
    if (snapshot.origin !== location.origin || !Array.isArray(snapshot.localStorage) || !Array.isArray(snapshot.databases) || snapshot.databases.length > 50) fail('网站存储来源或格式无效');
    const keys = new Set(), names = new Set(); let rows = 0;
    for (const pair of snapshot.localStorage) { if (!Array.isArray(pair) || pair.length !== 2 || pair.some(x => typeof x !== 'string') || keys.has(pair[0])) fail('网站存储键无效'); keys.add(pair[0]); }
    const keyPath = p => p === null || typeof p === 'string' || Array.isArray(p) && p.every(x => typeof x === 'string');
    for (const d of snapshot.databases) {
      if (typeof d.name !== 'string' || names.has(d.name) || !Number.isSafeInteger(d.version) || d.version < 1 || !Array.isArray(d.stores)) fail('网站数据库格式无效'); names.add(d.name);
      const stores = new Set();
      for (const s of d.stores) {
        if (typeof s.name !== 'string' || stores.has(s.name) || !keyPath(s.keyPath) || typeof s.autoIncrement !== 'boolean' || !Array.isArray(s.indexes) || !Array.isArray(s.rows)) fail('网站数据表格式无效'); stores.add(s.name);
        const indexes = new Set();
        for (const i of s.indexes) { if (typeof i.name !== 'string' || indexes.has(i.name) || !keyPath(i.keyPath) || i.keyPath === null || typeof i.unique !== 'boolean' || typeof i.multiEntry !== 'boolean') fail('网站索引格式无效'); indexes.add(i.name); }
        for (const r of s.rows) { if (++rows > MAX_ROWS) fail('网站存储记录过多'); decode(r.key); decode(r.value); }
      }
    }
  }
  function schema(d) { return JSON.stringify({name:d.name,version:d.version,stores:d.stores.map(s => ({name:s.name,keyPath:s.keyPath,autoIncrement:s.autoIncrement,indexes:s.indexes}))}); }
  async function replace(snapshot) {
    for (const d of snapshot.databases) {
      const r = indexedDB.open(d.name, d.version);
      r.onupgradeneeded = () => {
        try { const db = r.result; if (db.objectStoreNames.length) throw new Error('网站数据库结构版本不同，请先手动清理该网站后重试');
          for (const s of d.stores) { const store = db.createObjectStore(s.name, {keyPath:s.keyPath,autoIncrement:s.autoIncrement}); for (const i of s.indexes) store.createIndex(i.name,i.keyPath,{unique:i.unique,multiEntry:i.multiEntry}); }
        } catch (_) { r.transaction.abort(); }
      };
      const db = await request(r);
      try {
        const actual = {name:db.name,version:db.version,stores:[]};
        if (db.objectStoreNames.length) {
          const tx = db.transaction(Array.from(db.objectStoreNames),'readonly');
          for (const name of Array.from(db.objectStoreNames).sort()) { const st = tx.objectStore(name); actual.stores.push({name,keyPath:st.keyPath,autoIncrement:st.autoIncrement,indexes:Array.from(st.indexNames).sort().map(n => {const i=st.index(n);return {name:n,keyPath:i.keyPath,unique:i.unique,multiEntry:i.multiEntry};})}); }
        }
        if (schema(actual) !== schema(d)) fail('网站数据库结构版本不同，请先手动清理该网站后重试');
        if (d.stores.length) await new Promise((resolve,reject) => {
          const tx = db.transaction(d.stores.map(s => s.name),'readwrite');
          tx.oncomplete=resolve; tx.onabort=tx.onerror=()=>reject(new Error('网站存储恢复失败'));
          try { for (const s of d.stores) { const st=tx.objectStore(s.name); st.clear(); for (const row of s.rows) {const value=decode(row.value); if(s.keyPath===null)st.put(value,decode(row.key));else st.put(value);} } }
          catch(e) {tx.abort();reject(e);}
        });
      } finally {db.close();}
    }
    localStorage.clear(); for(const [key,value] of snapshot.localStorage)localStorage.setItem(key,value);
  }
  async function restore(snapshot) {
    validate(snapshot);
    const backup = await capture(); // Never destroy a local database we cannot back up.
    for(const d of snapshot.databases){const previous=backup.databases.find(x=>x.name===d.name);if(previous&&schema(previous)!==schema(d))fail('网站数据库结构版本不同，请先手动清理该网站后重试');}
    try { await replace(snapshot); }
    catch (_) { try { await replace(backup); for(const d of snapshot.databases)if(!backup.databases.some(x=>x.name===d.name))await replace({origin:snapshot.origin,localStorage:backup.localStorage,databases:[{...d,stores:d.stores.map(s=>({...s,rows:[]}))}]}); } catch (_) { fail('网站存储恢复失败，回滚未完成；保留同步文件后重试'); } fail('网站存储恢复失败，已回滚本地数据'); }
    return true;
  }
  return {capture, restore, encode, decode, validate};
})()
