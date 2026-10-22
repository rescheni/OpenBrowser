'use strict';
const crypto = require('crypto');
const portable = require('./portable-sync');

const CONFIG_FIELDS = ['name', 'group', 'note', 'startUrl', 'userAgent', 'platform', 'locale', 'timezone', 'screenWidth', 'screenHeight', 'devicePixelRatio', 'hardwareConcurrency', 'deviceMemory', 'touchPoints', 'mobile', 'webglVendor', 'webglRenderer', 'canvasNoise', 'audioNoise', 'webRtcMode', 'webRtcIp'];
function canonical(value) {
  if (Array.isArray(value)) return '[' + value.map(canonical).join(',') + ']';
  if (value && typeof value === 'object') return '{' + Object.keys(value).sort().map(k => JSON.stringify(k) + ':' + canonical(value[k])).join(',') + '}';
  return JSON.stringify(value);
}
function hash(value) { return crypto.createHash('sha256').update(canonical(value)).digest('hex'); }
function cookieValue(record) {
  portable.validateCookie(record);
  const url = new URL(record.url), [pair, ...parts] = record.setCookie.split(';').map(s => s.trim());
  const i = pair.indexOf('='), attrs = {};
  for (const item of parts) {
    const index = item.indexOf('='), k = (index < 0 ? item : item.slice(0, index)).toLowerCase();
    let v = index < 0 ? true : item.slice(index + 1).trim();
    if (k === 'expires') v = Date.parse(v) / 1000;
    if (k === 'domain') v = v.replace(/^\./, '').toLowerCase();
    if (k === 'samesite') v = v.toLowerCase();
    attrs[k] = v;
  }
  const path = attrs.path || url.pathname.slice(0, url.pathname.lastIndexOf('/')) || '/';
  const result = { name: pair.slice(0, i), value: pair.slice(i + 1), host: attrs.domain || url.hostname, domain: Boolean(attrs.domain), path, secure: Boolean(attrs.secure), httpOnly: Boolean(attrs.httponly), sameSite: attrs.samesite || 'lax', expires: attrs.expires || 0 };
  if ('max-age' in attrs) result.maxAge = attrs['max-age'];
  return result;
}
function digests(profile) {
  const config = {};
  CONFIG_FIELDS.forEach(k => { config[k] = profile[k] ?? (['group', 'note', 'startUrl', 'webRtcIp'].includes(k) ? '' : null); });
  config.proxy = profile.proxy.type === 'direct' ? { type: 'direct' } : {
    type: profile.proxy.type, host: profile.proxy.host.toLowerCase(), port: profile.proxy.port,
    username: profile.proxy.username || '', password: profile.proxy.password || '',
  };
  const cookies = [...new Map(profile.cookies.map(cookieValue).map(c => [canonical(c), c])).values()];
  return { config: hash(config), cookies: hash(cookies.sort((a, b) => canonical(a) < canonical(b) ? -1 : canonical(a) > canonical(b) ? 1 : 0)), siteData: hash([...(profile.siteData || [])].sort((a,b) => a.origin < b.origin ? -1 : a.origin > b.origin ? 1 : 0)), credentials: hash([...(profile.credentials || [])].sort((a,b) => canonical([a.origin,a.username]) < canonical([b.origin,b.username]) ? -1 : canonical([a.origin,a.username]) > canonical([b.origin,b.username]) ? 1 : 0)) };
}

/** Three-way merge by section. No clocks, silent overwrites, or propagated deletions. */
function plan(local, remote, state, options) {
  portable.validateBundle(local);
  const sections = ['config', ...(options.includeCookies ? ['cookies'] : []), ...(options.includeSiteData ? ['siteData'] : []), ...(options.includeCredentials ? ['credentials'] : [])];
  const baseline = (p, old = {}) => Object.fromEntries(Object.entries({...old, ...digests(p)}).filter(([k]) => sections.includes(k) || old[k] !== undefined).map(([k,v]) => [k, sections.includes(k) ? v : old[k]]));
  const space = options.space;
  if (!/^[A-Za-z0-9_-]{1,64}$/.test(space)) throw new Error('空间标识需为 1–64 位字母、数字、下划线或短横线');
  if (remote) {
    portable.validateBundle(remote);
    if (remote.sync?.version !== 1 || remote.sync.space !== space) throw new Error('远端文件不属于此自动同步空间，请使用新的文件地址或正确空间标识');
  }
  const localMap = new Map(local.profiles.map(p => [p.id, p])), remoteMap = new Map((remote?.profiles || []).map(p => [p.id, p]));
  const output = new Map(remoteMap), imports = [], conflicts = [], bases = { ...(state.bases || {}) }, included = new Set(localMap.keys());
  const excluded = new Set(options.excluded || []);
  for (const id of new Set([...localMap.keys(), ...remoteMap.keys()])) {
    const l = localMap.get(id), r = remoteMap.get(id), base = bases[id];
    if (!l) {
      // A previously synced local removal is kept removed, without deleting remote data.
      if (!r || excluded.has(id) || base || !options.acceptNew) continue;
      imports.push(r); included.add(id); const d = digests(r); bases[id] = baseline(r); continue;
    }
    if (!r) { output.set(id, l); bases[id] = baseline(l); continue; }
    const ld = digests(l), rd = digests(r), chosen = { ...l }, selected = {};
    for (const section of sections) {
      let side;
      if (ld[section] === rd[section] || rd[section] === base?.[section]) side = 'local';
      else if (ld[section] === base?.[section]) side = 'remote';
      else {
        const decision = state.decisions?.[id]?.[section];
        if (decision && decision.local === ld[section] && decision.remote === rd[section] && ['local', 'remote'].includes(decision.side)) side = decision.side;
        else conflicts.push({ id, name: l.name, section, local: ld[section], remote: rd[section] });
      }
      selected[section] = side;
    }
    if (sections.some(section => !selected[section])) continue;
    const configSource = selected.config === 'remote' ? r : l;
    Object.assign(chosen, configSource, { engine: l.engine, cookies: options.includeCookies ? (selected.cookies === 'remote' ? r.cookies : l.cookies) : r.cookies });
    for (const section of ['siteData', 'credentials']) {
      const value = sections.includes(section) ? (selected[section] === 'remote' ? r[section] : l[section]) : r[section];
      if (value === undefined) delete chosen[section]; else chosen[section] = value;
    }
    output.set(id, chosen);
    const next = digests(chosen);
    bases[id] = baseline(chosen, base);
    if (Object.values(selected).includes('remote')) imports.push(chosen);
  }
  const body = portable.validateBundle({ format: portable.FORMAT, version: Math.max(local.version, remote?.version || 1), source: 'sync', createdAt: Date.now(), sync: { version: 1, space, revision: crypto.randomUUID() }, profiles: [...output.values()] });
  return { body, imports, conflicts, bases, included: [...included] };
}
function resolve(conflicts, side) {
  if (!['local', 'remote'].includes(side)) throw new Error('未知的冲突解决方式');
  const decisions = {};
  for (const c of conflicts) { decisions[c.id] ||= {}; decisions[c.id][c.section] = { side, local: c.local, remote: c.remote }; }
  return decisions;
}
module.exports = { CONFIG_FIELDS, canonical, hash, cookieValue, digests, plan, resolve };
