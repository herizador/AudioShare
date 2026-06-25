package com.audioshare.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.util.Log

class NetworkChangeReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "NetworkChangeReceiver"
        var onNetworkChanged: ((Boolean, String?) -> Unit)? = null

        /**
         * Permite que las actividades u otros componentes soliciten una verificación manual
         * del estado actual de la red y activen el callback.
         */
        fun triggerNetworkStatusCheck(context: Context) {
            // Se crea una instancia temporal para acceder al método no estático.
            // Para este caso, es seguro ya que no hay estado de instancia persistente
            // requerido por checkNetworkStatus.
            NetworkChangeReceiver().checkNetworkStatus(context)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ConnectivityManager.CONNECTIVITY_ACTION,
            WifiManager.WIFI_STATE_CHANGED_ACTION -> {
                checkNetworkStatus(context)
            }
        }
    }

    internal fun checkNetworkStatus(context: Context) {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val network = connectivityManager.activeNetwork
        val networkCapabilities = connectivityManager.getNetworkCapabilities(network)

        val isConnected = networkCapabilities != null &&
                networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)

        val isWifi = networkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

        val currentIp = if (isWifi) {
            getCurrentWifiIp(context)
        } else {
            null
        }

        Log.d(TAG, "Network status - Connected: $isConnected, WiFi: $isWifi, IP: $currentIp")

        onNetworkChanged?.invoke(isConnected && isWifi, currentIp)
    }

    private fun getCurrentWifiIp(context: Context): String? {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiInfo = wifiManager.connectionInfo
            val ipInt = wifiInfo.ipAddress

            if (ipInt != 0) {
                return String.format(
                    "%d.%d.%d.%d",
                    ipInt and 0xff,
                    ipInt shr 8 and 0xff,
                    ipInt shr 16 and 0xff,
                    ipInt shr 24 and 0xff
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting WiFi IP", e)
        }
        return null
    }
}