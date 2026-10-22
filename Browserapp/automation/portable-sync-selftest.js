'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const http = require('node:http');
const { once } = require('node:events');
const sync = require('./portable-sync');
const password = 'test-only-password-跨端';
const profile = () => ({
  id: 'portable-test-123', name: '测试环境', group: '', note: '', startUrl: 'https://example.com/', engine: 'webview',
  userAgent: 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36',
  platform: 'Linux armv8l', locale: 'zh-CN', timezone: 'Asia/Shanghai', screenWidth: 412, screenHeight: 915, devicePixelRatio: 2.625,
  hardwareConcurrency: 8, deviceMemory: 4, touchPoints: 5, mobile: true, webglVendor: 'Qualcomm', webglRenderer: 'Adreno',
  canvasNoise: true, audioNoise: false, webRtcMode: 'blocked', webRtcIp: '', deviceModel: 'Pixel 8', updatedAt: 1,
  proxy: { type: 'direct', host: '', port: 0, username: '', password: '' }, cookies: [],
});
const bundle = () => ({ format: sync.FORMAT, version: 1, createdAt: 1, source: 'android', profiles: [profile()] });

test('encrypted exchange round trip, fresh nonce and wrong password', async () => {
  const a = await sync.seal(bundle(), password), b = await sync.seal(bundle(), password);
  assert.notDeepEqual(a, b);
  assert.deepEqual(await sync.open(a, password), bundle());
  await assert.rejects(sync.open(a, 'another-test-password'));
  a[a.length - 1] ^= 1;
  await assert.rejects(sync.open(a, password));
  await assert.rejects(sync.seal(bundle(), 'short'));
});

test('OBS1 fixed interoperability vector (also consumed by Android unit test)', async () => {
  const lines = require('fs').readFileSync(require('path').join(__dirname, '../../AndroidApp/app/src/test/resources/portable-sync-vector.txt'), 'utf8').trim().split('\n');
  assert.deepEqual(await sync.open(Buffer.from(lines[1], 'base64'), lines[0]), JSON.parse(lines[2]));
  assert.equal(vector(), lines[1]);
});

test('reject ID traversal, data-directory collisions and invalid proxy', () => {
  const body = bundle(); body.profiles[0].id = '../escape';
  assert.throws(() => sync.validateBundle(body));
  body.profiles = [{ ...profile(), id: 'abcdefghijklmnopqrst-1' }, { ...profile(), id: 'abcdefghijklmnopqrst-2' }];
  assert.throws(() => sync.validateBundle(body));
  body.profiles = [profile()]; body.profiles[0].proxy = { type: 'http', host: 'localhost', port: 0 };
  assert.throws(() => sync.validateBundle(body));
});

test('Cookie attribute conversion preserves host-only, HttpOnly, Secure and SameSite', () => {
  const source = [{ name: '__Host-session', value: 'test-only', domain: 'example.com', path: '/', secure: true, httpOnly: true, sameSite: 'Lax', expires: 2000000000 }];
  const records = sync.cookiesFromDesktop(source);
  assert.ok(!records[0].setCookie.includes('Domain='));
  const [c] = sync.cookiesToDesktop(records);
  assert.equal(c.url, 'https://example.com/'); assert.equal(c.domain, undefined);
  assert.equal(c.httpOnly, true); assert.equal(c.secure, true); assert.equal(c.sameSite, 'Lax');
  assert.equal(c.expires, 2000000000);
  const scoped = sync.cookiesToDesktop(sync.cookiesFromDesktop([{ ...source[0], domain: '.example.com' }]))[0];
  assert.equal(scoped.domain, '.example.com'); assert.equal(scoped.url, undefined);
});

test('reject Cookie scope mismatch, injection, partitioned and insecure SameSite=None', () => {
  for (const raw of ['x=y; Domain=other.example', 'x=y\r\nInjected: true', 'x=y; Partitioned', 'x=y; SameSite=None']) {
    assert.throws(() => sync.validateCookie({ url: 'https://example.com/', setCookie: raw }));
  }
});

test('merging keeps local by default, is idempotent and never deletes local-only profiles', () => {
  const local = [{ id: profile().id, name: 'local', cookies: '[local]' }, { id: 'local-only', name: 'keep' }];
  let result = sync.mergeIntoDesktop(local, bundle());
  assert.equal(result.profiles.length, 2); assert.equal(result.profiles[0].name, 'local');
  assert.equal(result.stats.kept, 1);
  result = sync.mergeIntoDesktop(local, bundle(), true, false);
  assert.equal(result.profiles[0].cookies, '[local]');
  assert.equal(result.profiles[0].fingerprint.screenWidth, 412);
  assert.equal(result.profiles[0].privacy.timezone, 'Asia/Shanghai');
  assert.equal(result.profiles.length, 2);
  assert.equal(sync.mergeIntoDesktop(result.profiles, bundle()).stats.added, 0);
});

test('Cookie import requires explicit opt-in and retained local profiles are untouched', () => {
  const body = bundle(); body.profiles[0].cookies = [{ url: 'https://example.com/', setCookie: 'session=test-only; Path=/; Secure; HttpOnly' }];
  assert.equal(sync.mergeIntoDesktop([], body).profiles[0].cookies, undefined);
  assert.equal(JSON.parse(sync.mergeIntoDesktop([], body, false, true).profiles[0].cookies)[0].httpOnly, true);
  const local = [{ id: profile().id, name: 'local', cookies: '[local]' }];
  assert.equal(sync.mergeIntoDesktop(local, body, false, true).profiles[0].cookies, '[local]');
});

test('desktop mapper exports common fields and omits unrelated account secrets', () => {
  const body = sync.fromDesktop([{ id: 'desktop-test', name: 'desktop', networkMode: 'proxy', proxy: 'socks5://user:pass@127.0.0.1:1080', language: 'en-US', platform: { password: 'never-export', totpSecret: 'never-export' }, privacy: {} }]);
  assert.equal(body.profiles[0].proxy.type, 'socks5');
  assert.equal(body.profiles[0].proxy.password, 'pass');
  assert.ok(!JSON.stringify(body).includes('never-export'));
});

test('restoring common fields overrides stale desktop fingerprint and real-hardware settings', () => {
  const old = { id: profile().id, name: 'old', privacy: { cores: 0, memory: 0, fingerprint: { userAgent: 'stale', os: 'windows' } } };
  const p = sync.mergeIntoDesktop([old], bundle(), true).profiles[0];
  const sanitized = require('../engine/profile-sanitizer').sanitizeProfile(p);
  const actual = require('./fingerprint').buildFingerprint(sanitized);
  assert.equal(actual.userAgent, profile().userAgent);
  assert.equal(actual.platform, profile().platform);
  assert.equal(actual.hardwareConcurrency, 8);
  assert.equal(actual.deviceMemory, 4);
  assert.equal(actual.screen.width, 412);
  assert.equal(actual.timezone, 'Asia/Shanghai');
});

test('conditional upload requires strong ETag or a create-only write', async () => {
  const calls = [];
  const perform = async (method, url, options) => {
    calls.push({ method, url, options });
    return method === 'GET' ? { status: 404, etag: '' } : { status: 201 };
  };
  const config = { getUrl: 'https://example.com/get', putUrl: 'https://example.com/put' };
  await sync.upload(config, Buffer.from('test'), perform);
  assert.equal(calls[1].url, config.putUrl);
  assert.equal(calls[1].options.createOnly, true);
  for (const etag of ['', 'W/"weak"']) {
    await assert.rejects(sync.upload(config, Buffer.from('test'), async () => ({ status: 200, etag })), /ETag/);
  }
  await assert.rejects(sync.upload(config, Buffer.from('test'), async method => method === 'GET' ? { status: 200, etag: '"version"' } : { status: 412 }), /其他设备/);
});

test('remote transport protects concurrent updates and does not follow redirects', async t => {
  let mode = 'ok', uploaded = null;
  const server = http.createServer((req, res) => {
    if (mode === 'redirect') { res.writeHead(302, { Location: 'https://example.com/' }); res.end(); return; }
    if (req.method === 'GET') { res.setHeader('ETag', '"v1"'); res.end('old'); return; }
    assert.equal(req.headers['if-match'], '"v1"');
    const chunks = []; req.on('data', b => chunks.push(b));
    req.on('end', () => { uploaded = Buffer.concat(chunks); res.writeHead(mode === 'conflict' ? 412 : 204); res.end(); });
  });
  server.listen(0, '127.0.0.1');
  try { await once(server, 'listening'); }
  catch (error) { if (error.code === 'EPERM') { t.skip('Environment denies listening sockets'); return; } throw error; }
  try {
    const config = { getUrl: `http://127.0.0.1:${server.address().port}/snapshot` };
    await sync.upload(config, Buffer.from('test-only'));
    assert.equal(uploaded.toString(), 'test-only');
    mode = 'conflict'; await assert.rejects(sync.upload(config, Buffer.from('other')), /其他设备/);
    mode = 'redirect'; await assert.rejects(sync.download(config), /302/);
    assert.throws(() => sync.request('GET', 'http://public.example/file'));
  } finally { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); }
});

test('LAN server exchanges encrypted blobs, rejects unknown tokens, and needs ETag', async t => {
  let lan;
  try { lan = await sync.serveLocal(await sync.seal(bundle(), password), { host: '127.0.0.1' }); }
  catch (error) { if (error.code === 'EPERM') { t.skip('Environment denies listening sockets'); return; } throw error; }
  try {
    const config = { getUrl: lan.url };
    assert.deepEqual(await sync.open(await sync.download(config), password), bundle());
    assert.equal((await sync.request('GET', lan.url + '-wrong')).status, 404);
    assert.equal((await sync.request('PUT', lan.url, { data: Buffer.from('invalid') })).status, 412);
    await sync.upload(config, await sync.seal(bundle(), password));
    assert.ok(lan.received());
  } finally { lan.close(); }
});

function vector() {
  const salt = Buffer.alloc(16, 1), iv = Buffer.alloc(12, 2);
  const key = crypto.pbkdf2Sync(password, salt, sync.ITERATIONS, 32, 'sha256');
  const cipher = crypto.createCipheriv('aes-256-gcm', key, iv); cipher.setAAD(Buffer.from('OBS1'));
  const data = Buffer.concat([cipher.update(JSON.stringify(bundle())), cipher.final()]);
  return Buffer.concat([Buffer.from('OBS1'), salt, iv, data, cipher.getAuthTag()]).toString('base64');
}

if (process.argv.includes('--vector')) console.log(JSON.stringify({ password, data: vector(), plain: JSON.stringify(bundle()) }));
