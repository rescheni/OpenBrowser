package com.openbrowser.mobile.ui

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.openbrowser.mobile.R
import com.openbrowser.mobile.data.BrowserEngine
import com.openbrowser.mobile.data.DevicePresets
import com.openbrowser.mobile.data.FingerprintGenerator
import com.openbrowser.mobile.data.Profile
import com.openbrowser.mobile.data.ProfileStore
import com.openbrowser.mobile.data.ProxyConfig
import com.openbrowser.mobile.data.ProxyType
import com.openbrowser.mobile.databinding.ActivityProfileEditBinding
import com.openbrowser.mobile.proxy.ProxyTester
import com.openbrowser.mobile.web.WebDataCleaner

class ProfileEditActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProfileEditBinding
    private lateinit var store: ProfileStore
    private lateinit var profile: Profile
    private var isNew = false

    private var selectedProxyType = ProxyType.DIRECT
    private var selectedWebRtcMode = WEBRTC_BLOCKED
    private var selectedEngine = BrowserEngine.WEBVIEW

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileEditBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = ProfileStore(this)

        val existingId = intent.getStringExtra(EXTRA_PROFILE_ID)
        val existing = existingId?.let { store.get(it) }
        if (existing != null) {
            profile = existing
        } else {
            isNew = true
            profile = DevicePresets.defaultPreset.applyTo(
                Profile(
                    name = getString(R.string.action_new_profile) + " ${store.count() + 1}",
                    group = "默认分组",
                ),
            )
        }

        binding.toolbar.title = getString(if (isNew) R.string.edit_title_new else R.string.edit_title_edit)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.inflateMenu(R.menu.menu_edit)
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_save) {
                save()
                true
            } else {
                false
            }
        }

        setupDropdowns()
        bindProfile()

        binding.btnTestProxy.setOnClickListener { testProxy() }
        binding.btnClearData.setOnClickListener { confirmClearData() }
        binding.btnSave.setOnClickListener { save() }
        binding.btnRandomFingerprint.setOnClickListener { confirmRandomFingerprint() }
        Motion.pressScale(binding.btnSave, 0.98f)
        if (!isNew) {
            binding.tvHeroTitle.setText(R.string.editor_hero_title_edit)
            binding.tvHeroDesc.setText(R.string.editor_hero_desc_edit)
        }
        Motion.staggerIn(binding.content, startDelay = 30L, step = 35L)
    }

    override fun onResume() {
        super.onResume()
        updateDataSize()
    }

    private fun setupDropdowns() {
        binding.presetInput.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, DevicePresets.all.map { it.title }),
        )
        binding.presetInput.setOnItemClickListener { _, _, position, _ ->
            applyPreset(DevicePresets.all[position])
        }

        binding.engineInput.setAdapter(
            ArrayAdapter(
                this,
                android.R.layout.simple_list_item_1,
                BrowserEngine.entries.map { engineLabel(it) },
            ),
        )
        binding.engineInput.setOnItemClickListener { _, _, position, _ ->
            selectedEngine = BrowserEngine.entries[position]
        }

        binding.webrtcInput.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, WEBRTC_MODES.map { webRtcLabel(it) }),
        )
        binding.webrtcInput.setOnItemClickListener { _, _, position, _ ->
            selectedWebRtcMode = WEBRTC_MODES[position]
            binding.etWebRtcIp.isEnabled = selectedWebRtcMode == WEBRTC_SPOOF
        }

        binding.proxyTypeInput.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, ProxyType.entries.map { proxyLabel(it) }),
        )
        binding.proxyTypeInput.setOnItemClickListener { _, _, position, _ ->
            selectedProxyType = ProxyType.entries[position]
            updateProxyFieldState()
        }
    }

    private fun bindProfile() {
        binding.etName.setText(profile.name)
        binding.etGroup.setText(profile.group)
        binding.etStartUrl.setText(profile.startUrl)
        binding.etNote.setText(profile.note)
        binding.presetInput.setText(DevicePresets.match(profile)?.title ?: profile.deviceModel, false)

        selectedEngine = BrowserEngine.from(profile.engine)
        binding.engineInput.setText(engineLabel(selectedEngine), false)

        binding.etUserAgent.setText(profile.userAgent)
        binding.etPlatform.setText(profile.platform)
        binding.etLocale.setText(profile.locale)
        binding.etTimezone.setText(profile.timezone)
        binding.etScreenWidth.setText(profile.screenWidth.toString())
        binding.etScreenHeight.setText(profile.screenHeight.toString())
        binding.etDpr.setText(trimNumber(profile.devicePixelRatio))
        binding.etCpu.setText(profile.hardwareConcurrency.toString())
        binding.etMemory.setText(profile.deviceMemory.toString())
        binding.etTouch.setText(profile.touchPoints.toString())
        binding.swMobile.isChecked = profile.mobile
        binding.etWebglVendor.setText(profile.webglVendor)
        binding.etWebglRenderer.setText(profile.webglRenderer)
        binding.swCanvas.isChecked = profile.canvasNoise
        binding.swAudio.isChecked = profile.audioNoise

        selectedWebRtcMode = when (profile.webRtcMode) {
            WEBRTC_REAL, WEBRTC_SPOOF -> profile.webRtcMode
            else -> WEBRTC_BLOCKED
        }
        binding.webrtcInput.setText(webRtcLabel(selectedWebRtcMode), false)
        binding.etWebRtcIp.setText(profile.webRtcIp)
        binding.etWebRtcIp.isEnabled = selectedWebRtcMode == WEBRTC_SPOOF

        selectedProxyType = profile.proxy.type
        binding.proxyTypeInput.setText(proxyLabel(selectedProxyType), false)
        binding.etProxyHost.setText(profile.proxy.host)
        binding.etProxyPort.setText(if (profile.proxy.port > 0) profile.proxy.port.toString() else "")
        binding.etProxyUser.setText(profile.proxy.username)
        binding.etProxyPass.setText(profile.proxy.password)
        updateProxyFieldState()
    }

    private fun applyPreset(preset: com.openbrowser.mobile.data.DevicePreset) {        val applied = preset.applyTo(profile)
        profile = applied
        binding.etUserAgent.setText(applied.userAgent)
        binding.etPlatform.setText(applied.platform)
        binding.etScreenWidth.setText(applied.screenWidth.toString())
        binding.etScreenHeight.setText(applied.screenHeight.toString())
        binding.etDpr.setText(trimNumber(applied.devicePixelRatio))
        binding.etTouch.setText(applied.touchPoints.toString())
        binding.swMobile.isChecked = applied.mobile
        binding.etWebglVendor.setText(applied.webglVendor)
        binding.etWebglRenderer.setText(applied.webglRenderer)
        binding.presetInput.setText(preset.title, false)
    }

    /** 随机换一套设备指纹，语言 / 时区 / 代理保持不变。 */
    private fun confirmRandomFingerprint() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.random_fingerprint_title)
            .setMessage(R.string.random_fingerprint_message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_confirm) { _, _ ->
                applyFingerprint(FingerprintGenerator.apply(profile))
                toast(getString(R.string.random_fingerprint_done))
            }
            .show()
    }

    private fun applyFingerprint(applied: Profile) {
        profile = applied
        binding.etUserAgent.setText(applied.userAgent)
        binding.etPlatform.setText(applied.platform)
        binding.etScreenWidth.setText(applied.screenWidth.toString())
        binding.etScreenHeight.setText(applied.screenHeight.toString())
        binding.etDpr.setText(trimNumber(applied.devicePixelRatio))
        binding.etCpu.setText(applied.hardwareConcurrency.toString())
        binding.etMemory.setText(applied.deviceMemory.toString())
        binding.etTouch.setText(applied.touchPoints.toString())
        binding.swMobile.isChecked = applied.mobile
        binding.etWebglVendor.setText(applied.webglVendor)
        binding.etWebglRenderer.setText(applied.webglRenderer)
        // 随机结果不对应任何预设，直接显示随机出来的机型名
        binding.presetInput.setText(applied.deviceModel, false)
    }

    private fun updateProxyFieldState() {
        val enabled = selectedProxyType != ProxyType.DIRECT
        binding.etProxyHost.isEnabled = enabled
        binding.etProxyPort.isEnabled = enabled
        binding.etProxyUser.isEnabled = enabled
        binding.etProxyPass.isEnabled = enabled
        binding.btnTestProxy.isEnabled = true
    }

    private fun collectProxy(): ProxyConfig? {
        val config = ProxyConfig(
            type = selectedProxyType,
            host = binding.etProxyHost.text.toString().trim(),
            port = binding.etProxyPort.text.toString().trim().toIntOrNull() ?: 0,
            username = binding.etProxyUser.text.toString().trim(),
            password = binding.etProxyPass.text.toString(),
        )
        if (selectedProxyType != ProxyType.DIRECT && !config.enabled) return null
        return config
    }

    private fun save() {
        val name = binding.etName.text.toString().trim()
        if (name.isBlank()) {
            toast(getString(R.string.name_required))
            binding.etName.requestFocus()
            return
        }
        val proxy = collectProxy()
        if (proxy == null) {
            toast(getString(R.string.proxy_required))
            return
        }

        val updated = profile.copy(
            name = name,
            group = binding.etGroup.text.toString().trim(),
            note = binding.etNote.text.toString().trim(),
            startUrl = binding.etStartUrl.text.toString().trim(),
            engine = selectedEngine.id,
            userAgent = binding.etUserAgent.text.toString().trim().ifBlank { profile.userAgent },
            platform = binding.etPlatform.text.toString().trim(),
            locale = binding.etLocale.text.toString().trim().ifBlank { "zh-CN" },
            timezone = binding.etTimezone.text.toString().trim().ifBlank { "Asia/Shanghai" },
            screenWidth = binding.etScreenWidth.text.toString().toIntOrNull()?.coerceAtLeast(200) ?: profile.screenWidth,
            screenHeight = binding.etScreenHeight.text.toString().toIntOrNull()?.coerceAtLeast(200) ?: profile.screenHeight,
            devicePixelRatio = binding.etDpr.text.toString().toDoubleOrNull()?.coerceIn(0.5, 10.0) ?: profile.devicePixelRatio,
            hardwareConcurrency = binding.etCpu.text.toString().toIntOrNull()?.coerceIn(1, 128) ?: 8,
            deviceMemory = binding.etMemory.text.toString().toIntOrNull()?.coerceIn(1, 128) ?: 4,
            touchPoints = binding.etTouch.text.toString().toIntOrNull()?.coerceIn(0, 10) ?: 5,
            mobile = binding.swMobile.isChecked,
            webglVendor = binding.etWebglVendor.text.toString().trim(),
            webglRenderer = binding.etWebglRenderer.text.toString().trim(),
            canvasNoise = binding.swCanvas.isChecked,
            audioNoise = binding.swAudio.isChecked,
            webRtcMode = selectedWebRtcMode,
            webRtcIp = binding.etWebRtcIp.text.toString().trim(),
            proxy = proxy,
            updatedAt = System.currentTimeMillis(),
        )
        store.upsert(updated)
        toast(getString(R.string.saved))
        setResult(RESULT_OK)
        finish()
    }

    private fun testProxy() {
        val proxy = collectProxy()
        if (proxy == null) {
            toast(getString(R.string.proxy_required))
            return
        }
        binding.tvProxyResult.text = getString(R.string.proxy_testing)
        binding.btnTestProxy.isEnabled = false
        Thread {
            val result = ProxyTester.test(proxy)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                binding.btnTestProxy.isEnabled = true
                binding.tvProxyResult.text = result.message
            }
        }.start()
    }

    private fun confirmClearData() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.clear_data_confirm_title)
            .setMessage(getString(R.string.clear_data_confirm_message, profile.name) + "\n\n" + getString(R.string.clear_data_running_hint))
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_confirm) { _, _ ->
                runCatching { WebDataCleaner.clearProfile(this, profile) }.onSuccess {
                    toast(getString(R.string.clear_data_done, profile.name)); updateDataSize()
                }.onFailure { toast(getString(R.string.sync_failed, it.message.orEmpty())) }
            }
            .show()
    }

    private fun updateDataSize() {
        binding.tvDataSize.text = getString(R.string.profile_data_size, WebDataCleaner.sizeLabel(this, profile))
    }

    private fun engineLabel(engine: BrowserEngine): String = when (engine) {
        BrowserEngine.WEBVIEW -> getString(R.string.engine_webview)
        BrowserEngine.GECKO -> getString(R.string.engine_gecko)
    }

    private fun proxyLabel(type: ProxyType): String = when (type) {        ProxyType.DIRECT -> getString(R.string.proxy_direct)
        ProxyType.HTTP -> getString(R.string.proxy_http)
        ProxyType.HTTPS -> getString(R.string.proxy_https)
        ProxyType.SOCKS5 -> getString(R.string.proxy_socks5)
    }

    private fun webRtcLabel(mode: String): String = when (mode) {
        WEBRTC_BLOCKED -> getString(R.string.webrtc_blocked)
        WEBRTC_SPOOF -> getString(R.string.webrtc_spoof)
        else -> getString(R.string.webrtc_real)
    }

    private fun trimNumber(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_PROFILE_ID = "com.openbrowser.mobile.extra.PROFILE_ID"

        private const val WEBRTC_BLOCKED = "blocked"
        private const val WEBRTC_REAL = "real"
        private const val WEBRTC_SPOOF = "spoof"
        private val WEBRTC_MODES = listOf(WEBRTC_BLOCKED, WEBRTC_REAL, WEBRTC_SPOOF)

        fun intent(context: android.content.Context, profileId: String?): Intent =
            Intent(context, ProfileEditActivity::class.java).apply {
                if (profileId != null) putExtra(EXTRA_PROFILE_ID, profileId)
            }
    }
}
