package com.tvtube.app

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream

class MainActivity : Activity() {

    private lateinit var webView: WebView

    private val adUrlKeywords = listOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "/pagead/",
        "/api/stats/ads",
        "/ptracking",
        "/get_midroll_info",
        "ad_break",
        "play.google.com/log"
    )

    private val adBlockScript = """
        (function() {
            if (window.__tvTubeLoaded) return;
            window.__tvTubeLoaded = true;

            // 1. Report 4K display capability so YouTube doesn't hide 1080p/1440p/4K options
            try {
                Object.defineProperty(window.screen, 'width', { get: () => 3840 });
                Object.defineProperty(window.screen, 'height', { get: () => 2160 });
                Object.defineProperty(window.screen, 'availWidth', { get: () => 3840 });
                Object.defineProperty(window.screen, 'availHeight', { get: () => 2160 });
            } catch (e) {}

            // 2. Strip ad payloads from YouTube's internal API responses
            const origParse = JSON.parse;
            JSON.parse = function(text, reviver) {
                const data = origParse.call(this, text, reviver);
                if (data && typeof data === 'object') {
                    if (data.adPlacements) data.adPlacements = [];
                    if (data.playerAds) data.playerAds = [];
                    if (data.adSlots) data.adSlots = [];
                }
                return data;
            };

            // 3. Fallback: fast-forward & click skip if an ad slips through
            setInterval(function() {
                const player = document.querySelector('.html5-video-player');
                const video = document.querySelector('video');
                if (player && player.classList.contains('ad-showing') && video) {
                    video.muted = true;
                    if (isFinite(video.duration) && video.duration > 0) {
                        video.currentTime = video.duration;
                    }
                    const skipBtn = document.querySelector(
                        '.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button'
                    );
                    if (skipBtn) skipBtn.click();
                }
            }, 300);
        })();
    """.trimIndent()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        webView = WebView(this)
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
        setContentView(webView)
        webView.requestFocus()

        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT

        // Modern Cobalt 26 Android TV User-Agent to unlock 1080p / 1440p / 4K / 60fps
        settings.userAgentString =
            "Mozilla/5.0 (Linux armeabi-v7a; Android 10) Cobalt/26.android.1.1036254-gold (unlike Gecko) v8/11.4.183.40-jit gles Starboard/17, Sony_ATV_sdm845_13140765/52.1.C.0.268 (KDDI, SOV38) com.google.android.youtube.tv/6.36.300"

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.webChromeClient = WebChromeClient()

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url?.toString()?.lowercase()
                    ?: return super.shouldInterceptRequest(view, request)
                if (adUrlKeywords.any { url.contains(it) }) {
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                view?.evaluateJavascript(adBlockScript, null)
            }
        }

        webView.loadUrl("https://www.youtube.com/tv")
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Translate TV remote Back button into a real native Escape key event
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            val escapeEvent = KeyEvent(
                event.downTime,
                event.eventTime,
                event.action,
                KeyEvent.KEYCODE_ESCAPE,
                event.repeatCount,
                event.metaState,
                event.deviceId,
                event.scanCode,
                event.flags,
                event.source
            )
            webView.dispatchKeyEvent(escapeEvent)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
