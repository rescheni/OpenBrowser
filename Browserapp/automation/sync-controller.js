'use strict';
const fs = require('fs/promises');
const path = require('path');
const crypto = require('crypto');
const portable = require('./portable-sync');
const merge = require('./sync-merge');
const { provider } = require('./sync-provider');
const SECRETS = ['password', 'remotePassword', 'secretKey', 'sessionToken'];

class SyncController {
  constructor({ file, safeStorage, readLocal, applyLocal, excluded, changed }) {
    Object.assign(this, { file, safeStorage, readLocal, applyLocal, excluded, changed });
    this.document = { config: { enabled: false, provider: 'webdav', interval: 15, space: 'default', includeCookies: false, acceptNew: false }, state: {}, history: [] };
    this.timer = null; this.busy = false; this.failures = 0; this.generation = 0; this.queue = Promise.resolve();
  }
  available() {
    return this.safeStorage?.isEncryptionAvailable?.() && this.safeStorage.getSelectedStorageBackend?.() !== 'basic_text';
  }
  async init() {
    try {
      const encrypted = await fs.readFile(this.file);
      if (!this.available()) throw new Error('系统密钥库不可用，无法解锁同步设置');
      this.document = JSON.parse(this.safeStorage.decryptString(encrypted));
      this.schedule(1000);
    } catch (e) { if (e.code !== 'ENOENT') this.loadError = '同步设置读取失败，未启动自动同步。请解锁系统密钥库后重启。'; }
    return this;
  }
  public() {
    const config = { ...this.document.config };
    SECRETS.forEach(k => { config['has_' + k] = Boolean(config[k]); delete config[k]; });
    // Signed URLs may contain credentials; they are displayed only as configuration fields.
    return { config, busy: this.busy, history: this.document.history, conflicts: this.document.state?.conflicts || [], error: this.loadError || '', secureStorage: Boolean(this.available()) };
  }
  async persist() {
    if (!this.available()) throw new Error('系统密钥库不可用，不能保存自动同步凭据');
    const snapshot = JSON.stringify(this.document);
    this.queue = this.queue.catch(() => {}).then(async () => {
      await fs.mkdir(path.dirname(this.file), { recursive: true });
      const temp = this.file + '.tmp';
      const out = await fs.open(temp, 'w', 0o600);
      try { await out.writeFile(this.safeStorage.encryptString(snapshot)); await out.sync(); } finally { await out.close(); }
      await fs.rename(temp, this.file);
    });
    return this.queue;
  }
  async save(input) {
    if (!this.available()) throw new Error('系统密钥库不可用，不能保存自动同步凭据');
    if (this.busy && input.enabled === true) throw new Error('请先暂停同步，等待任务结束后修改设置');
    if (this.loadError) throw new Error(this.loadError);
    const previous = this.document.config, next = { ...previous, ...input };
    const sameSpace = next.space === previous.space && next.provider === previous.provider && next.getUrl === previous.getUrl && next.localFile === previous.localFile;
    for (const key of SECRETS) if (!input[key]) next[key] = sameSpace ? previous[key] : '';
    if (!/^[A-Za-z0-9_-]{1,64}$/.test(next.space || '') || !['webdav', 's3', 'object', 'lan', 'local'].includes(next.provider)) throw new Error('空间标识或提供商无效');
    next.interval = Math.max(1, Math.min(1440, Number(next.interval) || 15));
    next.enabled = input.enabled === true; next.includeCookies = input.includeCookies === undefined ? previous.includeCookies === true : input.includeCookies === true; next.acceptNew = input.acceptNew === undefined ? previous.acceptNew === true : input.acceptNew === true;
    for (const key of ['includeSiteData', 'includeCredentials']) next[key] = input[key] === undefined ? previous[key] === true : input[key] === true;
    if (next.enabled && String(next.password || '').length < 12) throw new Error('同步密码至少 12 字符');
    if (next.enabled && !(next.provider === 'local' ? next.localFile : next.getUrl)) throw new Error('请选择同步文件或填写完整对象 URL');
    if (next.enabled && next.provider === 's3' && (!next.region || !next.accessKey || !next.secretKey)) throw new Error('请填写 S3 凭据和 Region');
    if (next.provider === 'webdav' && next.username && next.putUrl && new URL(next.getUrl).origin !== new URL(next.putUrl).origin) throw new Error('WebDAV 上传下载地址必须同源');
    const oldDocument = JSON.parse(JSON.stringify(this.document));
    this.generation++; this.abort?.abort(); this.failures = 0;
    this.document.config = next;
    if (!sameSpace) this.document.state = {};
    else if (['includeCookies', 'includeSiteData', 'includeCredentials'].some(k => next[k] !== previous[k])) {
      for (const base of Object.values(this.document.state.bases || {})) for (const [option, section] of [['includeCookies','cookies'],['includeSiteData','siteData'],['includeCredentials','credentials']]) if (next[option] !== previous[option]) delete base[section];
      this.document.state.conflicts = []; this.document.state.decisions = {};
    }
    try { await this.persist(); } catch (error) { this.document = oldDocument; this.schedule(); throw error; }
    this.schedule(1000); return this.public();
  }
  schedule(delay) {
    clearTimeout(this.timer);
    if (!this.document.config.enabled || this.loadError) return;
    this.timer = setTimeout(() => { this.run().catch(() => {}); }, delay ?? this.document.config.interval * 60000);
    this.timer.unref?.();
  }
  kick() { if (!this.busy) this.schedule(2500); }
  async run() {
    if (this.busy) return this.public();
    if (!this.document.config.enabled || this.loadError) throw new Error(this.loadError || '请先保存并启用自动同步');
    this.busy = true; clearTimeout(this.timer); this.abort = new AbortController();
    const generation = this.generation, config = { ...this.document.config }, state = this.document.state;
    const active = () => { if (this.abort.signal.aborted || generation !== this.generation) throw new Error('同步设置已变化，任务停止'); };
    try {
      const transport = provider(config, this.abort.signal), version = await transport.read(); active();
      const remote = version.status === 404 ? null : await portable.open(version.data, config.password);
      const local = await this.readLocal(config); active();
      const result = merge.plan(local, remote, state, { ...config, excluded: this.excluded?.() || [] });
      if (result.conflicts.length) {
        this.document.state = { ...state, conflicts: result.conflicts };
        this.record('conflict', `${result.conflicts.length} 项冲突，未覆盖任何一端`); await this.persist(); return this.public();
      }
      const current = await this.readLocal(config);
      if (merge.canonical(current.profiles.map(merge.digests)) !== merge.canonical(local.profiles.map(merge.digests))) throw new Error('本机数据已变化，请重试');
      active();
      const signature = b => merge.canonical(b.profiles.map(p => ({ id: p.id, ...merge.digests(p) })).sort((a, b) => a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
      if (!remote || signature(remote) !== signature(result.body)) await transport.write(await portable.seal(result.body, config.password), version);
      active();
      await this.applyLocal(result.imports, local, config);
      active();
      this.document.state = { bases: result.bases, conflicts: [], decisions: {}, lastSuccess: Date.now() };
      this.record('success', `同步完成，接收 ${result.imports.length} 个环境`); this.failures = 0;
      await this.persist(); this.changed?.(); return this.public();
    } catch (error) {
      this.failures++;
      // Do not persist URLs, signatures or native errors that can contain credentials.
      this.record('error', /冲突|变化|空间|密码|ETag|HTTP \d+|关闭|代理|CPU|内存|配置|密钥|Cookie|容量|快照|HTTPS/.test(error.message || '') && !/https?:|Signature=|Credential=/.test(error.message) ? error.message.slice(0, 200) : '同步失败，请检查网络、权限、空间密码或运行中的环境');
      await this.persist().catch(() => {}); throw new Error(this.document.history[0].message);
    } finally {
      this.busy = false;
      this.schedule(this.failures ? Math.min(3600000, 30000 * 2 ** Math.min(this.failures - 1, 7)) : undefined);
    }
  }
  record(status, message) { this.document.history = [{ at: Date.now(), status, message }, ...(this.document.history || [])].slice(0, 50); }
  async resolve(side) {
    if (this.busy) throw new Error('请等待当前同步结束');
    this.document.state.decisions = merge.resolve(this.document.state.conflicts || [], side);
    await this.persist(); return this.run();
  }
  async forget() {
    if (this.busy) throw new Error('请先暂停同步，等待当前任务结束后再清除连接');
    this.close(); await this.queue.catch(() => {}); await fs.rm(this.file, { force: true }); await fs.rm(this.file + '.tmp', { force: true });
    this.document = { config: { enabled: false, space: 'default', provider: 'webdav', interval: 15 }, state: {}, history: [] }; this.loadError = ''; return this.public();
  }
  close() { this.generation++; clearTimeout(this.timer); this.abort?.abort(); }
}
module.exports = { SyncController };
