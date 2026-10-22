'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs/promises');
const path = require('path');
const os = require('os');
const {source, validateSites, validateCredentials, WebsiteStateStore, enrich, restorePending} = require('./website-state');
const portable = require('./portable-sync');
const merge = require('./sync-merge');
const {SyncController} = require('./sync-controller');
const fixture = JSON.parse(require('fs').readFileSync(path.join(__dirname, '../../AndroidApp/app/src/test/resources/portable-sync-vector.txt'), 'utf8').trim().split('\n')[2]);
const copy = x => structuredClone(x);
const site = (value = 'token') => ({origin:'https://example.com', localStorage:[['session',value]], databases:[]});
const profile = () => ({...copy(fixture.profiles[0]), siteData:[site()], credentials:[{origin:'https://example.com', username:'alice', password:'test-only-password'}]});
const body = p => ({...copy(fixture),version:2,profiles:[p || profile()]});
const remote = b => ({...copy(b),sync:{version:1,space:'unit',revision:'test'}});
const options = {space:'unit',includeCookies:true,includeSiteData:true,includeCredentials:true,acceptNew:true};
function localStorageMock() {
  const map = new Map();return {map,get length(){return map.size},key:i=>[...map.keys()][i],getItem:k=>map.get(k)??null,setItem:(k,v)=>map.set(String(k),String(v)),clear:()=>map.clear()};
}
function runtime(idb={databases:async()=>[]}, storage=localStorageMock()) {return {api:new Function('location','indexedDB','localStorage','return '+source)({origin:'https://example.com',protocol:'https:'},idb,storage),storage};}

test('the desktop and Android execute identical website-state runtime bytes',async()=>{
  assert.equal(source,await fs.readFile(path.join(__dirname,'../../AndroidApp/app/src/main/assets/sync/website-state-runtime.js'),'utf8'));
});
test('structured values round-trip without type loss or prototype mutation',async()=>{
  const {api}=runtime();
  const sparse=new Array(3);sparse[1]=undefined;
  const value={date:new Date('2026-01-01T00:00:00Z'), bytes:new Uint16Array([1,65535]), blob:new Blob(['blob'],{type:'text/plain'}), file:new File(['file'],'x.txt',{lastModified:1000}), map:new Map([[7,new Set([9n,undefined])]]),array:sparse,numbers:[NaN,Infinity,-Infinity,-0],buffer:new Uint8Array([2,3]).buffer};
  Object.defineProperty(value,'__proto__',{value:{polluted:'no'},enumerable:true});
  const encoded=await api.encode(value),decoded=api.decode(JSON.parse(JSON.stringify(encoded)));
  assert.deepEqual(await api.encode(decoded),encoded);
  assert.equal(decoded.date instanceof Date,true);assert.equal(decoded.bytes instanceof Uint16Array,true);
  assert.equal(0 in decoded.array,false);assert.equal(1 in decoded.array,true);
  assert.equal({}.polluted,undefined);assert.equal(Object.getPrototypeOf(decoded),Object.prototype);
});
test('unsupported and cyclic values reject the entire snapshot',async()=>{
  const {api}=runtime();const cycle={};cycle.self=cycle;
  await assert.rejects(api.encode(cycle),/循环/);
  await assert.rejects(api.encode(new WeakMap()),/类型/);
  await assert.rejects(api.encode(()=>{}),/不支持/);
});
test('origin, schema, payload and credential validation reject malformed data before import',()=>{
  assert.throws(()=>validateSites([{...site(),origin:'https://example.com/'}]),/来源/);
  assert.throws(()=>validateSites([site(),site()]),/重复/);
  assert.throws(()=>validateSites([{...site(),localStorage:[['x','a'],['x','b']]}]),/键/);
  assert.throws(()=>validateSites([{...site(),localStorage:[['x','a'.repeat(2*1024*1024)]]}]),/2 MB/);
  assert.throws(()=>validateCredentials([{origin:'https://u:p@example.com',username:'a',password:'b'}]),/来源/);
  assert.throws(()=>validateCredentials([{origin:'https://example.com',username:'a',password:'b',extra:'secret'}]),/字段/);
  assert.throws(()=>portable.validateBundle({...body(),version:1}),/新版/);
});
test('localStorage restore enforces exact origin and replaces removed keys',async()=>{
  const {api,storage}=runtime();storage.setItem('removed','old');
  await api.restore(site('new'));
  assert.deepEqual([...storage.map],[['session','new']]);
  await assert.rejects(api.restore({...site(),origin:'https://other.example'}),/来源/);
  assert.deepEqual([...storage.map],[['session','new']]);
});
test('localStorage quota failure restores the original local data',async()=>{
  const {api,storage}=runtime();storage.setItem('existing','keep');const set=storage.setItem;
  storage.setItem=(k,v)=>{if(k==='session')throw Error('quota');set(k,v)};
  await assert.rejects(api.restore(site()),/已回滚/);
  assert.deepEqual([...storage.map],[['existing','keep']]);
});
test('independent config, website data and password edits merge without conflict',()=>{
  const base=body(),l=copy(base),r=remote(base),id=l.profiles[0].id;
  l.profiles[0].name='local config';r.profiles[0].siteData=[site('remote-token')];r.profiles[0].credentials[0].password='remote-password';
  const plan=merge.plan(l,r,{bases:{[id]:merge.digests(base.profiles[0])}},options);
  assert.equal(plan.conflicts.length,0);assert.equal(plan.body.version,2);
  const result=plan.body.profiles[0];assert.equal(result.name,'local config');assert.equal(result.siteData[0].localStorage[0][1],'remote-token');assert.equal(result.credentials[0].password,'remote-password');
});
test('website and password conflicts require version-bound review; password deletions sync',()=>{
  const base=body(),l=copy(base),r=remote(base),id=l.profiles[0].id;
  l.profiles[0].siteData=[site('left')];r.profiles[0].siteData=[site('right')];l.profiles[0].credentials[0].password='left';r.profiles[0].credentials=[];
  const state={bases:{[id]:merge.digests(base.profiles[0])}},first=merge.plan(l,r,state,options);
  assert.deepEqual(first.conflicts.map(c=>c.section),['siteData','credentials']);
  state.decisions=merge.resolve(first.conflicts,'remote');const next=merge.plan(l,r,state,options);
  assert.equal(next.conflicts.length,0);assert.deepEqual(next.imports[0].credentials,[]);
  r.profiles[0].siteData=[site('third')];assert.equal(merge.plan(l,r,state,options).conflicts[0].section,'siteData');
});
test('opt-out preserves remote sections and does not establish their baselines',()=>{
  const l=body(),r=remote(body());l.profiles[0].siteData=[];l.profiles[0].credentials=[];
  const result=merge.plan(l,r,{}, {...options,includeSiteData:false,includeCredentials:false});
  assert.deepEqual(result.body.profiles[0].siteData,r.profiles[0].siteData);assert.deepEqual(result.body.profiles[0].credentials,r.profiles[0].credentials);
  assert.equal(result.bases[l.profiles[0].id].siteData,undefined);assert.equal(result.bases[l.profiles[0].id].credentials,undefined);
});
test('encrypted v2 exchange round-trips site state and password vault',async()=>{
  const b=body(),encrypted=await portable.seal(b,'test-password-only');
  assert.equal(encrypted.includes(Buffer.from('test-only-password')),false);
  assert.deepEqual(await portable.open(encrypted,'test-password-only'),b);
});
const secure={isEncryptionAvailable:()=>true,getSelectedStorageBackend:()=> 'test-keychain',encryptString:s=>Buffer.from(s).map(b=>b^123),decryptString:b=>Buffer.from(b).map(b=>b^123).toString()};
test('encrypted store consumes each origin once, keeps other sites and never captures over pending imports',async()=>{
  const dir=await fs.mkdtemp(path.join(os.tmpdir(),'ob-sites-'));const app={getPath:()=>dir};const store=new WebsiteStateStore(app,secure),id='unit';
  try {
    await store.capture(id,[site('old'),{...site('second'),origin:'https://second.example'}]);
    await store.queueImport(id,profile(),options);
    await store.capture(id,[site('stale')]);
    assert.equal((await store.export(id)).siteData[0].localStorage[0][1],'token');
    const d=await store.read(id);await store.applied(id,'stale-revision',site());assert.ok((await store.read(id)).pending);
    await store.applied(id,d.pending.revision,site());assert.equal((await store.read(id)).pending,undefined);
    assert.equal((await store.export(id)).siteData.length,2);
    await store.capture('same-data',[site()]);
    await store.queueImport('same-data',profile(),options);
    assert.equal((await store.read('same-data')).pending,undefined,'unchanged config imports must not replay website state');
    await store.queueImport('same-data',profile(),{...options,forceSiteData:true});
    assert.ok((await store.read('same-data')).pending,'manual recovery can explicitly reapply an identical snapshot');
    const raw=await fs.readFile(store.file(id));assert.equal(raw.includes(Buffer.from('test-only-password')),false);
    await store.clearSites(id);assert.equal((await store.export(id)).siteData.length,0);assert.equal((await store.export(id)).credentials.length,1);
  }finally{await fs.rm(dir,{recursive:true,force:true});}
});
test('two controllers exchange encrypted website state and password edits in both directions',async()=>{
  const dir=await fs.mkdtemp(path.join(os.tmpdir(),'ob-sites-controllers-'));const controllers=[];
  try {
    const locals=[body(),body()];const id=locals[0].profiles[0].id;
    const config={...options,provider:'local',localFile:path.join(dir,'shared.obsync'),password:'test-password-only',interval:60,enabled:true};
    for(let i=0;i<2;i++){
      const controller=await new SyncController({file:path.join(dir,`settings-${i}.enc`),safeStorage:secure,readLocal:async()=>copy(locals[i]),excluded:()=>[],applyLocal:async profiles=>{for(const p of profiles)locals[i].profiles[0]=copy(p)}}).init();
      controllers.push(controller);await controller.save(config);
    }
    await controllers[0].run();await controllers[1].run();
    locals[0].profiles[0].siteData=[site('from-desktop')];await controllers[0].run();await controllers[1].run();
    assert.equal(locals[1].profiles[0].siteData[0].localStorage[0][1],'from-desktop');
    locals[1].profiles[0].credentials[0].password='from-phone';await controllers[1].run();await controllers[0].run();
    assert.equal(locals[0].profiles[0].credentials[0].password,'from-phone');
    await controllers[0].save({...config,includeCredentials:false});assert.equal(controllers[0].document.state.bases[id].credentials,undefined);
    await controllers[0].save(config);assert.equal(controllers[0].document.state.bases[id].credentials,undefined);
    const disk=await fs.readFile(config.localFile);assert.equal(disk.includes(Buffer.from('from-phone')),false);
  }finally{controllers.forEach(c=>c.close());await fs.rm(dir,{recursive:true,force:true});}
});

// Transactional test double: commits readwrite changes only on completion.
// Real WebView/Chromium compatibility is still covered by the documented device check.
function memoryIDB() {
  const databases = new Map();let failPuts=0;
  const keyId = k => JSON.stringify(k);
  function dbHandle(model) {
    return {name:model.name,version:model.version,get objectStoreNames(){return [...model.stores.keys()]},close(){},
      createObjectStore(name,options){const s={name,...options,indexes:new Map(),rows:new Map()};model.stores.set(name,s);return {createIndex:(name,keyPath,o)=>s.indexes.set(name,{name,keyPath,...o})}},
      transaction(names,mode){
        const tx={aborted:false};const working=mode==='readwrite'?structuredClone(model.stores):model.stores;let cursors=0,complete=false;
        const finish=()=>{if(complete||tx.aborted||cursors)return;complete=true;if(mode==='readwrite')model.stores=working;tx.oncomplete?.()};
        tx.abort=()=>{if(tx.aborted||complete)return;tx.aborted=true;setImmediate(()=>tx.onabort?.())};
        tx.objectStore=name=>{
          const s=working.get(name);if(!s)throw Error('missing store');
          return {name:s.name,keyPath:s.keyPath,autoIncrement:s.autoIncrement,indexNames:[...s.indexes.keys()],index:n=>s.indexes.get(n),
            clear(){s.rows.clear()},put(value,key){if(failPuts>0){failPuts--;tx.abort();return}if(s.keyPath!==null)key=value[s.keyPath];s.rows.set(keyId(key),{key:structuredClone(key),value:structuredClone(value)})},
            openCursor(){
              const req={};const rows=[...s.rows.values()];let i=0;cursors++;
              const emit=()=>{if(tx.aborted)return;const row=rows[i++];if(row){req.result={primaryKey:row.key,value:row.value,continue:()=>setImmediate(emit)};req.onsuccess?.()}else{req.result=null;req.onsuccess?.();cursors--;setImmediate(finish)}};
              setImmediate(emit);return req;
            }};
        };
        setImmediate(finish);return tx;
      }};
  }
  return {models:databases,failNextPut(){failPuts++},async databases(){return [...databases.values()].map(d=>({name:d.name,version:d.version}))},
    open(name,version){const req={};setImmediate(()=>{
      let model=databases.get(name);
      if(model&&version&&version<model.version){req.onerror?.();return}
      if(!model||version&&version>model.version){
        const previous=model;model=model?structuredClone(model):{name,version:version||1,stores:new Map()};model.version=version||model.version;
        req.result=dbHandle(model);let aborted=false;req.transaction={abort(){aborted=true}};req.onupgradeneeded?.();
        if(aborted){if(previous)databases.set(name,previous);req.onerror?.();return}databases.set(name,model);
      }
      req.result=dbHandle(model);req.onsuccess?.();
    });return req;}
  };
}
async function databaseSite(api,text='incoming') {
  return {...site(text),databases:[{name:'app',version:3,stores:[{name:'sessions',keyPath:'id',autoIncrement:false,indexes:[{name:'byUser',keyPath:'user',unique:true,multiEntry:false}],rows:[{key:await api.encode(1),value:await api.encode({id:1,user:'alice',token:text,bytes:new Uint8Array([1,2,3])})}]}]}]};
}
test('IndexedDB schema, indexes, typed values and row deletions restore through a single write transaction',async()=>{
  const idb=memoryIDB(),{api}=runtime(idb),incoming=await databaseSite(api);
  await api.restore(incoming);
  assert.deepEqual(await api.capture(),incoming);
  const empty=copy(incoming);empty.databases[0].stores[0].rows=[];
  await api.restore(empty);assert.deepEqual(await api.capture(),empty);
});
test('IndexedDB write failure rolls back earlier values and keeps localStorage',async()=>{
  const idb=memoryIDB(),{api}=runtime(idb),original=await databaseSite(api,'original');
  await api.restore(original);idb.failNextPut();
  await assert.rejects(api.restore(await databaseSite(api,'replacement')),/已回滚/);
  assert.deepEqual(await api.capture(),original);
});
test('incompatible IndexedDB versions reject before modifying local data',async()=>{
  const idb=memoryIDB(),{api}=runtime(idb),original=await databaseSite(api,'original');await api.restore(original);
  const changed=await databaseSite(api,'replacement');changed.databases[0].version++;
  await assert.rejects(api.restore(changed),/结构版本/);assert.deepEqual(await api.capture(),original);
});
test('origin omissions preserve unrelated databases while row deletions are applied',async()=>{
  const idb=memoryIDB(),{api}=runtime(idb),original=await databaseSite(api);await api.restore(original);
  await api.restore(site('local-only'));
  assert.equal((await api.capture()).databases[0].stores[0].rows.length,1);
});

test('CDP restoration intercepts its bootstrap before applying and closes targets on failure',async t=>{
  const cdp=require('../cdp');let applied=0,closed=0,navUrl='';const calls=[];
  const conn={onEvent:null,close(){closed++},async command(method,params={},opts={}){
    calls.push(method);
    if(method==='Target.createTarget')return {targetId:'target'};
    if(method==='Target.attachToTarget')return {sessionId:'session'};
    if(method==='Page.navigate'){
      navUrl=params.url;setImmediate(()=>conn.onEvent({sessionId:'session',method:'Fetch.requestPaused',params:{requestId:'request',resourceType:'Document',request:{url:navUrl}}}));return {};
    }
    if(method==='Fetch.fulfillRequest'){setImmediate(()=>conn.onEvent({sessionId:'session',method:'Page.loadEventFired',params:{}}));return {}}
    if(method==='Runtime.evaluate')return params.expression.includes('.restore(')?{exceptionDetails:{text:'fail'}}:{result:{value:true}};
    return {};
  }};
  t.mock.method(cdp,'browserSocket',async()=> 'ws://test-only');t.mock.method(cdp,'connect',async()=>conn);
  await assert.rejects(restorePending(1,{read:async()=>({pending:{revision:'r',sites:[site()]}}),applied:async()=>{applied++}},'unit'),/待应用/);
  assert.equal(applied,0);assert.equal(closed,1);assert.ok(calls.includes('Target.closeTarget'));
  assert.ok(calls.indexOf('Fetch.fulfillRequest')<calls.indexOf('Runtime.evaluate'));
  assert.ok(navUrl.startsWith('https://example.com/.well-known/'));
});
