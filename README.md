# wen?

A Discord bot that answers: *"When is the next [event]?"*

Query iCal feeds via slash commands.

## Features

- **Calendar Integration** — Fetches and parses iCal (.ics) feeds
- **Keyword Matching** — e.g. `/wen f1` → looks up the F1 race calendar
- **Event Filtering** — e.g. `/wen f1 sprint` → only show sprint races
- **Background Refresh** — Calendars refresh on configurable intervals

## Tech

- Java 25
- `java.net.http` (HttpClient, WebSocket)
- `biweekly` for iCal parsing
- `tomlj` for configuration
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

| Setting | Value | Reason |
|---------|-------|--------|
| Public Bot | Your choice | Whether others can invite it |
| Requires OAuth2 Code Grant | ❌ Off | Not needed |
| Presence Intent | ❌ Off | We don't track presence |
| Server Members Intent | ❌ Off | We don't need member lists |
| Message Content Intent | ❌ Off | We use slash commands only |

### 4. Required Permissions

**OAuth2 Scopes:**
- `bot`
- `applications.commands`

**Bot Permissions:**
- None required

### 5. Generate Invite URL

1. Go to **OAuth2** → **URL Generator**
2. Select scopes: `bot`, `applications.commands`
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
[[sources]]
name = "Formula 1"
url = "https://example.com/f1-calendar.ics"
keywords = ["f1", "formula1"]
refreshInterval = "PT1H"  # ISO-8601 duration
isDefault = false

[sources.defaultMatcher]
field = "summary"
contains = "Grand Prix"

[sources.matchers.sprint]
field = "summary"
contains = "Sprint"
```

### Source Fields

| Field | Required | Description |
|-------|----------|-------------|
| `name` | ✅ | Display name for the source |
| `url` | ✅ | iCal feed URL |
| `keywords` | ✅ | Trigger words (e.g., `["f1", "formula1"]`) |
| `refreshInterval` | ❌ | How often to refresh (default: `PT6H`) |
| `isDefault` | ❌ | Use when no keyword matches (default: `false`) |
| `defaultMatcher` | ❌ | Filter applied to all events from this source |
| `matchers.<name>` | ❌ | Named filters users can specify |

### Matcher Fields

| Field | Required | Description |
|-------|----------|-------------|
| `contains` | ✅ | Substring to match (case-insensitive) |
| `field` | ❌ | Which field to match: `summary`, `description`, `location` (default: `summary`) |

---

## Usage

```
/wen <source> [filter]

Examples:
/wen f1              → next F1 race
/wen f1 sprint       → next F1 sprint race
/wen nba finals      → next NBA Finals game
```

---

## Building

```bash
mvn clean package
```

Produces a shaded JAR in `target/`.

## Running

```bash
java -jar target/wen.jar
```

---

## License

MIT
