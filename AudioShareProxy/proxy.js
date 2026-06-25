const Fastify = require("fastify");
const fastifyWebsocket = require("@fastify/websocket");
const fastifyCors = require("@fastify/cors");
const { createClient } = require("redis");

const PORT = process.env.PORT || 3000;
const REDIS_URL = process.env.REDIS_URL || "redis://localhost:6379";
const ROOM_TTL_S = 15;
const HEARTBEAT_TIMEOUT_MS = ROOM_TTL_S * 1000;

const rooms = new Map();

async function buildRedis() {
  const client = createClient({ url: REDIS_URL });
  client.on("error", (err) => console.error("Redis error:", err));
  await client.connect();
  console.log("Redis conectado");
  return client;
}

function generateRoomCode(length = 6) {
  const chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
  let code = "";
  for (let i = 0; i < length; i++) code += chars[Math.floor(Math.random() * chars.length)];
  return code;
}

async function createRoom(redis, code) {
  const key = `sala:${code}`;
  const ok = await redis.setNX(key, JSON.stringify({ createdAt: Date.now(), hostPeerId: null }));
  if (!ok) return false;
  await redis.expire(key, ROOM_TTL_S);
  return true;
}

async function roomExists(redis, code) {
  const exists = await redis.exists(`sala:${code}`);
  return exists === 1;
}

async function refreshRoomTTL(redis, code) {
  await redis.expire(`sala:${code}`, ROOM_TTL_S);
}

async function deleteRoom(redis, code) {
  await redis.del(`sala:${code}`);
}

function getOrCreateRoom(roomCode, hostWs) {
  let room = rooms.get(roomCode);
  if (!room) {
    room = {
      hostWs: null,
      guestWs: null,
      webClients: new Set(),
      lastActivity: Date.now(),
    };
    rooms.set(roomCode, room);
  }
  if (hostWs !== undefined) room.hostWs = hostWs;
  return room;
}

async function start() {
  const redis = await buildRedis();

  const app = Fastify({ logger: { level: "info" } });

  await app.register(fastifyCors, { origin: true });
  await app.register(fastifyWebsocket);

  // Health check
  app.get("/", async () => {
    return { status: "ok", service: "AudioShare Proxy v2", rooms: rooms.size };
  });

  // Room creation
  app.post("/api/rooms", async (req, reply) => {
    const code = generateRoomCode();
    const created = await createRoom(redis, code);
    if (!created) {
      reply.code(500).send({ error: "Error al crear sala" });
      return;
    }
    reply.send({ roomCode: code });
  });

  // Heartbeat
  app.post("/api/rooms/:code/heartbeat", async (req, reply) => {
    const { code } = req.params;
    if (!(await roomExists(redis, code))) {
      reply.code(404).send({ error: "Sala no encontrada" });
      return;
    }
    await refreshRoomTTL(redis, code);
    reply.send({ ok: true });
  });

  // WebSocket principal
  app.register(async function (fastify) {
    fastify.get("/ws", { websocket: true }, async (socket, req) => {
      const url = new URL(req.url, `http://${req.headers.host}`);
      const role = url.searchParams.get("role") || "web";
      const roomCode = url.searchParams.get("room");

      if (!roomCode) {
        socket.close(4000, "Falta c\u00f3digo de sala");
        return;
      }

      console.log(`WS conectado \u2014 rol: ${role}, sala: ${roomCode}`);

      // ── HOST (Android) ──
      if (role === "host") {
        const room = getOrCreateRoom(roomCode, socket);
        room.lastActivity = Date.now();

        await redis.set(`sala:${roomCode}`, JSON.stringify({ createdAt: Date.now(), hostPeerId: "ws" }));
        await redis.expire(`sala:${roomCode}`, ROOM_TTL_S);

        socket.send("Conectado como HOST");
        socket.isAlive = true;

        const pingInterval = setInterval(() => {
          if (!socket.isAlive) {
            clearInterval(pingInterval);
            handleHostDisconnect(roomCode, redis);
            return;
          }
          socket.isAlive = false;
          try { socket.ping(); } catch { clearInterval(pingInterval); }
        }, 2000);

        socket.on("pong", () => { socket.isAlive = true; });

        socket.on("message", (data) => {
          if (!(data instanceof Buffer)) return;
          room.lastActivity = Date.now();
          refreshRoomTTL(redis, roomCode);

          // Reenviar a invitados Android (legacy WebSocket)
          if (room.guestWs) {
            try { room.guestWs.send(data); } catch {}
          }

          // Reenviar a web clients (PCM directo por WS)
          for (const ws of room.webClients) {
            try { ws.send(data); } catch (err) { room.webClients.delete(ws); }
          }
        });

        socket.on("close", () => {
          clearInterval(pingInterval);
          handleHostDisconnect(roomCode, redis);
        });

        socket.on("error", () => {
          clearInterval(pingInterval);
          handleHostDisconnect(roomCode, redis);
        });

        return;
      }

      // ── GUEST (Android) ──
      if (role === "guest") {
        const room = getOrCreateRoom(roomCode, undefined);
        room.guestWs = socket;

        if (!room.hostWs) {
          socket.send("no_host");
          socket.close(4002, "no_host");
          return;
        }

        socket.send(JSON.stringify({ type: "ready" }));
        console.log(`Guest conectada sala ${roomCode}`);

        socket.on("close", () => {
          const r = rooms.get(roomCode);
          if (r) r.guestWs = null;
        });
        socket.on("error", () => {
          const r = rooms.get(roomCode);
          if (r) r.guestWs = null;
        });

        return;
      }

      // ── PWA / otros (audio directo por WS) ──
      const exists = await roomExists(redis, roomCode);
      if (!exists) {
        socket.send(JSON.stringify({ type: "error", message: "no_host" }));
        socket.close(4002, "no_host");
        return;
      }

      let room = rooms.get(roomCode);
      if (!room) {
        room = getOrCreateRoom(roomCode, undefined);
      }

      if (!room.hostWs) {
        socket.send(JSON.stringify({ type: "error", message: "no_host" }));
        socket.close(4002, "no_host");
        return;
      }

      room.webClients.add(socket);
      socket.send(JSON.stringify({ type: "ready" }));
      console.log(`Web conectada sala ${roomCode} (total: ${room.webClients.size})`);

      socket.on("close", () => {
        const r = rooms.get(roomCode);
        if (r) {
          r.webClients.delete(socket);
          if (r.webClients.size === 0 && !r.hostWs) rooms.delete(roomCode);
        }
      });

      socket.on("error", () => {
        const r = rooms.get(roomCode);
        if (r) {
          r.webClients.delete(socket);
          if (r.webClients.size === 0 && !r.hostWs) rooms.delete(roomCode);
        }
      });

    });
  });

  function handleHostDisconnect(roomCode, redis) {
    const room = rooms.get(roomCode);
    if (room) {
      // Notificar a todos los web clients
      for (const ws of room.webClients) {
        try {
          if (ws.readyState === 1) {
            ws.send(JSON.stringify({ type: "host_disconnected" }));
          }
        } catch {}
      }
      room.webClients.clear();
      rooms.delete(roomCode);
    }
    deleteRoom(redis, roomCode);
    console.log(`Sala ${roomCode} eliminada por desconexión del host`);
  }

  // Limpieza periódica de salas expiradas en Redis
  async function cleanExpiredRooms() {
    try {
      const keys = await redis.keys("sala:*");
      for (const key of keys) {
        const ttl = await redis.ttl(key);
        if (ttl <= 0) {
          const code = key.replace("sala:", "");
          const room = rooms.get(code);
          if (room) {
            for (const ws of room.webClients) {
              try { if (ws.readyState === 1) ws.close(); } catch {}
            }
            rooms.delete(code);
          }
          await redis.del(key);
          console.log(`Sala ${code} limpiada por expiración`);
        }
      }
    } catch (err) {
      console.error("Error en limpieza periódica:", err.message);
    }
  }

  setInterval(cleanExpiredRooms, 10000);

  await app.listen({ port: PORT, host: "0.0.0.0" });
  console.log(`Proxy AudioShare v2 escuchando en puerto ${PORT}`);
}

start().catch((err) => {
  console.error("Error fatal:", err);
  process.exit(1);
});
