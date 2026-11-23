const express = require("express");
const cors = require("cors");

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

function requireBridgeKey(req, res, next) {
  const supplied = req.get("authorization") || "";
  const expected = `Bearer ${apiKey}`;
  if (supplied.length !== expected.length ||
      !require("crypto").timingSafeEqual(Buffer.from(supplied), Buffer.from(expected))) {
    return res.status(401).json({ ok: false, error: "unauthorized" });
  }
  next();
}

app.get("/health", (_req, res) => {
  res.json({ ok: true, service: "AxoBridge API" });
});

// Public status only: no key, player names, or private server data is returned.
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

// Called only by the installed Minecraft plugin.
app.post("/api/bridge/heartbeat", requireBridgeKey, (req, res) => {
  const body = req.body || {};
  const serverName = typeof body.serverName === "string" ? body.serverName.slice(0, 40) : "Minecraft";
  const minecraftVersion = typeof body.minecraftVersion === "string" ? body.minecraftVersion.slice(0, 20) : "unknown";
  const onlinePlayers = Number.isInteger(body.onlinePlayers) ? Math.max(0, Math.min(body.onlinePlayers, 10000)) : 0;
  const maxPlayers = Number.isInteger(body.maxPlayers) ? Math.max(0, Math.min(body.maxPlayers, 10000)) : 0;

  latestHeartbeat = { serverName, minecraftVersion, onlinePlayers, maxPlayers, seenAt: Date.now() };
  res.json({ ok: true, received: true });
});

app.listen(port, "0.0.0.0", () => {
  console.log(`AxoBridge API listening on port ${port}`);
});
