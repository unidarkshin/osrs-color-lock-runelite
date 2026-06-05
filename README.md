# Color Locked

RuneLite plugin for **Group Ironman color-lock** runs. Pulls each member's assigned color and the gated-item ruleset from the [Color Lock hub](https://group.thegrandchart.com), then:

- Blocks restricted right-click actions (Eat / Equip / Wield / Wear / Hold / Drink) on items your color cannot use.
- Strips Mine / Chop when carrying a restricted pickaxe or axe in inventory or equipment.
- Adds a small red marker on restricted items in inventory / bank / worn gear.
- Provides an item lookup sidebar that shows each item's palette (and your group's intersection).
- Mirrors the hub-assigned color into the plugin config and grays the manual picker while synced.
- Sends a heartbeat so the hub can show who's online and log color-desync events.

## Quick start

1. Install **Color Locked** from the RuneLite plugin hub.
2. Open the **Color Locked** settings.
3. Fill in:
   - **Group access code** — your combined `Slug#0000` code (you can also paste the full invite URL, or enter the slug and legacy member code separately).
   - **Group password** — only if your group uses one.
4. Tick **Sync with group**. Watch the chat for the confirmation banner.

If you don't have a Color Lock group yet, the plugin still works as a manual color-lock helper: pick **Your color lock** in settings and items outside that palette will be flagged.

## What data leaves your client

Color Locked talks to **one host:** `group.thegrandchart.com`. Nothing is sent anywhere else.

| Request | Sent | Why |
|---------|------|-----|
| `POST /api/plugin/v1/auth` | Group code, Member code, optional Group password | Trade for a short-lived Bearer JWT. |
| `GET  /api/plugin/v1/state` | Bearer JWT | Read your assigned color and group palette. |
| `PATCH /api/plugin/v1/me` (~every 60 s while logged in) | Bearer JWT, your in-game display name, `presence.online` (+ world tile), `currentColor`, skill levels, current HP and prayer; also `sync.enabled` when you toggle the checkbox | Heartbeat for online status and skill tracking. `collectionLog` snapshot on login; on a new clog drop, `totalCount`, `newDrop`, and optional `collectionItem` (one PATCH per unique). |
| `GET  {state.items.url}`, `GET /api/v1/items?colored=1&groupFilters=1&…`, or deprecated `GET /api/items?…` (last retry) | Bearer JWT when synced | Filtered item rules JSON (group potion/food/ammo policy). |
| `POST /api/plugin/v1/resolve/{slug}` | Group code + Member code + Group password when needed | Fallback when `POST …/auth` returns HTTP 404 before JWT `/auth` is fully deployed server-side. |

The plugin does **not** send: bank contents, chat, location, hashes, IP, telemetry, or anything else not listed above. The JWT is held in memory only and discarded on plugin disable or shutdown. Credentials are stored in the standard RuneLite config (same place as every other plugin).

Toggle **Sync with group** off to stop all network traffic except the public item rules pull.

### Local cache (offline / manual mode)

After the first successful items download each login, the plugin writes a copy of the rules JSON to
`%USERPROFILE%\.runelite\color-lock-helper\items-manifest.json` (same folder RuneLite uses for cache, logs, and plugin data).
If the hub is unreachable, enforcement falls back to that file. Credentials and assigned color still use normal RuneLite config (`ConfigManager`); only the item catalog is cached on disk.

## Increasing RuneLite memory (recommended if using many plugins)

If RuneLite feels laggy or freezes with many plugins active, increase the client's heap size from the default 512 MB:

### Windows

1. Open the Start menu and search for **RuneLite (configure)**.
   - Alternatively, open a terminal and run: `"%LOCALAPPDATA%\RuneLite\RuneLite.exe" --configure`
2. A settings window appears. Find the **JVM Arguments** field.
3. Add `-Xmx1024m` (sets the max heap to 1 GB). If other arguments are already there, add it at the end separated by a space.
4. Click **Save**.
5. Launch RuneLite through the **Jagex Launcher** as usual — the new memory setting is applied automatically.

### macOS

1. Open a terminal and run: `/Applications/RuneLite.app/Contents/MacOS/RuneLite --configure`
2. Add `-Xmx1024m` to the **JVM Arguments** field and click **Save**.
3. Launch RuneLite through the **Jagex Launcher** as usual.

### Linux

1. Run: `runelite --configure` (or the full path to your RuneLite install).
2. Add `-Xmx1024m` to the **JVM Arguments** field and click **Save**.
3. Launch RuneLite through the **Jagex Launcher** as usual.

The setting persists across launches until you change it. If you still experience lag with many plugins (especially 117 HD), try `-Xmx1536m` or `-Xmx2048m`.

## Build

Requires JDK 11+.

```bash
./gradlew build
```

Output: `build/libs/osrs-color-lock-runelite-1.0.0.jar`.

Local dev (sideload):

```bash
scripts/run-runelite-dev.bat   # Windows
```

builds, copies the jar into `~/.runelite/sideloaded-plugins/`, and launches the dev client.

## Item rules / data contract

Item rules come from the same JSON used by the web app. See [`DATA_CONTRACT.md`](./DATA_CONTRACT.md) for the schema, schema version, and the canonical source (`unidarkshin/osrs-color-lock`). The plugin never duplicates color math — it reads `usableColors` from the payload.

## License

BSD-2-Clause. See [`LICENSE`](./LICENSE).
