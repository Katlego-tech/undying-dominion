package com.undyingdominion.app

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * The whole app: a fullscreen, landscape WebView that shows the finder, then the game served by the
 * Dominion the player picks (docs/design/android.md). Back in a game asks before leaving.
 */
class MainActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var finder: ServerFinder
    private val prefs by lazy { getSharedPreferences("dominion", MODE_PRIVATE) }
    private val assets by lazy {
        WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this)).build()
    }

    /** The address of the game being played, while one is. */
    private var playing: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        finder = ServerFinder(this, ::found, ::unavailable)

        web = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#09080C"))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webViewClient = Client()
        }
        // window.Dominion exists only on the finder's own origin, so no page served by a PC can reach it.
        // An old system WebView has no message channel: the finder still joins by a link the app
        // catches (PLAY), and says that finding servers needs the WebView updated.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(web, "Dominion", setOf(FINDER_ORIGIN)) { _, message, _, isMainFrame, _ ->
                if (isMainFrame) FinderMessages.parse(message.data)?.let(::handle)
            }
        }
        setContentView(web)
        immersive()
        showFinder(null)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
        if (showingFinder()) finder.start()
    }

    override fun onPause() {
        finder.stop()
        web.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        finder.stop()
        web.destroy()
        super.onDestroy()
    }

    /** Fullscreen: no status bar, no navigation bar until swiped in. */
    private fun immersive() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        }
    }

    // ---------------------------------------------------------------- the finder

    /** Whether the page on show is the finder. UI thread. */
    private fun showingFinder() = web.url?.startsWith(FINDER) == true

    private fun showFinder(unreachable: String?) {
        playing = null
        val query = buildString {
            append("?last=").append(Uri.encode(prefs.getString(LAST_SERVER, "") ?: ""))
            unreachable?.let { append("&unreachable=").append(Uri.encode(it)) }
        }
        web.loadUrl(FINDER + query)
        finder.start()
    }

    private fun found(servers: List<Server>) {
        val json = Beacons.toJson(servers)
        runOnUiThread {
            if (showingFinder()) web.evaluateJavascript("window.finder && window.finder.found($json)", null)
        }
    }

    private fun unavailable() = runOnUiThread {
        if (showingFinder()) web.evaluateJavascript("window.finder && window.finder.unavailable()", null)
    }

    /** A request from the finder page; the listener calls this on the UI thread. */
    private fun handle(message: FinderMessage) {
        when (message) {
            FinderMessage.Scan -> finder.start()
            FinderMessage.Stop -> finder.stop()
            is FinderMessage.Play -> play(message.address)
        }
    }

    private fun play(address: String) {
        prefs.edit().putString(LAST_SERVER, address).apply()
        finder.stop()
        playing = address
        web.loadUrl("http://$address/?app=1")
    }

    // ---------------------------------------------------------------- Back

    @Deprecated("Back is handled here for every API level the app supports")
    override fun onBackPressed() {
        if (playing == null) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
            return
        }
        // The game closes an aiming mode or the rise-as picker first, as Esc would (window.appBack).
        web.evaluateJavascript("typeof window.appBack === 'function' && window.appBack()") { handled ->
            if (handled != "true") confirmLeave()
        }
    }

    private fun confirmLeave() {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setMessage("Leave the Dominion?")
            .setPositiveButton("Leave") { _, _ -> showFinder(null) }
            .setNegativeButton("Stay", null)
            .setOnDismissListener { immersive() }
            .show()
    }

    // ---------------------------------------------------------------- loading

    private inner class Client : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
            assets.shouldInterceptRequest(request.url)

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            // The game's "Return" after a severed connection comes back to the finder.
            if (url.startsWith(FINDER)) {
                showFinder(null)
                return true
            }
            // Joining without the message channel: honoured only from the finder itself.
            if (url.startsWith(PLAY)) {
                if (showingFinder()) Addresses.parse(request.url.getQueryParameter("address") ?: "")?.let(::play)
                return true
            }
            return false
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            val address = playing
            if (request.isForMainFrame && address != null) showFinder(address)
        }
    }

    private companion object {
        const val FINDER_ORIGIN = "https://appassets.androidplatform.net"
        const val FINDER = "$FINDER_ORIGIN/assets/finder/index.html"
        const val PLAY = "$FINDER_ORIGIN/play"
        const val LAST_SERVER = "lastServer"
    }
}
