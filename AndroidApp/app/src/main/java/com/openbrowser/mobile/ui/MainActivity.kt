package com.openbrowser.mobile.ui

import android.content.Context
import android.content.Intent
import android.app.Dialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.view.inputmethod.InputMethodManager
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.openbrowser.mobile.R
import com.openbrowser.mobile.data.BrowserEngine
import com.openbrowser.mobile.data.FingerprintGenerator
import com.openbrowser.mobile.data.Profile
import com.openbrowser.mobile.data.ProfileStore
import com.openbrowser.mobile.data.TrashProfileStore
import com.openbrowser.mobile.data.TrashedProfile
import com.openbrowser.mobile.databinding.ActivityMainBinding
import com.openbrowser.mobile.databinding.DialogBrowserListBinding
import com.openbrowser.mobile.databinding.ItemBrowserRowBinding
import com.openbrowser.mobile.web.WebDataCleaner
import com.openbrowser.mobile.web.ProfileDataCleaner
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var store: ProfileStore
    private lateinit var trashStore: TrashProfileStore
    private var allProfiles: List<Profile> = emptyList()
    private var animateListOnce = true
    private val adapter = ProfileAdapter(
        onOpen = { openBrowser(it) },
        onEdit = { openEditor(it.id) },
        onMore = { profile, anchor -> showProfileMenu(profile, anchor) },
    )

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        uri ?: return@registerForActivityResult
        runCatching {
            contentResolver.openOutputStream(uri)?.use { out ->
                out.write(store.exportJson().toByteArray(Charsets.UTF_8))
            } ?: error("无法写入文件")
        }.onSuccess {
            toast(getString(R.string.export_done, "profiles.json"))
        }.onFailure {
            toast(getString(R.string.export_failed, it.message ?: ""))
        }
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri ?: return@registerForActivityResult
        val text = runCatching {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
        if (text.isNullOrBlank()) {
            toast(getString(R.string.import_failed))
            return@registerForActivityResult
        }
        val count = runCatching { store.importFrom(text) }.getOrDefault(0)
        if (count > 0) {
            binding.etSearch.setText("")
            toast(resources.getQuantityString(R.plurals.import_done, count, count))
            refresh()
        } else {
            toast(getString(R.string.import_failed))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = ProfileStore(this)
        trashStore = TrashProfileStore(this)
        permanentlyRemoveExpiredTrash()

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        binding.recyclerView.layoutAnimation =
            AnimationUtils.loadLayoutAnimation(this, R.anim.layout_fall_down)

        binding.fab.setOnClickListener {
            // 只打开编辑器，用户点“保存”后才真正落库，取消则不留空环境。
            openEditor(null)
        }
        binding.btnEmptyCreate.setOnClickListener { openEditor(null) }

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                applyFilter(s?.toString().orEmpty())
            }

            override fun afterTextChanged(s: Editable?) = Unit
        })

        binding.etSearch.setOnEditorActionListener { _, _, _ ->
            hideKeyboardAndFocus()
            true
        }

        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy > 16 && binding.fab.isExtended) {
                    binding.fab.shrink()
                } else if (dy < -16 && !binding.fab.isExtended) {
                    binding.fab.extend()
                }
            }
        })

        Motion.staggerIn(binding.header)

        binding.toolbar.inflateMenu(R.menu.menu_main)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    true
                }

                R.id.action_import -> {
                    importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                    true
                }

                R.id.action_export -> {
                    exportLauncher.launch("openbrowser-profiles.json")
                    true
                }

                R.id.action_trash -> {
                    showTrash()
                    true
                }

                else -> false
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        allProfiles = store.all()
        binding.tvStats.text =
            resources.getQuantityString(R.plurals.main_stats, allProfiles.size, allProfiles.size)
        applyFilter(binding.etSearch.text?.toString().orEmpty())

        if (animateListOnce && allProfiles.isNotEmpty()) {
            animateListOnce = false
            binding.recyclerView.scheduleLayoutAnimation()
        }
    }

    private fun applyFilter(rawQuery: String) {
        val query = rawQuery.trim()
        val filtered = if (query.isEmpty()) {
            allProfiles
        } else {
            allProfiles.filter { profile ->
                profile.name.contains(query, ignoreCase = true) ||
                    profile.group.contains(query, ignoreCase = true) ||
                    profile.note.contains(query, ignoreCase = true)
            }
        }
        adapter.submit(filtered)

        val none = allProfiles.isEmpty()
        val noResults = filtered.isEmpty()
        updateEmptyState(noResults)
        binding.recyclerView.visibility = if (noResults) View.GONE else View.VISIBLE
        binding.tvEmptyTitle.setText(if (none) R.string.empty_title else R.string.search_empty_title)
        binding.tvEmptyHint.setText(if (none) R.string.empty_hint else R.string.search_empty_hint)
        binding.btnEmptyCreate.visibility = if (none) View.VISIBLE else View.GONE
    }

    private fun updateEmptyState(visible: Boolean) {
        binding.emptyView.animate().cancel()
        if (visible) {
            if (binding.emptyView.visibility != View.VISIBLE) {
                binding.emptyView.alpha = 0f
                binding.emptyView.visibility = View.VISIBLE
            }
            binding.emptyView.animate().alpha(1f).setDuration(200L).start()
        } else if (binding.emptyView.visibility == View.VISIBLE) {
            binding.emptyView.animate().alpha(0f).setDuration(150L).withEndAction {
                binding.emptyView.visibility = View.GONE
                binding.emptyView.alpha = 1f
            }.start()
        }
    }

    private fun openBrowser(profile: Profile) {
        hideKeyboardAndFocus()
        BrowserLauncher.open(this, profile)
    }

    private fun openEditor(profileId: String?) {
        if (profileId == null) binding.etSearch.setText("")
        hideKeyboardAndFocus()
        startActivity(ProfileEditActivity.intent(this, profileId))
    }

    private fun hideKeyboardAndFocus() {
        binding.etSearch.clearFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.etSearch.windowToken, 0)
    }

    private fun showProfileMenu(profile: Profile, anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, MENU_RANDOM_FINGERPRINT, 0, R.string.menu_random_fingerprint)
        popup.menu.add(0, MENU_DUPLICATE, 1, R.string.menu_duplicate)
        popup.menu.add(0, MENU_PIN, 2, R.string.menu_pin_shortcut)
        popup.menu.add(0, MENU_CLEAR, 3, R.string.menu_clear_data)
        popup.menu.add(0, MENU_DELETE, 4, R.string.menu_delete)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_RANDOM_FINGERPRINT -> {
                    confirmRandomFingerprint(profile)
                    true
                }

                MENU_DUPLICATE -> {
                    val copy = store.duplicate(profile.id)
                    if (copy != null) {
                        toast(getString(R.string.duplicated, copy.name))
                        refresh()
                    }
                    true
                }

                MENU_CLEAR -> {
                    confirmClearData(profile)
                    true
                }

                MENU_PIN -> {
                    pinShortcut(profile)
                    true
                }

                MENU_DELETE -> {
                    confirmDelete(profile)
                    true
                }

                else -> false
            }
        }
        popup.show()
    }

    private fun confirmRandomFingerprint(profile: Profile) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.random_fingerprint_title)
            .setMessage(R.string.random_fingerprint_message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_confirm) { _, _ ->
                store.upsert(FingerprintGenerator.apply(profile))
                toast(getString(R.string.random_fingerprint_done))
                refresh()
            }
            .show()
    }

    private fun pinShortcut(profile: Profile) {
        when (ShortcutHelper.requestPin(this, profile)) {
            ShortcutHelper.Result.REQUESTED -> toast(getString(R.string.shortcut_pinned))
            ShortcutHelper.Result.FALLBACK -> toast(getString(R.string.shortcut_fallback))
            ShortcutHelper.Result.FAILED -> toast(getString(R.string.shortcut_failed))
        }
    }

    private fun confirmClearData(profile: Profile) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.clear_data_confirm_title)
            .setMessage(getString(R.string.clear_data_confirm_message, profile.name) + "\n\n" + getString(R.string.clear_data_running_hint))
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_confirm) { _, _ ->
                runCatching { WebDataCleaner.clearProfile(this, profile) }
                    .onSuccess { toast(getString(R.string.clear_data_done, profile.name)) }
                    .onFailure { toast(getString(R.string.sync_failed, it.message.orEmpty())) }
            }
            .show()
    }

    private fun confirmDelete(profile: Profile) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_confirm_title)
            .setMessage(getString(R.string.delete_confirm_message, profile.name) + "\n\n" + getString(R.string.delete_trash_hint))
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                trashStore.add(profile)
                store.delete(profile.id)
                ShortcutHelper.removePin(this, profile)
                toast(getString(R.string.moved_to_trash, profile.name))
                refresh()
            }
            .show()
    }

    private fun showTrash() {
        val dialog = Dialog(this, R.style.Theme_OpenBrowser_FullScreenDialog)
        val view = DialogBrowserListBinding.inflate(layoutInflater)
        dialog.setContentView(view.root)
        view.tvListTitle.setText(R.string.trash_title)
        view.searchList.hint = getString(R.string.trash_search_hint)
        view.btnListClear.setText(R.string.trash_empty)
        view.btnListClose.setOnClickListener { dialog.dismiss() }

        val items = trashStore.all().toMutableList()
        val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        fun render(filter: String) {
            view.listContainer.removeAllViews()
            val query = filter.trim()
            val visible = items.filter {
                query.isEmpty() || it.profile.name.contains(query, true) ||
                    it.profile.group.contains(query, true)
            }
            view.tvListEmpty.setText(if (query.isEmpty()) R.string.trash_empty_state else R.string.list_no_match)
            view.tvListEmpty.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
            visible.forEach { item ->
                val row = ItemBrowserRowBinding.inflate(layoutInflater, view.listContainer, false)
                row.tvRowTitle.text = item.profile.name
                row.tvRowUrl.text = getString(
                    if (item.profile.engine == BrowserEngine.GECKO.id) R.string.engine_gecko else R.string.engine_webview,
                )
                row.tvRowTime.text = getString(
                    R.string.trash_deleted_at,
                    timeFormat.format(Date(item.deletedAt)),
                )
                row.root.setOnClickListener { restoreFromTrash(item, dialog, items) }
                row.btnRowDelete.setOnClickListener { confirmPermanentDelete(item, dialog, items) }
                view.listContainer.addView(row.root)
            }
        }
        view.btnListClear.setOnClickListener {
            if (items.isEmpty()) return@setOnClickListener
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.trash_empty_confirm_title)
                .setMessage(R.string.trash_empty_confirm_message)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_delete) { _, _ ->
                    items.toList().forEach { item -> runCatching { permanentlyDelete(item) }.onSuccess { items.remove(item) }.onFailure { toast(getString(R.string.sync_failed, it.message.orEmpty())) } }
                    render("")
                }
                .show()
        }
        view.searchList.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = render(s?.toString().orEmpty())
        })
        render("")
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    private fun restoreFromTrash(item: TrashedProfile, dialog: Dialog, items: MutableList<TrashedProfile>) {
        if (store.get(item.profile.id) != null) {
            toast(getString(R.string.trash_restore_conflict))
            return
        }
        store.upsert(item.profile)
        trashStore.remove(item.profile.id)
        items.remove(item)
        dialog.dismiss()
        toast(getString(R.string.trash_restored, item.profile.name))
        refresh()
    }

    private fun confirmPermanentDelete(item: TrashedProfile, dialog: Dialog, items: MutableList<TrashedProfile>) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.trash_permanent_title)
            .setMessage(getString(R.string.trash_permanent_message, item.profile.name))
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                runCatching { permanentlyDelete(item) }.onSuccess {
                    items.remove(item); dialog.dismiss(); toast(getString(R.string.trash_permanently_deleted))
                }.onFailure { toast(getString(R.string.sync_failed, it.message.orEmpty())) }
            }
            .show()
    }

    private fun permanentlyDelete(item: TrashedProfile) {
        ProfileDataCleaner.clear(this, item.profile)
        trashStore.remove(item.profile.id)
        ShortcutHelper.removePin(this, item.profile)
    }

    private fun permanentlyRemoveExpiredTrash() {
        trashStore.purgeExpired().forEach { runCatching { permanentlyDelete(it) } }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val MENU_DUPLICATE = 1
        private const val MENU_PIN = 2
        private const val MENU_CLEAR = 3
        private const val MENU_DELETE = 4
        private const val MENU_RANDOM_FINGERPRINT = 5
    }
}
