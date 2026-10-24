package com.undyingdominion.app

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** A Dominion heard on the LAN. [host] is where the beacon came from, never an address written inside it. */
data class Server(
    val name: String,
    val host: String,
    val port: Int,
    val modes: List<String>,
    val players: Int,
    val max: Int,
    val seenAt: Long,
) {
    val address get() = "$host:$port"
}

/** Reading the server's beacon (docs/design/android.md §6 Beacon). */
object Beacons {
    const val PORT = 5001

    /** The server a datagram from [host] describes, or null for anything that isn't a Dominion's beacon. */
    fun parse(bytes: ByteArray, host: String, now: Long): Server? = try {
        val json = JSONObject(String(bytes, Charsets.UTF_8))
        val port = json.optInt("port", -1)
        if (json.optString("game") != "undying-dominion" || !json.has("name") || port !in 1..65535) {
            null
        } else {
            Server(
                name = json.getString("name"),
                host = host,
                port = port,
                modes = modesOf(json),
                players = json.optInt("players", 0),
                max = json.optInt("max", 0),
                seenAt = now,
            )
        }
    } catch (e: JSONException) {
        null
    }

    /** A hub lists `modes`; a server from before the hub names one `mode` (docs/design/modes.md §6 Beacon). */
    private fun modesOf(json: JSONObject): List<String> {
        val modes = json.optJSONArray("modes")
        val listed = if (modes == null) emptyList() else (0 until modes.length()).mapNotNull { modes.optString(it).ifEmpty { null } }
        return listed.ifEmpty { listOf(json.optString("mode", "free-for-all")) }
    }

    /** What finder.found() takes: the servers without the time they were heard. */
    fun toJson(servers: List<Server>): String {
        val array = JSONArray()
        for (s in servers) {
            array.put(
                JSONObject()
                    .put("name", s.name).put("host", s.host).put("port", s.port)
                    .put("modes", JSONArray(s.modes)).put("players", s.players).put("max", s.max),
            )
        }
        return array.toString()
    }
}

/** The servers heard lately: one row per address, newest first, forgotten after six silent seconds. */
class ServerList {
    private val byAddress = LinkedHashMap<String, Server>()

    @Synchronized
    fun heard(server: Server) {
        byAddress[server.address] = server
    }

    @Synchronized
    fun current(now: Long): List<Server> {
        byAddress.values.removeAll { now - it.seenAt > FORGET_AFTER_MILLIS }
        return byAddress.values.sortedByDescending { it.seenAt }
    }

    companion object {
        const val FORGET_AFTER_MILLIS = 6000L
    }
}

/** A typed address: a host, or host:port with the port in range. */
object Addresses {
    private val pattern = Regex("""^([A-Za-z0-9.-]+)(?::(\d{1,5}))?$""")
    const val DEFAULT_PORT = 5000

    fun parse(text: String): String? {
        val match = pattern.matchEntire(text.trim()) ?: return null
        val port = match.groupValues[2].ifEmpty { DEFAULT_PORT.toString() }.toInt()
        return if (port in 1..65535) "${match.groupValues[1]}:$port" else null
    }
}
