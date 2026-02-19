# wen?

[![build](https://github.com/anirbanmu/wen/actions/workflows/build.yml/badge.svg)](https://github.com/anirbanmu/wen/actions/workflows/build.yml)

A Discord bot that tells you when stuff is. Written in Java because why not.

[Install wen](https://discord.com/oauth2/authorize?client_id=1470268308862533715) -- works as a user install or a server bot

`/wen f1` → next F1 race. `/wen f1 sprint` → next sprint. That's it. That's the bot.

## What it does

- Fetches iCal (.ics) feeds in the background
- Responds to Discord slash commands with upcoming events
- Keyword lookup, named filters, free-text search, autocomplete
- Prefilters for noisy calendars (e.g., only "Grand Prix" events from a full F1 feed)

## What it runs on

256MB RAM. Shared CPU. One Fly.io machine.

| Component   | Details                                                   |
|-------------|-----------------------------------------------------------|
| Runtime     | Java 25, JLink-stripped to only the modules the app needs |
| GC          | ZGC, 40MB soft max, compact object headers                |
| Concurrency | Virtual threads — gateway, calendar refresh, HTTP, health |
| HTTP        | `java.net.http` — HttpClient + WebSocket, no frameworks   |
| Parsing     | `biweekly` (iCal), `tomlj` (config), `dsl-json` (JSON)    |
| Container   | Multi-stage Docker, `alpine:3` runtime                    |
| Deploy      | Fly.io, `shared-cpu-1x`, 256MB, single machine            |

64MB heap (Xms64m/Xmx64m with -XX:SoftMaxHeapSize=40m), ZGC, virtual threads. Java's fine.

### In production

~1% CPU. <200MB RSS. GC pauses under 100µs. 4 dependencies.

No Spring. No Netty. No Jackson. No Guava. Just the JDK and a few small libraries.

---

## Usage

```
/wen f1              → next F1 event
/wen f1 sprint       → named filter
/wen f1 monaco       → free-text search across event fields
/wen help            → list available calendars and filters
```

---

## How it works

```
Discord Gateway (WebSocket)
  → GatewayEventParser (dsl-json)
    → Processor (query parse, calendar lookup, filter)
      → CalendarFeed.query() (pre-sorted events, predicate match)
        → DiscordHttpClient.respond() (rate-limited HTTP)
```

Calendar feeds refresh on configurable intervals via virtual threads. Each feed runs its own
background loop with jitter to avoid thundering herd. A semaphore limits concurrent refreshes to 3.
GC telemetry via JFR streams — pause stats, allocation stalls, and heap usage logged every 60s.

---

## Configuration

Create a `config.toml`:

```toml
[[calendars]]
name = "Formula 1"
url = "https://example.com/f1-calendar.ics"
keywords = ["f1", "formula1"]
refreshInterval = "PT1H"  # ISO-8601 duration
fallback = false

[calendars.prefilter]
field = "summary"
contains = "Grand Prix"

[calendars.filters.sprint]
field = "summary"
contains = "Sprint"
```

### Calendar fields

|       Field       | Required |                   Description                   |
|-------------------|----------|-------------------------------------------------|
| `name`            | ✅        | Display name                                    |
| `url`             | ✅        | iCal feed URL                                   |
| `keywords`        | ✅        | Trigger words (e.g., `["f1", "formula1"]`)      |
| `refreshInterval` |          | How often to refresh (default: `PT6H`)          |
| `fallback`        |          | Use when no keyword matches (default: `false`)  |
| `prefilter`       |          | Filter applied to all events from this calendar |
| `filters.<name>`  |          | Named filters users can specify                 |

### Filter fields

|   Field    | Required |                                Description                                 |
|------------|----------|----------------------------------------------------------------------------|
| `contains` | ✅        | Substring to match (case-insensitive)                                      |
| `field`    |          | `summary`, `description`, `location`, or `categories` (default: `summary`) |

---

## Discord setup

### 1. Create application

1. Go to [Discord Developer Portal](https://discord.com/developers/applications)
2. **New Application** → name it
3. Note the **Application ID**

### 2. Create bot

1. **Bot** tab → **Add Bot**
2. **Reset Token** → copy it (this is your `DISCORD_TOKEN`)

### 3. Bot settings

|        Setting         |   Value   |             Why              |
|------------------------|-----------|------------------------------|
| Public Bot             | Your call | Whether others can invite it |
| Presence Intent        | Off       | Don't need it                |
| Server Members Intent  | Off       | Don't need it                |
| Message Content Intent | Off       | Slash commands only          |

### 4. Invite

1. **OAuth2** → **URL Generator**
2. Scopes: `bot`, `applications.commands`
3. Bot permissions: none
4. Open the URL to invite

### 5. Environment variables

|         Variable         |  Required  |                               Description                                |
|--------------------------|------------|--------------------------------------------------------------------------|
| `DISCORD_TOKEN`          | Yes        | Bot token from Discord Developer Portal                                  |
| `DISCORD_APPLICATION_ID` | Yes        | Application ID from Discord Developer Portal                             |
| `WEN_CONFIG_B64`         | Production | Base64-encoded `config.toml` (required in Docker/Fly.io)                 |
| `HEALTH_PORT`            | No         | Health check HTTP port (default: `8080`)                                 |
| `UNHEALTHY_THRESHOLD_MS` | No         | Watchdog exit threshold in milliseconds (default: `600000` = 10 minutes) |

Locally, you can pass the config file path via the `-Dconfig=path/to/config.toml` system property instead of `WEN_CONFIG_B64`.

```bash
# local development
DISCORD_TOKEN="your_bot_token"
DISCORD_APPLICATION_ID="your_application_id"

# production (container)
DISCORD_TOKEN="your_bot_token"
DISCORD_APPLICATION_ID="your_application_id"
WEN_CONFIG_B64="$(base64 -w0 config.toml)"
```

---

## Building

```bash
mvn clean package
```

## Running locally

```bash
DISCORD_TOKEN="..." DISCORD_APPLICATION_ID="..." java -Dconfig=config.toml -jar target/wen.jar
```

## Deploying

### Docker

```bash
docker build -t wen .
docker run -e DISCORD_TOKEN=... -e DISCORD_APPLICATION_ID=... -e WEN_CONFIG_B64=... wen
```

`WEN_CONFIG_B64` is your `config.toml`, base64-encoded. The entrypoint decodes it at startup.

### Fly.io

```bash
fly apps create wen
fly secrets set DISCORD_TOKEN=... DISCORD_APPLICATION_ID=... WEN_CONFIG_B64=...
fly deploy
```

Subsequent deploys just need `fly deploy`. Health check on `/health:8080`, configured in `fly.toml`.

---

Built for the "wen race" crowd 🏁

---

## License

MIT
