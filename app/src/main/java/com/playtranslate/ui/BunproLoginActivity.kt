package com.playtranslate.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isGone
import androidx.core.view.isVisible
import com.playtranslate.Prefs
import com.playtranslate.R

/**
 * A throwaway browser whose only job is to hand [BunproSettingsActivity] a fresh
 * `frontend_api_token` — the credential [com.playtranslate.bunpro.BunproClient]
 * needs, which Bunpro issues only to a logged-in browser session.
 *
 * Exists because that token **expires with no refresh path** (see
 * `docs/features/bunpro-integration.md`), so re-entry is routine rather than
 * one-time. Reading it by hand means desktop DevTools and a copy across devices;
 * Chrome for Android runs no extensions, so there is no browser-side shortcut
 * either. A WebView sidesteps both: the login happens on-device and the cookie
 * lands in a jar this process can already read.
 *
 * **The cookie jar, not `document.cookie`, is what makes this work.** Bunpro may
 * well mark the token `HttpOnly` — injected JS would then never see it — but
 * [CookieManager] is the native store behind the WebView and hands over
 * `HttpOnly` entries regardless. The `https://` [ORIGIN] matters for the same
 * reason: a plain-`http` argument silently drops `Secure` cookies.
 *
 * Deliberately does not touch [Prefs] — it returns the token as an activity
 * result and lets the settings screen validate before anything is persisted, so
 * a capture that turns out to be junk never overwrites a working credential.
 */
class BunproLoginActivity : SettingsSubPageActivity() {

    override val layoutResId = R.layout.activity_bunpro_login

    private lateinit var web: WebView
    private lateinit var progress: ProgressBar

    /** The saved token this login is meant to replace. Held so a jar that still
     *  contains it can't be mistaken for a successful capture — see
     *  [capturedToken]. */
    private var staleToken: String = ""

    private val handler = Handler(Looper.getMainLooper())

    /**
     * Bunpro logs in over XHR and then routes client-side, so the navigation
     * that sets the cookie may be the last one [WebViewClient.onPageFinished]
     * ever reports. Polling is what actually catches the token; the
     * `onPageFinished` check is just a faster path on a full page load.
     */
    private val pollForToken = object : Runnable {
        override fun run() {
            val token = capturedToken()
            if (token != null) {
                finishWith(token)
                return
            }
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onContentCreated(savedInstanceState: Bundle?) {
        staleToken = Prefs(this).bunproToken

        progress = findViewById(R.id.progressLogin)
        web = findViewById(R.id.webBunpro)

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }

        // Bunpro's login is a JS app; without these two there is no form to
        // fill in. Nothing else is enabled — no file access, no JS bridge.
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true

        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                progress.isVisible = true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progress.isGone = true
                capturedToken()?.let { finishWith(it) }
            }
        }

        // Reached only when the saved token stopped working, which means the
        // session that issued it is dead too. Dropping it forces a real login;
        // left in place, Bunpro would keep replaying the same stale cookie and
        // we would "capture" the very credential we came here to replace.
        if (staleToken.isNotBlank() && rawToken() == staleToken) clearBunproCookies()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })

        web.loadUrl(LOGIN_URL)
        handler.postDelayed(pollForToken, POLL_INTERVAL_MS)
    }

    /**
     * The `frontend_api_token` sitting in the jar right now, or null. Values are
     * unquoted but never URL-decoded: the token is base64url, so decoding could
     * only corrupt it (`+` would become a space).
     */
    private fun rawToken(): String? =
        CookieManager.getInstance().getCookie(ORIGIN)
            ?.split(';')
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("$TOKEN_COOKIE=") }
            ?.substringAfter('=')
            ?.trim('"')
            ?.takeIf { it.isNotBlank() }

    /** [rawToken] once it is demonstrably *new* — a jar still holding
     *  [staleToken] means the login hasn't happened yet. */
    private fun capturedToken(): String? = rawToken()?.takeIf { it != staleToken }

    /**
     * Expires every `bunpro.jp` cookie, scoped both host-only and to the shared
     * `.bunpro.jp` domain since we can't tell which way the token was set.
     * Targeted rather than `removeAllCookies` so this can never disturb an
     * unrelated WebView elsewhere in the app.
     */
    private fun clearBunproCookies() {
        val jar = CookieManager.getInstance()
        val raw = jar.getCookie(ORIGIN) ?: return
        raw.split(';').forEach { pair ->
            val name = pair.substringBefore('=').trim()
            if (name.isEmpty()) return@forEach
            jar.setCookie(ORIGIN, "$name=; Max-Age=0; Path=/")
            jar.setCookie(ORIGIN, "$name=; Max-Age=0; Path=/; Domain=$COOKIE_DOMAIN")
        }
        jar.flush()
    }

    /** Hands the token back. Never logged — it is a live credential. */
    private fun finishWith(token: String) {
        handler.removeCallbacks(pollForToken)
        // Persist the session so a later visit can capture without a re-login.
        CookieManager.getInstance().flush()
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_TOKEN, token))
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacks(pollForToken)
        if (::web.isInitialized) {
            (web.parent as? ViewGroup)?.removeView(web)
            web.destroy()
        }
        super.onDestroy()
    }

    companion object {
        private const val ORIGIN = "https://bunpro.jp"
        private const val COOKIE_DOMAIN = ".bunpro.jp"
        private const val LOGIN_URL = "$ORIGIN/login"
        private const val TOKEN_COOKIE = "frontend_api_token"
        private const val POLL_INTERVAL_MS = 400L

        /** Result extra: the captured token. Only set with [Activity.RESULT_OK]. */
        const val EXTRA_TOKEN = "extra_bunpro_token"

        fun newIntent(context: Context): Intent =
            Intent(context, BunproLoginActivity::class.java)
    }
}
