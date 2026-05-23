package com.zaneschepke.wireguardautotunnel.core.tether

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import timber.log.Timber

/**
 * Manages a local-only hotspot to keep the Wi-Fi stack active for wireless debugging
 * when no Wi-Fi network is connected.
 */
object HotspotManager {

    private const val TAG = "HotspotManager"
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var isStarting = false

    val isActive: Boolean get() = reservation != null

    fun start(context: Context): Boolean {
        if (reservation != null) return true
        if (isStarting) return false
        isStarting = true

        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        try {
            wifiManager.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation?) {
                    reservation = res
                    isStarting = false
                    Timber.i("$TAG: local-only hotspot started")
                }

                override fun onStopped() {
                    reservation = null
                    isStarting = false
                    Timber.i("$TAG: local-only hotspot stopped")
                }

                override fun onFailed(reason: Int) {
                    isStarting = false
                    Timber.w("$TAG: failed to start hotspot, reason=$reason")
                }
            }, Handler(Looper.getMainLooper()))
            return true
        } catch (e: SecurityException) {
            isStarting = false
            Timber.w("$TAG: permission denied: ${e.message}")
            return false
        } catch (e: Exception) {
            isStarting = false
            Timber.w("$TAG: failed: ${e.message}")
            return false
        }
    }

    fun stop() {
        reservation?.close()
        reservation = null
        isStarting = false
        Timber.i("$TAG: stopped")
    }
}
