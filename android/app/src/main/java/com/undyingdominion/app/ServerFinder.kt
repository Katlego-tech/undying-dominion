package com.undyingdominion.app

import android.content.Context
import android.net.wifi.WifiManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException

/**
 * Listens for Dominions' beacons on UDP 5001 and reports the servers heard lately about twice a
 * second, on its own thread. The multicast lock keeps the Wi-Fi radio passing broadcasts up.
 */
class ServerFinder(context: Context, private val onServers: (List<Server>) -> Unit) {

    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val lock = wifi.createMulticastLock("undying-dominion-finder").apply { setReferenceCounted(false) }
    private val servers = ServerList()

    @Volatile
    private var socket: DatagramSocket? = null
    private var thread: Thread? = null

    @Synchronized
    fun start() {
        if (thread != null) return
        lock.acquire()
        thread = Thread(::listen, "server-finder").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun stop() {
        thread?.interrupt()
        thread = null
        socket?.close()
        socket = null
        if (lock.isHeld) lock.release()
    }

    private fun listen() {
        val listening = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                soTimeout = REPORT_EVERY_MILLIS
                bind(InetSocketAddress(Beacons.PORT))
            }
        } catch (e: SocketException) {
            // Another app holds the port: nothing is heard, and the finder's typed address still works.
            onServers(emptyList())
            return
        }
        socket = listening
        val buffer = ByteArray(1024)
        var reportedAt = 0L
        while (!Thread.currentThread().isInterrupted && !listening.isClosed) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                listening.receive(packet)
                val host = packet.address?.hostAddress ?: continue
                Beacons.parse(packet.data.copyOf(packet.length), host, System.currentTimeMillis())?.let(servers::heard)
            } catch (e: SocketTimeoutException) {
                // nothing this half second
            } catch (e: SocketException) {
                break  // closed by stop()
            }
            val now = System.currentTimeMillis()
            if (now - reportedAt >= REPORT_EVERY_MILLIS) {
                reportedAt = now
                onServers(servers.current(now))
            }
        }
        listening.close()
    }

    private companion object {
        const val REPORT_EVERY_MILLIS = 500
    }
}
