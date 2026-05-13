# OSRS Color Lock — RuneLite plugin

Client helper for **Group Ironman color-lock** runs. Loads the same item database as [osrs-color-lock](https://github.com/unidarkshin/osrs-color-lock): see `DATA_CONTRACT.md` for the canonical URL and JSON shape.

**Status:** scaffold only — Gradle project + config + downloader stub. Menu interception (Eat / Equip / …) comes next.

## Build

Requirements: JDK 11+.

```bash
./gradlew build
```

## Data source

Configure **Items manifest URL** in the plugin panel (defaults to `/data/items.json` on your deployed Next app).

The web app attaches `usableColors` and `schemaVersion` via `npm run export-data` and `/api/items`. Do not duplicate color math in Java — consume those fields only.
