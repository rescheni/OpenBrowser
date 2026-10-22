'use strict';
const crypto = require('crypto');
const sha = data => crypto.createHash('sha256').update(data).digest('hex');
const hmac = (key, value) => crypto.createHmac('sha256', key).update(value).digest();
const encode = value => encodeURIComponent(value).replace(/[!'()*]/g, c => '%' + c.charCodeAt(0).toString(16).toUpperCase());

/** AWS SigV4, single-object GET/PUT. Works with virtual-host and path-style URLs. */
function sign(method, address, config, data = Buffer.alloc(0), extra = {}, at = new Date()) {
  const url = new URL(address);
  if (url.protocol !== 'https:' && !(url.protocol === 'http:' && require('./portable-sync').isLocalHost(url.hostname))) throw new Error('S3 地址需使用 HTTPS，私网 IP 可使用 HTTP');
  if (url.search || url.hash || url.username || url.password) throw new Error('S3 原生签名使用无查询参数的完整对象 URL');
  if (!/^[A-Za-z0-9-]{1,64}$/.test(config.region || '') || !config.accessKey || !config.secretKey) throw new Error('请填写 S3 Region、Access Key 和 Secret Key');
  const stamp = at.toISOString().replace(/[:-]|\.\d{3}/g, ''), day = stamp.slice(0, 8);
  const headers = { ...Object.fromEntries(Object.entries(extra).map(([k, v]) => [k.toLowerCase(), String(v).trim().replace(/\s+/g, ' ')])), host: url.host, 'x-amz-date': stamp, 'x-amz-content-sha256': sha(data) };
  if (config.sessionToken) headers['x-amz-security-token'] = config.sessionToken;
  const names = Object.keys(headers).sort(), signed = names.join(';');
  const uri = url.pathname.split('/').map(p => encode(decodeURIComponent(p))).join('/') || '/';
  const canonical = [method, uri, '', names.map(n => n + ':' + headers[n] + '\n').join(''), signed, sha(data)].join('\n');
  const scope = `${day}/${config.region}/s3/aws4_request`;
  const stringToSign = ['AWS4-HMAC-SHA256', stamp, scope, sha(canonical)].join('\n');
  const keys = [hmac('AWS4' + config.secretKey, day)];
  keys.push(hmac(keys[0], config.region)); keys.push(hmac(keys[1], 's3')); keys.push(hmac(keys[2], 'aws4_request'));
  const signature = hmac(keys[3], stringToSign).toString('hex'); keys.forEach(k => k.fill(0));
  return { ...headers, Authorization: `AWS4-HMAC-SHA256 Credential=${config.accessKey}/${scope},SignedHeaders=${signed},Signature=${signature}` };
}
module.exports = { sign };
