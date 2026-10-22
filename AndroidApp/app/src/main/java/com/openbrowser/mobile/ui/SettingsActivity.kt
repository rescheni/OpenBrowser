package com.openbrowser.mobile.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.openbrowser.mobile.BuildConfig
import com.openbrowser.mobile.R
import com.openbrowser.mobile.ThemePrefs
import com.openbrowser.mobile.UiPrefs
import com.openbrowser.mobile.data.ProfileStore
import com.openbrowser.mobile.data.TrashProfileStore
import com.openbrowser.mobile.databinding.ActivitySettingsBinding
import com.openbrowser.mobile.web.WebDataCleaner
import com.openbrowser.mobile.web.ProfileDataCleaner

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var store: ProfileStore
    private lateinit var trashStore: TrashProfileStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = ProfileStore(this)
        trashStore = TrashProfileStore(this)

        binding.toolbar.setNavigationOnClickListener { finish() }

        when (ThemePrefs.current(this)) {
            AppCompatDelegate.MODE_NIGHT_NO -> binding.themeLight.isChecked = true
            AppCompatDelegate.MODE_NIGHT_YES -> binding.themeDark.isChecked = true
            else -> binding.themeSystem.isChecked = true
        }

        binding.themeGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.themeLight -> AppCompatDelegate.MODE_NIGHT_NO
                R.id.themeDark -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            ThemePrefs.save(this, mode)
            AppCompatDelegate.setDefaultNightMode(mode)
        }

        if (UiPrefs.addressBarBottom(this)) {
            binding.addressBottom.isChecked = true
        } else {
            binding.addressTop.isChecked = true
        }
        binding.addressGroup.setOnCheckedChangeListener { _, checkedId ->
            UiPrefs.saveAddressBarBottom(this, checkedId == R.id.addressBottom)
        }

        binding.tvVersion.text = getString(R.string.settings_version, BuildConfig.VERSION_NAME)
        binding.tvAboutDesc.setText(R.string.settings_about_desc)
        binding.tvAboutLimits.setText(R.string.settings_about_limits)
        binding.tvDisclaimer.setText(R.string.settings_disclaimer)

        binding.btnClearAll.setOnClickListener { confirmClearAll() }
        binding.btnSync.setOnClickListener { startActivity(android.content.Intent(this, SyncActivity::class.java)) }
        Motion.pressScale(binding.btnClearAll, 0.98f)
        Motion.staggerIn(binding.content, startDelay = 40L, step = 45L)
    }

    override fun onResume() {
        super.onResume()
        updateStats()
    }

    private fun updateStats() {
        binding.tvProfileCount.text = getString(R.string.settings_profile_count, store.count())
        binding.tvStorage.text = getString(R.string.settings_storage_used, WebDataCleaner.totalSize(this))
    }

    private fun confirmClearAll() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_clear_all)
            .setMessage(getString(R.string.settings_clear_all_message) + "\n\n" + getString(R.string.clear_data_running_hint))
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_confirm) { _, _ ->
                runCatching {
                    val removed = store.allStrict()
                    val trashed = trashStore.all().map { it.profile }
                    val all = (removed + trashed).distinctBy { it.id }
                    val lease = com.openbrowser.mobile.sync.ProfileLeases.acquire(this, all.map { it.id }) ?: error("请先关闭所有浏览器环境，或等待同步结束")
                    lease.use {
                        all.forEach { ProfileDataCleaner.clearUnderLease(this, it) }
                        WebDataCleaner.clearAllUnderLease(this)
                        store.clearAll(); trashStore.clear()
                        com.openbrowser.mobile.sync.SyncStore(this).clear()
                        android.util.AtomicFile(java.io.File(filesDir, "sync-local.previous")).delete()
                        com.openbrowser.mobile.sync.SyncScheduler.configure(this)
                        ShortcutHelper.removePins(this, all)
                    }
                    updateStats()
                    Toast.makeText(this, R.string.settings_clear_all_done, Toast.LENGTH_SHORT).show()
                }.onFailure { Toast.makeText(this, getString(R.string.sync_failed, it.message.orEmpty()), Toast.LENGTH_LONG).show() }
            }
            .show()
    }
}
