package com.openbrowser.mobile.ui

import android.annotation.SuppressLint
import android.app.Dialog
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.openbrowser.mobile.BuildConfig
import com.openbrowser.mobile.R
import com.openbrowser.mobile.UiPrefs
import com.openbrowser.mobile.data.BookmarkStore
import com.openbrowser.mobile.data.DownloadStore
import com.openbrowser.mobile.data.HistoryStore
import com.openbrowser.mobile.data.Profile
import com.openbrowser.mobile.data.ProfileStore
import com.openbrowser.mobile.databinding.ActivityBrowserBinding
import com.openbrowser.mobile.databinding.DialogBrowserListBinding
import com.openbrowser.mobile.databinding.DialogTabsBinding
import com.openbrowser.mobile.databinding.ItemBrowserRowBinding
import com.openbrowser.mobile.databinding.ItemTabCardBinding
import com.openbrowser.mobile.proxy.ProxyManager
import com.openbrowser.mobile.web.FingerprintScript
import com.openbrowser.mobile.web.ProcessRelauncher
import com.openbrowser.mobile.web.WebDataCleaner
import com.openbrowser.mobile.web.WebViewProcess
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 单环境浏览器界面，运行在独立的 `:browser` 进程。
 *
 * 进入前先确定 WebView 数据目录后缀，确保不同环境的 Cookie / localStorage /
 * IndexedDB / 缓存完全隔离；切换环境时通过重启进程来切换后缀。
 *
 * 一个环境 = 一个任务，内部支持多个标签页：所有标签共用一个 WebView 数据目录，
 * 因此登录态在标签之间共享，但不会串到其他环境。
 */
class BrowserActivity : AppCompatActivity() {

    private data class Tab(
        val id: Int,
        val view: WebView,
        var title: String,
        var url: String,
        var desktop: Boolean = false,
    )

    private lateinit var binding: ActivityBrowserBinding
    private lateinit var store: ProfileStore
    private lateinit var historyStore: HistoryStore
    private lateinit var bookmarkStore: BookmarkStore
    private lateinit var downloadStore: DownloadStore
    private lateinit var profile: Profile
    private var clearingWebsiteData = false
    private val cookieCaptures = java.util.concurrent.atomic.AtomicInteger(0)
    private var syncLease: com.openbrowser.mobile.sync.ProfileLeases? = null
    private var fallbackScript: String? = null
    private val websiteBridge by lazy { com.openbrowser.mobile.sync.WebsiteBrowserBridge(this, profile.id) { toast(it) } }
    private fun loadWebsite(view: WebView, url: String) { if (!websiteBridge.load(view, url)) view.loadUrl(url) }
    private fun captureWebsiteData(view: WebView) {
        if (clearingWebsiteData) return
        val config = runCatching { com.openbrowser.mobile.sync.SyncStore(this).read().getJSONObject("config") }.getOrNull() ?: return
        val ids = config.optJSONArray("selected") ?: return
        if (config.optBoolean("enabled") && config.optBoolean("includeSiteData") && (0 until ids.length()).any { ids.optString(it) == profile.id }) websiteBridge.capture(view)
    }
    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private val tabs = mutableListOf<Tab>()
    private var activeTabId = -1
    private var nextTabId = 1

    private val activeTab: Tab? get() = tabs.firstOrNull { it.id == activeTabId }
    private val webView: WebView? get() = activeTab?.view

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        val callback = pendingFileCallback
        pendingFileCallback = null
        callback?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
    }

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
        downloadStore = DownloadStore(this)
        val found = store.get(profileId)
        if (found == null) {
            Toast.makeText(this, R.string.operation_failed, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        profile = found
        syncLease = com.openbrowser.mobile.sync.ProfileLeases.acquire(this, listOf(profile.id), true)
        if (syncLease == null) {
            toast(getString(R.string.sync_profile_busy))
            finish()
            return
        }

        if (!ensureDataDirectory(profile)) return

        binding = ActivityBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyAddressBarPosition()
        setupTopBar()
        setupFindBar()
        binding.btnTabCount.setOnClickListener { showTabSwitcher() }
        applyProxyAndLoad()
        store.markUsed(profile.id)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                navigateBack()
            }
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

    /**
     * 确保当前进程使用该环境的数据目录。
     * 后缀一旦设置就不能更改，因此换环境时需要重启 `:browser` 进程。
     */
    private fun ensureDataDirectory(target: Profile): Boolean {
        val suffix = target.dataSuffix
        val applied = WebViewProcess.appliedSuffix
        if (applied == null) {
            try {
                WebView.setDataDirectorySuffix(suffix)
            } catch (error: Exception) {
                // 隔离失败必须停止，不能让不同环境落入共享数据目录。
                Toast.makeText(this, R.string.browser_reopen_failed, Toast.LENGTH_LONG).show()
                finish()
                return false
            }
            WebViewProcess.appliedSuffix = suffix
            return registerRuntime(suffix)
        }
        if (applied == suffix) return registerRuntime(suffix)

        val attempt = intent.getIntExtra(EXTRA_RELAUNCH_ATTEMPT, 0)
        if (attempt >= 2) {
            Toast.makeText(this, R.string.browser_reopen_failed, Toast.LENGTH_LONG).show()
            finish()
            return false
        }
        val targetIntent = Intent(this, BrowserActivity::class.java)
            .putExtra(EXTRA_PROFILE_ID, target.id)
            .putExtra(EXTRA_RELAUNCH_ATTEMPT, attempt + 1)
        ProcessRelauncher.schedule(this, targetIntent)
        finish()
        ProcessRelauncher.killCurrentProcess()
        return false
    }

    private fun registerRuntime(suffix: String): Boolean = runCatching { WebViewProcess.record(this, suffix); true }.getOrElse {
        Toast.makeText(this, R.string.browser_reopen_failed, Toast.LENGTH_LONG).show()
        finish()
        false
    }

    private fun setupTopBar() {
        binding.urlBar.hint = profile.name
        binding.btnBack.setOnClickListener { navigateBack() }
        binding.btnMenu.setOnClickListener { showBrowserMenu() }
        binding.urlBar.setOnEditorActionListener { _, actionId, event ->
            val enter = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_GO || enter) {
                loadFromUrlBar()
                true
            } else {
                false
            }
        }
    }

    /** 页内查找栏：输入即高亮，上一个 / 下一个切换，返回键或 × 关闭。 */
    private fun setupFindBar() {
        binding.findInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val view = webView ?: return
                val query = s?.toString().orEmpty()
                if (query.isEmpty()) {
                    view.clearMatches()
                    binding.tvFindCount.text = ""
                } else {
                    view.findAllAsync(query)
                }
            }
        })
        binding.findInput.setOnEditorActionListener { _, actionId, event ->
            val enter = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER &&
                event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEARCH || enter) {
                webView?.findNext(true)
                true
            } else {
                false
            }
        }
        binding.btnFindPrev.setOnClickListener { webView?.findNext(false) }
        binding.btnFindNext.setOnClickListener { webView?.findNext(true) }
        binding.btnFindClose.setOnClickListener { closeFindBar() }
    }

    private fun showFindBar() {
        if (binding.findBar.visibility == View.VISIBLE) return
        binding.findBar.visibility = View.VISIBLE
        binding.findBar.alpha = 0f
        binding.findBar.animate().alpha(1f).setDuration(160L).start()
        binding.findInput.requestFocus()
        getSystemService(InputMethodManager::class.java)
            ?.showSoftInput(binding.findInput, InputMethodManager.SHOW_IMPLICIT)
        val current = binding.findInput.text.toString()
        if (current.isNotEmpty()) webView?.findAllAsync(current)
    }

    private fun closeFindBar() {
        binding.findBar.visibility = View.GONE
        binding.findInput.setText("")
        binding.tvFindCount.text = ""
        webView?.clearMatches()
        getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(binding.findInput.windowToken, 0)
    }

    /** 按设置把地址栏挪到页面底部（网页 → 进度条 → 地址栏）。 */
    private fun applyAddressBarPosition() {
        if (!UiPrefs.addressBarBottom(this)) return
        val root = binding.root
        val bar = binding.addressBar
        val progress = binding.progressBar
        val web = binding.webContainer

        root.removeAllViews()
        root.addView(web)
        root.addView(binding.findBar)
        root.addView(progress)
        root.addView(bar)

        val params = bar.layoutParams as? LinearLayout.LayoutParams ?: return
        params.topMargin = 0
        params.bottomMargin = dp(6)
        bar.layoutParams = params
    }

    /** 溢出菜单：和常见浏览器的「⋮」一致。 */
    private fun showBrowserMenu() {
        val popup = PopupMenu(this, binding.btnMenu)
        popup.menuInflater.inflate(R.menu.menu_browser, popup.menu)
        popup.menu.findItem(R.id.action_forward)?.let { it.isEnabled = webView?.canGoForward() == true }
        val currentUrl = activeTab?.url.orEmpty()
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
        popup.menu.findItem(R.id.action_desktop)?.isChecked = activeTab?.desktop == true
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_vault_fill -> { webView?.let { com.openbrowser.mobile.sync.CredentialVaultDialog.fill(this, it, profile.id) }; true }
                R.id.action_cookie_snapshot -> {
                    confirmCookieSnapshot()
                    true
                }
                R.id.action_new_tab -> {
                    openTab()
                    true
                }

                R.id.action_history -> {
                    showHistory()
                    true
                }

                R.id.action_bookmarks -> {
                    showBookmarks()
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

                R.id.action_downloads -> {
                    showDownloads()
                    true
                }

                R.id.action_forward -> {
                    webView?.let { if (it.canGoForward()) it.goForward() }
                    true
                }

                R.id.action_reload -> {
                    webView?.reload()
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

    // ---------------------------------------------------------------- 标签页

    private fun openTab(initialUrl: String? = null) {
        if (tabs.size >= MAX_TABS) {
            toast(resources.getQuantityString(R.plurals.tab_limit, MAX_TABS, MAX_TABS))
            return
        }
        val view = createWebView()
        val tab = Tab(nextTabId++, view, getString(R.string.tab_default_title), "")
        tabs.add(tab)
        binding.webContainer.addView(
            view,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        activateTab(tab.id)
        when {
            initialUrl != null -> {
                setUrlBarText(initialUrl)
                loadWebsite(view, initialUrl)
            }
            // 首个标签沿用环境启动页（默认是内置检测页）
            tabs.size == 1 -> loadStartPage()
            // 之后的标签打开一张带搜索框、常用网站和最近访问的起始页
            else -> openStartPage()
        }
    }

    private fun activateTab(id: Int) {
        activeTabId = id
        tabs.forEach { it.view.visibility = if (it.id == id) View.VISIBLE else View.GONE }
        val tab = activeTab ?: return
        binding.urlBar.clearFocus()
        binding.urlBar.setText(urlLabel(tab.url))
        binding.progressBar.visibility = View.INVISIBLE
        updateTabCount()
    }

    private fun closeTab(id: Int) {
        val index = tabs.indexOfFirst { it.id == id }
        if (index < 0) return
        val tab = tabs.removeAt(index)
        binding.webContainer.removeView(tab.view)
        runCatching {
            tab.view.stopLoading()
            tab.view.destroy()
        }
        if (tabs.isEmpty()) {
            finish()
            return
        }
        if (tab.id == activeTabId) {
            activateTab((tabs.getOrNull(index) ?: tabs.last()).id)
        } else {
            updateTabCount()
        }
    }

    private fun updateTabCount() {
        binding.btnTabCount.text = tabs.size.toString()
    }

    /** 手机 Chrome 式标签页切换：全屏网格，点卡片切换，× 关闭。 */
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
                card.tvCurrent.visibility = if (tab.id == activeTabId) View.VISIBLE else View.GONE
                Motion.pressScale(card.root, 0.97f)
                card.root.setOnClickListener {
                    activateTab(tab.id)
                    dialog.dismiss()
                }
                card.btnCloseTab.setOnClickListener {
                    dialog.dismiss()
                    closeTab(tab.id)
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

    private fun navigateBack() {
        if (customView != null) {
            exitFullscreen()
            return
        }
        if (binding.findBar.visibility == View.VISIBLE) {
            closeFindBar()
            return
        }
        val view = webView
        if (view != null && view.canGoBack()) {
            view.goBack()
            return
        }
        if (tabs.size > 1) {
            closeTab(activeTabId)
            return
        }
        finish()
    }

    private fun tabOf(view: WebView): Tab? = tabs.firstOrNull { it.view === view }

    /** 退出视频全屏，还原浏览器界面。 */
    private fun exitFullscreen() {
        val view = customView ?: return
        customView = null
        (view.parent as? ViewGroup)?.removeView(view)
        binding.root.visibility = View.VISIBLE
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
    }

    // ---------------------------------------------------------------- WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val view = WebView(this)
        view.setBackgroundColor(0xFF000000.toInt())

        with(view.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(true)
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = profile.userAgent
            cacheMode = WebSettings.LOAD_DEFAULT
            if (profile.mobile) {
                textZoom = 100
            }
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(view, true)
        }

        view.setFindListener { activeMatchOrdinal, numberOfMatches, isFinal ->
            if (tabOf(view)?.id != activeTabId) return@setFindListener
            binding.tvFindCount.text = when {
                numberOfMatches > 0 -> "$activeMatchOrdinal/$numberOfMatches"
                isFinal -> getString(R.string.find_no_match)
                else -> ""
            }
        }

        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        websiteBridge.install(view)
        val script = FingerprintScript.build(this, profile)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(view, script, setOf("*"))
        } else {
            fallbackScript = script
        }

        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (request.isForMainFrame && uri.scheme in listOf("http", "https") && websiteBridge.load(view, uri.toString())) return true
                if (uri.toString() == CHECK_URL) {
                    openCheckPage()
                    return true
                }
                val scheme = uri.scheme?.lowercase().orEmpty()
                if (scheme == "openbrowser") {
                    when (uri.host?.lowercase()) {
                        "open" -> {
                            val target = uri.getQueryParameter("url").orEmpty()
                            if (target.startsWith("http://") || target.startsWith("https://")) {
                                loadWebsite(view, target)
                            }
                        }

                        "search" -> {
                            val query = uri.getQueryParameter("q").orEmpty()
                            if (query.isNotBlank()) {
                                loadWebsite(view, "https://www.bing.com/search?q=" + Uri.encode(query))
                            }
                        }

                        "check" -> openCheckPage()
                    }
                    return true
                }
                if (scheme == "http" || scheme == "https" || scheme == "about" || scheme == "javascript") {
                    return false
                }
                openExternal(uri)
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (websiteBridge.isBootstrap(view, url)) return
                if (url != null && websiteBridge.load(view, url)) return
                val tab = tabOf(view) ?: return
                if (!url.isNullOrBlank()) tab.url = url
                if (tab.id == activeTabId) {
                    binding.progressBar.visibility = View.VISIBLE
                    if (!url.isNullOrBlank() && !binding.urlBar.hasFocus()) binding.urlBar.setText(urlLabel(url))
                }
                fallbackScript?.let { view.evaluateJavascript(it, null) }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (websiteBridge.isBootstrap(view, url)) return
                captureWebsiteData(view)
                val tab = tabOf(view) ?: return
                if (!url.isNullOrBlank()) tab.url = url
                if (tab.id == activeTabId) {
                    binding.progressBar.visibility = View.INVISIBLE
                    if (!url.isNullOrBlank() && !url.startsWith("about:") &&
                        !url.startsWith(START_BASE_URL) && !binding.urlBar.hasFocus()
                    ) {
                        binding.urlBar.setText(urlLabel(url))
                    }
                }
                if (!url.isNullOrBlank() && !url.startsWith(CHECK_BASE_URL) && !url.startsWith(START_BASE_URL)) {
                    historyStore.record(profile.id, url, view.title.orEmpty())
                }
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                val tab = tabOf(view) ?: return
                if (!url.isNullOrBlank()) tab.url = url
                if (tab.id == activeTabId && !url.isNullOrBlank() && !binding.urlBar.hasFocus()) {
                    binding.urlBar.setText(urlLabel(url))
                }
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                if (!request.isForMainFrame) return
                val tab = tabOf(view) ?: return
                if (tab.id == activeTabId) binding.progressBar.visibility = View.INVISIBLE
                toast(getString(R.string.browser_error, error.description?.toString().orEmpty()))
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
                handler.cancel()
                toast(getString(R.string.browser_error, error.url ?: "SSL"))
            }
        }

        view.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                val tab = tabOf(view) ?: return
                if (tab.id != activeTabId) return
                binding.progressBar.progress = newProgress
                binding.progressBar.visibility = if (newProgress in 1..99) View.VISIBLE else View.INVISIBLE
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                val tab = tabOf(view) ?: return
                tab.title = title?.takeIf { it.isNotBlank() } ?: getString(R.string.tab_default_title)
            }

            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams,
            ): Boolean {
                pendingFileCallback?.onReceiveValue(null)
                pendingFileCallback = filePathCallback
                return try {
                    fileChooserLauncher.launch("*/*")
                    true
                } catch (error: Exception) {
                    pendingFileCallback = null
                    false
                }
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                // 环境隔离场景下默认拒绝摄像头/麦克风等硬件权限
                request.deny()
            }

            /** target="_blank" / window.open 转成当前环境内的新标签页。 */
            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message,
            ): Boolean {
                if (tabs.size >= MAX_TABS) {
                    toast(resources.getQuantityString(R.plurals.tab_limit, MAX_TABS, MAX_TABS))
                    return false
                }
                val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                val newView = createWebView()
                val tab = Tab(nextTabId++, newView, getString(R.string.tab_default_title), "")
                tabs.add(tab)
                binding.webContainer.addView(
                    newView,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
                activateTab(tab.id)
                transport.webView = newView
                resultMsg.sendToTarget()
                return true
            }

            /** 视频全屏：把播放器 View 盖到整屏，退出时还原。 */
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                (findViewById<ViewGroup>(android.R.id.content))?.addView(
                    view,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
                binding.root.visibility = View.GONE
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }

            override fun onHideCustomView() {
                exitFullscreen()
            }
        }

        view.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            enqueueDownload(url, userAgent, contentDisposition, mimeType)
        }

        return view
    }

    // ---------------------------------------------------------------- 导航

    private fun applyProxyAndLoad() {
        ProxyManager.apply(profile.proxy) { applied ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (applied) {
                    applyPendingCookies { openTab() }
                } else {
                    MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.browser_proxy_failed)
                        .setMessage(ProxyManager.lastMessage)
                        .setCancelable(false)
                        .setPositiveButton(R.string.action_confirm) { _, _ -> finish() }
                        .show()
                }
            }
        }
    }

    private fun confirmCookieSnapshot() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.GET_COOKIE_INFO)) {
            toast(getString(R.string.sync_cookie_snapshot_unsupported))
            return
        }
        MaterialAlertDialogBuilder(this).setTitle(R.string.sync_cookie_snapshot)
            .setMessage(R.string.sync_cookie_snapshot_confirm)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_confirm) { _, _ -> captureCookieSnapshot(true) }.show()
    }

    private fun captureCookieSnapshot(notify: Boolean) {
        if (tabs.isEmpty() || !WebViewFeature.isFeatureSupported(WebViewFeature.GET_COOKIE_INFO)) return
        val urls = (tabs.map { it.url } + historyStore.list(profile.id).map { it.url })
            .mapNotNull { raw -> runCatching {
                val uri = java.net.URI(raw)
                if (uri.scheme !in listOf("http", "https") || uri.host == null || uri.host.endsWith(".openbrowser")) null
                else java.net.URI(uri.scheme, null, uri.host, uri.port, uri.path.ifEmpty { "/" }, null, null).toString()
            }.getOrNull() }.distinct().take(200)
        val captureLease = com.openbrowser.mobile.sync.ProfileLeases.acquire(this, listOf(profile.id), true) ?: return
        cookieCaptures.incrementAndGet()
        Thread {
            val result = runCatching {
                val records = org.json.JSONArray()
                val seen = mutableSetOf<String>()
                urls.forEach { url ->
                    if (WebViewFeature.isFeatureSupported(WebViewFeature.GET_COOKIE_INFO)) {
                        androidx.webkit.CookieManagerCompat.getCookieInfo(CookieManager.getInstance(), url).forEach { raw ->
                            if (!raw.split(';').any { it.trim().equals("Partitioned", true) } && seen.add(java.net.URI(url).host + "|" + raw)) {
                                val record = JSONObject().put("url", url).put("setCookie", raw)
                                com.openbrowser.mobile.sync.PortableBundle.validateCookie(record)
                                records.put(record)
                            }
                        }
                    }
                }
                require(records.length() <= 5000) { "Cookie 数量超过限制" }
                com.openbrowser.mobile.sync.CookieSnapshots(this).save(profile.id, records)
                com.openbrowser.mobile.sync.SyncScheduler.kick(this)
                records.length()
            }
            captureLease.close()
            cookieCaptures.decrementAndGet()
            runOnUiThread {
                if (notify && !isFinishing && !isDestroyed) result
                    .onSuccess { toast(getString(R.string.sync_cookie_snapshot_done, it)) }
                    .onFailure { toast(getString(R.string.sync_failed, it.message.orEmpty())) }
            }
        }.start()
    }

    /** 在正确的环境数据目录内应用 Cookie，全部完成前不发出页面请求。 */
    private fun applyPendingCookies(onReady: () -> Unit) {
        val snapshots = com.openbrowser.mobile.sync.CookieSnapshots(this)
        Thread {
            val pending = runCatching { snapshots.pending(profile.id) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                fun fail() {
                    MaterialAlertDialogBuilder(this).setMessage(R.string.sync_cookie_apply_failed).setCancelable(false)
                        .setPositiveButton(R.string.action_confirm) { _, _ -> finish() }.show()
                }
                if (pending.isFailure) { fail(); return@runOnUiThread }
                val snapshot = pending.getOrNull()
                if (snapshot == null) { onReady(); return@runOnUiThread }
                val savedRecords = snapshot.getJSONArray("cookies")
                val records = org.json.JSONArray()
                snapshot.optJSONArray("deletions")?.let { deletes -> for (i in 0 until deletes.length()) records.put(deletes.get(i)) }
                for (i in 0 until savedRecords.length()) records.put(savedRecords.get(i))
                fun applyNext(index: Int) {
                    if (isFinishing || isDestroyed) return
                    if (index >= records.length()) {
                        Thread {
                            val result = runCatching {
                                CookieManager.getInstance().flush()
                                snapshots.save(profile.id, savedRecords)
                                snapshots.applied(profile.id, snapshot)
                            }
                            runOnUiThread { if (!isFinishing && !isDestroyed) { if (result.isSuccess) onReady() else fail() } }
                        }.start()
                        return
                    }
                    val record = records.getJSONObject(index)
                    runCatching {
                        com.openbrowser.mobile.sync.PortableBundle.validateCookie(record)
                        CookieManager.getInstance().setCookie(record.getString("url"), record.getString("setCookie")) { success ->
                            if (success) applyNext(index + 1) else fail()
                        }
                    }.onFailure { fail() }
                }
                applyNext(0)
            }
        }.start()
    }

    /** 启动页留空时打开内置环境检测页，方便确认出口 IP、时区与指纹是否自洽。 */
    private fun loadStartPage() {
        binding.urlBar.clearFocus()
        val target = profile.startUrl.trim()
        if (target.isEmpty() || target == CHECK_URL) {
            openCheckPage()
            return
        }
        val url = normalizeUrl(target)
        setUrlBarText(url)
        webView?.let { loadWebsite(it, url) }
    }

    private fun openCheckPage() {
        val view = webView ?: return
        val html = runCatching {
            assets.open("check.html").bufferedReader().use { it.readText() }
        }.getOrNull()
        if (html == null) {
            toast(getString(R.string.operation_failed))
            return
        }
        setUrlBarText(getString(R.string.check_page_title))
        view.loadDataWithBaseURL(CHECK_BASE_URL, html, "text/html", "utf-8", null)
    }

    /** 新标签页的起始页：搜索框 + 常用网站 + 最近访问。 */
    private fun openStartPage() {
        val view = webView ?: return
        val html = runCatching {
            assets.open("start.html").bufferedReader().use { it.readText() }
        }.getOrNull()
        if (html == null) {
            openCheckPage()
            return
        }
        val bookmarks = bookmarkStore.list(profile.id).take(8)
        val history = historyStore.list(profile.id).take(6)
        val page = html
            .replace("__PROFILE_NAME__", escapeHtml(profile.name))
            .replace("__BOOKMARKS_JSON__", linksJson(bookmarks.map { it.title to it.url }))
            .replace("__HISTORY_JSON__", linksJson(history.map { it.title to it.url }))
        binding.urlBar.setText("")
        binding.urlBar.clearFocus()
        view.loadDataWithBaseURL(START_BASE_URL, page, "text/html", "utf-8", null)
    }

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

    /** 内置检测页 / 起始页在地址栏里不显示内部 URL。 */
    private fun urlLabel(url: String?): String = when {
        url.isNullOrBlank() -> ""
        url == CHECK_URL || url.startsWith(CHECK_BASE_URL) -> getString(R.string.check_page_title)
        url.startsWith(START_BASE_URL) -> ""
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
        webView?.let { loadWebsite(it, url) }
    }

    private fun normalizeUrl(input: String): String {
        val text = input.trim()
        if (text.isEmpty()) return CHECK_URL
        if (text == CHECK_URL) return CHECK_URL
        if (text.startsWith("http://") || text.startsWith("https://") ||
            text.startsWith("file:") || text.startsWith("about:")
        ) {
            return text
        }
        if (text.contains(" ") || !text.contains(".")) {
            return "https://www.bing.com/search?q=" + Uri.encode(text)
        }
        return "https://$text"
    }

    private fun openExternal(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }.onFailure {
            toast(getString(R.string.browser_external_missing))
        }
    }

    private fun enqueueDownload(url: String, userAgent: String, contentDisposition: String?, mimeType: String?) {
        // 系统 DownloadManager 不继承 WebView 的代理覆盖，不能用于代理环境。
        if (profile.proxy.type != com.openbrowser.mobile.data.ProxyType.DIRECT) {
            MaterialAlertDialogBuilder(this)
                .setMessage(R.string.browser_proxy_download_unavailable)
                .setPositiveButton(R.string.action_confirm, null)
                .show()
            return
        }
        runCatching {
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimeType)
                addRequestHeader("User-Agent", userAgent)
                CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalFilesDir(this@BrowserActivity, Environment.DIRECTORY_DOWNLOADS, fileName)
            }
            getSystemService(DownloadManager::class.java).enqueue(request).also { id ->
                downloadStore.add(profile.id, id, fileName)
            }
            toast(getString(R.string.browser_download_started, fileName))
        }.onFailure {
            toast(it.message ?: getString(R.string.operation_failed))
        }
    }

    private data class BrowserListItem(
        val title: String,
        val url: String,
        val timestamp: Long,
    )

    private fun showHistory() = showBrowserList(history = true)

    private fun showBookmarks() = showBrowserList(history = false)

    /** 历史记录 / 书签共用的全屏列表。 */
    private fun showBrowserList(history: Boolean) {
        val dialog = Dialog(this, R.style.Theme_OpenBrowser_FullScreenDialog)
        val view = DialogBrowserListBinding.inflate(layoutInflater)
        dialog.setContentView(view.root)

        view.tvListTitle.text = getString(
            if (history) R.string.browser_history else R.string.browser_bookmarks,
        )
        view.btnListClear.setOnClickListener {
            if (history) {
                historyStore.clear(profile.id)
            } else {
                bookmarkStore.clear(profile.id)
            }
            dialog.dismiss()
            toast(getString(if (history) R.string.history_cleared else R.string.bookmarks_cleared))
        }
        view.btnListClose.setOnClickListener { dialog.dismiss() }

        val items = if (history) {
            historyStore.list(profile.id).map { BrowserListItem(it.title, it.url, it.visitedAt) }.toMutableList()
        } else {
            bookmarkStore.list(profile.id).map { BrowserListItem(it.title, it.url, it.createdAt) }.toMutableList()
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

            visible.forEach { item ->
                val row = ItemBrowserRowBinding.inflate(layoutInflater, container, false)
                row.tvRowTitle.text = item.title.ifBlank { item.url }
                row.tvRowUrl.text = item.url
                row.tvRowTime.text = timeFormat.format(Date(item.timestamp))
                row.root.setOnClickListener {
                    dialog.dismiss()
                    openTab(item.url)
                }
                row.btnRowDelete.setOnClickListener {
                    if (history) {
                        historyStore.remove(profile.id, item.url)
                    } else {
                        bookmarkStore.remove(profile.id, item.url)
                    }
                    items.remove(item)
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

    /** 本环境发起过的下载任务；文件本身由系统 DownloadManager 管理。 */
    private fun showDownloads() {
        val dialog = Dialog(this, R.style.Theme_OpenBrowser_FullScreenDialog)
        val view = DialogBrowserListBinding.inflate(layoutInflater)
        dialog.setContentView(view.root)

        view.tvListTitle.text = getString(R.string.browser_downloads)
        view.searchList.visibility = View.GONE
        view.btnListClear.setOnClickListener {
            downloadStore.clear(profile.id)
            dialog.dismiss()
            toast(getString(R.string.downloads_cleared))
        }
        view.btnListClose.setOnClickListener { dialog.dismiss() }

        val manager = getSystemService(DownloadManager::class.java)
        val container = view.listContainer
        val timeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        val records = downloadStore.list(profile.id)

        view.tvListEmpty.text = getString(R.string.list_empty)
        view.tvListEmpty.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE

        records.forEach { record ->
            val row = ItemBrowserRowBinding.inflate(layoutInflater, container, false)
            var status = getString(R.string.download_status_running)
            var timestamp = 0L
            var mediaType: String? = null

            runCatching {
                manager.query(DownloadManager.Query().setFilterById(record.id))?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        status = when (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                            DownloadManager.STATUS_SUCCESSFUL -> getString(R.string.download_status_done)
                            DownloadManager.STATUS_FAILED -> getString(R.string.download_status_failed)
                            DownloadManager.STATUS_PAUSED -> getString(R.string.download_status_paused)
                            else -> getString(R.string.download_status_running)
                        }
                        timestamp = cursor.getLong(
                            cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP),
                        )
                        mediaType = cursor.getString(
                            cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_MEDIA_TYPE),
                        )
                    }
                }
            }

            row.tvRowTitle.text = record.name
            row.tvRowUrl.text = status
            row.tvRowTime.text = if (timestamp > 0L) timeFormat.format(Date(timestamp)) else ""
            row.root.setOnClickListener {
                val uri = runCatching { manager.getUriForDownloadedFile(record.id) }.getOrNull()
                if (uri == null) {
                    toast(getString(R.string.download_not_ready))
                    return@setOnClickListener
                }
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mediaType ?: "*/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runCatching { startActivity(intent) }.onFailure {
                    toast(getString(R.string.browser_external_missing))
                }
            }
            row.btnRowDelete.setOnClickListener {
                downloadStore.remove(profile.id, record.id)
                container.removeView(row.root)
                if (container.childCount == 0) view.tvListEmpty.visibility = View.VISIBLE
            }
            container.addView(row.root)
        }

        dialog.show()
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        Motion.staggerIn(container, startDelay = 40L, step = 18L, translateDp = 6f)
    }

    private fun toggleBookmark() {
        val tab = activeTab
        val url = tab?.url.orEmpty()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            toast(getString(R.string.browser_empty_page))
            return
        }
        val added = bookmarkStore.toggle(profile.id, url, tab?.title.orEmpty())
        toast(
            getString(
                if (added) R.string.browser_bookmark_added else R.string.browser_bookmark_removed,
            ),
        )
    }

    private fun shareCurrent() {
        val url = activeTab?.url.orEmpty()
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
        val url = activeTab?.url.orEmpty()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            toast(getString(R.string.browser_empty_page))
            return
        }
        openExternal(Uri.parse(url))
    }

    /** 桌面版网站：临时换 UA 与视口，刷新生效；每个标签独立，不影响环境指纹配置。 */
    private fun toggleDesktopMode() {
        val tab = activeTab ?: return
        tab.desktop = !tab.desktop
        val settings = tab.view.settings
        if (tab.desktop) {
            settings.userAgentString = DESKTOP_USER_AGENT
            settings.loadWithOverviewMode = false
            settings.useWideViewPort = true
        } else {
            settings.userAgentString = profile.userAgent
            settings.loadWithOverviewMode = true
        }
        tab.view.reload()
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
            getString(R.string.label_group) to profile.group.ifBlank { getString(R.string.profile_ungrouped) },
            getString(R.string.label_user_agent) to profile.userAgent,
            getString(R.string.label_platform) to profile.platform,
            getString(R.string.label_locale) to profile.locale,
            getString(R.string.label_timezone) to profile.timezone,
            getString(R.string.browser_info_screen) to "${profile.screenWidth}×${profile.screenHeight} @ ${dpr}x",
            getString(R.string.label_webgl_vendor) to profile.webglVendor,
            getString(R.string.label_webgl_renderer) to profile.webglRenderer,
            getString(R.string.label_webrtc_mode) to profile.webRtcMode,
            getString(R.string.label_proxy_type) to if (profile.proxy.enabled) {
                profile.proxy.display()
            } else {
                getString(R.string.profile_direct)
            },
            getString(R.string.browser_info_data_dir) to profile.dataSuffix,
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
                if (cookieCaptures.get() > 0 || websiteBridge.busy) {
                    toast(getString(R.string.sync_profile_busy))
                } else {
                    clearingWebsiteData = true
                    tabs.forEach { it.view.stopLoading() }
                    CookieManager.getInstance().removeAllCookies {
                        runCatching {
                            CookieManager.getInstance().flush()
                            WebStorage.getInstance().deleteAllData()
                            com.openbrowser.mobile.sync.CookieSnapshots(this).clear(profile.id)
                            com.openbrowser.mobile.sync.WebsiteSnapshots(this, profile.id).clearSites()
                            com.openbrowser.mobile.sync.SyncScheduler.kick(this)
                            if (!isDestroyed && !isFinishing) {
                                tabs.forEach { tab -> tab.view.clearCache(true); tab.view.clearFormData(); tab.view.clearHistory() }
                                toast(getString(R.string.clear_data_done, profile.name))
                                finish()
                            }
                        }.onFailure {
                            clearingWebsiteData = false
                            if (!isDestroyed && !isFinishing) toast(getString(R.string.sync_failed, it.message.orEmpty()))
                        }
                    }
                }
            }
            .show()
    }

    override fun onPause() {
        super.onPause()
        if (tabs.isNotEmpty()) {
            CookieManager.getInstance().flush()
            tabs.forEach { captureWebsiteData(it.view) }
            val config = runCatching { com.openbrowser.mobile.sync.SyncStore(this).read().getJSONObject("config") }.getOrNull()
            val ids = config?.optJSONArray("selected")
            if (!clearingWebsiteData && config?.optBoolean("enabled") == true && config.optBoolean("includeCookies") && ids != null &&
                (0 until ids.length()).any { ids.optString(it) == profile.id }) captureCookieSnapshot(false)
        }
    }

    override fun onDestroy() {
        // 入口校验失败时不能通过 WebViewFeature 间接初始化默认 WebView 数据目录。
        if (::binding.isInitialized) ProxyManager.stop()
        pendingFileCallback?.onReceiveValue(null)
        pendingFileCallback = null
        customView?.let { view -> (view.parent as? ViewGroup)?.removeView(view) }
        customView = null
        customViewCallback = null
        tabs.forEach { tab ->
            binding.webContainer.removeView(tab.view)
            runCatching {
                tab.view.stopLoading()
                tab.view.destroy()
            }
        }
        tabs.clear()
        if (::binding.isInitialized && ::profile.isInitialized) websiteBridge.close()
        syncLease?.close(); syncLease = null
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_PROFILE_ID = "com.openbrowser.mobile.extra.PROFILE_ID"
        const val EXTRA_RELAUNCH_ATTEMPT = "com.openbrowser.mobile.extra.RELAUNCH_ATTEMPT"

        /** 内置环境检测页，启动页留空（或填这个地址）时打开。 */
        private const val CHECK_URL = "openbrowser://check"
        private const val CHECK_BASE_URL = "https://check.openbrowser/"
        private const val START_BASE_URL = "https://start.openbrowser/"

        /** 单个环境最多同时保留的标签数，避免 WebView 占用过多内存。 */
        private const val MAX_TABS = 8

        /** 「桌面版网站」使用的 UA；和环境配置的移动端 UA 分开，切换后刷新即生效。 */
        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36"

        fun intent(context: Context, profileId: String): Intent =
            Intent(context, BrowserActivity::class.java).putExtra(EXTRA_PROFILE_ID, profileId)
    }
}
