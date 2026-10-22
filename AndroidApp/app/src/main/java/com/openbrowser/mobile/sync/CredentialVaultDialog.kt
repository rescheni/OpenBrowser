package com.openbrowser.mobile.sync

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.webkit.WebView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.openbrowser.mobile.R
import com.openbrowser.mobile.data.ProfileStore
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.Executors

/** App-owned vault. No access to Google Password Manager or native Chrome Login Data. */
object CredentialVaultDialog {
    private val executor = Executors.newSingleThreadExecutor()
    private fun toast(a: AppCompatActivity, text: String) { Toast.makeText(a, text, Toast.LENGTH_LONG).show() }
    private fun <T> task(a: AppCompatActivity, block: () -> T, done: (T) -> Unit) {
        executor.execute { val result = runCatching(block); a.runOnUiThread { if (!a.isDestroyed && !a.isFinishing) result.onSuccess(done).onFailure { toast(a, a.getString(R.string.sync_vault_failed)) } } }
    }
    fun choose(a: AppCompatActivity) {
        val profiles = ProfileStore(a).all()
        if (profiles.isEmpty()) return
        MaterialAlertDialogBuilder(a).setTitle(R.string.sync_vault_title).setItems(profiles.map { it.name }.toTypedArray()) { _, index -> show(a, profiles[index].id) }.show()
    }
    fun show(a: AppCompatActivity, id: String) {
        a.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        task(a, { WebsiteSnapshots(a, id).read().optJSONArray("credentials") ?: org.json.JSONArray() }) { items ->
            val labels = listOf(a.getString(R.string.sync_vault_add)) + (0 until items.length()).map { val c = items.getJSONObject(it); c.getString("origin") + " · " + c.getString("username") }
            MaterialAlertDialogBuilder(a).setTitle(R.string.sync_vault_title).setItems(labels.toTypedArray()) { _, which ->
                if (which == 0) edit(a, id, null)
                else {
                    val c = items.getJSONObject(which - 1)
                    MaterialAlertDialogBuilder(a).setTitle(c.getString("origin")).setItems(arrayOf(a.getString(R.string.sync_vault_copy), a.getString(R.string.sync_vault_edit), a.getString(R.string.sync_vault_delete))) { _, action ->
                        when (action) {
                            0 -> copy(a, c.getString("password"))
                            1 -> edit(a, id, c)
                            2 -> MaterialAlertDialogBuilder(a).setMessage(R.string.sync_vault_delete_confirm).setNegativeButton(R.string.action_cancel, null).setPositiveButton(R.string.action_confirm) { _, _ ->
                                mutate(a, id) { WebsiteSnapshots(a, id).editCredential(c.getString("origin"), c.getString("username"), null, true) }
                            }.show()
                        }
                    }.show()
                }
            }.show()
        }
    }
    private fun edit(a: AppCompatActivity, id: String, previous: JSONObject?) {
        val body = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 10, 40, 10) }
        fun field(label: Int, secret: Boolean = false): TextInputEditText {
            val wrapper = TextInputLayout(a).apply { hint = a.getString(label) }
            val input = TextInputEditText(a).apply { isSaveEnabled = false; inputType = InputType.TYPE_CLASS_TEXT or (if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_NORMAL) }
            wrapper.addView(input); body.addView(wrapper); return input
        }
        val origin = field(R.string.sync_vault_origin); val username = field(R.string.sync_vault_username); val password = field(R.string.sync_vault_password, true)
        origin.setText(previous?.optString("origin").orEmpty()); username.setText(previous?.optString("username").orEmpty())
        if (previous != null) { origin.isEnabled = false; username.isEnabled = false }
        MaterialAlertDialogBuilder(a).setTitle(R.string.sync_vault_title).setView(body).setNegativeButton(R.string.action_cancel, null).setPositiveButton(R.string.action_confirm) { _, _ ->
            val o = origin.text.toString().trim(); val u = username.text.toString(); val p = password.text.toString().takeIf { it.isNotEmpty() }; password.setText("")
            mutate(a, id) { WebsiteSnapshots(a, id).editCredential(o, u, p) }
        }.show()
    }
    private fun mutate(a: AppCompatActivity, id: String, block: () -> Unit) = task(a, {
        val lease = ProfileLeases.acquire(a, listOf(id)) ?: error("环境正在使用")
        lease.use { block() }
    }) { show(a, id) }
    private fun copy(a: AppCompatActivity, password: String) {
        val clipboard = a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("OpenBrowser", password)
        if (android.os.Build.VERSION.SDK_INT >= 33) clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        clipboard.setPrimaryClip(clip)
        Handler(Looper.getMainLooper()).postDelayed({ runCatching { if (clipboard.primaryClip?.getItemAt(0)?.text?.toString() == password) clipboard.clearPrimaryClip() } }, 30000)
        toast(a, a.getString(R.string.sync_vault_copied))
    }
    fun fill(a: AppCompatActivity, view: WebView, id: String) {
        val raw = view.url ?: return
        val u = runCatching { URI(raw) }.getOrNull() ?: return
        val port = if (u.port == -1 || u.scheme == "https" && u.port == 443 || u.scheme == "http" && u.port == 80) "" else ":${u.port}"
        val origin = runCatching { WebsiteData.origin("${u.scheme}://${u.host}$port") }.getOrNull() ?: return
        task(a, { val all = WebsiteSnapshots(a, id).read().optJSONArray("credentials") ?: org.json.JSONArray(); (0 until all.length()).map { all.getJSONObject(it) }.filter { it.getString("origin") == origin } }) { entries ->
            if (entries.isEmpty()) { toast(a, a.getString(R.string.sync_vault_empty)); return@task }
            MaterialAlertDialogBuilder(a).setTitle(R.string.sync_vault_fill).setItems(entries.map { it.getString("username") }.toTypedArray()) { _, index ->
                val c = entries[index]
                val script = "(function(){if(location.origin!==${JSONObject.quote(origin)})return;const p=document.querySelector('input[type=password]');if(!p)return;const f=p.form||document;const u=f.querySelector('input[autocomplete=username],input[type=email],input[name=username],input[name=email],input[type=text]');const set=(e,v)=>{if(!e)return;Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(e,v);e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));};set(u,${JSONObject.quote(c.getString("username"))});set(p,${JSONObject.quote(c.getString("password"))});})()"
                view.evaluateJavascript(script, null)
            }.show()
        }
    }
}
