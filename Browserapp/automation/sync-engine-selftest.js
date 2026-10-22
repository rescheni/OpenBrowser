'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs/promises');
const path = require('path');
const os = require('os');
const crypto = require('crypto');
const merge = require('./sync-merge');
const portable = require('./portable-sync');
const { sign } = require('./s3-sign');
const { SyncController } = require('./sync-controller');
const fixture = JSON.parse(require('fs').readFileSync(path.join(__dirname, '../../AndroidApp/app/src/test/resources/portable-sync-vector.txt'), 'utf8').trim().split('\n')[2]);
const copy = v => JSON.parse(JSON.stringify(v));
const local = () => copy(fixture);
const vault = body => ({ ...copy(body), sync: { version: 1, space: 'unit', revision: 'test' } });
const options = { space: 'unit', includeCookies: true, acceptNew: true };

test('AWS published SigV4 GET example matches its exact signature', () => {
  const headers = sign('GET', 'https://examplebucket.s3.amazonaws.com/test.txt', { region: 'us-east-1', accessKey: 'AKIAIOSFODNN7EXAMPLE', secretKey: 'wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY' }, Buffer.alloc(0), { Range: 'bytes=0-9' }, new Date('2013-05-24T00:00:00Z'));
  assert.ok(headers.Authorization.endsWith('Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41'));
});

test('first connection rejects mismatched space and reports same-ID differences', () => {
  const l = local(), r = vault(local()); r.profiles[0].name = 'different';
  assert.equal(merge.plan(l, r, {}, options).conflicts.length, 1);
  assert.throws(() => merge.plan(l, { ...r, sync: { version: 1, space: 'other' } }, {}, options));
  assert.throws(() => merge.plan(l, local(), {}, options));
});

test('three-way merge combines independent config and Cookie changes', () => {
  const l = local(), r = vault(local()); const id = l.profiles[0].id;
  const bases = { [id]: merge.digests(l.profiles[0]) };
  l.profiles[0].note = 'local edit';
  r.profiles[0].cookies = [{ url: 'https://example.com/', setCookie: 'session=test-only; Path=/; HttpOnly; Secure' }];
  const result = merge.plan(l, r, { bases }, options);
  assert.equal(result.conflicts.length, 0);
  assert.equal(result.body.profiles[0].note, 'local edit');
  assert.equal(result.imports[0].cookies[0].setCookie, r.profiles[0].cookies[0].setCookie);
});

test('conflict decisions are bound to both reviewed versions', () => {
  const l = local(), r = vault(local()), id = l.profiles[0].id;
  const state = { bases: { [id]: merge.digests(l.profiles[0]) } };
  l.profiles[0].name = 'left'; r.profiles[0].name = 'right';
  const conflict = merge.plan(l, r, state, options);
  state.decisions = merge.resolve(conflict.conflicts, 'local');
  assert.equal(merge.plan(l, r, state, options).body.profiles[0].name, 'left');
  r.profiles[0].name = 'another remote edit';
  assert.equal(merge.plan(l, r, state, options).conflicts.length, 1);
});

test('local removals and opted-out IDs do not resurrect or delete remote profiles', () => {
  const l = local(), r = vault(local()), id = l.profiles[0].id;
  const state = { bases: { [id]: merge.digests(l.profiles[0]) } };
  l.profiles = [];
  const result = merge.plan(l, r, state, options);
  assert.equal(result.imports.length, 0); assert.equal(result.body.profiles.length, 1);
  assert.equal(merge.plan(l, r, {}, { ...options, excluded: [id] }).imports.length, 0);
  assert.equal(merge.plan(l, r, {}, { ...options, acceptNew: false }).imports.length, 0);
});

test('Cookie opt-out preserves remote Cookies and does not establish a Cookie baseline', () => {
  const l = local(), r = vault(local()), id = l.profiles[0].id;
  r.profiles[0].cookies = [{ url: 'https://example.com/', setCookie: 'x=test-only; Path=/' }];
  const result = merge.plan(l, r, {}, { ...options, includeCookies: false });
  assert.equal(result.body.profiles[0].cookies.length, 1);
  assert.equal(result.bases[id].cookies, undefined);
});

test('Cookie attributes have stable order-independent digests', () => {
  const a = local().profiles[0], b = copy(a);
  a.cookies = [{ url: 'https://example.com/a', setCookie: 'x=test-only; Path=/; HttpOnly; Secure; SameSite=Lax' }];
  b.cookies = [{ url: 'https://example.com/b', setCookie: 'x=test-only; samesite=lax; secure; httponly; path=/' }];
  assert.deepEqual(merge.digests(a), merge.digests(b));
  a.cookies.push(copy(a.cookies[0]));
  assert.deepEqual(merge.digests(a), merge.digests(b), 'the same domain Cookie found on multiple URLs must not cause sync loops');
});

test('engine preserves consumed Cookie state across stale and redacted UI updates', async () => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'openbrowser-cookie-state-'));
  try {
    const { BrowserEngine } = require('../engine');
    const engine = new BrowserEngine({ getPath: () => root });
    const incoming = { id: 'cookie-state-test', name: 'test', networkMode: 'direct', proxy: 'Direct', cookies: JSON.stringify([{ url: 'https://example.com/', name: 'session', value: 'test-only', path: '/', expires: Date.now() / 1000 - 60 }]), cookieSyncManaged: true, cookieSyncPending: true, __cookieSyncImport: true };
    await engine.syncProfiles([incoming]);
    engine.profiles.get(incoming.id).cookieSyncPending = false;
    const stale = { ...incoming }; delete stale.__cookieSyncImport;
    await engine.syncProfiles([stale]); assert.equal(engine.profiles.get(incoming.id).cookieSyncPending, false);
    await engine.syncProfiles([{ ...stale, cookies: '' }]); assert.equal(engine.profiles.get(incoming.id).cookieSyncPending, false);
    await engine.syncProfiles([incoming]); assert.equal(engine.profiles.get(incoming.id).cookieSyncPending, true);
    let received;
    await engine.importProfileCookies({ command: async (method, value) => { assert.equal(method, 'Storage.setCookies'); received = value.cookies; } }, incoming.cookies);
    assert.ok(received[0].expires > 0 && received[0].expires < Date.now() / 1000, 'expired setters must reach CDP as deletions');
  } finally { await fs.rm(root, { recursive: true, force: true }); }
});

test('managed Cookie deletion produces an expired setter, then excludes it from exported snapshots', () => {
  const p = local().profiles[0];
  const previous = { id: p.id, name: 'before', advanced: {}, cookies: JSON.stringify([{ url: 'https://example.com/', name: 'session', value: 'test-only', path: '/', secure: true }]) };
  const result = portable.mergeIntoDesktop([previous], local(), true, true, true).profiles[0];
  const expired = JSON.parse(result.cookies)[0];
  assert.ok(expired.expires < Date.now() / 1000 && expired.expires > 0);
  assert.equal(result.cookieSyncPending, true); assert.equal(result.__cookieSyncImport, true);
  assert.deepEqual(portable.cookiesFromDesktop(result.cookies), []);
});

test('manual snapshots cannot overwrite an automatic vault when inspected by the product UI', async () => {
  const password = 'test-only-password-123', data = await portable.seal(vault(local()), password);
  let writes = 0;
  await assert.rejects(portable.upload({ getUrl: 'https://example.com/vault' }, Buffer.from('unused'), async method => {
    if (method === 'PUT') writes++;
    return { status: 200, etag: '"version"', data };
  }, async previous => { if ((await portable.open(previous, password)).sync) throw new Error('automatic vault'); }), /automatic vault/);
  assert.equal(writes, 0);
});

test('oversized Cookie snapshots fail before engine JSON truncation can corrupt them', () => {
  const body = local(); body.profiles[0].cookies = Array.from({ length: 40 }, (_, n) => ({ url: 'https://example.com/', setCookie: `cookie${n}=${'x'.repeat(15000)}; Path=/` }));
  assert.throws(() => portable.mergeIntoDesktop([], body, true, true, true), /容量限制/);
});

function keychain() {
  const key = crypto.randomBytes(32);
  return {
    isEncryptionAvailable: () => true, getSelectedStorageBackend: () => 'test-keychain',
    encryptString(text) { const iv = crypto.randomBytes(12), c = crypto.createCipheriv('aes-256-gcm', key, iv); const data = Buffer.concat([c.update(text), c.final()]); return Buffer.concat([iv, c.getAuthTag(), data]); },
    decryptString(data) { const d = crypto.createDecipheriv('aes-256-gcm', key, data.subarray(0, 12)); d.setAuthTag(data.subarray(12, 28)); return Buffer.concat([d.update(data.subarray(28)), d.final()]).toString(); },
  };
}
test('two controllers sync both ways through local ciphertext and resolve conflicts without data loss', async () => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'openbrowser-sync-engine-'));
  const profiles = [local().profiles, []]; const controllers = [];
  try {
    for (let n = 0; n < 2; n++) {
      controllers.push(await new SyncController({ file: path.join(root, 'settings-' + n), safeStorage: keychain(),
        readLocal: () => ({ ...local(), profiles: copy(profiles[n]) }),
        applyLocal: async incoming => { for (const p of incoming) { const i = profiles[n].findIndex(v => v.id === p.id); if (i < 0) profiles[n].push(copy(p)); else profiles[n][i] = copy(p); } },
      }).init());
      await controllers[n].save({ enabled: true, provider: 'local', space: 'unit', password: 'test-only-password-123', localFile: path.join(root, 'vault.obsync'), interval: 15, includeCookies: true, acceptNew: true });
    }
    const [a, b] = controllers;
    await a.run(); await b.run(); assert.equal(profiles[1].length, 1);
    profiles[1][0].note = 'phone edit'; await b.run(); await a.run(); assert.equal(profiles[0][0].note, 'phone edit');
    profiles[0][0].name = 'computer'; profiles[1][0].name = 'phone';
    await a.run(); const conflicting = await b.run(); assert.equal(conflicting.conflicts.length, 1);
    await b.resolve('remote'); assert.equal(profiles[1][0].name, 'computer');
    const stored = await fs.readFile(path.join(root, 'settings-0'));
    assert.ok(!stored.includes(Buffer.from('test-only-password-123')));
    const encrypted = await fs.readFile(path.join(root, 'vault.obsync'));
    assert.ok(!encrypted.includes(Buffer.from('computer')));
    assert.equal((await portable.open(encrypted, 'test-only-password-123')).profiles[0].name, 'computer');
    await a.save({ enabled: false }); assert.equal(a.public().config.enabled, false);
    await a.forget(); assert.equal(a.public().history.length, 0);
  } finally { controllers.forEach(c => c.close()); await fs.rm(root, { recursive: true, force: true }); }
});

test('credentials cannot be persisted in an unprotected keychain or sent over private-network HTTP', async () => {
  const controller = new SyncController({ file: '/tmp/unused-sync-settings', safeStorage: { isEncryptionAvailable: () => true, getSelectedStorageBackend: () => 'basic_text' } });
  await assert.rejects(controller.save({ enabled: true, provider: 'local', localFile: '/tmp/unused-vault', space: 'unit', password: 'test-only-password' }), /密钥库/);
  assert.equal(controller.public().config.enabled, false);
  assert.throws(() => portable.request('GET', 'http://192.168.1.10/vault', { username: 'test-user', password: 'test-only' }), /HTTPS/);
  controller.close();
});
