# RepairChest

Server-side Rising World plugin that turns a registered chest and a linked sign into a repair station.

Players put a damaged whitelisted tool and the materials into the chest. After a short pause the sign shows what is still missing. When everything is present, the chest locks briefly, restores durability, removes only the required stacks, and unlocks. Players do not need to install anything.

## How it works

1. Place an allowed storage chest (skull / gold / silver / armored) and a sign nearby.
2. Look at the chest and run `/make-repair-chest <NAME>`.
3. Look at the sign and run `/make-repair-sign <NAME>` (same name).
4. A player puts a damaged whitelisted item into the chest (mining drill, chainsaw, trimmer, bow, or crossbow).
5. After **2 seconds** without further changes, the contents are scanned.
6. Sign and chat show the missing materials -- or an error (two items, already at 100%).
7. Further puts restart the same timer.
8. When all materials are present: the chest locks for ~2s, durability is set to max, then only the required mats are removed.
9. Take the repaired item out -> station returns to idle. Leftover materials stay.

Notes:

- While idle, nothing happens unless exactly **one damaged** whitelist item is in the chest (junk, full tools, coal alone: silent).
- If two tools are put in and one is taken out again, the station wakes after the debounce.
- Gold ingots use the same staffel for **every** repair (10; below 15% durability -> 15; below 10% -> 20). Other mats are per-tool in `RepairPricing`.
- Bows (`bow1`) need yarn, lumber, any knife, and gold. Crossbows need yarn, an iron plate, any knife, and gold. The knife is a tool requirement and is **not** consumed.
- Pressing F on a registered sign (idle only) shows chat `Put your damaged Item into the Chest`. Sign text cannot be edited.
- Only the admin who runs a command gets command feedback. Station messages go to the sign and the player using the chest.

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

## License

MIT -- see [LICENSE](LICENSE).
