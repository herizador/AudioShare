# audioShare

Transmisión de audio en tiempo real desde Android hacia navegadores web y otros dispositivos. Sin cables, sin configuraciones de red complejas.

## ¿Qué hace?

audioShare captura el audio del sistema Android (reproducido por cualquier app) y lo transmite en tiempo real a:

- **Navegadores web (PWA)** — ábrelo desde cualquier dispositivo con Chrome/Firefox/Safari
- **Otros Android** — reciben el audio y lo reproducen por el altavoz/auriculares

Dos modos de conexión:

| Modo | Cómo funciona |
|------|---------------|
| **LAN** | Descubrimiento automático por NSD en la red local. Conexión TCP directa. Sin internet. |
| **Sala (Proxy)** | El host se conecta a un proxy público. Los invitados ingresan un código de 4 dígitos. Funciona a través de internet. |

## Arquitectura

```
┌─────────────────────────────────────────────────────┐
│                   audioShare                        │
├─────────────────┬─────────────────┬─────────────────┤
│  AudioShareApp  │  AudioShareWEB  │ AudioShareProxy │
│  (Android)      │  (PWA web)      │ (Node.js)       │
│                 │                 │                 │
│  Captura PCM    │  Recibe PCM     │ Reenvía buffers │
│  NSD discovery  │  AudioWorklet   │ WebSocket       │
│  TCP directo    │  Visualizador   │ Redis metadatos │
│  WebSocket      │  Canvas         │ Fastify server  │
└─────────────────┴─────────────────┴─────────────────┘
```

### AudioShareApp — Android (Kotlin + Jetpack Compose)

- **minSdk:** 29 (Android 10) | **targetSdk:** 34
- Captura audio del sistema mediante `MediaProjection` + `AudioRecord`
- Formato: PCM 16-bit, mono, 44100 Hz
- **Modo LAN:** Descubrimiento NSD (`_audioshare._tcp`), TCP directo puerto `8887`, loopback local
- **Modo Sala:** WebSocket con rol `host` o `guest`, heartbeat cada 5s
- Interfaz Cyberpunk/Minimalista: fondo negro, acento neón `#CCFF00`, violeta `#7C3AED`

### AudioShareWEB — PWA (HTML/JS vanilla, sin build step)

- Un solo `index.html` autocontenido (HTML+CSS+JS inline)
- Recibe PCM binario por WebSocket → `AudioWorkletProcessor` con ring buffer
- Visualizador circular animado en Canvas con cálculo RMS
- Service Worker para instalación como PWA

### AudioShareProxy — Proxy Node.js (Fastify + Redis)

- Fastify en puerto `3000`
- WebSockets multiplexados por sala y rol (`host`, `guest`, `pwa`)
- Redis almacena metadatos con TTL 15s
- Heartbeat del host renueva el TTL
- Limpieza automática de salas expiradas cada 10s

## Cómo empezar

### Prerrequisitos

| Componente | Requisito |
|------------|-----------|
| Android    | Android Studio Hedgehog+, JDK 17 |
| Proxy      | Node.js 18+, Redis |
| Web        | Navegador moderno (Chrome 90+, Firefox 90+, Safari 15+) |

### Compilar APK (Android)

```sh
cd AudioShareApp
./gradlew assembleDebug
```

### Iniciar proxy local

```sh
cd Proxy-Audio-share-main
npm install
Redis_URL=redis://localhost:6379 npm start
```

> Sin Redis el proxy falla al arrancar. También puedes usar el proxy público en `wss://proxy-audio-share.onrender.com/ws`.

### Desplegar PWA

Solo sirve `Audio-Share-WEB-main/` con cualquier servidor estático:

```sh
cd Audio-Share-WEB-main
npx serve .
```

O súbelo a cualquier hosting estático (Vercel, Netlify, GitHub Pages).

## Uso

### Modo LAN (red local)

1. **Host:** Abre la app → LAN → Host → "Iniciar Transmisión" → acepta permisos
2. **Invitado:** Abre la app → LAN → Invitado → selecciona el host en la lista → "Conectar"

### Modo Sala (a través de internet)

1. **Host:** Abre la app → Sala → Host → se genera un código de 4 dígitos → "Iniciar Transmisión"
2. **Invitado Android:** Sala → Invitado → ingresa el código → "Conectar"
3. **Invitado Web:** Abre `https://<url-de-la-pwa>` → ingresa el código → "Ingresar"

## Roles WebSocket

| Parámetro | Quién | Audio |
|-----------|-------|-------|
| `role=host` | Android host | WebSocket binary (PCM out) |
| `role=guest` | Android guest | WebSocket binary (PCM in) |
| `role=pwa` | PWA | WebSocket binary (PCM in) |

## Licencia

Uso interno. Todos los derechos reservados.
