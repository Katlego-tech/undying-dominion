package com.undyingdominion.app

import org.json.JSONException
import org.json.JSONObject

/** What the finder page asks of the app, posted as JSON on `window.Dominion` (docs/design/android.md §6). */
sealed interface FinderMessage {
    data object Scan : FinderMessage
    data object Stop : FinderMessage
    data class Play(val address: String) : FinderMessage
}

object FinderMessages {
    /** The request in [json], or null for anything malformed, unknown, or an address that doesn't parse. */
    fun parse(json: String?): FinderMessage? = try {
        val message = JSONObject(json ?: "")
        when (message.optString("do")) {
            "scan" -> FinderMessage.Scan
            "stop" -> FinderMessage.Stop
            "play" -> Addresses.parse(message.optString("address"))?.let { FinderMessage.Play(it) }
            else -> null
        }
    } catch (e: JSONException) {
        null
    }
}
