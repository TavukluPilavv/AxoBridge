# AxoBridge

AxoBridge connects the AxoCraft website to a Minecraft Paper server.

## Current prototype scope

- Paper 1.21.1 plugin sends an authenticated heartbeat to the API.
- Website can read a limited public connection status from `GET /api/status`.
- The API key is stored only in the backend environment and the Minecraft plugin config. Never put it in website HTML or JavaScript.
- The API currently keeps the latest heartbeat in memory. A backend restart clears status until the plugin sends its next heartbeat.
- This prototype does not yet process purchases, grant ranks, transfer credits, or deliver chest items.

## Build the plugin

Requires Java 21 and Gradle 8.10+.

```bash
gradle clean build
```

The plugin JAR is created in `build/libs/`.

## Run the API locally

Requires Node.js 20+.

1. Set `AXOBRIDGE_API_KEY` to a random secret of at least 32 characters.
2. Set `ALLOWED_ORIGIN` to the exact AxoCraft website origin.
3. Run:

```bash
cd backend
npm install
npm start
```

The API listens on port 3000 by default.

## Configure the Minecraft plugin

1. Install the built JAR in the Paper server's `plugins` folder.
2. Start the server once, then stop it.
3. Edit `plugins/AxoBridge/config.yml`.
4. Set `api.base-url` to the deployed API URL (without a trailing slash).
5. Set `api.api-key` to the same secret used by the backend environment variable.
6. Start the server again and run `/axobridge status`.

## Endpoints

- `GET /health` - API health check.
- `GET /api/status` - limited public connection status.
- `POST /api/bridge/heartbeat` - authenticated plugin heartbeat.

## Before production

Deploy the API over HTTPS, use a unique long random key, restrict the allowed website origin, add persistent storage, authentication for website accounts, audit logs, and a signed purchase queue. Do not expose the server key to the browser. Do not use this prototype for real payments or paid-item delivery yet.
