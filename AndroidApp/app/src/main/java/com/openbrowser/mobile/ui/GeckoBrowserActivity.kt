package com.openbrowser.mobile.ui

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.URLUtil
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.openbrowser.mobile.R
import com.openbrowser.mobile.UiPrefs
import com.openbrowser.mobile.data.BookmarkStore
import com.openbrowser.mobile.data.BrowserEngineCapabilities
import com.openbrowser.mobile.data.HistoryStore
import com.openbrowser.mobile.data.Profile
import com.openbrowser.mobile.data.ProfileStore
import com.openbrowser.mobile.databinding.ActivityBrowserBinding
import com.openbrowser.mobile.databinding.DialogBrowserListBinding
import com.openbrowser.mobile.databinding.DialogTabsBinding
import com.openbrowser.mobile.databinding.ItemBrowserRowBinding
import com.openbrowser.mobile.databinding.ItemTabCardBinding
import com.openbrowser.mobile.web.AssetServer
import org.json.JSONObject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebRequestError
import org.mozilla.geckoview.WebResponse
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 使用 Firefox 内核（GeckoView）的环境浏览器，运行在独立的 `:gecko` 进程。
 *
 * 环境隔离依赖 [GeckoSessionSettings.Builder.contextId]：GeckoView 会为不同 contextId
 * 分配独立的 Cookie / localStorage / IndexedDB（等价于 Firefox 的多账户容器）。
 *
 * 注意：GeckoView 目前没有公开的代理配置 API，因此该内核下环境代理不会生效；
 * Canvas / WebGL 等指纹注入也只在系统 WebView 内核可用。
 */
class GeckoBrowserActivity : AppCompatActivity() {
    private var syncLease: com.openbrowser.mobile.sync.ProfileLeases? = null

    private data class Tab(
        var session: GeckoSession,
        var title: String,
        var url: String,
        var desktop: Boolean = false,
        var canGoBack: Boolean = false,
        var canGoForward: Boolean = false,
    )

    private lateinit var binding: ActivityBrowserBinding
    private lateinit var store: ProfileStore
    private lateinit var historyStore: HistoryStore
    private lateinit var bookmarkStore: BookmarkStore
    private lateinit var profile: Profile
    private lateinit var geckoView: GeckoView
    private lateinit var assetServer: AssetServer

    private val tabs = mutableListOf<Tab>()
    private var activeId = -1
    private var nextId = 1
    private var fullscreen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val profileId = intent.getStringExtra(EXTRA_PROFILE_ID)
        if (profileId.isNullOrBlank()) {
            finish()
            return
        }
        store = ProfileStore(this)
        historyStore = HistoryStore(this)
        bookmarkStore = BookmarkStore(this)
        val found = store.get(profileId)
        if (found == null) {
            Toast.makeText(this, R.string.operation_failed, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        profile = found
        if (com.openbrowser.mobile.web.GeckoDataCleanup.pending(this, profile.id).exists()) {
            com.openbrowser.mobile.web.GeckoDataCleanup.request(this, profile)
            Toast.makeText(this, R.string.sync_profile_busy, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        syncLease = com.openbrowser.mobile.sync.ProfileLeases.acquire(this, listOf(profile.id), true)
        if (syncLease == null) {
            Toast.makeText(this, R.string.sync_profile_busy, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // Gecko 尚未接入上游代理。必须在打开任何会话之前拒绝，不能提示后继续直连。
        if (profile.proxy.type != com.openbrowser.mobile.data.ProxyType.DIRECT) {
            Toast.makeText(this, R.string.gecko_proxy_unsupported, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        binding = ActivityBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.findBar.visibility = View.GONE
        applyAddressBarPosition()

        geckoView = GeckoView(this).apply {
            setBackgroundColor(0xFF000000.toInt())
        }
        binding.webContainer.addView(
            geckoView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        assetServer = AssetServer().apply { start() }
        setupTopBar()
        binding.btnTabCount.setOnClickListener { showTabSwitcher() }

        openTab(first = true)
        store.markUsed(profile.id)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleBack()
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val newId = intent.getStringExtra(EXTRA_PROFILE_ID)
        if (!newId.isNullOrBlank() && newId != profile.id) {
            recreate()
        }
    }

    private fun setupTopBar() {
        binding.urlBar.hint = profile.name
        binding.btnBack.setOnClickListener { handleBack() }
        binding.btnMenu.setOnClickListener { showBrowserMenu() }
        binding.findInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                findInPage(s?.toString().orEmpty(), forward = true)
            }
        })
        binding.findInput.setOnEditorActionListener { _, actionId, event ->
            val enter = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER &&
                event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEARCH || enter) {
                findInPage(binding.findInput.text.toString(), forward = true)
                true
            } else {
                false
            }
        }
        binding.btnFindPrev.setOnClickListener {
            findInPage(binding.findInput.text.toString(), forward = false)
        }
        binding.btnFindNext.setOnClickListener {
            findInPage(binding.findInput.text.toString(), forward = true)
        }
        binding.btnFindClose.setOnClickListener { closeFindBar() }
        binding.urlBar.setOnEditorActionListener { _, actionId, event ->
            val enter = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER &&
                event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_GO || enter) {
                loadFromUrlBar()
                true
            } else {
                false
            }
        }
    }

    private fun showFindBar() {
        if (binding.findBar.visibility == View.VISIBLE) return
        binding.findBar.visibility = View.VISIBLE
        binding.findBar.alpha = 0f
        binding.findBar.animate().alpha(1f).setDuration(160L).start()
        binding.findInput.requestFocus()
        getSystemService(InputMethodManager::class.java)
            ?.showSoftInput(binding.findInput, InputMethodManager.SHOW_IMPLICIT)
        findInPage(binding.findInput.text.toString(), forward = true)
    }

    private fun closeFindBar() {
        binding.findBar.visibility = View.GONE
        binding.findInput.setText("")
        binding.tvFindCount.text = ""
        activeTab()?.session?.finder?.clear()
        getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(binding.findInput.windowToken, 0)
    }

    private fun findInPage(query: String, forward: Boolean) {
        val finder = activeTab()?.session?.finder ?: return
        if (query.isBlank()) {
            finder.clear()
            binding.tvFindCount.text = ""
            return
        }
        val flags = if (forward) 0 else GeckoSession.FINDER_FIND_BACKWARDS
        finder.setDisplayFlags(GeckoSession.FINDER_DISPLAY_HIGHLIGHT_ALL)
        finder.find(query, flags).then { result ->
            val total = result?.total ?: 0
            binding.tvFindCount.text = if (total > 0) {
                "${result?.current}/$total"
            } else {
                "0/0"
            }
            GeckoResult.fromValue(Unit)
        }
    }

    /** 与 WebView 内核保持一致：地址栏可放到页面底部。 */
    private fun applyAddressBarPosition() {
        if (!UiPrefs.addressBarBottom(this)) return
        val root = binding.root
        root.removeAllViews()
        root.addView(binding.webContainer)
        root.addView(binding.findBar)
        root.addView(binding.progressBar)
        root.addView(binding.addressBar)

        val params = binding.addressBar.layoutParams as? LinearLayout.LayoutParams ?: return
        params.topMargin = 0
        params.bottomMargin = dp(6)
        binding.addressBar.layoutParams = params
    }

    // ---------------------------------------------------------------- 会话 / 标签

    private fun createSession(desktop: Boolean = false): GeckoSession {
        val settings = GeckoSessionSettings.Builder()
            .contextId(profile.dataSuffix)
            .userAgentOverride(if (desktop) DESKTOP_USER_AGENT else profile.userAgent)
            .suspendMediaWhenInactive(true)
            .build()
        val session = GeckoSession(settings)
        session.open(runtimeInstance(this))
        session.navigationDelegate = navigationDelegate
        session.progressDelegate = progressDelegate
        session.contentDelegate = contentDelegate
        session.permissionDelegate = permissionDelegate
        return session
    }

    private fun openTab(initialUrl: String? = null, first: Boolean = false): Tab? {
        if (tabs.size >= MAX_TABS) {
            toast(resources.getQuantityString(R.plurals.tab_limit, MAX_TABS, MAX_TABS))
            return null
        }
        val session = createSession()
        val tab = Tab(session, getString(R.string.tab_default_title), "")
        tabs.add(tab)
        activateTab(tab)
        when {
            initialUrl != null -> session.loadUri(initialUrl)
            first -> loadStartPage()
            else -> openStartPage()
        }
        return tab
    }

    private fun activateTab(tab: Tab) {
        activeId = tabs.indexOf(tab)
        geckoView.setSession(tab.session)
        tab.session.setActive(true)
        tabs.forEach { if (it !== tab) it.session.setActive(false) }
        binding.urlBar.clearFocus()
        binding.urlBar.setText(urlLabel(tab.url))
        binding.progressBar.visibility = View.INVISIBLE
        updateTabCount()
    }

    private fun activateTab(index: Int) {
        tabs.getOrNull(index)?.let { activateTab(it) }
    }

    private fun closeTab(index: Int) {
        val tab = tabs.getOrNull(index) ?: return
        val wasActive = tabs.indexOf(tab) == activeId
        tabs.removeAt(index)
        runCatching {
            if (wasActive) geckoView.releaseSession()
            tab.session.close()
        }
        if (tabs.isEmpty()) {
            finish()
            return
        }
        if (wasActive) {
            activateTab(tabs.getOrNull(index) ?: tabs.last())
        } else if (activeId > index) {
            activeId -= 1
        }
        updateTabCount()
    }

    private fun updateTabCount() {
        binding.btnTabCount.text = tabs.size.toString()
    }

    private fun tabOf(session: GeckoSession): Tab? = tabs.firstOrNull { it.session === session }

    private fun activeTab(): Tab? = tabs.getOrNull(activeId)

    private fun showTabSwitcher() {
        val dialog = Dialog(this, R.style.Theme_OpenBrowser_FullScreenDialog)
        val view = DialogTabsBinding.inflate(layoutInflater)
        dialog.setContentView(view.root)
        view.btnCloseTabs.setOnClickListener { dialog.dismiss() }
        view.btnNewTabFromSwitcher.setOnClickListener {
            dialog.dismiss()
            openTab()
        }

        val host = view.tabList
        host.removeAllViews()
        var index = 0
        while (index < tabs.size) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            }
            host.addView(row)

            for (column in 0 until 2) {
                val tab = tabs.getOrNull(index + column)
                if (tab == null) {
                    val spacer = View(this)
                    spacer.layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                    row.addView(spacer)
                    continue
                }
                val card = ItemTabCardBinding.inflate(layoutInflater, row, false)
                card.root.layoutParams = LinearLayout.LayoutParams(0, dp(140), 1f).apply {
                    setMargins(dp(4), dp(4), dp(4), dp(4))
                }
                card.tvTitle.text = tab.title.ifBlank { getString(R.string.tab_default_title) }
                card.tvUrl.text = urlLabel(tab.url).ifBlank { getString(R.string.tab_default_title) }
                card.tvCurrent.visibility =
                    if (tabs.indexOf(tab) == activeId) View.VISIBLE else View.GONE
                Motion.pressScale(card.root, 0.97f)
                card.root.setOnClickListener {
                    activateTab(tabs.indexOf(tab))
                    dialog.dismiss()
                }
                card.btnCloseTab.setOnClickListener {
                    dialog.dismiss()
                    closeTab(tabs.indexOf(tab))
                }
                row.addView(card.root)
            }
            index += 2
        }

        dialog.show()
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        Motion.staggerIn(host, startDelay = 60L, step = 26L, translateDp = 8f)
    }

    // ---------------------------------------------------------------- 内核回调

    private val navigationDelegate = object : GeckoSession.NavigationDelegate {
        override fun onLocationChange(
            session: GeckoSession,
            url: String?,
            permissions: List<GeckoSession.PermissionDelegate.ContentPermission>,
            isTopLevel: Boolean,
        ) {
            val tab = tabOf(session) ?: return
            tab.url = url.orEmpty()
            if (tabs.indexOf(tab) == activeId && !binding.urlBar.hasFocus()) {
                binding.urlBar.setText(urlLabel(tab.url))
            }
        }

        override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
            tabOf(session)?.canGoBack = canGoBack
        }

        override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
            tabOf(session)?.canGoForward = canGoForward
        }

        override fun onLoadRequest(
            session: GeckoSession,
            request: GeckoSession.NavigationDelegate.LoadRequest,
        ): GeckoResult<AllowOrDeny> {
            val uri = runCatching { Uri.parse(request.uri) }.getOrNull()
                ?: return GeckoResult.deny()
            return when (uri.scheme?.lowercase()) {
                "openbrowser" -> {
                    handleInternalUri(uri)
                    GeckoResult.deny()
                }

                "http", "https", "about", "blob", "data", "javascript" -> GeckoResult.allow()

                else -> {
                    openExternal(uri)
                    GeckoResult.deny()
                }
            }
        }

        override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession> {
            if (tabs.size >= MAX_TABS) {
                toast(resources.getQuantityString(R.plurals.tab_limit, MAX_TABS, MAX_TABS))
                return GeckoResult.fromException(IllegalStateException("tab limit reached"))
            }
            val newSession = createSession()
            val tab = Tab(newSession, getString(R.string.tab_default_title), "")
            tabs.add(tab)
            activateTab(tab)
            return GeckoResult.fromValue(newSession)
        }

        override fun onLoadError(
            session: GeckoSession,
            uri: String?,
            error: WebRequestError,
        ): GeckoResult<String>? {
            if (tabOf(session) != null) {
                binding.progressBar.visibility = View.INVISIBLE
                toast(getString(R.string.browser_error, error.code.toString()))
            }
            return null
        }
    }

    private val progressDelegate = object : GeckoSession.ProgressDelegate {
        override fun onPageStart(session: GeckoSession, url: String) {
            val tab = tabOf(session) ?: return
            tab.url = url
            if (tabs.indexOf(tab) == activeId) {
                binding.progressBar.progress = 0
                binding.progressBar.visibility = View.VISIBLE
            }
        }

        override fun onProgressChange(session: GeckoSession, progress: Int) {
            if (tabOf(session)?.let { tabs.indexOf(it) } != activeId) return
            binding.progressBar.progress = progress
            binding.progressBar.visibility =
                if (progress in 1..99) View.VISIBLE else View.INVISIBLE
        }

        override fun onPageStop(session: GeckoSession, success: Boolean) {
            val tab = tabOf(session) ?: return
            if (tabs.indexOf(tab) == activeId) binding.progressBar.visibility = View.INVISIBLE
            if (!success) return
            val url = tab.url
            if ((url.startsWith("http://") || url.startsWith("https://")) &&
                !url.startsWith(assetServer.baseUrl)
            ) {
                historyStore.record(profile.id, url, tab.title)
            }
        }
    }

    private val contentDelegate = object : GeckoSession.ContentDelegate {
        override fun onTitleChange(session: GeckoSession, title: String?) {
            tabOf(session)?.title = title?.takeIf { it.isNotBlank() }
                ?: getString(R.string.tab_default_title)
        }

        override fun onCloseRequest(session: GeckoSession) {
            closeTab(tabs.indexOfFirst { it.session === session })
        }

        override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
            setFullscreen(fullScreen)
        }

        override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
            saveDownload(response)
        }
    }

    private val permissionDelegate = object : GeckoSession.PermissionDelegate {
        override fun onContentPermissionRequest(
            session: GeckoSession,
            perm: GeckoSession.PermissionDelegate.ContentPermission,
        ): GeckoResult<Int> =
            GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)

        override fun onAndroidPermissionsRequest(
            session: GeckoSession,
            permissions: Array<String>?,
            callback: GeckoSession.PermissionDelegate.Callback,
        ) {
            callback.reject()
        }

        override fun onMediaPermissionRequest(
            session: GeckoSession,
            uri: String,
            video: Array<GeckoSession.PermissionDelegate.MediaSource>?,
            audio: Array<GeckoSession.PermissionDelegate.MediaSource>?,
            callback: GeckoSession.PermissionDelegate.MediaCallback,
        ) {
            callback.reject()
        }
    }

    // ---------------------------------------------------------------- 导航

    private fun handleBack() {
        if (fullscreen) {
            activeTab()?.session?.exitFullScreen()
            setFullscreen(false)
            return
        }
        val tab = activeTab()
        if (tab != null && tab.canGoBack) {
            tab.session.goBack()
            return
        }
        if (tabs.size > 1) {
            closeTab(activeId)
            return
        }
        finish()
    }

    private fun setFullscreen(enter: Boolean) {
        if (fullscreen == enter) return
        fullscreen = enter
        binding.addressBar.visibility = if (enter) View.GONE else View.VISIBLE
        binding.progressBar.visibility = View.INVISIBLE
        if (enter) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun loadStartPage() {
        val target = profile.startUrl.trim()
        if (target.isEmpty() || target == CHECK_URL) {
            openCheckPage()
        } else {
            val url = normalizeUrl(target)
            setUrlBarText(url)
            activeTab()?.session?.loadUri(url)
        }
    }

    private fun openCheckPage() {
        val tab = activeTab() ?: return
        val html = readAsset("check.html")
        if (html == null) {
            toast(getString(R.string.operation_failed))
            return
        }
        assetServer.checkHtml = html
        setUrlBarText(getString(R.string.check_page_title))
        tab.url = assetServer.checkUrl
        tab.session.loadUri(assetServer.checkUrl)
    }

    private fun openStartPage() {
        val tab = activeTab() ?: return
        val html = readAsset("start.html")
        if (html == null) {
            openCheckPage()
            return
        }
        val bookmarks = bookmarkStore.list(profile.id).take(8)
        val history = historyStore.list(profile.id).take(6)
        assetServer.startHtml = html
            .replace("__PROFILE_NAME__", escapeHtml(profile.name))
            .replace("__BOOKMARKS_JSON__", linksJson(bookmarks.map { it.title to it.url }))
            .replace("__HISTORY_JSON__", linksJson(history.map { it.title to it.url }))
        binding.urlBar.setText("")
        binding.urlBar.clearFocus()
        tab.url = assetServer.startUrl
        tab.session.loadUri(assetServer.startUrl)
    }

    private fun readAsset(name: String): String? = runCatching {
        assets.open(name).bufferedReader().use { it.readText() }
    }.getOrNull()

    private fun linksJson(items: List<Pair<String, String>>): String =
        items.joinToString(prefix = "[", postfix = "]") { (title, url) ->
            "{\"title\":${JSONObject.quote(title)},\"url\":${JSONObject.quote(url)}}"
        }

    private fun escapeHtml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun setUrlBarText(value: String) {
        binding.urlBar.setText(value)
        binding.urlBar.setSelection(binding.urlBar.text.length)
    }

    private fun urlLabel(url: String?): String = when {
        url.isNullOrBlank() -> ""
        url == assetServer.checkUrl -> getString(R.string.check_page_title)
        url.startsWith("${assetServer.baseUrl}/start") -> ""
        else -> url
    }

    private fun loadFromUrlBar() {
        val typed = binding.urlBar.text.toString().trim()
        binding.urlBar.clearFocus()
        if (typed.isEmpty() || typed == CHECK_URL || typed == getString(R.string.check_page_title)) {
            openCheckPage()
            return
        }
        val url = normalizeUrl(typed)
        setUrlBarText(url)
        activeTab()?.session?.loadUri(url)
    }

    private fun normalizeUrl(input: String): String {
        val text = input.trim()
        if (text.isEmpty()) return CHECK_URL
        if (text == CHECK_URL) return CHECK_URL
        if (text.startsWith("http://") || text.startsWith("https://") ||
            text.startsWith("about:")
        ) {
            return text
        }
        if (text.contains(" ") || !text.contains(".")) {
            return "https://www.bing.com/search?q=" + Uri.encode(text)
        }
        return "https://$text"
    }

    private fun handleInternalUri(uri: Uri) {
        when (uri.host?.lowercase()) {
            "open" -> {
                val target = uri.getQueryParameter("url").orEmpty()
                if (target.startsWith("http://") || target.startsWith("https://")) {
                    activeTab()?.session?.loadUri(target)
                }
            }

            "search" -> {
                val query = uri.getQueryParameter("q").orEmpty()
                if (query.isNotBlank()) {
                    activeTab()?.session?.loadUri(
                        "https://www.bing.com/search?q=" + Uri.encode(query),
                    )
                }
            }

            "check" -> openCheckPage()
        }
    }

    private fun openExternal(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }.onFailure {
            toast(getString(R.string.browser_external_missing))
        }
    }

    // ---------------------------------------------------------------- 菜单

    private fun showBrowserMenu() {
        val popup = PopupMenu(this, binding.btnMenu)
        popup.menuInflater.inflate(R.menu.menu_browser_gecko, popup.menu)
        val tab = activeTab()
        popup.menu.findItem(R.id.action_forward)?.isEnabled = tab?.canGoForward == true
        val currentUrl = tab?.url.orEmpty()
        val isWebPage = currentUrl.startsWith("http://") || currentUrl.startsWith("https://")
        popup.menu.findItem(R.id.action_bookmark)?.apply {
            isEnabled = isWebPage
            title = getString(
                if (bookmarkStore.contains(profile.id, currentUrl)) {
                    R.string.browser_bookmark_remove
                } else {
                    R.string.browser_bookmark_add
                },
            )
        }
        popup.menu.findItem(R.id.action_share)?.isEnabled = isWebPage
        popup.menu.findItem(R.id.action_external)?.isEnabled = isWebPage
        popup.menu.findItem(R.id.action_desktop)?.isChecked = tab?.desktop == true
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_new_tab -> {
                    openTab()
                    true
                }

                R.id.action_history -> {
                    showBrowserList(history = true)
                    true
                }

                R.id.action_bookmarks -> {
                    showBrowserList(history = false)
                    true
                }

                R.id.action_bookmark -> {
                    toggleBookmark()
                    true
                }

                R.id.action_share -> {
                    shareCurrent()
                    true
                }

                R.id.action_external -> {
                    openCurrentExternal()
                    true
                }

                R.id.action_desktop -> {
                    toggleDesktopMode()
                    true
                }

                R.id.action_find -> {
                    showFindBar()
                    true
                }

                R.id.action_forward -> {
                    if (tab?.canGoForward == true) tab.session.goForward()
                    true
                }

                R.id.action_reload -> {
                    tab?.session?.reload()
                    true
                }

                R.id.action_home -> {
                    openStartPage()
                    true
                }

                R.id.action_check -> {
                    openCheckPage()
                    true
                }

                R.id.action_info -> {
                    showProfileInfo()
                    true
                }

                R.id.action_clear -> {
                    confirmClearData()
                    true
                }

                R.id.action_close -> {
                    finish()
                    true
                }

                else -> false
            }
        }
        popup.show()
    }

    private fun toggleBookmark() {
        val tab = activeTab() ?: return
        val url = tab.url
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            toast(getString(R.string.browser_empty_page))
            return
        }
        val added = bookmarkStore.toggle(profile.id, url, tab.title)
        toast(
            getString(
                if (added) R.string.browser_bookmark_added else R.string.browser_bookmark_removed,
            ),
        )
    }

    private fun shareCurrent() {
        val url = activeTab()?.url.orEmpty()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            toast(getString(R.string.browser_empty_page))
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }
        runCatching {
            startActivity(Intent.createChooser(intent, getString(R.string.browser_share_chooser)))
        }.onFailure { toast(getString(R.string.browser_external_missing)) }
    }

    private fun openCurrentExternal() {
        val url = activeTab()?.url.orEmpty()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            toast(getString(R.string.browser_empty_page))
            return
        }
        openExternal(Uri.parse(url))
    }

    /** 桌面版网站：GeckoView 的 UA 可以在运行时切换，刷新后生效。 */
    private fun toggleDesktopMode() {
        val tab = activeTab() ?: return
        tab.desktop = !tab.desktop
        tab.session.settings.userAgentOverride =
            if (tab.desktop) DESKTOP_USER_AGENT else profile.userAgent
        tab.session.reload()
        toast(getString(if (tab.desktop) R.string.browser_desktop_on else R.string.browser_desktop_off))
    }

    private fun showProfileInfo() {
        val dpr = if (profile.devicePixelRatio == profile.devicePixelRatio.toLong().toDouble()) {
            profile.devicePixelRatio.toLong().toString()
        } else {
            profile.devicePixelRatio.toString()
        }
        val rows = listOf(
            getString(R.string.label_name) to profile.name,
            getString(R.string.browser_info_engine) to getString(R.string.engine_gecko),
            getString(R.string.label_user_agent) to profile.userAgent,
            getString(R.string.label_platform) to profile.platform,
            getString(R.string.label_locale) to profile.locale,
            getString(R.string.label_timezone) to profile.timezone,
            getString(R.string.browser_info_screen) to "${profile.screenWidth}×${profile.screenHeight} @ ${dpr}x",
            getString(R.string.label_webrtc_mode) to profile.webRtcMode,
            getString(R.string.label_proxy_type) to if (profile.proxy.enabled) {
                getString(R.string.gecko_proxy_unsupported)
            } else {
                getString(R.string.profile_direct)
            },
            getString(R.string.browser_info_container) to profile.dataSuffix,
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.browser_info_title)
            .setMessage(rows.joinToString("\n") { "${it.first}: ${it.second}" })
            .setPositiveButton(R.string.action_confirm, null)
            .show()
    }

    private fun confirmClearData() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.clear_data_confirm_title)
            .setMessage(getString(R.string.clear_data_confirm_message, profile.name))
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_confirm) { _, _ ->
                val target = profile.id
                runCatching {
                    runtimeInstance(this).storageController
                        .clearDataForSessionContext(profile.dataSuffix)
                        .accept({
                            runCatching { com.openbrowser.mobile.sync.CookieSnapshots(this).clear(target) }
                            if (!isDestroyed && !isFinishing && profile.id == target) {
                                tabs.forEach { it.session.reload() }
                                toast(getString(R.string.clear_data_done, profile.name))
                            }
                        }, { if (!isDestroyed && !isFinishing) toast(getString(R.string.operation_failed)) })
                }.onFailure { toast(getString(R.string.operation_failed)) }
            }
            .show()
    }

    private data class ListItem(val title: String, val url: String, val timestamp: Long)

    private fun showBrowserList(history: Boolean) {
        val dialog = Dialog(this, R.style.Theme_OpenBrowser_FullScreenDialog)
        val view = DialogBrowserListBinding.inflate(layoutInflater)
        dialog.setContentView(view.root)

        view.tvListTitle.text = getString(
            if (history) R.string.browser_history else R.string.browser_bookmarks,
        )
        view.btnListClear.setOnClickListener {
            if (history) historyStore.clear(profile.id) else bookmarkStore.clear(profile.id)
            dialog.dismiss()
            toast(getString(if (history) R.string.history_cleared else R.string.bookmarks_cleared))
        }
        view.btnListClose.setOnClickListener { dialog.dismiss() }

        val items = if (history) {
            historyStore.list(profile.id).map { ListItem(it.title, it.url, it.visitedAt) }.toMutableList()
        } else {
            bookmarkStore.list(profile.id).map { ListItem(it.title, it.url, it.createdAt) }.toMutableList()
        }

        val container = view.listContainer
        val timeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

        fun render(filter: String) {
            container.removeAllViews()
            val keyword = filter.trim()
            val visible = if (keyword.isEmpty()) {
                items
            } else {
                items.filter {
                    it.title.contains(keyword, ignoreCase = true) ||
                        it.url.contains(keyword, ignoreCase = true)
                }
            }
            view.tvListEmpty.text = getString(
                if (keyword.isEmpty()) R.string.list_empty else R.string.list_no_match,
            )
            view.tvListEmpty.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE

            visible.forEach { entry ->
                val row = ItemBrowserRowBinding.inflate(layoutInflater, container, false)
                row.tvRowTitle.text = entry.title.ifBlank { entry.url }
                row.tvRowUrl.text = entry.url
                row.tvRowTime.text = timeFormat.format(Date(entry.timestamp))
                row.root.setOnClickListener {
                    dialog.dismiss()
                    openTab(entry.url)
                }
                row.btnRowDelete.setOnClickListener {
                    if (history) {
                        historyStore.remove(profile.id, entry.url)
                    } else {
                        bookmarkStore.remove(profile.id, entry.url)
                    }
                    items.remove(entry)
                    render(view.searchList.text.toString())
                }
                container.addView(row.root)
            }
        }

        view.searchList.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = render(s?.toString().orEmpty())
        })
        render("")

        dialog.show()
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        Motion.staggerIn(container, startDelay = 40L, step = 18L, translateDp = 6f)
    }

    // ---------------------------------------------------------------- 下载

    /**
     * Gecko 的下载不走系统 DownloadManager，直接读响应流写进公共下载目录。
     * 读取在后台线程完成，避免阻塞界面。
     */
    @SuppressLint("InlinedApi")
    private fun saveDownload(response: WebResponse) {
        val fileName = URLUtil.guessFileName(
            response.uri,
            response.headers["Content-Disposition"],
            response.headers["Content-Type"],
        )
        val mimeType = response.headers["Content-Type"].orEmpty().substringBefore(';')
        toast(getString(R.string.browser_download_started, fileName))
        Thread {
            runCatching {
                val stream = response.body ?: error("empty response body")
                stream.use { input ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val values = ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                            put(
                                MediaStore.MediaColumns.MIME_TYPE,
                                mimeType.ifBlank { "application/octet-stream" },
                            )
                            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                        }
                        val target = contentResolver.insert(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                            values,
                        ) ?: error("insert failed")
                        contentResolver.openOutputStream(target)?.use { input.copyTo(it) }
                    } else {
                        val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
                        dir.mkdirs()
                        File(dir, fileName).outputStream().use { input.copyTo(it) }
                    }
                }
            }.onFailure {
                runOnUiThread { toast(getString(R.string.operation_failed)) }
            }
        }.start()
    }

    // ---------------------------------------------------------------- 生命周期

    override fun onDestroy() {
        runCatching { assetServer.stop() }
        tabs.forEach { tab -> runCatching { tab.session.close() } }
        tabs.clear()
        runCatching { geckoView.releaseSession() }
        syncLease?.close(); syncLease = null
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_PROFILE_ID = "com.openbrowser.mobile.extra.PROFILE_ID"

        private const val MAX_TABS = 8
        private const val CHECK_URL = "openbrowser://check"

        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36"

        private fun runtimeInstance(context: Context): GeckoRuntime {
            return GeckoRuntimeHolder.get(context)
        }

        fun intent(context: Context, profileId: String): Intent =
            Intent(context, GeckoBrowserActivity::class.java)
                .putExtra(EXTRA_PROFILE_ID, profileId)
    }
}
