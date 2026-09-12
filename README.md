# RepairChest

Server-side Rising World plugin that turns a registered chest and a linked sign into a repair station.

Players put a damaged whitelisted tool and the materials into the chest. After a short pause the sign shows what is still missing. When everything is present, the chest locks briefly, restores durability, removes only the required stacks, and unlocks. Players do not need to install anything.

## How it works

1. Place an allowed storage chest (skull / gold / silver / armored) and a sign nearby.
2. Look at the chest and run `/make-repair-chest <NAME>`.
3. Look at the sign and run `/make-repair-sign <NAME>` (same name).
4. A player puts a damaged whitelisted item into the chest (mining drill, chainsaw, trimmer, bow, crossbow, repeater, or morning star).
5. After **2 seconds** without further changes, the contents are scanned.
6. The sign shows the missing materials -- or an error (two items, already at 100%). Without a sign, the same text goes to chat.
7. Further puts restart the same timer.
8. When all materials are present: the chest locks for ~2s, durability is set to max, then only the required mats are removed.
9. Take the repaired item out -> station returns to idle. Leftover materials stay.

Notes:

- While idle, nothing happens unless exactly **one damaged** whitelist item is in the chest (junk, full tools, coal alone: silent).
- If two tools are put in and one is taken out again, the station wakes after the debounce.
- Repair materials come from the live Rising World crafting recipe for the target item and variant (`Definitions.getRecipe`), not from hardcoded tables.
- Durability share uses exact values (no floor-to-percent first). Above 15% remaining, each consumed material costs
  `ceil(ingredient.count * missingDurability / (maxDurability * recipe.amount))`.
- At **15% remaining or below** (including 0%), the full per-item recipe price applies:
  `ceil(ingredient.count / recipe.amount)`. Exactly 15.0% counts as full price.
- Every repair also costs a flat **5 gold ingots**, on top of any gold already in the crafting recipe. Full-durability items are not repaired or charged.
- Recipe tools marked reusable (`consume = false`) must be present in the full recipe quantity and are not consumed (sign shows `(tool)`). Crafting stations are not material requirements.
- The SQLite whitelist still decides which items can be repaired. Missing, empty or unusable crafting recipes block repair (`No usable crafting recipe`); there is no gold-only fallback.
- Pressing F on a registered sign (idle only) shows chat `Put your damaged Item into the Chest`. Sign text cannot be edited.
- Only the admin who runs a command gets command feedback. Station messages go to the linked sign; chat is used only if the station has no sign.

## Commands

Admin only (`Server_Admins` in `server.properties`).

Look at the chest or sign first, then use chat or the `^` console **with** a leading `/`.

| Command                     | Effect                                                                        |
| --------------------------- | ----------------------------------------------------------------------------- |
| `/make-repair-chest <NAME>` | Register the focused chest under `NAME`. Name is case-sensitive.              |
| `/make-repair-sign <NAME>`  | Link the focused sign to the existing chest `NAME`. One sign per station.     |
| `/remove-repair-chest`      | Unregister (chest in focus). Clears the linked sign text. World objects stay. |
| `/repair-info`              | Show name, type, state, debounce, sign yes/no, whitelist.                     |

### Rules

- Allowed chests: `skullchest`, `goldchest`, `silverchest`, `armoredchest`.
- Transient storage and non-storage objects are rejected.
- One name, one chest, one sign. A second sign for the same station is not allowed.
- Empty chests may be registered (players fill them later).
- On repair only the recipe amounts are removed; leftovers stay.
- After server start every station is `idle` and unlocked. Mid-repair work is not resumed.

## Install

Put the jar here and restart the server:

```text
Plugins/RepairChest/RepairChest.jar
```

State is stored automatically in:

```text
Plugins/RepairChest/repair.db
```

On startup, missing or replaced chests are cleaned out of the database so dead entries do not stick around.

## Source structure

Flat package `de.mahagst.risingworld.repairchest` -- one Maven project, one plugin JAR.

```text
RepairChestPlugin.java   -- lifecycle
RepairCommands.java      -- commands + storage/sign events
RepairService.java       -- put/take/scan/repair + timers
StationRegistry.java     -- RAM index, identity, lock/sign
RepairPricing.java       -- recipe quote, gold fee, allocation
RepairRepository.java    -- SQLite
RepairSettings.java      -- all knobs (defaults())
Messages.java
RepairStation.java
WhitelistEntry.java
```

Pricing uses live API recipes plus a flat gold fee. Startup replaces the whitelist from `RepairSettings` seeds. OZ Tools is compile-only; runtime stays standalone.

## Verify

Run `mvn -B clean verify`. Tests cover rounding, the 15% boundary, gold fee, variants/batches, missing recipes, allocation and repair-before-consume. In-game smoke test still needed.

## License

MIT -- see [LICENSE](LICENSE).
