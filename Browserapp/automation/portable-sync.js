'use strict';

// Cross-platform exchange. Never copy Chromium/Gecko databases between engines.
const crypto = require('crypto');
const http = require('http');
const https = require('https');
const os = require('os');
const { promisify } = require('util');
const pbkdf2 = promisify(crypto.pbkdf2);
const FORMAT = 'openbrowser-portable';
const MAX_BYTES = 8 * 1024 * 1024;
const ITERATIONS = 210000;
const MAGIC = Buffer.from('OBS1');
const ID = /^[A-Za-z0-9_-]{1,128}$/;

function validateBundle(body) {
  if (body?.format !== FORMAT || ![1, 2].includes(body.version) || !Array.isArray(body.profiles) || body.profiles.length > 1000) throw new Error('不支持的跨端交换文件');
  const ids = new Set();
  const suffixes = new Set();
  for (const p of body.profiles) {
    if (!p || !ID.test(p.id) || typeof p.name !== 'string' || p.name.length > 100 || !p.userAgent || p.userAgent.length > 1000 || /[\r\n]/.test(p.userAgent)) throw new Error('环境字段无效');
    const suffix = p.id.replace(/-/g, '').slice(0, 20) || 'default';
    if (ids.has(p.id) || suffixes.has(suffix)) throw new Error('环境 ID 或隔离目录冲突');
    ids.add(p.id); suffixes.add(suffix);
    for (const [key, min, max] of [['screenWidth', 1, 16384], ['screenHeight', 1, 16384], ['devicePixelRatio', .1, 16], ['hardwareConcurrency', 1, 256], ['deviceMemory', 1, 1024], ['touchPoints', 0, 64]]) {
      if (typeof p[key] !== 'number' || !Number.isFinite(p[key]) || p[key] < min || p[key] > max) throw new Error(`指纹参数无效: ${key}`);
      if (key !== 'devicePixelRatio' && !Number.isInteger(p[key])) throw new Error(`指纹参数暂不支持: ${key}`);
    }
    if (!['webview', 'gecko'].includes(p.engine) || [p.platform, p.locale, p.timezone].some(v => typeof v !== 'string' || !v || v.length > 200)) throw new Error('环境内核或语言参数无效');
    if (!['blocked', 'real', 'spoof'].includes(p.webRtcMode)) throw new Error('WebRTC 设置无效');
    const proxy = p.proxy;
    if (!proxy || !['direct', 'http', 'https', 'socks5'].includes(proxy.type)) throw new Error('代理类型无效');
    if (proxy.type !== 'direct' && (typeof proxy.host !== 'string' || !proxy.host || /[\s/@?#\r\n]/.test(proxy.host) || !Number.isInteger(proxy.port) || proxy.port < 1 || proxy.port > 65535)) throw new Error('代理地址或端口无效');
    if (!Array.isArray(p.cookies) || p.cookies.length > 5000) throw new Error('Cookie 数量无效');
    if (p.engine === 'gecko' && p.cookies.length) throw new Error('Gecko 暂不支持 Cookie 交换');
    p.cookies.forEach(validateCookie);
    if (p.siteData !== undefined || p.credentials !== undefined) {
      if (body.version !== 2) throw new Error('网站存储和密码库需要新版同步协议');
      const sites = require('./website-state');
      if (p.siteData !== undefined) sites.validateSites(p.siteData);
      if (p.credentials !== undefined) sites.validateCredentials(p.credentials);
      if (p.engine === 'gecko' && p.siteData?.length) throw new Error('Gecko 暂不支持网站存储交换');
    }
  }
  return body;
}

function validateCookie(record) {
  const url = new URL(record?.url);
  const raw = record?.setCookie;
  if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || typeof raw !== 'string' || raw.length > 16384 || /[\r\n\0]/.test(raw)) throw new Error('Cookie 记录无效');
  const parts = raw.split(';').map(s => s.trim());
  if (!/^[^=;\s]+=[^\r\n]*$/.test(parts[0])) throw new Error('Cookie 名称无效');
  let secure = false;
  let sameSite = '';
  for (const attr of parts.slice(1)) {
    const [name, ...rest] = attr.split('=');
    const value = rest.join('=').trim();
    if (name.toLowerCase() === 'domain') {
      const domain = value.replace(/^\./, '').toLowerCase();
      if (!domain || !(url.hostname === domain || url.hostname.endsWith('.' + domain))) throw new Error('Cookie 域名与来源不匹配');
    }
    if (name.toLowerCase() === 'partitioned') throw new Error('暂不支持分区 Cookie 迁移');
    if (name.toLowerCase() === 'secure') secure = true;
    if (name.toLowerCase() === 'samesite') sameSite = value.toLowerCase();
  }
  if ((secure && url.protocol !== 'https:') || (sameSite === 'none' && !secure)) throw new Error('Cookie 安全属性无效');
  return record;
}

async function seal(body, password) {
  validateBundle(body);
  if (typeof password !== 'string' || password.length < 12) throw new Error('同步密码至少 12 个字符');
  const plain = Buffer.from(JSON.stringify(body));
  if (plain.length > MAX_BYTES - 48) throw new Error('交换文件超过 8 MB');
  const salt = crypto.randomBytes(16), iv = crypto.randomBytes(12);
  const key = await pbkdf2(password, salt, ITERATIONS, 32, 'sha256');
  try {
    const cipher = crypto.createCipheriv('aes-256-gcm', key, iv);
    cipher.setAAD(MAGIC);
    const data = Buffer.concat([cipher.update(plain), cipher.final()]);
    // Java GCM doFinal uses ciphertext || tag. Fixed KDF parameters prevent DoS.
    return Buffer.concat([MAGIC, salt, iv, data, cipher.getAuthTag()]);
  } finally { key.fill(0); plain.fill(0); }
}

async function open(buffer, password) {
  if (!Buffer.isBuffer(buffer) || buffer.length < 48 || buffer.length > MAX_BYTES || !buffer.subarray(0, 4).equals(MAGIC)) throw new Error('无效或过大的交换文件');
  if (typeof password !== 'string' || password.length < 12) throw new Error('同步密码至少 12 个字符');
  const key = await pbkdf2(password, buffer.subarray(4, 20), ITERATIONS, 32, 'sha256');
  let plain;
  try {
    const decipher = crypto.createDecipheriv('aes-256-gcm', key, buffer.subarray(20, 32));
    decipher.setAAD(MAGIC);
    decipher.setAuthTag(buffer.subarray(-16));
    plain = Buffer.concat([decipher.update(buffer.subarray(32, -16)), decipher.final()]);
    return validateBundle(JSON.parse(plain.toString('utf8')));
  } catch (_) { throw new Error('同步密码错误、文件损坏或数据格式不受支持'); }
  finally { key.fill(0); plain?.fill(0); }
}

function cookiesFromDesktop(raw) {
  if (!raw) return [];
  const cookies = typeof raw === 'string' ? JSON.parse(raw) : raw;
  if (!Array.isArray(cookies)) throw new Error('Cookie JSON 必须是数组');
  return cookies.filter(c => !c.partitionKey && !c.partitioned && !(Number(c.expires) > 0 && Number(c.expires) <= Date.now() / 1000)).map(c => {
    const domain = String(c.domain || (c.url ? new URL(c.url).hostname : ''));
    const secure = Boolean(c.secure);
    const url = c.url || `${secure ? 'https' : 'http'}://${domain.replace(/^\./, '')}${c.path || '/'}`;
    const attrs = [`${c.name}=${c.value}`, `Path=${c.path || '/'}`];
    if (domain.startsWith('.')) attrs.push(`Domain=${domain}`);
    if (secure) attrs.push('Secure');
    if (c.httpOnly) attrs.push('HttpOnly');
    if (c.sameSite && ['strict', 'lax', 'none'].includes(String(c.sameSite).toLowerCase())) attrs.push(`SameSite=${c.sameSite}`);
    if (Number(c.expires) > 0) attrs.push(`Expires=${new Date(Number(c.expires) * 1000).toUTCString()}`);
    return validateCookie({ url, setCookie: attrs.join('; ') });
  });
}

function cookiesToDesktop(records) {
  return records.map(record => {
    validateCookie(record);
    const url = new URL(record.url);
    const [pair, ...attrs] = record.setCookie.split(';').map(s => s.trim());
    const index = pair.indexOf('=');
    const c = { url: record.url, name: pair.slice(0, index), value: pair.slice(index + 1), path: url.pathname.replace(/[^/]*$/, '') || '/', secure: false, httpOnly: false };
    for (const attr of attrs) {
      const i = attr.indexOf('='), name = (i < 0 ? attr : attr.slice(0, i)).toLowerCase();
      const value = i < 0 ? '' : attr.slice(i + 1).trim();
      if (name === 'domain') { c.domain = value; delete c.url; }
      if (name === 'path') c.path = value;
      if (name === 'secure') c.secure = true;
      if (name === 'httponly') c.httpOnly = true;
      if (name === 'samesite') c.sameSite = ({ strict: 'Strict', lax: 'Lax', none: 'None' })[value.toLowerCase()];
      if (name === 'expires' && Number.isFinite(Date.parse(value))) c.expires = Date.parse(value) / 1000;
      if (name === 'max-age' && /^-?\d+$/.test(value)) c.expires = Date.now() / 1000 + Number(value);
    }
    return c;
  });
}

function fromDesktop(profiles, includeCookies = false) {
  const { buildFingerprint } = require('./fingerprint');
  const list = profiles.map(p => {
    const fp = buildFingerprint(p);
    if (!fp.hardwareConcurrency || !fp.deviceMemory || fp.canvas.mode === 'blocked' || fp.audio.mode === 'muted') throw new Error('请为跨端环境设置明确的 CPU / 内存参数；暂不支持屏蔽 Canvas 或静音 Audio 的配置');
    let proxy = { type: 'direct', host: '', port: 0, username: '', password: '' };
    if (p.networkMode !== 'direct' && p.proxy && !/^(direct|none)$/i.test(p.proxy)) {
      const parsed = require('../proxy-forwarder').parseProxy(p.proxy);
      if (!parsed) throw new Error('代理配置无效');
      proxy = { type: parsed.protocol, host: parsed.host, port: parsed.port, username: parsed.username || '', password: parsed.password || '' };
    }
    return {
      id: p.id, name: p.name, group: p.group_name || '', note: p.note || '', startUrl: p.startUrl || '', engine: 'webview',
      userAgent: fp.userAgent, platform: fp.platform, locale: fp.languages?.[0] || p.language || 'en-US', timezone: fp.timezone || 'UTC',
      screenWidth: fp.screen.width, screenHeight: fp.screen.height, devicePixelRatio: fp.screen.devicePixelRatio,
      hardwareConcurrency: fp.hardwareConcurrency, deviceMemory: fp.deviceMemory, touchPoints: fp.maxTouchPoints || 0, mobile: fp.mobile,
      webglVendor: fp.webgl.vendor || '', webglRenderer: fp.webgl.renderer || '', canvasNoise: fp.canvas.mode !== 'real', audioNoise: fp.audio.mode !== 'real',
      webRtcMode: fp.webrtc === 'real' ? 'real' : 'blocked', webRtcIp: '', deviceModel: fp.mobileDevice?.name || '', proxy,
      updatedAt: Date.now(), cookies: includeCookies ? cookiesFromDesktop(p.cookies) : [],
    };
  });
  return validateBundle({ format: FORMAT, version: 1, createdAt: Date.now(), source: 'desktop', profiles: list });
}

function mergeIntoDesktop(local, body, replace = false, includeCookies = false, managed = false) {
  validateBundle(body);
  const byId = new Map(local.map(p => [p.id, p]));
  const stats = { added: 0, updated: 0, kept: 0 };
  for (const p of body.profiles) {
    const prev = byId.get(p.id);
    if (prev && !replace) { stats.kept++; continue; }
    const q = p.proxy, host = q.host.includes(':') ? `[${q.host}]` : q.host;
    const proxy = q.type === 'direct' ? 'Direct' : `${q.type}://${q.username ? `${encodeURIComponent(q.username)}:${encodeURIComponent(q.password || '')}@` : ''}${host}:${q.port}`;
    const osName = /android/i.test(p.userAgent) ? 'android' : /iphone|ipad/i.test(p.userAgent) ? 'ios' : /mac/i.test(p.platform) ? 'macos' : /linux/i.test(p.platform) ? 'linux' : 'windows';
    const fingerprint = {
      os: osName, userAgent: p.userAgent, platform: p.platform, screenWidth: p.screenWidth, screenHeight: p.screenHeight,
      devicePixelRatio: p.devicePixelRatio, hardwareConcurrency: p.hardwareConcurrency, deviceMemory: p.deviceMemory,
      cores: p.hardwareConcurrency, memory: p.deviceMemory, maxTouchPoints: p.touchPoints, webglVendor: p.webglVendor, webglRenderer: p.webglRenderer,
      canvas: p.canvasNoise ? 'noise' : 'real', audio: p.audioNoise ? 'noise' : 'real', webglMeta: 'custom', webrtc: p.webRtcMode === 'real' ? 'real' : 'disabled',
      languages: [...new Set([p.locale, p.locale.split('-')[0], 'en', 'en-US'])],
    };
    const next = {
      ...prev, id: p.id, name: p.name, note: p.note, startUrl: p.startUrl, group_name: p.group,
      userAgent: p.userAgent, language: p.locale, fingerprint, os: osName,
      networkMode: q.type === 'direct' ? 'direct' : 'proxy', proxy, proxyId: null,
      proxyMeta: { ...prev?.proxyMeta, proxyId: null, fillFingerprint: false },
      privacy: { ...prev?.privacy, fingerprint, cores: p.hardwareConcurrency, memory: p.deviceMemory, clientHints: {}, timezoneMode: 'custom', timezoneFromIp: false, timezone: p.timezone, languageFromIp: false, webrtc: p.webRtcMode === 'real' ? 'real' : 'disabled', canvas: p.canvasNoise ? 'noise' : 'real', audio: p.audioNoise ? 'noise' : 'real', webglMeta: 'custom' },
      advanced: { ...prev?.advanced, cloudBackup: prev?.advanced?.cloudBackup === true },
    };
    if (includeCookies && (p.cookies.length || managed)) {
      if (managed && prev?.advanced?.saveCookies === false) throw new Error('请先在环境偏好中开启 Cookie 保存');
      const incoming = cookiesToDesktop(p.cookies);
      if (managed && prev?.cookies) {
        const key = c => `${c.name}|${c.domain || new URL(c.url).hostname}|${c.path || '/'}`;
        const keys = new Set(incoming.map(key));
        const old = JSON.parse(prev.cookies);
        for (const c of old) if (!keys.has(key(c))) incoming.push({ ...c, value: '', expires: Date.now() / 1000 - 60 });
      }
      next.cookies = JSON.stringify(incoming); next.__secretsExplicit = true;
      if (incoming.length > 10000 || next.cookies.length > 500000) throw new Error('Cookie 快照超过桌面单环境容量限制，请减少快照范围后重试');
      if (managed) { next.cookieSyncManaged = true; next.cookieSyncPending = true; next.__cookieSyncImport = true; }
    }
    byId.set(p.id, next);
    stats[prev ? 'updated' : 'added']++;
  }
  return { profiles: [...byId.values()], stats };
}

function isLocalHost(host) {
  return host === 'localhost' || /^127\.\d+\.\d+\.\d+$/.test(host) || /^10\.\d+\.\d+\.\d+$/.test(host) || /^192\.168\.\d+\.\d+$/.test(host) || /^172\.(1[6-9]|2\d|3[01])\.\d+\.\d+$/.test(host);
}

function request(method, address, { data, username, password, etag, createOnly, headers: extraHeaders, signal } = {}) {
  const url = new URL(address);
  if (url.username || url.password || url.hash || !(url.protocol === 'https:' || (url.protocol === 'http:' && isLocalHost(url.hostname)))) throw new Error('远端地址必须使用 HTTPS，局域网 IP 可用 HTTP');
  if (username && url.protocol !== 'https:' && url.hostname !== 'localhost' && !url.hostname.startsWith('127.')) throw new Error('带账号的 WebDAV 需要 HTTPS');
  const headers = { 'Content-Type': 'application/octet-stream', 'Cache-Control': 'no-cache' };
  if (username) headers.Authorization = 'Basic ' + Buffer.from(`${username}:${password || ''}`).toString('base64');
  if (data) headers['Content-Length'] = data.length;
  if (etag) headers['If-Match'] = etag;
  if (createOnly) headers['If-None-Match'] = '*';
  Object.assign(headers, extraHeaders || {});
  return new Promise((resolve, reject) => {
    const req = (url.protocol === 'https:' ? https : http).request(url, { method, headers, signal }, res => {
      const chunks = []; let size = 0;
      res.on('data', chunk => { size += chunk.length; if (size > MAX_BYTES) { res.destroy(); req.destroy(new Error('远端文件超过 8 MB')); } else chunks.push(chunk); });
      res.on('error', () => reject(new Error('远端响应中断')));
      res.on('end', () => resolve({ status: res.statusCode, etag: res.headers.etag || '', data: Buffer.concat(chunks) }));
    });
    const timeout = setTimeout(() => req.destroy(new Error('连接超时')), 30000);
    req.on('close', () => clearTimeout(timeout));
    req.on('error', () => reject(new Error('远端连接失败，请检查地址、权限和网络')));
    req.end(data);
  });
}

async function download(config) {
  const r = await request('GET', config.getUrl, config);
  if (r.status !== 200) throw new Error(`下载失败 HTTP ${r.status}`);
  return r.data;
}

async function upload(config, data, perform = request, inspectPrevious) {
  // No unconditional replacement: detect concurrent writes; refuse servers without ETags.
  if (config.username && new URL(config.getUrl).origin !== new URL(config.putUrl || config.getUrl).origin) throw new Error('带账号的 WebDAV 上传和下载地址必须同源');
  const previous = await perform('GET', config.getUrl, config);
  if (previous.status !== 404 && previous.status !== 200) throw new Error(`读取远端版本失败 HTTP ${previous.status}`);
  if (previous.status === 200 && (!previous.etag || previous.etag.startsWith('W/'))) throw new Error('服务器缺少强 ETag，请使用新的文件名上传');
  if (previous.status === 200 && inspectPrevious) await inspectPrevious(previous.data);
  const r = await perform('PUT', config.putUrl || config.getUrl, { ...config, data, etag: previous.etag, createOnly: previous.status === 404 });
  if (r.status === 412 || r.status === 409) throw new Error('远端已被其他设备修改，请重新拉取后再试');
  if (![200, 201, 204].includes(r.status)) throw new Error(`上传失败 HTTP ${r.status}`);
}

async function serveLocal(data, options = {}) {
  const host = options.host || Object.values(os.networkInterfaces()).flat().find(a => a?.family === 'IPv4' && !a.internal && isLocalHost(a.address))?.address;
  if (!host) throw new Error('未找到局域网 IPv4 地址');
  if (!isLocalHost(host)) throw new Error('仅允许局域网地址');
  const token = crypto.randomBytes(32).toString('hex');
  let current = data, incoming = false;
  const tag = () => '"' + crypto.createHash('sha256').update(current).digest('hex') + '"';
  const server = http.createServer((req, res) => {
    res.setHeader('Cache-Control', 'no-store');
    if (req.url !== '/' + token || req.headers.origin) { res.writeHead(404); res.end(); return; }
    if (req.method === 'GET') { res.setHeader('ETag', tag()); res.end(current); return; }
    if (req.method !== 'PUT') { res.writeHead(405); res.end(); return; }
    const expected = req.headers['if-match'];
    const chunks = []; let length = 0;
    req.on('data', chunk => { length += chunk.length; if (length > MAX_BYTES) req.destroy(); else chunks.push(chunk); });
    req.on('end', () => {
      const next = Buffer.concat(chunks);
      if (expected !== tag()) { res.writeHead(412); res.end(); return; }
      if (next.length < 48 || !next.subarray(0, 4).equals(MAGIC)) { res.writeHead(400); res.end(); return; }
      current = next; incoming = true; res.writeHead(204); res.end();
    });
    req.on('error', () => {});
  });
  server.requestTimeout = 15000; server.headersTimeout = 10000; server.maxConnections = 8;
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, host, resolve); });
  const close = () => { clearTimeout(timer); server.close(); server.closeAllConnections?.(); current = Buffer.alloc(0); };
  const timer = setTimeout(close, 10 * 60 * 1000); timer.unref();
  return { url: `http://${host}:${server.address().port}/${token}`, close, received: () => incoming ? Buffer.from(current) : null };
}

module.exports = { FORMAT, MAX_BYTES, ITERATIONS, validateBundle, validateCookie, seal, open, fromDesktop, mergeIntoDesktop, cookiesFromDesktop, cookiesToDesktop, isLocalHost, request, upload, download, serveLocal };
