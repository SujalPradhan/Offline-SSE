package com.offlinesse.broadcast

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.net.NetworkInterface

class HotspotManager(private val context: Context) {

    interface Listener {
        fun onHotspotStarted(ssid: String, password: String, ips: List<String>)
        fun onHotspotStopped()
        fun onHotspotFailed(reason: Int)
    }

    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    var isRunning = false
        private set

    @SuppressLint("MissingPermission")
    fun start(listener: Listener) {
        if (isRunning) return
        
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        wifiManager.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
            override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                reservation = res
                isRunning = true

                val ssid: String
                val password: String
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val config = res.softApConfiguration
                    ssid = config?.ssid ?: "Unknown"
                    password = config?.passphrase ?: "Unknown"
                } else {
                    @Suppress("DEPRECATION")
                    val config = res.wifiConfiguration
                    @Suppress("DEPRECATION")
                    ssid = config?.SSID?.replace("\"", "") ?: "Unknown"
                    @Suppress("DEPRECATION")
                    password = config?.preSharedKey?.replace("\"", "") ?: "Unknown"
                }

                // Wait 1 second to ensure the network interface is up and IP is assigned
                mainHandler.postDelayed({
                    listener.onHotspotStarted(ssid, password, getActiveIps())
                }, 1000)
            }

            override fun onStopped() {
                isRunning = false
                reservation = null
                listener.onHotspotStopped()
            }

            override fun onFailed(reason: Int) {
                isRunning = false
                reservation = null
                listener.onHotspotFailed(reason)
            }
        }, mainHandler)
    }

    fun stop() {
        reservation?.close()
        reservation = null
        isRunning = false
    }

    private fun getActiveIps(): List<String> {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .filter { !it.isLoopbackAddress && it is java.net.Inet4Address }
                .map { it.hostAddress ?: "" }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
