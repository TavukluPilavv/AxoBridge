const express = require("express");
const cors = require("cors");
const crypto = require("crypto");

const app = express();
const port = Number(process.env.PORT || 3000);
const apiKey = process.env.AXOBRIDGE_API_KEY;
const allowedOrigin = process.env.ALLOWED_ORIGIN || "https://axocraft.com";

if (!apiKey || apiKey.length < 32) {
  console.error("AXOBRIDGE_API_KEY must be set to a random secret of at least 32 characters.");
  process.exit(1);
}

app.use(cors({
  origin: allowedOrigin,
  methods: ["GET", "POST"],
  allowedHeaders: ["Content-Type", "Authorization"]
}));
app.use(express.json({ limit: "8kb" }));

let latestHeartbeat = null;
// DEMO ONLY: in-memory queue. A Render restart clears pending demo orders.
const demoOrders = [];
const completedDemoOrders = new Set();
const demoChallenges = new Map();
const challengeCooldowns = new Map();

function safeEqual(a, b) {
  const left = Buffer.from(String(a));
  const right = Buffer.from(String(b));
  return left.length === right.length && crypto.timingSafeEqual(left, right);
}

function requireBridgeKey(req, res, next) {
  const supplied = (req.get("authorization") || "").replace(/^Bearer\s+/i, "");
  if (!safeEqual(supplied, apiKey)) {
    return res.status(401).json({ ok: false, error: "unauthorized" });
  }
  next();
}

app.get("/health", (_req, res) => {
  res.json({ ok: true, service: "AxoBridge API" });
});

app.get("/api/status", (_req, res) => {
  const connected = Boolean(latestHeartbeat && Date.now() - latestHeartbeat.seenAt < 150000);
  res.json({
    connected,
    serverName: connected ? latestHeartbeat.serverName : null,
    minecraftVersion: connected ? latestHeartbeat.minecraftVersion : null,
    onlinePlayers: connected ? latestHeartbeat.onlinePlayers : null,
    maxPlayers: connected ? latestHeartbeat.maxPlayers : null,
    lastSeen: latestHeartbeat ? new Date(latestHeartbeat.seenAt).toISOString() : null
  });
});

app.post("/api/bridge/heartbeat", requireBridgeKey, (req, res) => {
  const body = req.body || {};
  const serverName = typeof body.serverName === "string" ? body.serverName.slice(0, 40) : "Minecraft";
  const minecraftVersion = typeof body.minecraftVersion === "string" ? body.minecraftVersion.slice(0, 20) : "unknown";
  const onlinePlayers = Number.isInteger(body.onlinePlayers) ? Math.max(0, Math.min(body.onlinePlayers, 10000)) : 0;
  const maxPlayers = Number.isInteger(body.maxPlayers) ? Math.max(0, Math.min(body.maxPlayers, 10000)) : 0;
  latestHeartbeat = { serverName, minecraftVersion, onlinePlayers, maxPlayers, seenAt: Date.now() };
  res.json({ ok: true, received: true });
});

// Public demo claim request. The code is only useful when entered in-game
// by the Minecraft account whose name was entered on the website.
app.post("/api/demo-vip/request", (req, res) => {
  const username = String(req.body?.minecraftUsername || "").trim();
  if (!/^[A-Za-z0-9_]{3,16}$/.test(username)) {
    return res.status(400).json({ ok: false, error: "invalid_minecraft_username" });
  }
  const key = username.toLowerCase();
  const existingOrder = demoOrders.find(order => order.username.toLowerCase() === key && order.status !== "failed");
  if (existingOrder || completedDemoOrders.has(key)) {
    return res.status(409).json({ ok: false, error: "demo_already_used" });
  }
  const lastRequest = challengeCooldowns.get(key) || 0;
  if (Date.now() - lastRequest < 30000) {
    return res.status(429).json({ ok: false, error: "please_wait_before_requesting_another_code" });
  }
  const code = String(crypto.randomInt(100000, 1000000));
  demoChallenges.set(code, { username, expiresAt: Date.now() + 10 * 60 * 1000 });
  challengeCooldowns.set(key, Date.now());
  res.status(201).json({ ok: true, code, expiresInSeconds: 600, minecraftUsername: username });
});

// Called only by the authenticated server plugin after a player enters their code.
app.post("/api/bridge/verify", requireBridgeKey, (req, res) => {
  const code = String(req.body?.code || "").trim();
  const inGameUsername = String(req.body?.minecraftUsername || "").trim();
  if (!/^\d{6}$/.test(code) || !/^[A-Za-z0-9_]{3,16}$/.test(inGameUsername)) {
    return res.status(400).json({ ok: false, error: "invalid_code_or_username" });
  }
  const challenge = demoChallenges.get(code);
  if (!challenge || challenge.expiresAt < Date.now()) {
    demoChallenges.delete(code);
    return res.status(404).json({ ok: false, error: "code_invalid_or_expired" });
  }
  if (challenge.username.toLowerCase() !== inGameUsername.toLowerCase()) {
    return res.status(403).json({ ok: false, error: "username_does_not_match" });
  }
  const key = inGameUsername.toLowerCase();
  const existingOrder = demoOrders.find(order => order.username.toLowerCase() === key && order.status !== "failed");
  if (existingOrder || completedDemoOrders.has(key)) {
    demoChallenges.delete(code);
    return res.status(409).json({ ok: false, error: "demo_already_used" });
  }
  demoChallenges.delete(code);
  const order = {
    id: crypto.randomUUID(),
    username: challenge.username,
    product: "vip+",
    duration: "30d",
    status: "pending",
    createdAt: new Date().toISOString()
  };
  demoOrders.push(order);
  res.status(201).json({ ok: true, message: "verified_and_queued", product: "vip+", duration: "30d" });
});

// Plugin polls this endpoint. Only fixed product data is ever returned.
app.get("/api/bridge/orders/next", requireBridgeKey, (_req, res) => {
  const order = demoOrders.find(item => item.status === "pending");
  if (!order) return res.json({ ok: true, order: null });
  order.status = "processing";
  res.json({ ok: true, order: { id: order.id, username: order.username, product: order.product, duration: order.duration } });
});

app.post("/api/bridge/orders/:id/ack", requireBridgeKey, (req, res) => {
  const order = demoOrders.find(item => item.id === req.params.id);
  if (!order) return res.status(404).json({ ok: false, error: "order_not_found" });
  if (order.status === "delivered") return res.json({ ok: true, alreadyCompleted: true });
  const success = req.body?.success === true;
  order.status = success ? "delivered" : "failed";
  order.finishedAt = new Date().toISOString();
  if (success) completedDemoOrders.add(order.username.toLowerCase());
  res.json({ ok: true, status: order.status });
});

app.listen(port, "0.0.0.0", () => {
  console.log(`AxoBridge API listening on port ${port}`);
});
