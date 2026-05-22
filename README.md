# Color Locked

RuneLite plugin for **Group Ironman color-lock** runs. Pulls each member's assigned color and the gated-item ruleset from the [Color Lock hub](https://group.thegrandchart.com), then:

- Blocks restricted right-click actions (Eat / Equip / Wield / Wear / Hold / Drink) on items your color cannot use.
- Adds a small red marker on restricted items in inventory / bank / worn gear.
- Provides an item lookup sidebar that shows each item's palette (and your group's intersection).
- Mirrors the hub-assigned color into the plugin config and grays the manual picker while synced.
- Sends a heartbeat so the hub can show who's online and log color-desync events.

## Quick start

1. Install **Color Locked** from the RuneLite plugin hub.
2. Open the **Color Locked** settings.
3. Fill in:
   - **Group code** — from the hub URL `/g/<slug>` (you can also paste the full invite URL).
   - **Group password** — only if your group uses one.
   - **Member code** — your member/public code from the hub.
4. Tick **Sync with group**. Watch the chat for the confirmation banner.

If you don't have a Color Lock group yet, the plugin still works as a manual color-lock helper: pick **Your color lock** in settings and items outside that palette will be flagged.

## What data leaves your client

Color Locked talks to **one host:** `group.thegrandchart.com`. Nothing is sent anywhere else.

| Request | Sent | Why |
|---------|------|-----|
| `POST /api/plugin/v1/auth` | Group code, Member code, optional Group password | Trade for a short-lived Bearer JWT. |
| `GET  /api/plugin/v1/state` | Bearer JWT | Read your assigned color and group palette. |
| `PATCH /api/plugin/v1/me` (~every 60 s while logged in) | Bearer JWT, your in-game display name, `presence.online`, `currentColor`; also `sync.enabled` when you toggle the checkbox | Heartbeat for online status + desync audit. |
| `GET  {state.items.url}`, `GET /api/v1/items?colored=1&groupFilters=1&…`, or deprecated `GET /api/items?…` (last retry) | Bearer JWT when synced | Filtered item rules JSON (group potion/food/ammo policy). |
| `POST /api/plugin/v1/resolve/{slug}` | Group code + Member code + Group password when needed | Fallback when `POST …/auth` returns HTTP 404 before JWT `/auth` is fully deployed server-side. |

The plugin does **not** send: account name, bank contents, chat, location, hashes, IP, telemetry, or anything else not listed above. The JWT is held in memory only and discarded on plugin disable or shutdown. Credentials are stored in the standard RuneLite config (same place as every other plugin).

Toggle **Sync with group** off to stop all network traffic except the public item rules pull.

## Build

Requires JDK 11+.

```bash
./gradlew build
```

Output: `build/libs/osrs-color-lock-runelite-1.0-SNAPSHOT.jar`.

Local dev (sideload):

```bash
scripts/run-runelite-dev.bat   # Windows
```

builds, copies the jar into `~/.runelite/sideloaded-plugins/`, and launches the dev client.

## Item rules / data contract

Item rules come from the same JSON used by the web app. See [`DATA_CONTRACT.md`](./DATA_CONTRACT.md) for the schema, schema version, and the canonical source (`unidarkshin/osrs-color-lock`). The plugin never duplicates color math — it reads `usableColors` from the payload.

## License

BSD-2-Clause. See [`LICENSE`](./LICENSE).
