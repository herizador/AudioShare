package com.audioshare.app

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log

class NsdHelper(private val context: Context) {
    private val TAG = "NsdHelper"
    private val nsdManager: NsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val SERVICE_TYPE = "_audioshare._tcp."

    private var registeredService: NsdServiceInfo? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var resolveListener: NsdManager.ResolveListener? = null

    private var registrationListener: NsdManager.RegistrationListener? = null

    fun registerService(serviceName: String, port: Int, onResult: (Boolean) -> Unit) {
        val serviceInfo = NsdServiceInfo().apply {
            serviceType = SERVICE_TYPE
            this.port = port
        }
        serviceInfo.serviceName = serviceName

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                registeredService = info
                Log.d(TAG, "NSD registrado: ${info.serviceName}")
                onResult(true)
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "NSD registro falló: $errorCode")
                onResult(false)
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                registeredService = null
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        registrationListener = listener
        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun discoverServices(onFound: (NsdServiceInfo) -> Unit, onFinished: () -> Unit) {
        stopDiscovery()

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d(TAG, "NSD descubrimiento iniciado: $regType")
            }

            override fun onServiceFound(info: NsdServiceInfo) {
                Log.d(TAG, "NSD servicio encontrado: ${info.serviceName}")
                if (info.serviceType == SERVICE_TYPE) {
                    resolveService(info, onFound)
                }
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                Log.d(TAG, "NSD servicio perdido: ${info.serviceName}")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "NSD descubrimiento detenido")
                onFinished()
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "NSD inicio descubrimiento falló: $errorCode")
                nsdManager.stopServiceDiscovery(this)
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "NSD parada descubrimiento falló: $errorCode")
            }
        }

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.e(TAG, "Error iniciando NSD discovery: ${e.message}")
        }
    }

    private fun resolveService(info: NsdServiceInfo, onFound: (NsdServiceInfo) -> Unit) {
        resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "NSD resolución falló: $errorCode")
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "NSD resuelto: ${serviceInfo.serviceName} -> ${serviceInfo.host.hostAddress}")
                Handler(Looper.getMainLooper()).post { onFound(serviceInfo) }
            }
        }

        nsdManager.resolveService(info, resolveListener)
    }

    fun stopDiscovery() {
        try {
            discoveryListener?.let { nsdManager.stopServiceDiscovery(it) }
        } catch (e: Exception) {}
        discoveryListener = null
    }

    fun unregisterService() {
        try {
            registrationListener?.let { nsdManager.unregisterService(it) }
        } catch (e: Exception) {}
        registrationListener = null
        registeredService = null
    }

    fun destroy() {
        stopDiscovery()
        unregisterService()
    }
}
