# AxoBridge

AxoBridge connects the AxoCraft website backend to a Paper 1.21.1 server.

## Current demo scope

- Authenticated server heartbeat and public connection status.
- A restricted demo VIP+ delivery queue.
- The plugin accepts only a validated Minecraft username, the fixed `vip+` group, and the fixed `30d` duration.
- The plugin dispatches only the fixed LuckPerms command: `lp user <name> parent addtemp vip+ 30d`.
- Demo usernames can receive the demo VIP+ only once while the API process is running.
- The demo queue and used-name list are in memory and are cleared when Render restarts. This is not production storage.

## Important account/site limitation

The AxoCraft static website currently has a browser-only demo login. It is not a real authenticated account system and must not be trusted to choose a Minecraft username for a rank grant. Do not expose `AXOBRIDGE_API_KEY` in browser JavaScript. The `POST /api/admin/demo-vip` endpoint is server-to-server only and requires the private bearer key. Connect it only after a real website backend can verify the logged-in account and its saved Minecraft username.

## Build

Requires Java 21 and Gradle 8.10+.

```bash
gradle clean build
```

The plugin JAR is created in `build/libs/`.

## Run API

Requires Node.js 20+.

1. Set `AXOBRIDGE_API_KEY` to a random secret of at least 32 characters.
2. Set `ALLOWED_ORIGIN` to the exact website origin.
3. Run `npm install && npm start`.

## Plugin configuration

Set `api.base-url` to the API URL and `api.api-key` to the same private key used by the API. Never publish this key.

## Endpoints

- `GET /health` - API health.
- `GET /api/status` - limited public connection status.
- `POST /api/bridge/heartbeat` - authenticated plugin heartbeat.
- `POST /api/admin/demo-vip` - private server-to-server endpoint to enqueue one demo VIP+ order. JSON: `{"minecraftUsername":"PlayerName"}`.
- `GET /api/bridge/orders/next` - authenticated plugin polling endpoint.
- `POST /api/bridge/orders/:id/ack` - authenticated delivery result.

## Not production-ready

The demo queue is volatile/in-memory. Before connecting a public website button, implement real account authentication, verified Minecraft identity ownership, persistent database-backed orders, per-account eligibility, atomic one-time claims, audit logs, and safe retry/idempotency. Do not use for real payments or paid rank delivery.
