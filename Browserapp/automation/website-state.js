'use strict';
const fs = require('fs/promises');
const path = require('path');
const crypto = require('crypto');
const vm = require('vm');
const source = require('fs').readFileSync(path.join(__dirname, 'website-state-runtime.js'), 'utf8');
const MAX_BYTES = 6 * 1024 * 1024;
function origin(value) {
  const u = new URL(value);
  if (!['https:', 'http:'].includes(u.protocol) || u.username || u.password || u.origin !== value) throw new Error('网站存储来源无效');
  return u.origin;
}
function validateSites(sites) {
  if (!Array.isArray(sites) || sites.length > 50 || Buffer.byteLength(JSON.stringify(sites)) > MAX_BYTES) throw new Error('网站存储快照超过容量限制');
  const seen = new Set();
  for (const s of sites) {
    origin(s.origin); if (seen.has(s.origin)) throw new Error('网站存储来源重复'); seen.add(s.origin);
    const runtime = vm.runInNewContext(source, { location: {origin: s.origin}, TextEncoder, Blob, File: global.File });
    runtime.validate(s);
  }
  return sites;
}
function validateCredentials(items) {
  if (!Array.isArray(items) || items.length > 500) throw new Error('密码库数量无效');
  const seen = new Set();
  for (const c of items) {
    origin(c.origin);
    if (typeof c.username !== 'string' || c.username.length > 1000 || typeof c.password !== 'string' || c.password.length > 10000) throw new Error('密码库字段无效');
    const key = JSON.stringify([c.origin, c.username]); if (seen.has(key)) throw new Error('密码库条目重复'); seen.add(key);
    if (Object.keys(c).some(k => !['origin', 'username', 'password'].includes(k))) throw new Error('密码库字段无效');
  }
  return items;
}
class WebsiteStateStore {
  constructor(app, safeStorage) { this.directory = path.join(app.getPath('userData'), 'website-sync'); this.safeStorage = safeStorage; this.queue = Promise.resolve(); }
  file(id) { if (!/^[A-Za-z0-9_-]{1,128}$/.test(id)) throw new Error('环境 ID 无效'); return path.join(this.directory, id + '.enc'); }
  available() { return this.safeStorage?.isEncryptionAvailable?.() && this.safeStorage.getSelectedStorageBackend?.() !== 'basic_text'; }
  async read(id) {
    try { const bytes = await fs.readFile(this.file(id)); if (bytes.length > 8 * 1024 * 1024 || !this.available()) throw new Error('网站存储密钥库不可用'); return JSON.parse(this.safeStorage.decryptString(bytes)); }
    catch (e) { if (e.code === 'ENOENT') return { saved: [], credentials: [] }; throw e; }
  }
  async update(id, change) {
    const operation = this.queue.catch(() => {}).then(async () => {
      if (!this.available()) throw new Error('系统密钥库不可用，无法保存网站存储');
      const doc = await this.read(id); change(doc);
      const plain = JSON.stringify(doc); if (Buffer.byteLength(plain) > 7 * 1024 * 1024) throw new Error('网站存储快照超过容量限制');
      await fs.mkdir(this.directory, {recursive: true}); const file = this.file(id), temp = file + '.tmp';
      const out = await fs.open(temp, 'w', 0o600); try { await out.writeFile(this.safeStorage.encryptString(plain)); await out.sync(); } finally { await out.close(); }
      await fs.rename(temp, file);
    }); this.queue = operation; return operation;
  }
  async export(id) {
    await this.queue.catch(() => {});
    const doc = await this.read(id), sites = new Map((doc.saved || []).map(s => [s.origin, s]));
    for (const s of doc.pending?.sites || []) sites.set(s.origin, s);
    return { siteData: [...sites.values()].sort((a,b) => a.origin < b.origin ? -1 : a.origin > b.origin ? 1 : 0), credentials: doc.credentials || [] };
  }
  async capture(id, sites) {
    validateSites(sites);
    return this.update(id, d => { const map = new Map((d.saved || []).map(s => [s.origin, s])); for (const s of sites) if (!d.pending?.sites.some(p => p.origin === s.origin)) map.set(s.origin, s); d.saved = validateSites([...map.values()]); });
  }
  async queueImport(id, profile, config) {
    if (config.includeSiteData) validateSites(profile.siteData || []);
    if (config.includeCredentials) validateCredentials(profile.credentials || []);
    return this.update(id, d => {
      if (config.includeSiteData) {
        const current = new Map((d.saved || []).map(s => [s.origin,s]));
        for(const s of d.pending?.sites || [])current.set(s.origin,s);
        const digest = sites => require('./sync-merge').hash([...sites].sort((a,b)=>a.origin<b.origin?-1:a.origin>b.origin?1:0));
        if(config.forceSiteData || digest(current.values()) !== digest(profile.siteData || [])) d.pending = {revision:crypto.randomUUID(),sites:profile.siteData || []};
      }
      if (config.includeCredentials) d.credentials = profile.credentials || [];
    });
  }
  async applied(id, revision, site) { return this.update(id, d => { if (d.pending?.revision !== revision) return; const map = new Map((d.saved || []).map(s => [s.origin,s])); map.set(site.origin, site); d.saved = [...map.values()]; d.pending.sites = d.pending.sites.filter(s => s.origin !== site.origin); if (!d.pending.sites.length) delete d.pending; }); }
  async clearSites(id) { return this.update(id, d => { d.saved = []; delete d.pending; }); }
  async clear(id) { await this.queue.catch(() => {}); await fs.rm(this.file(id), {force:true}); await fs.rm(this.file(id) + '.tmp', {force:true}); }
}
async function enrich(body, store, config) {
  if (!config.includeSiteData && !config.includeCredentials) return body;
  for (const profile of body.profiles) {
    const state = await store.export(profile.id);
    if (config.includeSiteData) profile.siteData = state.siteData;
    if (config.includeCredentials) profile.credentials = state.credentials;
  }
  body.version = 2; return body;
}
async function captureOpenPages(port, cdp) {
  const sites = new Map();
  for (const tab of await cdp.tabs(port)) {
    if (!/^https?:\/\//.test(tab.url || '')) continue;
    const conn = await cdp.connect(tab.webSocketDebuggerUrl);
    try {
      const result = await conn.command('Runtime.evaluate', {expression: `(${source}).capture()`, awaitPromise:true, returnByValue:true}, {timeout:30000});
      if (result.exceptionDetails || !result.result?.value) throw new Error('网站存储快照失败，保留上一份快照');
      const snapshot = result.result.value; validateSites([snapshot]); sites.set(snapshot.origin, snapshot);
    } finally { conn.close(); }
  }
  return validateSites([...sites.values()]);
}
// Load a synthetic document at the exact origin, intercepting its only request.
// No application JavaScript executes while IndexedDB is being replaced.
async function restorePending(port, store, id) {
  const doc = await store.read(id);
  if (!doc.pending?.sites?.length) return;
  const cdp = require('../cdp');
  const connection = await cdp.connect(await cdp.browserSocket(port));
  try {
  for (const site of doc.pending?.sites || []) {
    validateSites([site]);
    const {targetId} = await connection.command('Target.createTarget', {url:'about:blank'});
    let sessionId;
    try { ({sessionId} = await connection.command('Target.attachToTarget', {targetId, flatten:true})); }
    catch (error) { await connection.command('Target.closeTarget', {targetId}).catch(() => {}); throw error; }
    const call = (method, params={}) => connection.command(method, params, {sessionId, timeout:45000});
    const url = site.origin + '/.well-known/openbrowser-restore-' + crypto.randomBytes(16).toString('hex');
    let intercepted = false, loadResolve, loadReject;
    const loaded = new Promise((resolve,reject) => {loadResolve=resolve;loadReject=reject});
    // Attach a handler before navigation so early errors never become unhandled.
    loaded.catch(() => {});
    const loadTimer = setTimeout(() => loadReject(new Error('网站存储恢复页面超时')),15000);
    const previous = connection.onEvent;
    connection.onEvent = event => {
      previous?.(event);
      if (event.sessionId !== sessionId) return;
      if (event.method === 'Page.loadEventFired' && intercepted) {loadResolve();return;}
      if (event.method !== 'Fetch.requestPaused') return;
      const p = event.params;
      if (p.request.url === url && p.resourceType === 'Document') {
        intercepted = true;
        call('Fetch.fulfillRequest', {requestId:p.requestId, responseCode:200, responseHeaders:[{name:'Content-Type',value:'text/html; charset=utf-8'},{name:'Cache-Control',value:'no-store'}], body:Buffer.from('<!doctype html><title>OpenBrowser</title>').toString('base64')}).catch(() => {});
      } else call('Fetch.failRequest', {requestId:p.requestId, errorReason:'Aborted'}).catch(() => {});
    };
    try {
      await call('Network.enable'); await call('Network.setBypassServiceWorker', {bypass:true});
      await call('Fetch.enable', {patterns:[{urlPattern:'*'}]});
      await call('Page.enable');
      const nav = await call('Page.navigate', {url}); if (nav.errorText) throw new Error('网站存储恢复页面失败');
      await loaded;
      const ready = await call('Runtime.evaluate', {expression:`location.href === ${JSON.stringify(url)} && location.origin === ${JSON.stringify(site.origin)}`,returnByValue:true});
      if (!intercepted || ready.exceptionDetails || ready.result?.value !== true) throw new Error('网站存储恢复来源校验失败');
      const result = await call('Runtime.evaluate', {expression:`(${source}).restore(${JSON.stringify(site)})`,awaitPromise:true,returnByValue:true});
      if (result.exceptionDetails || result.result?.value !== true) throw new Error('网站存储恢复失败，待应用快照已保留');
      await store.applied(id, doc.pending.revision, site);
    } finally { clearTimeout(loadTimer); connection.onEvent = previous; await connection.command('Target.closeTarget', {targetId}).catch(() => {}); }
  }
  } finally { connection.close(); }
}
module.exports = {source, origin, validateSites, validateCredentials, WebsiteStateStore, enrich, captureOpenPages, restorePending};
