package com.openbrowser.mobile.ui

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.openbrowser.mobile.R
import com.openbrowser.mobile.data.ProfileStore
import com.openbrowser.mobile.sync.PortableBundle
import com.openbrowser.mobile.sync.PortableCrypto
import com.openbrowser.mobile.sync.PortableTransport
import com.openbrowser.mobile.sync.SyncEndpoint
import org.json.JSONObject
import java.util.concurrent.Executors

/** 用户主动推送/拉取；密码、签名 URL 和远端登录信息仅留在当前界面内存。 */
class SyncActivity : AppCompatActivity() {
    private lateinit var password: TextInputEditText
    private lateinit var getUrl: TextInputEditText
    private lateinit var putUrl: TextInputEditText
    private lateinit var username: TextInputEditText
    private lateinit var remotePassword: TextInputEditText
    private lateinit var cookies: CheckBox
    private lateinit var replace: CheckBox
    private lateinit var status: TextView
    private lateinit var selectedLabel: TextView
    private val selected = mutableSetOf<String>()
    private val controls = mutableListOf<View>()
    private val worker = Executors.newSingleThreadExecutor()
    private var pendingExport: ByteArray? = null
    private var pendingPassword = ""
    private var pendingReplace = false
    private var pendingCookies = false
    private var pendingWebsiteOptions = JSONObject()
    private lateinit var mode: Spinner
    private lateinit var autoEnabled: CheckBox
    private lateinit var acceptNew: CheckBox
    private lateinit var websiteData: CheckBox
    private lateinit var credentialSync: CheckBox
    private lateinit var wifiOnly: CheckBox
    private lateinit var space: TextInputEditText
    private lateinit var interval: TextInputEditText
    private lateinit var region: TextInputEditText
    private lateinit var accessKey: TextInputEditText
    private lateinit var secretKey: TextInputEditText
    private lateinit var sessionToken: TextInputEditText
    private lateinit var automaticStatus: TextView
    private lateinit var localLabel: TextView
    private var localUri = ""
    private val chooseSyncFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { uri -> retainFile(uri) } }
    private val createSyncFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { it?.let { uri -> retainFile(uri) } }

    private val exportFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val bytes = pendingExport
        pendingExport = null
        if (uri != null && bytes != null) task({
            contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: error(getString(R.string.sync_write_failed))
            getString(R.string.sync_export_done)
        }) { status.text = it }
    }
    private val importFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val pass = pendingPassword
        pendingPassword = ""
        val replaceExisting = pendingReplace
        val withCookies = pendingCookies
        val options = pendingWebsiteOptions
        if (uri != null) task({
            val bytes = contentResolver.openInputStream(uri)?.use { PortableTransport.readLimited(it) } ?: error(getString(R.string.sync_read_failed))
            decode(bytes, pass)
        }) { preview(it, replaceExisting, withCookies, options) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val toolbar = MaterialToolbar(this).apply {
            setTitle(R.string.sync_title)
            setNavigationIcon(R.drawable.ic_arrow_back)
            setNavigationOnClickListener { finish() }
        }
        root.addView(toolbar)
        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(32)) }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        body.addView(TextView(this).apply { setText(R.string.sync_description) })
        password = field(body, R.string.sync_password, secret = true)
        selectedLabel = TextView(this).apply { setText(R.string.sync_select_profiles) }
        body.addView(selectedLabel)
        button(body, R.string.sync_select_profiles) { chooseProfiles() }
        cookies = CheckBox(this).apply { setText(R.string.sync_include_cookies); isSaveEnabled = false }
        replace = CheckBox(this).apply { setText(R.string.sync_replace); isSaveEnabled = false }
        body.addView(cookies); body.addView(replace)
        controls.add(cookies); controls.add(replace)
        button(body, R.string.sync_export_file) {
            val pass = passphrase() ?: return@button
            val ids = selected.toSet(); val withCookies = cookies.isChecked; val storageOptions = manualDataOptions()
            task({ encode(ids, withCookies, pass, storageOptions) }) { pendingExport = it; exportFile.launch("openbrowser-${System.currentTimeMillis()}.obsync") }
        }
        button(body, R.string.sync_import_file) {
            pendingPassword = passphrase() ?: return@button
            pendingCookies = cookies.isChecked; pendingReplace = replace.isChecked; pendingWebsiteOptions = manualDataOptions()
            importFile.launch(arrayOf("application/octet-stream", "*/*"))
        }
        body.addView(TextView(this).apply { setText(R.string.sync_remote_description); setPadding(0, dp(20), 0, dp(8)) })
        mode = Spinner(this).apply {
            adapter = ArrayAdapter(this@SyncActivity, android.R.layout.simple_spinner_dropdown_item, resources.getStringArray(R.array.sync_modes))
        }
        body.addView(mode); controls.add(mode)
        getUrl = field(body, R.string.sync_get_url)
        putUrl = field(body, R.string.sync_put_url)
        username = field(body, R.string.sync_username)
        remotePassword = field(body, R.string.sync_remote_password, secret = true)
        fun endpoint(): SyncEndpoint {
            val get = getUrl.text.toString().trim()
            return SyncEndpoint(get, putUrl.text.toString().trim().ifBlank { get }, if (mode.selectedItemPosition == 0) username.text.toString() else "", if (mode.selectedItemPosition == 0) remotePassword.text.toString() else "")
        }
        button(body, R.string.sync_push) {
            if (mode.selectedItemPosition >= 3) { status.setText(R.string.sync_auto_use_buttons); return@button }
            val pass = passphrase() ?: return@button
            val config = endpoint(); val ids = selected.toSet(); val withCookies = cookies.isChecked; val storageOptions = manualDataOptions()
            MaterialAlertDialogBuilder(this).setMessage(R.string.sync_push_confirm)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_confirm) { _, _ -> task({
                    PortableTransport.upload(config, encode(ids, withCookies, pass, storageOptions)) { previous ->
                        check(!decode(previous, pass).has("sync")) { "这是自动同步文件，请使用立即双向同步，不要用手动快照覆盖" }
                    }
                    getString(R.string.sync_push_done)
                }) { status.text = it } }.show()
        }
        button(body, R.string.sync_pull) {
            if (mode.selectedItemPosition >= 3) { status.setText(R.string.sync_auto_use_buttons); return@button }
            val pass = passphrase() ?: return@button
            val config = endpoint(); val withCookies = cookies.isChecked; val replaceExisting = replace.isChecked; val options = manualDataOptions()
            task({ decode(PortableTransport.download(config), pass) }) { preview(it, replaceExisting, withCookies, options) }
        }
        status = TextView(this).apply { setText(R.string.sync_idle); setPadding(0, dp(16), 0, 0) }
        body.addView(status)
        setupAutomatic(body)
    }

    private fun setupAutomatic(body: LinearLayout) {
        body.addView(TextView(this).apply { setText(R.string.sync_auto_description); setPadding(0, dp(24), 0, dp(8)) })
        autoEnabled = CheckBox(this).apply { setText(R.string.sync_auto_enabled) }
        acceptNew = CheckBox(this).apply { setText(R.string.sync_auto_accept_new) }
        wifiOnly = CheckBox(this).apply { setText(R.string.sync_auto_wifi) }
        websiteData = CheckBox(this).apply { setText(R.string.sync_include_site_data); isSaveEnabled = false }
        credentialSync = CheckBox(this).apply { setText(R.string.sync_include_credentials); isSaveEnabled = false }
        button(body, R.string.sync_vault_title) { com.openbrowser.mobile.sync.CredentialVaultDialog.choose(this) }
        listOf(autoEnabled, acceptNew, wifiOnly, websiteData, credentialSync).forEach { body.addView(it); controls.add(it) }
        space = field(body, R.string.sync_auto_space)
        interval = field(body, R.string.sync_auto_interval).apply { inputType = InputType.TYPE_CLASS_NUMBER }
        region = field(body, R.string.sync_s3_region)
        accessKey = field(body, R.string.sync_s3_access)
        secretKey = field(body, R.string.sync_s3_secret, true)
        sessionToken = field(body, R.string.sync_s3_token, true)
        localLabel = TextView(this).apply { setText(R.string.sync_auto_choose_file) }; body.addView(localLabel)
        button(body, R.string.sync_auto_choose_file) { chooseSyncFile.launch(arrayOf("application/octet-stream", "*/*")) }
        button(body, R.string.sync_auto_create_file) { createSyncFile.launch("openbrowser-sync-vault.obsync") }
        button(body, R.string.sync_auto_save) {
            val config = automaticConfig()
            task({ com.openbrowser.mobile.sync.SyncCoordinator(this).save(config); com.openbrowser.mobile.sync.SyncStore(this).read() }) { showAutomatic(it, true) }
        }
        button(body, R.string.sync_auto_run) {
            task({ val r = com.openbrowser.mobile.sync.SyncCoordinator(this).run(); r.message to com.openbrowser.mobile.sync.SyncStore(this).read() }) { (message, document) -> status.text = message; showAutomatic(document, false) }
        }
        button(body, R.string.sync_auto_pause) {
            task({
                val config = com.openbrowser.mobile.sync.SyncStore(this).read().getJSONObject("config").put("enabled", false)
                com.openbrowser.mobile.sync.SyncCoordinator(this).save(config); com.openbrowser.mobile.sync.SyncStore(this).read()
            }) { showAutomatic(it, true) }
        }
        button(body, R.string.sync_auto_refresh) { task({ com.openbrowser.mobile.sync.SyncStore(this).read() }) { showAutomatic(it, true) } }
        button(body, R.string.sync_auto_previous) {
            task({ com.openbrowser.mobile.sync.SyncCoordinator(this).previousBackup() }) { pendingExport = it; exportFile.launch("openbrowser-recovery-${System.currentTimeMillis()}.obsync") }
        }
        for ((title, side) in listOf(R.string.sync_auto_local to "local", R.string.sync_auto_remote to "remote")) button(body, title) {
            MaterialAlertDialogBuilder(this).setMessage(title).setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_confirm) { _, _ -> task({
                    val coordinator = com.openbrowser.mobile.sync.SyncCoordinator(this); coordinator.resolve(side); coordinator.run()
                    com.openbrowser.mobile.sync.SyncStore(this).read()
                }) { showAutomatic(it, false) } }.show()
        }
        button(body, R.string.sync_auto_forget) {
            MaterialAlertDialogBuilder(this).setMessage(R.string.sync_auto_forget_confirm).setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_confirm) { _, _ -> task({
                    com.openbrowser.mobile.sync.SyncCoordinator(this).forget()
                    com.openbrowser.mobile.sync.SyncStore(this).read()
                }) { showAutomatic(it, true) } }.show()
        }
        automaticStatus = TextView(this).apply { setPadding(0, dp(12), 0, 0) }; body.addView(automaticStatus)
        task({ com.openbrowser.mobile.sync.SyncStore(this).read() }) { showAutomatic(it, true) }
    }

    private fun retainFile(uri: android.net.Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            localUri = uri.toString(); localLabel.text = localUri
        } catch (_: Exception) { status.setText(R.string.sync_auto_file_permission) }
    }

    private fun automaticConfig(): JSONObject = JSONObject().put("enabled", autoEnabled.isChecked).put("acceptNew", acceptNew.isChecked).put("wifiOnly", wifiOnly.isChecked)
        .put("provider", listOf("webdav", "object", "lan", "s3", "local")[mode.selectedItemPosition]).put("space", space.text.toString().trim())
        .put("interval", interval.text.toString().toIntOrNull() ?: 15).put("password", password.text.toString()).put("includeCookies", cookies.isChecked)
        .put("includeSiteData", websiteData.isChecked).put("includeCredentials", credentialSync.isChecked)
        .put("getUrl", getUrl.text.toString().trim()).put("putUrl", putUrl.text.toString().trim()).put("username", username.text.toString()).put("remotePassword", remotePassword.text.toString())
        .put("region", region.text.toString().trim()).put("accessKey", accessKey.text.toString().trim()).put("secretKey", secretKey.text.toString()).put("sessionToken", sessionToken.text.toString())
        .put("localUri", localUri).put("selected", org.json.JSONArray(selected.toList()))

    private fun showAutomatic(document: JSONObject, fill: Boolean) {
        val c = document.getJSONObject("config")
        if (fill) {
            mode.setSelection(listOf("webdav", "object", "lan", "s3", "local").indexOf(c.optString("provider", "webdav")).coerceAtLeast(0))
            websiteData.isChecked = c.optBoolean("includeSiteData"); credentialSync.isChecked = c.optBoolean("includeCredentials")
            autoEnabled.isChecked = c.optBoolean("enabled"); acceptNew.isChecked = c.optBoolean("acceptNew"); wifiOnly.isChecked = c.optBoolean("wifiOnly"); cookies.isChecked = c.optBoolean("includeCookies")
            for ((view, key) in listOf(password to "password", getUrl to "getUrl", putUrl to "putUrl", username to "username", remotePassword to "remotePassword", space to "space", interval to "interval", region to "region", accessKey to "accessKey", secretKey to "secretKey", sessionToken to "sessionToken")) {
                view.setText(c.optString(key, when (key) { "space" -> "default"; "interval" -> "15"; "region" -> "us-east-1"; else -> "" }))
            }
            localUri = c.optString("localUri"); localLabel.text = localUri.ifEmpty { getString(R.string.sync_auto_choose_file) }
            selected.clear(); c.optJSONArray("selected")?.let { for (i in 0 until it.length()) selected.add(it.getString(i)) }
            selectedLabel.text = getString(R.string.sync_selected_count, selected.size)
        }
        val lines = mutableListOf(getString(if (c.optBoolean("enabled")) R.string.sync_auto_active else R.string.sync_auto_paused))
        val conflicts = document.optJSONObject("state")?.optJSONArray("conflicts")
        if (conflicts != null) for (i in 0 until conflicts.length()) { val conflict = conflicts.getJSONObject(i); lines.add("${conflict.optString("name")} · ${conflict.optString("section")}") }
        document.optJSONArray("history")?.let { history -> for (i in 0 until history.length()) {
            val entry = history.getJSONObject(i)
            lines.add(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(entry.optLong("at"))) + " · " + entry.optString("message"))
        } }
        automaticStatus.text = lines.joinToString("\n")
    }

    private fun chooseProfiles() {
        val profiles = ProfileStore(this).all()
        MaterialAlertDialogBuilder(this).setTitle(R.string.sync_select_profiles)
            .setMultiChoiceItems(profiles.map { it.name }.toTypedArray(), profiles.map { it.id in selected }.toBooleanArray()) { _, index, checked ->
                if (checked) selected.add(profiles[index].id) else selected.remove(profiles[index].id)
            }.setPositiveButton(R.string.action_confirm) { _, _ -> selectedLabel.text = getString(R.string.sync_selected_count, selected.size) }.show()
    }

    private fun manualDataOptions() = JSONObject().put("includeSiteData", websiteData.isChecked).put("includeCredentials", credentialSync.isChecked)

    private fun encode(ids: Set<String>, withCookies: Boolean, pass: String, options: JSONObject): ByteArray {
        val profiles = ProfileStore(this).all().filter { it.id in ids }
        require(profiles.isNotEmpty()) { getString(R.string.sync_select_profiles) }
        val plain = PortableBundle.create(this, profiles, withCookies, options).toString().toByteArray(Charsets.UTF_8)
        return try { PortableCrypto.seal(plain, pass) } finally { plain.fill(0) }
    }

    private fun decode(data: ByteArray, pass: String): JSONObject {
        val plain = try { PortableCrypto.open(data, pass) } catch (_: Exception) { error(getString(R.string.sync_decrypt_failed)) }
        return try { PortableBundle.validate(JSONObject(String(plain, Charsets.UTF_8))) } finally { plain.fill(0) }
    }

    private fun preview(body: JSONObject, replaceExisting: Boolean, withCookies: Boolean, options: JSONObject) {
        val profiles = body.getJSONArray("profiles")
        val local = ProfileStore(this).all().map { it.id }.toSet()
        val duplicates = (0 until profiles.length()).count { profiles.getJSONObject(it).getString("id") in local }
        val count = (0 until profiles.length()).sumOf { profiles.getJSONObject(it).getJSONArray("cookies").length() }
        status.text = getString(R.string.sync_idle)
        MaterialAlertDialogBuilder(this).setTitle(R.string.sync_import_file)
            .setMessage(getString(R.string.sync_preview, profiles.length(), duplicates, count) + "\n\n" + getString(if (replaceExisting) R.string.sync_replace else R.string.sync_keep_local) + "\n" + getString(if (withCookies) R.string.sync_cookie_import_on else R.string.sync_cookie_import_off) + "\n" + getString(R.string.sync_website_preview, getString(if (options.optBoolean("includeSiteData")) R.string.sync_data_on else R.string.sync_data_off), getString(if (options.optBoolean("includeCredentials")) R.string.sync_data_on else R.string.sync_data_off)))
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_confirm) { _, _ -> task({ PortableBundle.apply(this, body, replaceExisting, withCookies, options) }) { status.text = it } }.show()
    }

    private fun field(body: LinearLayout, hintResource: Int, secret: Boolean = false): TextInputEditText {
        val layout = TextInputLayout(this).apply { hint = getString(hintResource) }
        val edit = TextInputEditText(this).apply {
            isSingleLine = true; isSaveEnabled = false
            inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        layout.addView(edit)
        body.addView(layout, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        controls.add(edit)
        return edit
    }

    private fun button(body: LinearLayout, title: Int, action: () -> Unit) {
        val button = MaterialButton(this).apply { setText(title); setOnClickListener { action() } }
        body.addView(button); controls.add(button)
    }

    private fun passphrase(): String? = password.text.toString().takeIf { it.length >= 12 } ?: run {
        Toast.makeText(this, R.string.sync_password_required, Toast.LENGTH_LONG).show(); null
    }

    private fun <T> task(work: () -> T, done: (T) -> Unit) {
        controls.forEach { it.isEnabled = false }; status.setText(R.string.sync_working)
        worker.execute {
            val result = runCatching(work)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                controls.forEach { it.isEnabled = true }
                result.onSuccess(done).onFailure { status.text = getString(R.string.sync_failed, it.message.orEmpty()) }
            }
        }
    }

    override fun onDestroy() { pendingExport = null; pendingPassword = ""; worker.shutdownNow(); super.onDestroy() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
