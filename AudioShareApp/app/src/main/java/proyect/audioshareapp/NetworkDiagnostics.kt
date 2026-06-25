package com.audioshare.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.util.Log
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketException

object NetworkDiagnostics {
    private const val TAG = "NetworkDiagnostics"
    
    /**
     * Verifica si el dispositivo está conectado a Wi-Fi
     */
    fun isWifiConnected(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
    
    /**
     * Obtiene la IP local del dispositivo
     */
    fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                val addrs = intf.inetAddresses
                for (addr in addrs) {
                    val hostAddr = addr.hostAddress ?: continue
                    if (hostAddr.indexOf(':') < 0) { // IPv4
                        return hostAddr
                    }
                }
            }
        } catch (ex: Exception) {
            Log.e(TAG, "Error getting IP address: ${ex.message}")
        }
        return "N/A"
    }
    
    /**
     * Verifica si se puede hacer ping a una IP
     */
    fun canPingHost(host: String, timeoutMs: Int = 3000): Boolean {
        return try {
            val address = InetAddress.getByName(host)
            address.isReachable(timeoutMs)
        } catch (e: Exception) {
            Log.e(TAG, "Error pinging $host: ${e.message}")
            false
        }
    }
    
    /**
     * Verifica si un puerto está abierto en un host
     */
    fun isPortOpen(host: String, port: Int, timeoutMs: Int = 3000): Boolean {
        return try {
            val socket = Socket()
            socket.connect(java.net.InetSocketAddress(host, port), timeoutMs)
            socket.close()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Port $port not open on $host: ${e.message}")
            false
        }
    }
    
    /**
     * Obtiene información completa de red
     */
    fun getNetworkInfo(context: Context): String {
        val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wifiInfo = wifiManager.connectionInfo
        
        return """
            Network Diagnostics:
            - WiFi Connected: ${isWifiConnected(context)}
            - Local IP: ${getLocalIpAddress()}
            - WiFi SSID: ${wifiInfo.ssid}
            - WiFi Signal: ${wifiInfo.rssi} dBm
            - Network ID: ${wifiInfo.networkId}
        """.trimIndent()
    }
    
    /**
     * Verifica conectividad completa a un host
     */
    fun testHostConnectivity(host: String, port: Int): String {
        val canPing = canPingHost(host)
        val portOpen = isPortOpen(host, port)
        
        return """
            Host Connectivity Test ($host:$port):
            - Can ping: $canPing
            - Port open: $portOpen
            - Status: ${if (canPing && portOpen) "CONNECTED" else "FAILED"}
        """.trimIndent()
    }
} 