package com.undyingdominion.app

import android.webkit.JavascriptInterface

/**
 * `window.Dominion` on the finder page (docs/design/android.md §6). WebView calls these on its own
 * background thread; [host] hops to the UI thread. A game page served by a Dominion sees the object too,
 * so every call does nothing unless the finder is what's showing.
 */
class FinderBridge(private val host: Host) {

    interface Host {
        val showingFinder: Boolean
        fun scan()
        fun stopScan()
        fun play(address: String)
        fun lastServer(): String
    }

    @JavascriptInterface
    fun scan() {
        if (host.showingFinder) host.scan()
    }

    @JavascriptInterface
    fun stop() {
        if (host.showingFinder) host.stopScan()
    }

    @JavascriptInterface
    fun play(address: String) {
        if (!host.showingFinder) return
        Addresses.parse(address)?.let(host::play)
    }

    @JavascriptInterface
    fun lastServer(): String = if (host.showingFinder) host.lastServer() else ""
}
