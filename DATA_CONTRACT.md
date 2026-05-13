# Item JSON contract (shared with web app)

**Canonical source logic:** [`unidarkshin/osrs-color-lock`](https://github.com/unidarkshin/osrs-color-lock) — `src/lib/colors.ts` (`getUsableColors`) — **do not reimplement**; read `usableColors` from payloads.

## Endpoints (pick one)

| Environment | Example |
|-------------|---------|
| Static export | `https://<your-deployment>/data/items.json` |
| Live API | `https://<your-deployment>/api/items` |

Same array shape either way once the generator is deployed with `usableColors`.

## Consumable document

HTTP `GET` → **JSON array** of objects. Each object has (minimum for this plugin):

| Field | Type | Notes |
|-------|------|--------|
| `id` | int | OSRS item id |
| `name` | string | Display only |
| `category` | string | `food`, `weapon`, armour keys, … — use for action filtering |
| `equipable` | bool | Helps Equip/use checks |
| `healAmount` | int | \> 0 ⇒ food-ish for Eat |
| `red` … `white` | number | Percentages; informational |
| **`usableColors`** | string[] | One or more of: `red`, `yellow`, `green`, `blue`, `purple`, `brown`, `black`, `white` |
| **`schemaVersion`** | int | Current value: **1** — increment in web repo when incompatible |

Other fields (`stabAttack`, `tierLabel`, …) may be ignored.

## Compatibility

If `usableColors` is missing → treat manifest as stale; refuse to gate actions or bundle a pinned snapshot release.

Bump `schemaVersion` in web app (`src/lib/colorLockItemsPayload.ts`) whenever you intentionally break consumers; update this doc and publish a matching plugin release.
