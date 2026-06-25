package com.audioshare.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.audioshare.app.ui.theme.AudioShareTheme
import kotlin.random.Random

class MainActivity : ComponentActivity() {
    private val TAG = "MainActivity"
    private var mediaProjectionManager: MediaProjectionManager? = null
    private var nsdHelper: NsdHelper? = null

    private var connectionMode by mutableStateOf(ConnectionMode.LAN)
    private var connectionRole by mutableStateOf<ConnectionRole?>(null)
    private var connectionStatus by mutableStateOf("Desconectado")
    private var deviceName by mutableStateOf("")
    private var roomCode by mutableStateOf("")
    private var roomCodeInput by mutableStateOf("")
    private var isConnecting by mutableStateOf(false)
    private var isConnected by mutableStateOf(false)
    private var showRoomCopied by mutableStateOf(false)

    // NSD: lista de hosts descubiertos
    private var discoveredHosts by mutableStateOf(listOf<NsdServiceInfo>())
    private var selectedHost by mutableStateOf<NsdServiceInfo?>(null)

    private val mediaProjectionPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data: Intent = result.data ?: return@registerForActivityResult
            when {
                connectionMode == ConnectionMode.LAN && connectionRole == ConnectionRole.HOST -> {
                    AudioShareForegroundService.startHostService(this, data)
                    connectionStatus = "Conectado como Host (LAN)"
                    isConnected = true
                }
                connectionMode == ConnectionMode.ROOM && connectionRole == ConnectionRole.HOST -> {
                    val intent = Intent(this, AudioShareForegroundService::class.java).apply {
                        putExtra("modo_sala", true)
                        putExtra("room_code", roomCode)
                        putExtra("room_role", "host")
                        putExtra(AudioShareForegroundService.EXTRA_IS_HOST, true)
                        putExtra(AudioShareForegroundService.EXTRA_HOST_MEDIA_PROJECTION_DATA, data)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent)
                    } else {
                        startService(intent)
                    }
                    connectionStatus = "Conectado como Host (Sala)"
                    isConnected = true
                }
            }
            Toast.makeText(this, "Captura de audio iniciada", Toast.LENGTH_SHORT).show()
        } else {
            connectionStatus = "Permiso denegado"
            isConnected = false
        }
    }

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val recordAudioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        val postNotificationsGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions[Manifest.permission.POST_NOTIFICATIONS] ?: false
        } else true

        if (recordAudioGranted && postNotificationsGranted) {
            requestMediaProjectionPermission()
        } else {
            connectionStatus = "Permisos denegados"
            isConnected = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        nsdHelper = NsdHelper(this)
        WebRTCClient.initialize(this)

        setContent {
            AudioShareTheme {
                MainScreen()
            }
        }
    }

    override fun onDestroy() {
        nsdHelper?.destroy()
        nsdHelper = null
        super.onDestroy()
    }

    // ==========================================
    //  COMPOSABLES — INTERFAZ REDISEÑADA
    // ==========================================

    @Composable
    fun MainScreen() {
        val clipboardManager = LocalClipboardManager.current

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF000000))
                .padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "audioShare",
                        style = MaterialTheme.typography.headlineLarge,
                        color = Color.White,
                        letterSpacing = 4.sp
                    )
                    Text(
                        text = "streaming de audio",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.4f),
                        letterSpacing = 3.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // Selector de modo (LAN / Sala)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ModeCard(
                    text = "LAN",
                    subtitle = "Red local",
                    selected = connectionMode == ConnectionMode.LAN,
                    onClick = {
                        connectionMode = ConnectionMode.LAN
                        connectionRole = null
                        connectionStatus = "Desconectado"
                        isConnected = false
                        stopNsd()
                    },
                    modifier = Modifier.weight(1f)
                )
                ModeCard(
                    text = "SALA",
                    subtitle = "Proxy remoto",
                    selected = connectionMode == ConnectionMode.ROOM,
                    onClick = {
                        connectionMode = ConnectionMode.ROOM
                        connectionRole = null
                        connectionStatus = "Desconectado"
                        isConnected = false
                        stopNsd()
                    },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Selector de rol (Host / Invitado)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                RoleCard(
                    text = "HOST",
                    selected = connectionRole == ConnectionRole.HOST,
                    onClick = {
                        connectionRole = ConnectionRole.HOST
                        if (connectionMode == ConnectionMode.ROOM) {
                            roomCode = generarCodigo4Digitos()
                        }
                        connectionStatus = "Listo para iniciar como Host"
                        isConnected = false
                        stopNsd()
                    },
                    modifier = Modifier.weight(1f)
                )
                RoleCard(
                    text = "INVITADO",
                    selected = connectionRole == ConnectionRole.GUEST,
                    onClick = {
                        connectionRole = ConnectionRole.GUEST
                        connectionStatus = "Listo para iniciar como Invitado"
                        isConnected = false
                        if (connectionMode == ConnectionMode.LAN) {
                            iniciarNsdDiscovery()
                        } else {
                            stopNsd()
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Estado
            Text(
                text = connectionStatus,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            // UI específica del modo
            when (connectionMode) {
                ConnectionMode.LAN -> lanModeUI()
                ConnectionMode.ROOM -> roomModeUI(clipboardManager)
            }

            Spacer(modifier = Modifier.weight(1f))

            // Botón de detener
            if (isConnected) {
                Button(
                    onClick = { disconnect() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF1A1A1E),
                        contentColor = Color(0xFFFF1744).copy(alpha = 0.8f)
                    )
                ) {
                    Text(
                        "DETENER TRANSMISIÓN",
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 2.sp,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }

    // ========== LAN ==========

    @Composable
    private fun lanModeUI() {
        when (connectionRole) {
            ConnectionRole.HOST -> LanHostUI()
            ConnectionRole.GUEST -> LanGuestUI()
            null -> {}
        }
    }

    @Composable
    private fun LanHostUI() {
        Text(
            text = dispositivoNombre(),
            style = MaterialTheme.typography.titleMedium,
            color = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.padding(bottom = 20.dp)
        )

        NeonButton(
            text = "INICIAR TRANSMISIÓN",
            onClick = { startHostFlow() },
            enabled = !isConnected && !isConnecting
        )
    }

    @Composable
    private fun LanGuestUI() {
        if (discoveredHosts.isEmpty()) {
            // Indicador de escaneo
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    color = Color(0xFFCCFF00),
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "BUSCANDO HOSTS...",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.4f),
                    letterSpacing = 3.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.heightIn(max = 200.dp).fillMaxWidth()
            ) {
                items(discoveredHosts) { host ->
                    val isSelected = selectedHost == host
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable { selectedHost = host }
                            .then(
                                if (isSelected)
                                    Modifier.border(
                                        1.dp, Color(0xFFCCFF00).copy(alpha = 0.5f),
                                        RoundedCornerShape(12.dp)
                                    )
                                else Modifier
                            ),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected)
                                Color(0xFFCCFF00).copy(alpha = 0.08f)
                            else Color(0xFF1A1A1E).copy(alpha = 0.6f)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Phone,
                                contentDescription = "Dispositivo",
                                tint = Color.White.copy(alpha = 0.5f),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = host.serviceName,
                                color = Color.White.copy(alpha = 0.9f),
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = {
                    discoveredHosts = emptyList()
                    selectedHost = null
                    iniciarNsdDiscovery()
                }
            ) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "Buscar de nuevo",
                    tint = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    "Buscar",
                    color = Color.White.copy(alpha = 0.5f),
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Button(
                onClick = {
                    val host = selectedHost
                    if (host != null) {
                        iniciarGuestLan(host.host.hostAddress ?: "")
                    }
                },
                enabled = !isConnected && !isConnecting && selectedHost != null,
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFCCFF00),
                    contentColor = Color(0xFF000000)
                ),
                modifier = Modifier.height(48.dp).widthIn(min = 140.dp)
            ) {
                Text(
                    "CONECTAR",
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    fontSize = 14.sp
                )
            }
        }
    }

    // ========== SALA ==========

    @Composable
    private fun roomModeUI(clipboardManager: androidx.compose.ui.platform.ClipboardManager) {
        when (connectionRole) {
            ConnectionRole.HOST -> RoomHostUI(clipboardManager)
            ConnectionRole.GUEST -> RoomGuestUI()
            null -> {}
        }
    }

    @Composable
    private fun RoomHostUI(clipboardManager: androidx.compose.ui.platform.ClipboardManager) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = roomCode,
                style = MaterialTheme.typography.displayLarge,
                color = Color(0xFFCCFF00),
                letterSpacing = 12.sp,
                modifier = Modifier.padding(vertical = 16.dp)
            )

            if (showRoomCopied) {
                Text(
                    "¡Código copiado!",
                    color = Color(0xFFCCFF00),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            IconButton(
                onClick = {
                    clipboardManager.setText(AnnotatedString(roomCode))
                    showRoomCopied = true
                    android.os.Handler(android.os.Looper.getMainLooper())
                        .postDelayed({ showRoomCopied = false }, 1500)
                }
            ) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = "Copiar código",
                    tint = Color.White.copy(alpha = 0.6f)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            NeonButton(
                text = "INICIAR TRANSMISIÓN",
                onClick = { startHostRoom() },
                enabled = !isConnected && !isConnecting
            )
        }
    }

    @Composable
    private fun RoomGuestUI() {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            OutlinedTextField(
                value = roomCodeInput,
                onValueChange = { v ->
                    roomCodeInput = v.filter { it.isDigit() }.take(4)
                },
                label = { Text("Código de Sala") },
                singleLine = true,
                textStyle = TextStyle(
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 12.sp,
                    textAlign = TextAlign.Center,
                    color = Color.White
                ),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            )

            Spacer(modifier = Modifier.height(20.dp))

            NeonButton(
                text = "CONECTAR",
                onClick = { startGuestRoom() },
                enabled = !isConnected && !isConnecting && roomCodeInput.length == 4
            )
        }
    }

    // ========== COMPONENTES REUTILIZABLES ==========

    @Composable
    private fun ModeCard(
        text: String,
        subtitle: String? = null,
        selected: Boolean,
        onClick: () -> Unit,
        modifier: Modifier = Modifier
    ) {
        val borderWidth = if (selected) 2.dp else 1.dp
        val borderColor = if (selected) Color(0xFFCCFF00) else Color.White.copy(alpha = 0.1f)
        val textColor = if (selected) Color(0xFFCCFF00) else Color.White.copy(alpha = 0.7f)

        Card(
            modifier = modifier
                .height(100.dp)
                .clickable(onClick = onClick)
                .border(borderWidth, borderColor, RoundedCornerShape(16.dp)),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFF1A1A1E).copy(alpha = 0.8f)
            )
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.titleLarge,
                        color = textColor
                    )
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.4f)
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun RoleCard(
        text: String,
        selected: Boolean,
        onClick: () -> Unit,
        modifier: Modifier = Modifier
    ) {
        val borderColor = if (selected) Color(0xFF7C3AED) else Color.White.copy(alpha = 0.08f)
        val textColor = if (selected) Color(0xFF7C3AED) else Color.White.copy(alpha = 0.5f)

        Card(
            modifier = modifier
                .height(56.dp)
                .clickable(onClick = onClick)
                .border(1.dp, borderColor, RoundedCornerShape(12.dp)),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (selected)
                    Color(0xFF7C3AED).copy(alpha = 0.1f)
                else Color(0xFF1A1A1E).copy(alpha = 0.5f)
            )
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.titleMedium,
                    color = textColor
                )
            }
        }
    }

    @Composable
    private fun NeonButton(
        text: String,
        onClick: () -> Unit,
        enabled: Boolean = true
    ) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(26.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFCCFF00),
                contentColor = Color(0xFF000000),
                disabledContainerColor = Color(0xFF1A1A1E),
                disabledContentColor = Color.White.copy(alpha = 0.3f)
            )
        ) {
            Text(
                text = text,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                fontSize = 14.sp
            )
        }
    }

    // ==========================================
    //  LÓGICA — SIN CAMBIOS
    // ==========================================

    private fun dispositivoNombre(): String {
        return "${Build.MODEL} (${Build.MANUFACTURER})"
    }

    private fun generarCodigo4Digitos(): String {
        return String.format("%04d", Random.nextInt(10000))
    }

    private fun iniciarNsdDiscovery() {
        discoveredHosts = emptyList()
        selectedHost = null
        nsdHelper?.discoverServices(
            onFound = { info ->
                discoveredHosts = discoveredHosts + info
            },
            onFinished = {}
        )
    }

    private fun stopNsd() {
        nsdHelper?.stopDiscovery()
    }

    private fun startHostFlow() {
        val permissionsToRequest = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionsLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            requestMediaProjectionPermission()
        }
    }

    private fun requestMediaProjectionPermission() {
        val intent = mediaProjectionManager?.createScreenCaptureIntent()
        if (intent != null) {
            mediaProjectionPermissionLauncher.launch(intent)
        } else {
            connectionStatus = "Error interno: No se pudo crear el intent de captura."
            isConnected = false
        }
    }

    private fun iniciarGuestLan(ip: String) {
        AudioShareForegroundService.startService(
            context = this,
            isHost = false,
            projectionData = null,
            hostIpAddress = ip
        )
        connectionStatus = "Conectando como invitado (LAN)..."
        isConnected = true
    }

    private fun startHostRoom() {
        val permissionsToRequest = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionsLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            val intent = mediaProjectionManager?.createScreenCaptureIntent()
            if (intent != null) {
                mediaProjectionPermissionLauncher.launch(intent)
            } else {
                connectionStatus = "Error interno"
                isConnected = false
            }
        }
    }

    private fun startGuestRoom() {
        val intent = Intent(this, AudioShareForegroundService::class.java).apply {
            putExtra("modo_sala", true)
            putExtra("room_code", roomCodeInput)
            putExtra("room_role", "guest")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        connectionStatus = "Conectando como invitado (Sala)..."
        isConnected = true
    }

    private fun disconnect() {
        AudioShareForegroundService.stopService(this)
        connectionStatus = "Desconectado"
        isConnected = false
        nsdHelper?.unregisterService()
    }

    enum class ConnectionMode { LAN, ROOM }
    enum class ConnectionRole { HOST, GUEST }
}
