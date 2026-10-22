package com.openbrowser.mobile.sync

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors

/** Only main-frame messages with a native-issued nonce can update this profile.
 * Restores use a network-free HTML document with an HTTP(S) base origin. */
class WebsiteBrowserBridge(context: Context, private val id: String, private val report: (String) -> Unit) {
    private val app = context.applicationContext
    private val snapshots = WebsiteSnapshots(app, id)
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val source = app.assets.open("sync/website-state-runtime.js").bufferedReader().use { it.readText() }
    private val name = "obSync" + UUID.randomUUID().toString().replace("-", "")
    private data class Pending(val view: WebView, val origin: String, val lease: ProfileLeases, val restore: JSONObject? = null, val revision: String = "", val target: String = "")
    private val requests = mutableMapOf<String, Pending>()
    private val bootstrap = mutableMapOf<WebView, String>()
    private val restoring = mutableSetOf<String>()
    private var closed = false
    private val writing = java.util.concurrent.atomic.AtomicInteger(0)
    val busy: Boolean get() = requests.isNotEmpty() || writing.get() > 0
    private fun supported() = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
    private fun origin(url: String): String? = runCatching {
        val u = java.net.URI(url)
        val host = u.host ?: return@runCatching null
        val p = if (u.port == -1 || u.scheme == "https" && u.port == 443 || u.scheme == "http" && u.port == 80) "" else ":${u.port}"
        WebsiteData.origin("${u.scheme}://$host$p")
    }.getOrNull()
    fun install(view: WebView) {
        if (!supported()) return
        WebViewCompat.addWebMessageListener(view, name, setOf("*")) { web, message, sourceOrigin, isMainFrame, _ ->
            if (!isMainFrame || closed) return@addWebMessageListener
            val raw = runCatching { message.data }.getOrNull() ?: return@addWebMessageListener
            if (raw.length > 3 * 1024 * 1024) return@addWebMessageListener
            val data = runCatching { JSONObject(raw) }.getOrNull() ?: return@addWebMessageListener
            val token = data.optString("nonce"); val pending = requests[token] ?: return@addWebMessageListener
            if (pending.view !== web || origin(sourceOrigin.toString()) != pending.origin || origin(web.url.orEmpty()) != pending.origin) return@addWebMessageListener
            if (pending.restore != null && web.url != bootstrap[web]) return@addWebMessageListener
            requests.remove(token)
            writing.incrementAndGet()
            worker.execute {
                val result = runCatching {
                    check(data.optBoolean("ok")) { "网站存储处理失败，请关闭其他页面后重试；快照已保留" }
                    if (pending.restore != null) snapshots.applied(pending.revision, pending.restore)
                    else {
                        val site = data.getJSONObject("site"); require(site.getString("origin") == pending.origin)
                        snapshots.save(site)
                    }
                }
                pending.lease.close()
                writing.decrementAndGet()
                handler.post {
                    if (pending.restore != null) { restoring.remove(pending.origin); bootstrap.remove(web) }
                    if (!closed) result.onSuccess { if (pending.restore != null) { web.clearHistory(); web.loadUrl(pending.target) } }.onFailure { report("网站存储处理失败，待应用数据已保留；请关闭其他页面后重试") }
                }
            }
        }
    }
    /** True means handled; caller must not also load the network URL. */
    fun load(view: WebView, url: String): Boolean {
        val origin = origin(url) ?: return false
        val doc = runCatching { snapshots.read() }.getOrElse { report("网站存储快照无法解密，已停止打开网站"); return true }
        val pending = doc.optJSONObject("pending") ?: return false
        val sites = pending.getJSONArray("sites")
        val site = (0 until sites.length()).map { sites.getJSONObject(it) }.firstOrNull { it.getString("origin") == origin } ?: return false
        if (!supported()) { view.stopLoading(); report("请升级 Android System WebView 后应用网站存储"); return true }
        if (origin in restoring) { view.stopLoading(); report("该网站正在恢复，请稍后重试"); return true }
        val lease = ProfileLeases.acquire(app, listOf(id), true) ?: return true
        val token = UUID.randomUUID().toString(); val base = "$origin/.well-known/openbrowser-restore-$token"
        val request = Pending(view, origin, lease, site, pending.getString("revision"), url)
        requests[token] = request; bootstrap[view] = base; restoring.add(origin)
        val payload = site.toString().replace("<", "\\u003c").replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
        val send = "window[${JSONObject.quote(name)}].postMessage(JSON.stringify({nonce:${JSONObject.quote(token)},ok:ok}))"
        val html = "<!doctype html><meta charset='utf-8'><title>OpenBrowser</title><script>($source).restore($payload).then(function(){var ok=true;$send},function(){var ok=false;$send});</script>"
        view.stopLoading()
        view.loadDataWithBaseURL(base, html, "text/html", "UTF-8", base)
        timeout(token)
        return true
    }
    fun isBootstrap(view: WebView, url: String?): Boolean = bootstrap[view]?.let { it == url } == true
    fun capture(view: WebView) {
        if (closed || !supported() || requests.values.any { it.view === view } || bootstrap.containsKey(view)) return
        val currentOrigin = origin(view.url.orEmpty()) ?: return
        if (currentOrigin.endsWith(".openbrowser")) return
        val lease = ProfileLeases.acquire(app, listOf(id), true) ?: return
        val token = UUID.randomUUID().toString(); requests[token] = Pending(view, currentOrigin, lease)
        val prefix = "window[${JSONObject.quote(name)}].postMessage(JSON.stringify("
        val suffix = "))"
        val nonce = JSONObject.quote(token)
        view.evaluateJavascript("($source).capture().then(function(site){${prefix}{nonce:$nonce,ok:true,site:site}$suffix},function(){${prefix}{nonce:$nonce,ok:false}$suffix});void 0", null)
        timeout(token)
    }
    private fun timeout(token: String) {
        handler.postDelayed({ requests.remove(token)?.let { p ->
            p.lease.close()
            if (p.restore != null) { p.view.stopLoading(); restoring.remove(p.origin); bootstrap.remove(p.view) }
            if (!closed) report("网站存储处理超时，保留上一份快照；请关闭其他页面后重试")
        } }, 45000)
    }
    fun close() {
        closed = true
        requests.values.forEach { it.lease.close() }; requests.clear(); bootstrap.clear(); restoring.clear()
        worker.shutdown()
    }
}
