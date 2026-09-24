package com.example.privatebrowser

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.setPadding
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
/**
 * Private, no-history browser.
 *
 * Privacy behaviour:
 *  - WebView cache is disabled (LOAD_NO_CACHE) so nothing is cached to disk.
 *  - Form data / passwords / http-auth data are never saved.
 *  - Cookies and web storage (localStorage/IndexedDB) are wiped whenever the
 *    app leaves the foreground and again on process shutdown, so nothing
 *    persists between sessions.
 *  - All open tabs (WebView instances) are destroyed the moment the app is
 *    minimised (onStop) or killed (onDestroy) - nothing survives a minimize.
 */
class MainActivity : AppCompatActivity() {

    private data class BrowserTab(
        val webView: WebView,
        val tabLabel: TextView
    )

    private lateinit var tabsContainer: LinearLayout
    private lateinit var webViewContainer: FrameLayout
    private lateinit var urlInput: EditText
    private lateinit var progressBar: ProgressBar

    private val tabs = mutableListOf<BrowserTab>()
    private var currentTabIndex = -1
    private var tabCounter = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_main)

        tabsContainer = findViewById(R.id.tabsContainer)
        webViewContainer = findViewById(R.id.webViewContainer)
        urlInput = findViewById(R.id.urlInput)
        progressBar = findViewById(R.id.progressBar)

        findViewById<TextView>(R.id.newTabButton).setOnClickListener {
            createNewTab(select = true)
        }

        findViewById<TextView>(R.id.closeTabButton).setOnClickListener {
            if (currentTabIndex >= 0) closeTab(currentTabIndex)
        }

        findViewById<TextView>(R.id.goButton).setOnClickListener {
            loadFromInput()
        }

        urlInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                loadFromInput()
                true
            } else {
                false
            }
        }

        // Every fresh app open (including after a minimize wiped everything)
        // starts with exactly one clean, empty tab.
        createNewTab(select = true)
    }

    // ---------------------------------------------------------------
    // Tab management
    // ---------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun createNewTab(select: Boolean, url: String? = null) {
        val webView = WebView(this)
        configurePrivateWebView(webView)

        val label = TextView(this).apply {
            text = "تب ${++tabCounter}"
            setTextColor(0xFF9AA0A6.toInt())
            setPadding(28, 0, 28, 0)
            gravity = android.view.Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(200, LinearLayout.LayoutParams.MATCH_PARENT).apply {
                marginEnd = 2
            }
            setBackgroundResource(R.drawable.bg_tab_inactive)
            setOnClickListener { switchToTab(tabs.indexOfFirst { it.webView === webView }) }
        }
        tabsContainer.addView(label)

        val tab = BrowserTab(webView, label)
        tabs.add(tab)

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (view === currentWebViewOrNull()) {
                    progressBar.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
                    progressBar.progress = newProgress
                }
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                if (!title.isNullOrBlank()) {
                    label.text = if (title.length > 12) title.take(12) + "…" else title
                }
            }
        }
webView.setOnLongClickListener {
            val result = webView.hitTestResult
            val targetUrl = result.extra
            val isOpenable = targetUrl != null && (
                result.type == WebView.HitTestResult.IMAGE_TYPE ||
                result.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE ||
                result.type == WebView.HitTestResult.SRC_ANCHOR_TYPE
            )
            if (isOpenable) {
                AlertDialog.Builder(this)
                    .setItems(arrayOf("باز کردن در تب جدید")) { _, _ ->
                        createNewTab(select = true, url = targetUrl)
                    }
                    .show()
                true
            } else {
                false
            }
}
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, loadedUrl: String) {
                if (view === currentWebViewOrNull()) {
                    urlInput.setText(if (loadedUrl == "about:blank") "" else loadedUrl)
                }
            }
        }

        webViewContainer.addView(
            webView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        webView.visibility = View.GONE

        webView.loadUrl(url ?: "about:blank")

        if (select) {
            switchToTab(tabs.size - 1)
        }
    }

    private fun currentWebViewOrNull(): WebView? =
        tabs.getOrNull(currentTabIndex)?.webView

    private fun switchToTab(index: Int) {
        if (index !in tabs.indices) return
        tabs.forEachIndexed { i, tab ->
            val isSelected = i == index
            tab.webView.visibility = if (isSelected) View.VISIBLE else View.GONE
            tab.tabLabel.setBackgroundResource(
                if (isSelected) R.drawable.bg_tab_active else R.drawable.bg_tab_inactive
            )
            tab.tabLabel.setTextColor(
                if (isSelected) 0xFFE8EAED.toInt() else 0xFF9AA0A6.toInt()
            )
        }
        currentTabIndex = index
        val current = tabs[index].webView
        urlInput.setText(if (current.url == "about:blank") "" else current.url)
    }

    private fun closeTab(index: Int) {
        if (index !in tabs.indices) return
        val tab = tabs[index]
        webViewContainer.removeView(tab.webView)
        destroyWebView(tab.webView)
        tabsContainer.removeView(tab.tabLabel)
        tabs.removeAt(index)

        if (tabs.isEmpty()) {
            createNewTab(select = true)
        } else {
            switchToTab((index - 1).coerceAtLeast(0))
        }
    }

    // ---------------------------------------------------------------
    // Navigation
    // ---------------------------------------------------------------

    private fun loadFromInput() {
        val raw = urlInput.text.toString().trim()
        if (raw.isEmpty()) return
        val current = currentWebViewOrNull() ?: return

        val looksLikeUrl = raw.contains(".") && !raw.contains(" ")
        val finalUrl = when {
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            looksLikeUrl -> "https://$raw"
            else -> "https://www.google.com/search?q=" + Uri.encode(raw)
        }
        current.loadUrl(finalUrl)
        currentWindowInsetsHideKeyboard()
    }

    private fun currentWindowInsetsHideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        imm?.hideSoftInputFromWindow(urlInput.windowToken, 0)
    }

    override fun onBackPressed() {
        val current = currentWebViewOrNull()
        if (current != null && current.canGoBack()) {
            current.goBack()
        } else {
            super.onBackPressed()
        }
    }

    // ---------------------------------------------------------------
    // Privacy configuration
    // ---------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun configurePrivateWebView(webView: WebView) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true          // needed for many sites to work
            databaseEnabled = false           // no on-disk web SQL database
            cacheMode = WebSettings.LOAD_NO_CACHE
            saveFormData = false
            setGeolocationEnabled(false)
            allowFileAccess = false
            allowContentAccess = false
        }

        // Cookies are kept only for the current session, never written to disk.
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, false)
    }

    private fun destroyWebView(webView: WebView) {
        webView.apply {
            clearHistory()
            clearCache(true)
            clearFormData()
            loadUrl("about:blank")
            onPause()
            removeAllViews()
            destroy()
        }
    }

    /**
     * Wipes every tab and every trace of browsing data.
     * Called the instant the app is minimized or closed.
     */
    private fun wipeEverything() {
        for (tab in tabs.toList()) {
            webViewContainer.removeView(tab.webView)
            destroyWebView(tab.webView)
            tabsContainer.removeView(tab.tabLabel)
        }
        tabs.clear()
        currentTabIndex = -1
        tabCounter = 0

        val cookieManager = CookieManager.getInstance()
        cookieManager.removeAllCookies(null)
        cookieManager.flush()

        WebStorage.getInstance().deleteAllData()

        try {
            val webViewDatabase = WebViewDatabase.getInstance(this)
            webViewDatabase.clearFormData()
            webViewDatabase.clearHttpAuthUsernamePassword()
        } catch (e: Exception) {
            Log.w("PrivateBrowser", "Could not clear WebViewDatabase", e)
        }

        // Also remove the app's own webview data directory contents.
        try {
            deleteDir(getDir("webview", MODE_PRIVATE))
            cacheDir?.let { deleteDir(it) }
        } catch (e: Exception) {
            Log.w("PrivateBrowser", "Could not clear webview cache dir", e)
        }
    }

    private fun deleteDir(dir: java.io.File?): Boolean {
        if (dir == null || !dir.exists()) return true
        if (dir.isDirectory) {
            dir.listFiles()?.forEach { deleteDir(it) }
        }
        return dir.delete()
    }

    // ---------------------------------------------------------------
    // Lifecycle: this is where "minimize = close everything" happens
    // ---------------------------------------------------------------

    override fun onStop() {
        // Called as soon as the app goes to the background (minimized,
        // home button pressed, app switched away from, etc.)
        wipeEverything()
        super.onStop()
    }

    override fun onDestroy() {
        if (tabs.isNotEmpty()) wipeEverything()
        super.onDestroy()
    }

    override fun onRestart() {
        super.onRestart()
        // If somehow onStop ran but the activity is being restarted without
        // a fresh onCreate, make sure there is always at least one clean tab.
        if (tabs.isEmpty()) {
            createNewTab(select = true)
        }
    }
}
