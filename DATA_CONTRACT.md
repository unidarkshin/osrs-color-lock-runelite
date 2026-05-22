# Color Lock plugin ↔ hub data contract

**Canonical source logic:** [`unidarkshin/osrs-color-lock`](https://github.com/unidarkshin/osrs-color-lock) — `src/lib/colors.ts` (`getUsableColors`) — **do not reimplement**; read `usableColors` from payloads.

The plugin authenticates against the hub and reads its current color, group palette, and the items manifest URL from there. Periodic heartbeats keep the hub's "online" marker fresh and snapshot the player's in-client color for the audit log.

## Primary flow (Plugin v1)

| Step | Endpoint | Purpose |
|------|----------|---------|
| 1 | `POST {base}/api/plugin/v1/auth` | Trade credentials for a Bearer JWT. Prefer `accessCode` (`GroupSlug#0042`); legacy `slug` / `inviteUrl` + `publicCode` still works. Optional `joinPasscode`. |
| 2 | `GET  {base}/api/plugin/v1/state` | Pull current `group`, `member.assignedColor`, `member.status`, `items.url`, `items.schemaVersion`. |
| 3 | `PATCH {base}/api/plugin/v1/me` | Heartbeat (~60 s). Sends `runescapeUsername` (required), `presence.online`, `currentColor`, and `sync.enabled` when the user toggles the plugin checkbox. |
| ↳ | Items pull | `GET state.items.url` if provided (`colored=1&groupFilters=1`; plugin strips pinned `includePotions` / `excludeFood` / `includeAmmunition` when synced so Bearer group policy applies), else built URL from config. **Sync off:** plugin settings **Lock potions/food/ammunition** → query params. **Sync on:** hub group toggles via Bearer only. Deprecated `GET {base}/api/items?…` last retry. |
| ↳ | Auth 404 fallback | `POST {base}/api/plugin/v1/resolve/{slug}` — JWT-less; same member/group shapes as `/auth` when `/auth` returns `HTTP 404` but resolve is deployed. |

`{base}` is derived from the hub origin (currently `https://group.thegrandchart.com`).

### `POST /api/plugin/v1/auth`

Request body (JSON):

| Field | Required | Notes |
|-------|----------|-------|
| `accessCode` | preferred | Combined `GroupSlug#0000` (e.g. `FoxMango42#0042`). Plugin sends this when both Group code and Member code are filled, or when the user pastes the combined code into Group code. |
| `publicCode` | legacy | Member's public code (`#0042`) with `slug` or `inviteUrl`. |
| `slug` | legacy | Group code from `/g/<slug>`. |
| `inviteUrl` | legacy | Full hub invite URL in the Group code field. |
| `joinPasscode` | when required | Group password. |

Response:

```json
{
  "accessToken": "<jwt>",
  "tokenType": "Bearer",
  "expiresInSec": 3600,
  "group":  { "slug": "...", "name": "...", "enabledColors": ["red", "..."] },
  "member": {
    "assignedColor": "red",
    "colorLocked": false,
    "colorUnlockVotes": [],
    "status": "active",
    "pluginProfileRev": 7
  }
}
```

The plugin stores the JWT in memory only, with the wall-clock expiry, and re-auths when stale.

### `GET /api/plugin/v1/state`

`Authorization: Bearer <jwt>`. Response includes `group`, `member` (with the new `colorLocked`, `presenceOnline`, `presenceSummary`, `pluginStats`, and the `pluginSync*` audit fields), `roster`, and `items`:

```json
{
  "group":  { "slug": "...", "name": "...", "enabledColors": [...] },
  "member": { "assignedColor": "red", "status": "active", "pluginProfileRev": 7, "colorLocked": false, ... },
  "roster": [ ... ],
  "items":  { "url": "https://.../api/v1/items?mode=color-lock&colored=1&groupFilters=1&...", "schemaVersion": 88, "note": "..." }
}
```

The plugin stores `items.schemaVersion` from `/state` (`getHubItemsSchemaVersionHint`) alongside the loaded manifest's `schemaVersion` and warns in chat when expectations diverge. Roster rows and `pluginSync*` audit fields feed the sidebar **Group** tab.

### `PATCH /api/plugin/v1/me`

Heartbeat, sent roughly every minute while the plugin is enabled, credentials are filled, and the player is logged in. Also fired once with `presence.online: false` on plugin shutdown when possible, and once with `sync.enabled` whenever the user flips the checkbox.

Request body (everything except `runescapeUsername` is optional):

```json
{
  "runescapeUsername": "Zezima",
  "presence": { "online": true },
  "currentColor": "red",
  "sync": { "enabled": true }
}
```

| Field | Required | Notes |
|-------|----------|-------|
| `runescapeUsername` | yes | Pulled from `client.getLocalPlayer().getName()`. Validated client-side: 1–12 chars, `^[A-Za-z0-9 _-]+$`. Heartbeat is skipped when missing or invalid. |
| `presence.online` | optional | `true` on heartbeats, `false` on shutdown / on the sync-off PATCH. |
| `currentColor` | optional | The plugin's effective color (hub-assigned when synced, else the manual `assignedColor`). Audit-only — never changes `member.assignedColor`. |
| `sync.enabled` | optional | Sent exactly once per checkbox toggle so the hub history can timestamp the event and snapshot `currentColor`. Omitted on regular heartbeats. |
| `stats` | optional | Not sent today; reserved for future skill/HP/prayer snapshots. |

Hub considers members stale after ~180 s without a heartbeat, so a 60 s cadence keeps presence stable across short network hiccups. Response carries `X-OCL-API-Contract-Version: 8` (`ColorLockApiContracts.EXPECTED_PLUGIN_ME_API_CONTRACT_VERSION`).

## Items manifest

HTTP `GET` → **JSON array** of objects. Response headers `X-OCL-API-Contract-Version: 10` and `X-OCL-Items-Schema-Version: 88` (see `ColorLockApiContracts`). Default query (hub `state.items.url` and plugin fallback): `mode=color-lock`, `colored=1`, `groupFilters=1`; when sync is on, omit pinned `includePotions` / `excludeFood` / `includeAmmunition` so Bearer group policy applies. Optional query params the plugin does **not** send today: `category`, `slot`, `usableBy` (hub/UI filters). Each object has (minimum for this plugin):

| Field | Type | Notes |
|-------|------|--------|
| `id` | int | OSRS item id |
| `name` | string | Display only |
| `category` | string | `food`, `weapon`, armour keys, … — use for action filtering |
| `equipable` | bool | Helps Equip/use checks |
| `healAmount` | int | \> 0 ⇒ food-ish for Eat |
| `red` … `white` | number | Percentages; informational |
| **`usableColors`** | string[] | One or more of: `red`, `yellow`, `green`, `blue`, `purple`, `brown`, `black`, `white` |
| **`colorLockApplies`** | bool | **`false`** = do not gate. **`true`** = gate (when `usableColors` present). |
| **`schemaVersion`** | int | Current value: **88** — increment together with plugin (`ColorLockPlugin.EXPECTED_SCHEMA_VERSION`) |

Other fields (`stabAttack`, `tierLabel`, …) may be ignored.

### Compatibility

If `usableColors` is missing → treat manifest as stale; refuse to gate actions or bundle a pinned snapshot release.

Bump `schemaVersion` in web app (`src/lib/colorLockItemsPayload.ts`) whenever you intentionally break consumers; update this doc and publish a matching plugin release.
