package com.audioshare.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.*
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.io.IOException
import java.net.*
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class AudioShareForegroundService : Service() {
    private val TAG = "AudioShareForegroundService"

    // Recursos de audio
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var captureSampleRate: Int = 16000

    // Red LAN (TCP)
    private var tcpServerSocket: ServerSocket? = null
    private val tcpClients = mutableListOf<Socket>()

    // Señalización sala
    private var signalingWs: WebSocketClient? = null
    private var heartbeatJob: Job? = null

    // Estado
    private lateinit var serviceScope: CoroutineScope
    private var isServiceRunning = AtomicBoolean(false)
    private var mediaProjectionRef: MediaProjection? = null
    private var isRoomMode = false
    private var roomCode: String? = null
    private var roomRole: String? = null
    private var nsdHelper: NsdHelper? = null

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "audio_share_channel"
        private const val CHANNEL_NAME = "Audio Share Service"
        private const val TCP_AUDIO_PORT = 8887
        const val TARGET_SAMPLE_RATE = 16000

        const val EXTRA_IS_HOST = "is_host"
        const val EXTRA_PROJECTION_DATA = "projection_data"
        const val EXTRA_HOST_IP = "ip_address"
        const val EXTRA_HOST_MEDIA_PROJECTION_DATA = "HOST_MEDIA_PROJECTION_DATA"
        const val ACTION_STOP_SERVICE_INTERNAL = "STOP_SERVICE_INTERNAL"
        const val ACTION_SERVICE_STOPPED = "com.audioshare.app.ACTION_SERVICE_STOPPED"

        private const val PROXY_BASE = "https://proxy-audio-share.onrender.com"
        private const val WS_PROXY_BASE = "wss://proxy-audio-share.onrender.com"

        fun startHostService(context: Context, mediaProjectionData: Intent) {
            val intent = Intent(context, AudioShareForegroundService::class.java).apply {
                putExtra(EXTRA_IS_HOST, true)
                putExtra(EXTRA_HOST_MEDIA_PROJECTION_DATA, mediaProjectionData)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun startService(context: Context, isHost: Boolean, projectionData: Intent?, hostIpAddress: String?) {
            val intent = Intent(context, AudioShareForegroundService::class.java).apply {
                putExtra(EXTRA_IS_HOST, isHost)
                projectionData?.let { putExtra(EXTRA_PROJECTION_DATA, it) }
                hostIpAddress?.let { putExtra(EXTRA_HOST_IP, it) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val stopIntent = Intent(context, AudioShareForegroundService::class.java).apply {
                action = ACTION_STOP_SERVICE_INTERNAL
            }
            context.startService(stopIntent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        crearCanalNotificacion()
        serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        nsdHelper = NsdHelper(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP_SERVICE_INTERNAL) {
            serviceScope.launch { executeStopLogic() }
            return START_NOT_STICKY
        }
        if (isServiceRunning.get()) return START_NOT_STICKY

        isServiceRunning.set(true)
        isRoomMode = intent?.getBooleanExtra("modo_sala", false) ?: false
        roomCode = intent?.getStringExtra("room_code")
        roomRole = intent?.getStringExtra("room_role")
        val isHost = intent?.getBooleanExtra(EXTRA_IS_HOST, false) ?: false
        val hostIpAddress = intent?.getStringExtra(EXTRA_HOST_IP)
        val projectionData: Intent? = intent?.getParcelableExtra(EXTRA_PROJECTION_DATA)
            ?: intent?.getParcelableExtra(EXTRA_HOST_MEDIA_PROJECTION_DATA)

        Log.d(TAG, "Iniciando - isHost:$isHost roomMode:$isRoomMode role:$roomRole")

        when {
            isRoomMode && roomRole == "host" && projectionData != null -> {
                val notification = crearNotificacion("Host en sala $roomCode")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjectionRef = mpm.getMediaProjection(Activity.RESULT_OK, projectionData)
                if (mediaProjectionRef != null) {
                    serviceScope.launch { startHostRoomMode() }
                    return START_STICKY
                }
                stopSelf()
                return START_NOT_STICKY
            }

            isRoomMode && roomRole == "guest" -> {
                val notification = crearNotificacion("Conectando a sala $roomCode")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                serviceScope.launch { startGuestRoomModeWebRTC() }
                return START_STICKY
            }

            isHost -> {
                val notification = crearNotificacion("Compartiendo audio (Host)")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                if (projectionData != null) {
                    val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    mediaProjectionRef = mpm.getMediaProjection(Activity.RESULT_OK, projectionData)
                    if (mediaProjectionRef != null) {
                        serviceScope.launch { startHostMode() }
                        return START_STICKY
                    }
                }
                stopSelf()
                return START_NOT_STICKY
            }

            else -> {
                val notification = crearNotificacion("Recibiendo audio (Invitado)")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                serviceScope.launch { startGuestMode(hostIpAddress) }
                return START_STICKY
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.launch { executeStopLogic() }
        serviceScope.cancel()
        nsdHelper?.destroy()
        nsdHelper = null
        super.onDestroy()
    }

    // ─── STOP ──────────────────────────────────────────────

    private suspend fun executeStopLogic() {
        if (!isServiceRunning.get()) return
        isServiceRunning.set(false)

        heartbeatJob?.cancel()
        heartbeatJob = null

        signalingWs?.close()
        signalingWs = null

        try { audioRecord?.stop(); audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        try { audioTrack?.stop(); audioTrack?.release() } catch (_: Exception) {}
        audioTrack = null

        try { mediaProjectionRef?.stop() } catch (_: Exception) {}
        mediaProjectionRef = null

        try {
            tcpServerSocket?.close()
            tcpClients.forEach { it.close() }
            tcpClients.clear()
            tcpServerSocket = null
        } catch (_: Exception) {}

        nsdHelper?.unregisterService()

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()

        withContext(Dispatchers.Main) {
            Toast.makeText(this@AudioShareForegroundService, "Transmisión detenida.", Toast.LENGTH_SHORT).show()
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(Intent(ACTION_SERVICE_STOPPED))
        Log.d(TAG, "Servicio detenido completamente")
    }

    // ─── MODO LAN: HOST ────────────────────────────────────

    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun startHostMode() {
        Log.d(TAG, "Iniciando modo host LAN...")
        actualizarNotificacion("Esperando conexiones...")

        if (mediaProjectionRef == null) {
            actualizarNotificacion("Error: MediaProjection no disponible")
            executeStopLogic(); return
        }

        // Registrar NSD
        nsdHelper?.registerService("AudioShare-${Build.MODEL}", TCP_AUDIO_PORT) { ok ->
            Log.d(TAG, "NSD registro: $ok")
        }

        withContext(Dispatchers.IO) {
            try {
                startTcpServer()
                setupAudioCapture()

                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    throw IllegalStateException("AudioRecord no inicializado")
                }
                audioRecord?.startRecording()

                val frameBytes = captureSampleRate * 20 / 1000 * 2
                val buffer = ByteArray(frameBytes)
                var readErrors = 0

                while (isServiceRunning.get() && readErrors < 3) {
                    try {
                        val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                        if (bytesRead > 0) {
                            readErrors = 0
                            sendAudioDataToTcpClients(buffer, bytesRead)
                        } else if (bytesRead < 0) {
                            readErrors++
                            Log.w(TAG, "audioRecord.read devolvió $bytesRead (error $readErrors/3)")
                        }
                    } catch (e: Exception) {
                        readErrors++
                        Log.e(TAG, "Error en lectura de audio ($readErrors/3): ${e.message}")
                        if (readErrors >= 3) throw e
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error en host LAN: ${e.message}")
                actualizarNotificacion("Error: ${e.localizedMessage}")
            } finally {
                executeStopLogic()
            }
        }
    }

    private fun startTcpServer() {
        tcpServerSocket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(TCP_AUDIO_PORT))
        }
        Log.d(TAG, "TCP Server en puerto $TCP_AUDIO_PORT")

        serviceScope.launch(Dispatchers.IO) {
            try {
                while (isServiceRunning.get() && tcpServerSocket?.isClosed == false) {
                    try {
                        val client = tcpServerSocket?.accept()
                        client?.let {
                            it.keepAlive = true
                            it.tcpNoDelay = true
                            tcpClients.add(it)
                            Log.d(TAG, "Cliente TCP conectado: ${it.inetAddress.hostAddress}")
                            actualizarNotificacion("Invitados: ${tcpClients.size}")
                        }
                    } catch (_: Exception) {}
                }
            } finally {
                Log.d(TAG, "Bucle TCP finalizado")
            }
        }
    }

    private fun sendAudioDataToTcpClients(audioData: ByteArray, bytesRead: Int) {
        val toRemove = mutableListOf<Socket>()
        for (client in tcpClients.toList()) {
            try {
                client.getOutputStream().write(audioData, 0, bytesRead)
            } catch (_: IOException) {
                toRemove.add(client)
                try { client.close() } catch (_: Exception) {}
            }
        }
        if (toRemove.isNotEmpty()) {
            tcpClients.removeAll(toRemove)
            actualizarNotificacion("Invitados: ${tcpClients.size}")
        }
    }

    // ─── MODO LAN: INVITADO ────────────────────────────────

    private suspend fun startGuestMode(ipAddress: String?) {
        val ip = ipAddress ?: run {
            Log.e(TAG, "IP no proporcionada")
            executeStopLogic(); return
        }

        actualizarNotificacion("Conectando a $ip...")
        withContext(Dispatchers.IO) {
            var retryCount = 0
            while (retryCount < 3 && isServiceRunning.get()) {
                try {
                    val socket = Socket().apply {
                        reuseAddress = true
                        keepAlive = true
                        tcpNoDelay = true
                        connect(InetSocketAddress(ip, TCP_AUDIO_PORT), 3000)
                    }
                    actualizarNotificacion("Conectado a $ip")
                    setupAudioPlayback(44100)
                    audioTrack?.play()

                    val inputStream = socket.getInputStream().buffered()
                    val buffer = ByteArray(44100 * 20 / 1000 * 2)

                    while (isServiceRunning.get() && socket.isConnected) {
                        val bytesRead = inputStream.read(buffer)
                        if (bytesRead > 0) {
                            audioTrack?.write(buffer, 0, bytesRead)
                        } else if (bytesRead == -1) {
                            throw IOException("Conexión cerrada por el host")
                        }
                    }
                    break
                } catch (e: Exception) {
                    retryCount++
                    Log.e(TAG, "Intento $retryCount falló: ${e.message}")
                    if (retryCount < 3) delay(1000)
                }
            }
            executeStopLogic()
        }
    }

    // ─── MODO SALA: HOST ───────────────────────────────────

    private suspend fun startHostRoomMode() {
        actualizarNotificacion("Compartiendo audio (Sala)")
        val code = roomCode ?: run { executeStopLogic(); return }
        val wsUrl = "$WS_PROXY_BASE/ws?role=host&room=$code"

        // WebSocket de host (envía audio PCM binario)
        val wsConnected = CompletableDeferred<Boolean>()

        signalingWs = object : WebSocketClient(URI(wsUrl)) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                Log.d(TAG, "WS host conectado: $wsUrl")
                wsConnected.complete(true)
            }
            override fun onMessage(message: String) {
                Log.d(TAG, "WS host msg: $message")
            }
            override fun onMessage(message: ByteBuffer) {}
            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                Log.d(TAG, "WS host cerrado: $code $reason")
                if (isServiceRunning.get()) serviceScope.launch { executeStopLogic() }
            }
            override fun onError(ex: Exception?) {
                Log.e(TAG, "WS host error: ${ex?.message}")
            }
        }.apply {
            connectionLostTimeout = 5
            connect()
        }

        try {
            withTimeout(5000) { wsConnected.await() }
        } catch (_: TimeoutCancellationException) {
            actualizarNotificacion("Error conectando al proxy")
            executeStopLogic(); return
        }

        // Heartbeat cada 5s
        heartbeatJob = serviceScope.launch {
            while (isActive) {
                delay(5000)
                try {
                    val hbUrl = URL("$PROXY_BASE/api/rooms/$code/heartbeat")
                    val conn = hbUrl.openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.doOutput = true
                    conn.connectTimeout = 3000
                    conn.readTimeout = 3000
                    conn.outputStream.write("{}".toByteArray())
                    conn.responseCode
                    conn.disconnect()
                } catch (e: Exception) {
                    Log.w(TAG, "Heartbeat falló: ${e.message}")
                }
            }
        }

        // Captura y envío de audio
        setupAudioCapture()

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            throw IllegalStateException("AudioRecord no inicializado")
        }
        audioRecord?.startRecording()

        val frameBytes = captureSampleRate * 20 / 1000 * 2
        val buffer = ByteArray(frameBytes)
        var readErrors = 0

        while (isServiceRunning.get() && readErrors < 3) {
            try {
                val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                if (bytesRead > 0) {
                    readErrors = 0
                    try {
                        signalingWs?.send(buffer.copyOf(bytesRead))
                    } catch (_: Exception) {}
                } else if (bytesRead < 0) {
                    readErrors++
                    Log.w(TAG, "audioRecord.read devolvió $bytesRead (error $readErrors/3)")
                }
            } catch (e: Exception) {
                readErrors++
                Log.e(TAG, "Error en lectura de audio ($readErrors/3): ${e.message}")
                if (readErrors >= 3) {
                    throw e
                }
            }
        }
        executeStopLogic()
    }

    // ─── MODO SALA: INVITADO (WebRTC) ──────────────────────

    private suspend fun startGuestRoomModeWebRTC() {
        val code = roomCode ?: run { executeStopLogic(); return }
        val sampleRate = 44100
        actualizarNotificacion("Conectando a sala $code...")

        // Inicializar AudioTrack para reproducción
        val bufferSize = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val at = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(bufferSize * 2)
            .build()
        audioTrack = at

        val ready = CompletableDeferred<Boolean>()

        val wsUrl = "$WS_PROXY_BASE/ws?role=guest&room=$code"
        signalingWs = object : WebSocketClient(URI(wsUrl)) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                Log.d(TAG, "WS señalización conectado")
            }

            override fun onMessage(message: String) {
                try {
                    val json = JSONObject(message)
                    when (json.optString("type")) {
                        "ready" -> {
                            at.play()
                            actualizarNotificacion("Recibiendo audio...")
                            ready.complete(true)
                        }
                        "host_disconnected" -> {
                            actualizarNotificacion("Host desconectado")
                            serviceScope.launch { executeStopLogic() }
                        }
                        "error" -> {
                            Log.e(TAG, "Error: ${json.optString("message")}")
                            actualizarNotificacion("Error: ${json.optString("message")}")
                            serviceScope.launch { executeStopLogic() }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error procesando mensaje: ${e.message}")
                }
            }

            override fun onMessage(message: ByteBuffer) {
                try {
                    val bytes = ByteArray(message.remaining())
                    message.get(bytes)
                    at.write(bytes, 0, bytes.size)
                } catch (e: Exception) {
                    Log.e(TAG, "Error en audio playback: ${e.message}")
                }
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                Log.d(TAG, "WS cerrado: $code")
                if (isServiceRunning.get()) {
                    serviceScope.launch { executeStopLogic() }
                }
            }

            override fun onError(ex: Exception?) {
                Log.e(TAG, "WS error: ${ex?.message}")
                if (isServiceRunning.get()) {
                    serviceScope.launch { executeStopLogic() }
                }
            }
        }.apply { connect() }

        try {
            withTimeout(10000) { ready.await() }
            while (isServiceRunning.get()) {
                delay(1000)
            }
        } catch (_: TimeoutCancellationException) {
            actualizarNotificacion("Tiempo de espera agotado")
        } finally {
            executeStopLogic()
        }
    }

    // ─── AUDIO ─────────────────────────────────────────────

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun setupAudioCapture() {
        if (mediaProjectionRef == null) throw IllegalStateException("MediaProjection null")
        captureSampleRate = AudioOptimizer.findSupportedSampleRate()
        Log.d(TAG, "Usando sample rate de captura: $captureSampleRate Hz")
        audioRecord = AudioOptimizer.createOptimizedAudioRecord(mediaProjectionRef!!, captureSampleRate)
    }

    private fun setupAudioPlayback(sampleRate: Int = captureSampleRate) {
        audioTrack = AudioOptimizer.createOptimizedAudioTrack(sampleRate)
    }

    private fun crearCanalNotificacion() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT)
            (getSystemService(NotificationManager::class.java)).createNotificationChannel(channel)
        }
    }

    private fun crearNotificacion(texto: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val stopIntent = Intent(this, AudioShareForegroundService::class.java).apply {
            action = ACTION_STOP_SERVICE_INTERNAL
        }
        val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Audio Share Activo")
            .setContentText(texto)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "Detener", stopPendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun actualizarNotificacion(texto: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, crearNotificacion(texto))
    }
}
