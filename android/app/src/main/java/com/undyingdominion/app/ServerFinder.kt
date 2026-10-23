package com.undyingdominion.app

import android.content.Context
import android.net.wifi.WifiManager

/** The [BeaconListener] on UDP 5001, with the multicast lock that keeps the Wi-Fi radio passing broadcasts up. */
class ServerFinder(context: Context, onServers: (List<Server>) -> Unit, onUnavailable: () -> Unit) {

    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val lock = wifi.createMulticastLock("undying-dominion-finder").apply { setReferenceCounted(false) }
    private val listener = BeaconListener(Beacons.PORT, onServers, onUnavailable = {
        if (lock.isHeld) lock.release()
        onUnavailable()
    })

    @Synchronized
    fun start() {
        if (listener.listening) return
        lock.acquire()
        listener.start()
    }

    @Synchronized
    fun stop() {
        listener.stop()
        if (lock.isHeld) lock.release()
    }
}
