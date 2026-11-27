package com.tvtube.app

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
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

    private val tvTubeScript = """
        (function() {
            if (window.__tvTubeLoaded) return;
            window.__tvTubeLoaded = true;

            // 1. Report 4K screen dimensions (no DPR override, so zero extra UI RAM)
            try {
                Object.defineProperty(window.screen, 'width', { get: () => 3840, configurable: true });
                Object.defineProperty(window.screen, 'height', { get: () => 2160, configurable: true });
                Object.defineProperty(window.screen, 'availWidth', { get: () => 3840, configurable: true });
                Object.defineProperty(window.screen, 'availHeight', { get: () => 2160, configurable: true });
            } catch (e) {}

            // 2. Block AV1/HEVC/10-bit HDR, and mark supported H.264/VP9 streams as smooth
            const badCodecs = /av01|hev1|hvc1|vp09\.02|vp09\.03/i;
            if (window.MediaSource && MediaSource.isTypeSupported) {
                const origSupported = MediaSource.isTypeSupported.bind(MediaSource);
                MediaSource.isTypeSupported = function(mime) {
                    if (mime && badCodecs.test(mime)) return false;
                    return origSupported(mime);
                };
            }
            const origCanPlay = HTMLMediaElement.prototype.canPlayType;
            HTMLMediaElement.prototype.canPlayType = function(mime) {
                if (mime && badCodecs.test(mime)) return '';
                return origCanPlay.call(this, mime);
            };
            if (navigator.mediaCapabilities && navigator.mediaCapabilities.decodingInfo) {
                const origDecodingInfo = navigator.mediaCapabilities.decodingInfo.bind(navigator.mediaCapabilities);
                navigator.mediaCapabilities.decodingInfo = function(config) {
                    const ct = config && config.video && config.video.contentType;
                    if (ct && badCodecs.test(ct)) {
                        return Promise.resolve({ supported: false, smooth: false, powerEfficient: false });
                    }
                    return origDecodingInfo(config).then(function(res) {
                        if (res && res.supported) {
                            return { supported: true, smooth: true, powerEfficient: true };
                        }
                        return res;
                    });
                };
            }

            // 3. Strip UI animations, shadows, blurs, and ambient effects to reduce RAM/stutter
            const injectLiteCss = function() {
                if (document.getElementById('tvtube-lite-css')) return;
                const style = document.createElement('style');
                style.id = 'tvtube-lite-css';
                style.textContent = `
                    *, *::before, *::after {
                        animation: none !important;
                        transition: none !important;
                        box-shadow: none !important;
                        text-shadow: none !important;
                        backdrop-filter: none !important;
                        -webkit-backdrop-filter: none !important;
                    }
                    #cinematics, .ytlr-watch-ambient-mode {
                        display: none !important;
                    }
                `;
                (document.head || document.documentElement).appendChild(style);
            };
            if (document.head || document.documentElement) {
                injectLiteCss();
            } else {
                document.addEventListener('DOMContentLoaded', injectLiteCss, { once: true });
            }

            // 4. Strip ad payloads from YouTube's internal API responses
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

            // 5. Lightweight fallback ad-skipper
            setInterval(function() {
                const player = document.querySelector('.html5-video-player.ad-showing');
                if (!player) return;
                const video = document.querySelector('video');
                if (video) {
                    video.muted = true;
                    if (isFinite(video.duration) && video.duration > 0) {
                        video.currentTime = video.duration;
                    }
                }
                const skipBtn = document.querySelector(
                    '.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button'
                );
                if (skipBtn) skipBtn.click();
            }, 500);
        })();
    """.trimIndent()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        webView = WebView(this)
        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
        // 1:1 physical pixels so viewport isn't shrunk to 960x540
        webView.setInitialScale(100)
        setContentView(webView)
        webView.requestFocus()

        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.allowFileAccess = false

        // Standard Chromium Smart TV (webOS) UA — uses native MSE without proprietary hooks
        settings.userAgentString =
            "Mozilla/5.0 (Web0S; Linux/SmartTV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.5735.196 Safari/537.36 WebAppManager"

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, tvTubeScript, setOf("*"))
        }

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
                view?.evaluateJavascript(tvTubeScript, null)
            }
        }

        webView.loadUrl("https://www.youtube.com/tv")
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
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
