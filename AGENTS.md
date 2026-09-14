# AGENTS.md -- rw-repair-chest

Rising World server plugin (Unity API **0.9.3**): named repair stations (chest + optional sign). Player puts one damaged whitelisted item in; after debounce the plugin quotes materials (sign, or chat if no sign). When mats are complete: lock briefly, restore durability, consume only needed stacks, unlock. Leftovers stay.

Chat with the user in German. Code, identifiers, commits in English. ASCII punctuation in files (`--`, `...`, `->`); German umlauts in prose are fine.

Javadoc: local `RisingWorld/Data/SDK`, online <https://javadoc.rising-world.net/latest/>

## Flow

```text
Admin: place chest + sign -> /make-repair-chest <NAME> -> /make-repair-sign <NAME>
Player: put damaged whitelist item -> debounce -> quote on sign
       -> add mats -> debounce -> lock 2s -> durability THEN consume -> done
       -> take repaired item -> idle
```

No continuous poll. Pairing key: station `NAME` (case-sensitive). One chest + one sign per name. Anyone may use a station; only `isAdmin()` or a hardcoded operator UID in `RepairService` may run commands (silent ignore otherwise; do not mention UID bypass in chat).

| Command                     | Effect                                                              |
| --------------------------- | ------------------------------------------------------------------- |
| `/make-repair-chest <NAME>` | Register focused Storage chest (allowed types in settings)          |
| `/make-repair-sign <NAME>`  | Link focused sign to existing chest name                            |
| `/remove-repair-chest`      | Focus chest; drop DB + timers + RAM; blank sign; world objects stay |
| `/repair-info`              | Focus chest; name, state, debounce, whitelist summary               |
| `/reload-repair-chest`      | Reload `settings.json`; resync whitelist; stations stay             |

Focus: `getObjectElementInLineOfSight(settings.interactDistance(), ...)`.

## States

`idle` | `quoted` | `repairing` | `done` -- persisted, but **startup always forces `idle`**, unlocks, sets READY on sign; never resume mid-repair.

- Idle scan: silent unless exactly one damaged whitelist item **or** more than one whitelist item (`ONE_AT_A_TIME`).
- `done` + take whitelist item: immediate `idle` (no same-frame rescan).
- Repairable leaves before repair finishes -> `idle`.

## Events / timers

Hot path: RAM map of registered `storage_id` first (no SQLite on miss).

- Put / take / drop on registered storage: restart debounce (2s), except `repairing` (cancel). `done` take of whitelist item: idle now; other `done` takes: 0.25s scan.
- Soft lock while `repairing`: cancel `PlayerStorageAccessEvent`; force `setNewInfoID(1)` on `PlayerChangeObjectInfoEvent`; cancel sign edits / F-key editor on station signs.
- Repair timer (2s, one-shot): identity -> `recipeFor` while damaged -> `setDurability(max)` **then** `removeItem` planned slots -> unlock -> `done`.

`/remove-repair-chest` and `onDisable` kill all timers.

## Pricing / settings

Operator knobs live in `Plugins/RepairChest/settings.json` (must be named `.json`, not `.json.txt`). Missing file is created from `RepairSettings.defaults()`. Unreadable file is rewritten (defaults + whitelist labels from DB). `/reload-repair-chest` reloads it the same way. Formulas live in `RepairPricing` + tests.

```text
API getRecipe(name, variant) else variant 0 else settings.manualRecipes
  -> scale by missing durability (full recipe at <= fullPriceRemainingPercent)
  -> skip fullPriceOnlyIngredients outside that band
  -> + goldFee of goldItemName
  -> plan() allocates chest slots (no double-count; never consume repair target / durable tools)
```

Whitelist (SQLite mirror, replaced on start/reload from JSON `whitelist`) gates repairability -- a recipe alone is not enough. No free / gold-only repair if recipe missing. Edit whitelist, `manualRecipes`, `fullPriceOnlyIngredients`, chest types, fees in the JSON file. JSON stays the source of truth. Changing whitelist/settings never clears chest contents; the only world mutation that removes items is repair consume via `Storage.removeItem(slot, amount)` after durability restore, and only for non-durable planned mats.

Optional OZ Tools UI (soft-dep): if plugin `"OZ - Tools"` is present, `oz.OzWhitelistUi` registers under the **Einstellungen** tab (not PluginSettings). Add uses native `Player.showItemSelectionMenu`; Remove writes `settings.json` then `applySettings`. Item list and edit buttons only for `isAllowed` (same gate as commands); others see "Admins only". OZ still lists RepairChest in the global plugin sidebar. Without OZ the plugin runs as before (JSON + commands). Core classes must not `import de.omegazirkel...`; load the UI via `Class.forName` + invoke. OZ has no `unregisterPlayerPluginSettings` -- disable only clears a flag (overlay entry may remain until restart).

## Lock / API pitfalls

- Lock = `ObjectElement.setInfo(0|1)`, **not** `setStatus` / `setAttribute`.
- Chest: LOS -> `World.getStorage(globalID)`; reject null/transient. Sign: `World.getSign(...)`.
- Durability before consume (crash-safety).

## Persistence

`settings.json` = operator knobs. `repair.db` = stations (and a whitelist mirror). `foreign_keys=ON`, prefer `journal_mode=DELETE`, checkpoint on disable. Reload does not touch station rows.

RAM mirrors registered storage/sign ids; SQLite is source of truth (add after insert, remove only in `drop`). Identity: storage + `creation_date`; object type + position within `interactDistance` when loaded; bad sign -> unlink + blank, chest stays. No blind repair onto a replaced chest.

Schema: `repair_stations` (name PK, storage/sign identity + state), `repair_whitelist` (kind + type_id, variant NULL = any).

## Layout

Package `de.mahagst.risingworld.repairchest` -- one JAR. OZ-only code lives in subpackage `oz`. Prefer deleting wrappers over new layers.

```text
RepairChestPlugin   -- lifecycle only; OZ UI via reflection
RepairCommands      -- commands + storage/sign events
RepairService       -- put/take/scan/repair + timers; whitelist add/remove
StationRegistry     -- RAM index, identity, lock/sign
RepairPricing       -- quote / plan / gold
RepairRepository    -- SQLite
RepairSettings      -- record + built-in defaults() + withWhitelist
RepairSettingsStore -- settings.json load/save
oz/OzWhitelistUi    -- optional Einstellungen panel (OZ imports only here)
Messages, RepairStation, WhitelistEntry
```

**Not in v1:** YAML price tables, multi-sign, multi-item repair, client mods, OZ PluginSettings for fees/timers, CRUD for `manualRecipes` / `fullPriceOnlyIngredients`. OZ stays `provided` (not shaded). Runtime stays standalone without `Plugins/OZTools/`.

## Build

Java 20. JAR `RepairChest` -> `plugins/RepairChest/`. `plugin.yml` must be at `resources/plugin.yml` inside the JAR.
`plugin-api` and `rw-plugin-oz-tools` are `provided` -- install once into local `.m2` via workspace bootstrap (not on every Maven run).

```powershell
.\_tools\bootstrap-libs.ps1 -P rw-repair-chest
cd rw-repair-chest; mvn -B package
```

Quote `-D...` args in PowerShell. After pricing changes: `mvn -B clean verify`.
