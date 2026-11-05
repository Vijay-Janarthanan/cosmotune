package com.cosmotune.manga.ui

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.*
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.cosmotune.manga.data.MangaVerificationSessions

/** A private process and WebView profile; no native JS bridge and no music-account cookies. */
class MangaVerificationActivity : Activity() {
    private var browser: WebView? = null
    private lateinit var address: String
    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT < 28 || !Application.getProcessName().endsWith(":mangaverification")) { finish(); return }
        try {
            WebView.setDataDirectorySuffix("cosmotune_manga_verification")
            address = requireNotNull(intent.getStringExtra(ADDRESS))
            MangaVerificationSessions.checked(address)
            require(intent.getStringExtra(NONCE).orEmpty().matches(Regex("[a-f0-9-]{36}")))
            val expiry = intent.getLongExtra(EXPIRES, 0)
            require(expiry - System.currentTimeMillis() in 1..MangaVerificationSessions.REQUEST_TTL_MS)
            val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(24, 24, 24)) }
            status = TextView(this).apply {
                text = "Complete the website's check, then use its verified session.\n${MangaVerificationSessions.checked(address).host}"
                setTextColor(Color.WHITE); setPadding(20, 20, 20, 12)
            }
            layout.addView(status)
            val buttons = LinearLayout(this)
            buttons.addView(Button(this).apply { text = "Use verified session"; setOnClickListener { complete() } })
            buttons.addView(Button(this).apply { text = "Cancel"; setOnClickListener { finish() } })
            layout.addView(buttons)
            val view = WebView(this).also { browser = it; configure(it) }
            layout.addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            setContentView(layout)
            WebStorage.getInstance().deleteAllData()
            WebViewDatabase.getInstance(this).clearHttpAuthUsernamePassword()
            view.clearCache(true)
            CookieManager.getInstance().removeAllCookies { if (!isFinishing) view.loadUrl(address) }
            handler.postDelayed({ finish() }, expiry - System.currentTimeMillis())
        } catch (_: Exception) { finish() }
    }

    @Suppress("SetJavaScriptEnabled")
    private fun configure(view: WebView) {
        view.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
            allowFileAccess = false; allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false; setSupportMultipleWindows(false)
            setGeolocationEnabled(false)
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
        view.setDownloadListener { _, _, _, _, _ -> status.text = "Downloads are unavailable in source verification." }
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.isForMainFrame && runCatching { MangaVerificationSessions.checked(request.url.toString().substringBefore('#')) }.isFailure
            override fun onReceivedSslError(view: WebView, response: SslErrorHandler, error: android.net.http.SslError) {
                response.cancel(); status.text = "The website's secure connection failed. Return to Manga and try another source."
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) status.text = "The website could not load. Its browser check may not support this WebView."
            }
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) = request.deny()
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) = callback.invoke(origin, false, false)
        }
    }

    private fun complete() {
        val view = browser ?: return
        try {
            val current = MangaVerificationSessions.checked(view.url.orEmpty().substringBefore('#'))
            require(MangaVerificationSessions.origin(current) == MangaVerificationSessions.origin(MangaVerificationSessions.checked(address)))
            val cookies = CookieManager.getInstance().getCookie(address).orEmpty()
            require(cookies.length in 1..32_768)
            setResult(RESULT_OK, Intent().putExtra(NONCE, intent.getStringExtra(NONCE))
                .putExtra(ADDRESS, current.toString()).putExtra(COOKIES, cookies).putExtra(USER_AGENT, view.settings.userAgentString))
            finish()
        } catch (_: Exception) { status.text = "Return to the source website and complete its check before using this session." }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (Build.VERSION.SDK_INT >= 28 && Application.getProcessName().endsWith(":mangaverification")) {
            runCatching { CookieManager.getInstance().removeAllCookies(null); CookieManager.getInstance().flush(); WebStorage.getInstance().deleteAllData() }
            browser?.let { it.stopLoading(); it.clearCache(true); it.loadUrl("about:blank"); it.destroy() }
            Handler(Looper.getMainLooper()).postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 250)
        }
        browser = null
        super.onDestroy()
    }

    companion object {
        const val ADDRESS = "cosmotune.manga.verification.address"
        const val NONCE = "cosmotune.manga.verification.nonce"
        const val EXPIRES = "cosmotune.manga.verification.expires"
        const val COOKIES = "cosmotune.manga.verification.cookies"
        const val USER_AGENT = "cosmotune.manga.verification.userAgent"
    }
}
