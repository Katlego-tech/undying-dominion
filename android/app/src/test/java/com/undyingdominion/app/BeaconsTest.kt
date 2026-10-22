package com.undyingdominion.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Hearing Dominions on the LAN and reading typed addresses (docs/design/android.md §6). */
class BeaconsTest {

    private fun beacon(json: String) = json.toByteArray(Charsets.UTF_8)

    private val good = """{"game":"undying-dominion","v":1,"name":"kirito-laptop","port":5000,"mode":"free-for-all","players":2,"max":8}"""

    @Test
    fun aBeaconBecomesAServerAtTheSendersAddress() {
        val server = Beacons.parse(beacon(good), "10.1.1.140", 1000)!!
        assertEquals("kirito-laptop", server.name)
        assertEquals("10.1.1.140", server.host)
        assertEquals(5000, server.port)
        assertEquals("free-for-all", server.mode)
        assertEquals(2, server.players)
        assertEquals(8, server.max)
        assertEquals("10.1.1.140:5000", server.address)
    }

    @Test
    fun anotherGamesOrAMalformedDatagramIsIgnored() {
        assertNull(Beacons.parse(beacon("""{"game":"other","v":1,"port":5000}"""), "10.1.1.2", 0))
        assertNull(Beacons.parse(beacon("not json"), "10.1.1.2", 0))
        assertNull(Beacons.parse(beacon("""{"game":"undying-dominion","v":1}"""), "10.1.1.2", 0))
        assertNull(Beacons.parse(beacon("""{"game":"undying-dominion","v":1,"port":70000,"name":"x"}"""), "10.1.1.2", 0))
    }

    @Test
    fun aServerSilentForSixSecondsIsForgottenAndTheNewestHeardComesFirst() {
        val list = ServerList()
        list.heard(Beacons.parse(beacon(good), "10.1.1.140", 0)!!)
        list.heard(Beacons.parse(beacon(good.replace("kirito-laptop", "study-pc")), "10.1.1.57", 3000)!!)
        assertEquals(listOf("study-pc", "kirito-laptop"), list.current(5000).map { it.name })
        assertEquals(listOf("study-pc"), list.current(6500).map { it.name })
        assertEquals(emptyList<Server>(), list.current(9500))
    }

    @Test
    fun hearingAServerAgainKeepsOneRowWithTheLatestCount() {
        val list = ServerList()
        list.heard(Beacons.parse(beacon(good), "10.1.1.140", 0)!!)
        list.heard(Beacons.parse(beacon(good.replace("\"players\":2", "\"players\":3")), "10.1.1.140", 2000)!!)
        val now = list.current(2500)
        assertEquals(1, now.size)
        assertEquals(3, now[0].players)
    }

    @Test
    fun aTypedAddressIsAHostWithAnOptionalPort() {
        assertEquals("10.1.1.140:5000", Addresses.parse("10.1.1.140"))
        assertEquals("10.1.1.140:6000", Addresses.parse(" 10.1.1.140:6000 "))
        assertEquals("kirito-laptop:5000", Addresses.parse("kirito-laptop"))
        assertNull(Addresses.parse(""))
        assertNull(Addresses.parse("http://10.1.1.140"))
        assertNull(Addresses.parse("10.1.1.140:99999"))
        assertNull(Addresses.parse("10.1.1.140:"))
    }

    @Test
    fun theServerListIsHandedToTheFinderAsJson() {
        val server = Beacons.parse(beacon(good), "10.1.1.140", 0)!!
        val row = org.json.JSONArray(Beacons.toJson(listOf(server))).getJSONObject(0)
        assertEquals(setOf("name", "host", "port", "mode", "players", "max"), row.keySet())
        assertEquals("10.1.1.140", row.getString("host"))
        assertEquals(5000, row.getInt("port"))
    }
}
