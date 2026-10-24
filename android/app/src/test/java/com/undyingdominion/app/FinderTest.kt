package com.undyingdominion.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.BindException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** The finder's two edges: what the page may ask, and listening when the port is or isn't free. */
class FinderTest {

    @Test
    fun theFinderPageMayOnlyScanStopOrPlayAValidAddress() {
        assertEquals(FinderMessage.Scan, FinderMessages.parse("""{"do":"scan"}"""))
        assertEquals(FinderMessage.Stop, FinderMessages.parse("""{"do":"stop"}"""))
        assertEquals(FinderMessage.Play("10.1.1.140:5000"), FinderMessages.parse("""{"do":"play","address":"10.1.1.140"}"""))
        assertNull(FinderMessages.parse("""{"do":"play","address":"javascript:alert(1)"}"""))
        assertNull(FinderMessages.parse("""{"do":"exec"}"""))
        assertNull(FinderMessages.parse("not json"))
        assertNull(FinderMessages.parse(null))
    }

    @Test
    fun aHeldPortIsReportedAndALaterStartTriesAgain() {
        val port = DatagramSocket(0).use { it.localPort }
        val held = AtomicBoolean(true)
        val unavailable = CountDownLatch(1)
        val heard = AtomicReference<List<Server>>(emptyList())
        // Another app holds the port until held is cleared.
        val listener = BeaconListener(port, { heard.set(it) }, { unavailable.countDown() }) { p ->
            if (held.get()) throw BindException("Address already in use")
            DatagramSocket(p)
        }

        listener.start()
        assertTrue("the held port is reported", unavailable.await(3, TimeUnit.SECONDS))
        Thread.sleep(100)
        assertFalse("nothing claims to be listening", listener.listening)

        held.set(false)
        listener.start()
        assertTrue(listener.listening)
        val beacon = """{"game":"undying-dominion","v":1,"name":"kirito-laptop","port":5000,"modes":["free-for-all"],"players":1,"max":8}"""
            .toByteArray()
        DatagramSocket().use { sender ->
            val deadline = System.currentTimeMillis() + 3000
            while (heard.get().isEmpty() && System.currentTimeMillis() < deadline) {
                sender.send(DatagramPacket(beacon, beacon.size, InetAddress.getLoopbackAddress(), port))
                Thread.sleep(200)
            }
        }
        listener.stop()
        assertEquals(listOf("kirito-laptop"), heard.get().map { it.name })
    }
}
