# wen?

A Discord bot that answers: *"When is the next [event]?"*

Query iCal feeds via slash commands.

## Features

- **Calendar Integration** — Fetches and parses iCal (.ics) feeds
- **Keyword Matching** — e.g. `/wen f1` → looks up the F1 race calendar
- **Event Filtering** — e.g. `/wen f1 sprint` → only show sprint races
- **Free-text Search** — unrecognized filters search across summary, location, and description
- **Autocomplete** — suggestions as you type
- **Background Refresh** — Calendars refresh on configurable intervals
- **Fallback Calendar** — optional default when no keyword matches
- **Health Check** — HTTP health endpoint for orchestrators

## Tech

- Java 25
- `java.net.http` (HttpClient, WebSocket)
- `biweekly` for iCal parsing
- `tomlj` for configuration
- `dsl-json` for JSON serialization
- virtual threads
- ZGC

---

## Discord Setup

### 1. Create Application

1. Go to [Discord Developer Portal](https://discord.com/developers/applications)
2. Click **New Application** → name it (e.g., "wen")
3. Note down the **Application ID** (you'll need this)

### 2. Create Bot

1. Go to the **Bot** tab
2. Click **Add Bot**
3. Under **Token**, click **Reset Token** and copy it — this is your `BOT_TOKEN`
4. **Keep this secret!** Never commit it to git.

### 3. Bot Settings

Under the **Bot** tab, configure:

|          Setting           |    Value    |            Reason            |
|----------------------------|-------------|------------------------------|
| Public Bot                 | Your choice | Whether others can invite it |
| Requires OAuth2 Code Grant | ❌ Off       | Not needed                   |
| Presence Intent            | ❌ Off       | We don't track presence      |
| Server Members Intent      | ❌ Off       | We don't need member lists   |
| Message Content Intent     | ❌ Off       | We use slash commands only   |

### 4. Required Permissions

**OAuth2 Scopes:**
- `bot`
- `applications.commands`

**Bot Permissions:**
- None required

### 5. Generate Invite URL

1. Go to **OAuth2** → **URL Generator**
2. Select scopes: `bot`, `applications.commands`
   - **Note:** `bot` is required for the bot to appear in the member list.
   - `applications.commands` is required for slash commands.
3. Bot permissions: none
4. Copy the generated URL and open it to invite the bot to your server

### 6. Environment Variables

The bot expects:

```bash
DISCORD_TOKEN=your_bot_token_here
DISCORD_APPLICATION_ID=your_application_id_here
```

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

### Calendar Fields

|       Field       | Required |                   Description                   |
|-------------------|----------|-------------------------------------------------|
| `name`            | ✅        | Display name for the calendar                   |
| `url`             | ✅        | iCal feed URL                                   |
| `keywords`        | ✅        | Trigger words (e.g., `["f1", "formula1"]`)      |
| `refreshInterval` | ❌        | How often to refresh (default: `PT6H`)          |
| `fallback`        | ❌        | Use when no keyword matches (default: `false`)  |
| `prefilter`       | ❌        | Filter applied to all events from this calendar |
| `filters.<name>`  | ❌        | Named filters users can specify                 |

### Filter Fields

|   Field    | Required |                                          Description                                          |
|------------|----------|-----------------------------------------------------------------------------------------------|
| `contains` | ✅        | Substring to match (case-insensitive)                                                         |
| `field`    | ❌        | Which field to match: `summary`, `description`, `location`, `categories` (default: `summary`) |

---

## Usage

```
/wen <query>

Examples:
/wen f1              → next F1 race
/wen f1 sprint       → next F1 sprint race (named filter)
/wen f1 monaco       → free-text search across event fields
/wen help            → list available calendars and filters
```

---

## Building

```bash
mvn clean package
```

Produces a shaded JAR in `target/`.

## Running

```bash
java -Dconfig=config.toml -jar target/wen.jar
```

The `-Dconfig` system property defaults to `config.toml` in the working directory.

## Development

Run locally:

```bash
DISCORD_TOKEN="your_token" DISCORD_APPLICATION_ID="your_app_id" mvn exec:java -Dexec.mainClass="com.github.anirbanmu.wen.Main"
```

---

## Deployment

### Docker

```bash
docker build -t wen .
docker run -e DISCORD_TOKEN=... -e DISCORD_APPLICATION_ID=... -e WEN_CONFIG_B64=... wen
```

`WEN_CONFIG_B64` is your `config.toml` base64-encoded. The entrypoint decodes it to `/tmp/config.toml` at startup.

### Fly.io

Configured via `fly.toml`. Deploys with a health check on `/health` (port 8080).

---

## License

MIT
