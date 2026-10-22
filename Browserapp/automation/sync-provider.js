'use strict';
const fs = require('fs/promises');
const path = require('path');
const crypto = require('crypto');
const portable = require('./portable-sync');
const s3 = require('./s3-sign');
const tag = data => '"' + crypto.createHash('sha256').update(data).digest('hex') + '"';
async function localRead(file) {
  let handle;
  try {
    handle = await fs.open(file, 'r');
    if ((await handle.stat()).size > portable.MAX_BYTES) throw new Error('同步文件超过 8 MB');
    const data = await handle.readFile();
    if (data.length > portable.MAX_BYTES) throw new Error('同步文件超过 8 MB');
    return data.length ? { status: 200, etag: tag(data), data } : { status: 404, etag: '', data };
  } catch (e) { if (e.code === 'ENOENT') return { status: 404, etag: '', data: Buffer.alloc(0) }; throw e; }
  finally { await handle?.close(); }
}
function provider(config, signal) {
  const send = async (method, data, version) => {
    const address = method === 'GET' ? config.getUrl : (config.putUrl || config.getUrl);
    const conditional = version ? (version.status === 404 ? { 'If-None-Match': '*' } : { 'If-Match': version.etag }) : {};
    const headers = config.provider === 's3' ? s3.sign(method, address, config, data || Buffer.alloc(0), conditional) : conditional;
    return portable.request(method, address, { data, signal, headers, username: config.provider === 'webdav' ? config.username : '', password: config.provider === 'webdav' ? config.remotePassword : '' });
  };
  return {
    async read() {
      const r = config.provider === 'local' ? await localRead(config.localFile) : await send('GET');
      if (![200, 404].includes(r.status)) throw new Error(`读取同步空间失败 HTTP ${r.status}`);
      if (r.status === 200 && (!r.etag || r.etag.startsWith('W/'))) throw new Error('自动同步要求服务器返回强 ETag');
      return r;
    },
    async write(data, version) {
      if (signal?.aborted) throw new Error('同步已暂停');
      if (config.provider === 'local') {
        const file = path.resolve(config.localFile), lockPath = file + '.obsync-lock';
        await fs.mkdir(path.dirname(file), { recursive: true });
        let lock;
        try { lock = await fs.open(lockPath, 'wx', 0o600); }
        catch (e) { if (e.code === 'EEXIST') throw new Error('本地同步文件正在写入或有遗留锁，请确认其他写入已结束'); throw e; }
        const temp = file + '.' + crypto.randomUUID() + '.tmp';
        try {
          const current = await localRead(file);
          if (current.status !== version.status || current.etag !== version.etag) throw new Error('同步文件已变化，请重试');
          // Keep the preceding ciphertext as recovery, including for mirrored-folder conflicts.
          if (current.status === 200) await fs.writeFile(file + '.previous', current.data, { mode: 0o600 });
          const out = await fs.open(temp, 'wx', 0o600);
          try { await out.writeFile(data); await out.sync(); } finally { await out.close(); }
          await fs.rename(temp, file);
        } finally { await fs.rm(temp, { force: true }); await lock.close(); await fs.rm(lockPath, { force: true }); }
        return;
      }
      const r = await send('PUT', data, version);
      if ([409, 412].includes(r.status)) throw new Error('同步文件已变化，请重试');
      if (![200, 201, 204].includes(r.status)) throw new Error(`写入同步空间失败 HTTP ${r.status}`);
    },
  };
}
module.exports = { provider, localRead };
