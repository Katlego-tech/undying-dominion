package com.undyingdominion.app

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException

/**
 * Listens for Dominions' beacons on [port] on its own thread and reports the servers heard lately
 * about twice a second. If the port can't be taken, it says so once and stops, and a later [start]
 * tries again (docs/design/android.md §6).
 */
class BeaconListener(
    private val port: Int,
    private val onServers: (List<Server>) -> Unit,
    private val onUnavailable: () -> Unit,
    private val open: (Int) -> DatagramSocket = ::openShared,
) {
    private val servers = ServerList()

    @Volatile
    private var socket: DatagramSocket? = null
    private var thread: Thread? = null

    val listening: Boolean
        @Synchronized get() = thread != null

    @Synchronized
    fun start() {
        if (thread != null) return
        thread = Thread(::listen, "beacon-listener").apply {
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
    }

    private fun listen() {
        val listening = try {
            open(port).apply { soTimeout = REPORT_EVERY_MILLIS }
        } catch (e: SocketException) {
            // Another app holds the port. Clear the way for a later start(), and let the finder say so.
            synchronized(this) { if (thread === Thread.currentThread()) thread = null }
            onUnavailable()
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

        /** The port shared with any other listener that allows it, taking broadcasts. */
        fun openShared(port: Int) = DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = true
            bind(InetSocketAddress(port))
        }
    }
}
