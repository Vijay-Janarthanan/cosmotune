package com.cosmotune.ui.providers

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.*
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.cosmotune.providers.ProviderVerificationTarget
import kotlinx.serialization.json.*

/** Private, separate-process browser. No app stores, native JS bridge or general browser cookies. */
class ProviderVerificationActivity : Activity() {
    private lateinit var target: ProviderVerificationTarget
    private var view: WebView? = null
    private val children = mutableListOf<WebView>()
    private val handler = Handler(Looper.getMainLooper())
    private var completed = false
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT < 28 || !android.app.Application.getProcessName().endsWith(":provider_auth")) { fail(); return }
        try {
            WebView.setDataDirectorySuffix("cosmotune_provider_verification")
            val input = requireNotNull(intent.getStringExtra(TARGET)).also { require(it.length <= 40_000) }
            target = Json.decodeFromString<ProviderVerificationTarget>(input)
            require(System.currentTimeMillis() < target.expiresAt)
            val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(24, 24, 24)) }
            status = TextView(this).apply { text = "${target.title} · ${Uri.parse(target.url).host}"; setTextColor(Color.WHITE); setPadding(20, 16, 20, 12) }
            val buttons = LinearLayout(this)
            buttons.addView(Button(this).apply { text = "Open in browser"; setOnClickListener {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target.url))) }.onFailure { status.text = "Could not open browser. Return to the app to retry." }
            } })
            buttons.addView(Button(this).apply { text = "Close"; setOnClickListener { finish() } })
            layout.addView(status); layout.addView(buttons)
            val browser = WebView(this).also { view = it; configure(it) }
            layout.addView(browser, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            setContentView(layout)
            // This process has a dedicated data suffix. Clear only its own previous browser state.
            WebStorage.getInstance().deleteAllData()
            WebViewDatabase.getInstance(this).clearHttpAuthUsernamePassword()
            browser.clearCache(true); browser.clearFormData()
            CookieManager.getInstance().removeAllCookies {
                if (!isFinishing) { browser.loadUrl(target.url); pollGrant() }
            }
            handler.postDelayed({ if (!isFinishing) finish() }, (target.expiresAt - System.currentTimeMillis()).coerceAtLeast(1))
        } catch (_: Exception) { fail() }
    }

    @Suppress("SetJavaScriptEnabled")
    private fun configure(browser: WebView, popup: Boolean = false) {
        browser.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
            allowFileAccess = false; allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(true); javaScriptCanOpenWindowsAutomatically = true
            setGeolocationEnabled(false)
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(browser, false)
        browser.setDownloadListener { _, _, _, _, _ -> status.text = "Downloads are not available in verification." }
        browser.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                val address = request.url.toString()
                target.completedCallback(address)?.let { complete(it); return true }
                if (!target.allowsNavigation(address)) return true
                if (popup) { this@ProviderVerificationActivity.view?.loadUrl(address); return true }
                return false
            }
            override fun onReceivedSslError(view: WebView, response: SslErrorHandler, error: android.net.http.SslError) {
                response.cancel(); status.text = "Secure connection failed. Return to the app to retry."
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) status.text = "Page could not load. Open it in your browser, or return to the app."
            }
        }
        browser.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) { callback.invoke(origin, false, false) }
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean {
                val child = WebView(this@ProviderVerificationActivity).also { children += it; configure(it, true) }
                (resultMsg.obj as? WebView.WebViewTransport)?.webView = child
                resultMsg.sendToTarget()
                return true
            }
        }
    }

    private fun pollGrant() {
        if (completed || isFinishing) return
        view?.evaluateJavascript(ProviderVerificationTarget.GRANT_SCRIPT) { encoded ->
            runCatching {
                val body = Json.parseToJsonElement(Json.parseToJsonElement(encoded).jsonPrimitive.content).jsonObject
                val address = body["url"]?.jsonPrimitive?.contentOrNull ?: return@runCatching
                val text = body["text"]?.jsonPrimitive?.contentOrNull ?: return@runCatching
                target.verifiedPageGrant(address, text)?.let(::complete)
            }
            if (!completed && !isFinishing) handler.postDelayed(::pollGrant, 500)
        }
    }

    private fun complete(result: String) {
        if (completed) return
        completed = true
        setResult(RESULT_OK, Intent().putExtra(RESULT, result))
        finish()
    }
    private fun fail() { setResult(RESULT_FIRST_USER); finish() }
    override fun onBackPressed() { finish() }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        children.forEach { it.stopLoading(); it.destroy() }; children.clear()
        view?.let { it.stopLoading(); it.loadUrl("about:blank"); it.destroy() }; view = null
        super.onDestroy()
        if (Build.VERSION.SDK_INT >= 28 && android.app.Application.getProcessName().endsWith(":provider_auth")) {
            // Release this dedicated WebView process, so the next provider starts with fresh cookies/storage.
            Handler(Looper.getMainLooper()).postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 250)
        }
    }
    companion object {
        const val TARGET = "cosmotune.provider.verification.target"
        const val RESULT = "cosmotune.provider.verification.result"
    }
}
