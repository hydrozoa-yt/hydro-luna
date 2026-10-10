# Plan: Al Kharid toll gate (Lumbridge <-> Al Kharid)

Status: **implemented and verified, not committed.** Branch: `al-kharid-gate`.
Decided: the gate is not part of the door system, the `Doors` swing helper is made `internal` and reused, **bots are not touched**,
and the player **walks** across with a forced walk step (no slide, no teleport).

## As built (read this first; the design sections below are the plan and differ in the places listed here)

Files:
- `src/main/kotlin/game/game/npc/spawn/alKharid/BorderGate.kt` (new): everything, as `object BorderGate`.
- `src/main/kotlin/game/game/npc/spawn/alKharid/registerBorderGate.kts` (new): the four registrations.
- `src/main/kotlin/game/game/obj/doors/Doors.kt`: `Swap` and `doubleSwap` are `internal` (nothing else changed).
- `src/main/kotlin/engine/engine/obj/deletePeskyObjects.kts`: the two "Al Kharid gates" positions removed. **The same file also
  holds the user's uncommitted edit (the loop is commented out), so stage hunks separately.**
- Tests: `src/test/kotlin/game/npc/spawn/alKharid/BorderGateTest.kt` (10), `src/test/java/io/luna/game/model/collision/TollGateCollisionTest.java` (5).

Differences from the plan below:
1. **Logic is split out of the script** (`BorderGate.kt` + `registerBorderGate.kts`), the pairing of `Doors.kt` + `registerDoors.kts`.
   The plan allowed this for testability. The script cannot be called `borderGate.kts`: Kotlin compiles it to a class named
   `BorderGate`, which clashes with the object.
2. **The crossing is a weak `Action`, not a `LockedAction`.** `NavigationAction` (what walks the player to the gate row) is weak, and
   `ActionQueue.process` interrupts every weak action each tick while a strong action exists, so a `LockedAction` would cancel its own
   walk. The crossing itself is one forced step walked in the tick that opens the gate, so nothing needs locking. A weak action also
   cancels itself cleanly if the player walks away or opens another interface, before anything is charged.
3. **No closing sound, and the open sound is only heard by the payer.** `GRATE_OPEN` is played to the paying player, as `Doors` does
   for whoever clicks a door, and as the reference does. Hearing the gate (and the gate closing by itself) as a nearby player would
   need an area sound (`LocalSound`, used by the magic code); that was not part of the plan, so it is left as an open question.
4. The `TODO` on `isFree` currently reads `TODO@` (the version was removed by the user after it was written as `TODO@1.0`).
5. The gate and guard handlers use a guard-or-gate `talk(plr, guardId)` / `useGate(plr)` pair; `cross(plr, free)` is public so the
   quest, or later bot work, can call it.

Verification (all against the real server, in a scratch copy of `data/` on another port with no background bots, with bots standing
in for players and driving the real input pipeline: object and NPC clicks, option buttons):
- West side, gate click, pay: crosses on the same tick the gate opens, 15 coins become 5, gate restored exactly 3 ticks later.
- East side, guard click from a few tiles away, exactly 10 coins: walks to the nearest gate row, crosses, 0 coins.
- East side, gate click from (3268,3226), which is not a gate row: steps onto row 3227 first, then crosses.
- 9 coins, decline, "where does the money go": nothing taken, nothing moves, gate never opens.
- Two payers in the same tick: the second waits until the gate is shut again, each paid exactly once.
- While the gate is open, east-west movement is blocked for everybody else (the blockers), and the open leaves are exactly
  1562 at (3267,3227) facing south and 1563 at (3267,3228) facing north, with object 83 walls on the original tiles.
- Missing leaf: told it is stuck, nothing charged, nothing moved. Walking away on the way to the gate cancels it with nothing charged.
- Running on: still exactly one tile.
- `./gradlew test` passes in full.

Things the harness cannot show, to check in a real client:
- The open leaf spawning on the tile the player is standing on (the lane start tile), `Doors` only special-cases diagonal doors.
- The walk animation, the sound, and whether 3 ticks looks right for the gate staying open (`OPEN_TICKS` in `BorderGate.kt`).

Noticed along the way, not part of this change:
- Startup logs `NORTH_FALADOR_CHAOS_TEMPLE overlaps LVL_20_WILDERNESS_CHAOS_TEMPLE` from `SubZone.findAreaOverlaps` (a background
  loader thread exception) on the current branch, unrelated to the gate.
- `BotReflex` auto-continues player, NPC and text dialogues but not option dialogues, so a bot that opens this conversation stops at
  the options.
- Bot clicks on an NPC do not walk to it, bots walk separately (`navigator`) and then click.

## Goal

Four Border Guards stand at the gate between Lumbridge and Al Kharid, two on each side. Talking to a guard, or using the gate
itself, starts a conversation about the 10 coin toll. If the player pays (later also: if they are a "friend of Al Kharid"), the gate opens and
the player walks through. Nobody else can slip through while it is open. The gate swings shut again by itself.

## Facts (verified against this repo, the 377 cache, and the LostCity 377-wip content)

| Thing | Value | Source |
|---|---|---|
| Gate leaves (closed) | `2882` at (3268,3227) and `2883` at (3268,3228), both `STRAIGHT_WALL`, direction `WEST`, z=0 | cache probe (scratchpad harness, `MapDecoder`) |
| Gate leaves (open) | `1562` (for 2882) and `1563` (for 2883). They are not placed on the map, only used as the open state | LostCity `border_gate_toll_left/right` `next_loc_stage` |
| Guards | `925` = Lumbridge side (west), `926` = Al Kharid side (east), both with a single "Talk-to" option | LostCity `pack/npc.pack`, `all.npc` |
| Guard spawns | 925 at (3267,3226) and (3267,3229); 926 at (3268,3226) and (3268,3229) | `data/game/world/npc_spawns.jsonc` (~line 125599) |
| Invisible blocker | object `83` (`inviswall`): `solid`, `impenetrable`, no model | `data/dumps/objects.json`, LostCity `pack/loc.pack` |
| Toll | 10 coins (item `995`), free after Prince Ali Rescue | task + LostCity |
| Side of the gate | `x < 3268` is Lumbridge, `x >= 3268` is Al Kharid | LostCity script, guard spawns |
| Door data | 2882/2883/1562/1563 appear in **none** of `data/game/world/doors/*.json`, so the Doors plugin ignores them and will keep ignoring them | grep |
| Quests | Luna has no quest system (only `QuestJournalInterface`), so there is no Prince Ali Rescue state to read | grep |
| `deletePeskyObjects.kts` | lists (3268,3227)/(3268,3228) as "Al Kharid gates" to delete for bots. The deleting loop is **commented out in the working tree** (uncommitted change by the user), so the gates currently exist | `git diff` |
| Script compilation | `.kts` files are compiled in the same Gradle module as `.kt` files (`-Xallow-any-scripts-in-source-roots`), so `internal` Kotlin members are visible to them | `build.gradle.kts` |
| Forced step | `WalkingQueue.addStep(Direction)` does no collision check, and `WalkingQueue.allowsStep` only ever refuses NPCs that have a non-`NORMAL` route strategy, so a player's queued step always happens | `WalkingQueue.java` |
| Locks | `Mob.lock()` (what `LockedAction` uses) drops the player's incoming client messages (`GameMessageReader`) and blocks logout, but does **not** stop the walking queue (that is a separate `walking.isLocked`) | `Mob.java`, `GameMessageReader.java`, `LogoutService.java` |
| Tick order | `World.preSynchronize` runs `actions.process()` then `walking.process()` for each player, so a step queued by an action is walked in the same tick | `World.java` ~line 425 |

Reach rules for a west-facing straight wall (`CollisionMatrix.reachedWall`): a player can use leaf (3268,y) from (3267,y), from
(3268,y) itself, and from (3268,y+1) or (3268,y-1) when those tiles have no wall between them and the leaf. So on the Lumbridge
side the player is always on the gate row (y=3227 or 3228), on the Al Kharid side they may be on rows 3226..3229.

### Key observation: the swing is the double-door swing

The open placements in the reference are: left leaf (2882) moves one tile west and turns to `SOUTH`, right leaf (2883) moves one
tile west and turns to `NORTH`. That is exactly what `Doors.doubleSwap` computes for a `LEFT`/`RIGHT` leaf (open offset (-1,0)
for a west wall, then a quarter turn counter-clockwise for the left leaf and clockwise for the right). The open leaves end up at
(3267,3227) facing south and (3267,3228) facing north. They only wall off the north and south edges, so the east-west corridor
through the former gate line is open.

## How LostCity does it (the reference, `scripts/areas/area_alkharid/scripts/border_gate.rs2`, 377-wip)

Not copied, only the behaviour:

1. Gate click: find a border guard next to the player on the player's side (west guard 925, east guard 926), then start the
   guard's conversation. Guard click: same conversation.
2. Conversation (file lines ~26-54): the player asks to come through. If the quest flag is set the guard waves them through for
   free. Otherwise the guard states the toll and the player picks one of three options: decline (guard shrugs it off), ask where
   the money goes (guard answers, conversation ends), or agree. Agreeing with fewer than 10 coins gets an "I don't have enough"
   line. Otherwise a payment message is shown and 10 coins are removed.
3. Passing: the player is first moved onto the gate row on their own side (`~forcemove`), then both leaves are swapped for their
   open versions, and the invisible wall is placed on the **original** leaf tiles so nobody can walk through. The open leaves and the
   blockers both last 3 ticks. The player takes the single step to the other side while the grate-open sound plays.
4. Guards randomly turn to face neighbouring tiles on a timer (cosmetic, see "Out of scope").

### Forced walking in the reference

There is no dedicated "forcewalk" packet in the 377 protocol. A normal walk is the movement section of the player-update packet,
and the exact-move is a separate update block. The reference keeps the two apart on purpose: the comment above its
`agility_force_move` proc says a forced walk "isn't the same as an exactmove". Its `~forcemove` / `~forcewalk` / `~agility_walk`
procs (`scripts/skill_agility/scripts/agility.rs2`) move the player one tile per tick with an unclipped `p_teleport` to the
adjacent tile, described as "p_teleport but doesn't telejump from a distance", so the client sees an ordinary walk step with the
normal walking animation. The gate's own crossing is one such step.

Luna's equivalent is `plr.walking.addStep(direction)`: the same single unvalidated walk step, rendered by the same movement update
as any walk, with the normal walk animation. `ExactMovement` / `ExactMovementAction` (used by the agility courses) is the other
mechanism and is not used here.

## Design

### Where it lives

One new script: `src/main/kotlin/game/game/npc/spawn/alKharid/borderGate.kts` (package `game.npc.spawn.alKharid`). It sits next to
`zeke.kts`, `louie.kts` etc., the Al Kharid scripts of the existing "Generic NPC spawns" plugin, so there is **no new plugin and no
`info.plugin.kts`**. LostCity files this under `area_alkharid` too. Following how the other scripts here work, the ids are plain
constants at the top of the script.

If the crossing logic turns out to be worth JUnit coverage, split it into a `BorderGate.kt` beside the script (the same pairing as
`Obelisk.kt` + `activateObelisk.kts`); the default is a single script.

### Borrowing the swing from `Doors` (the only change outside the script)

The script needs the open placement of each leaf. Rather than repeating the offset and rotation rules, call the existing one:

- `Doors.kt`: change `private class Swap` and `private fun doubleSwap` to `internal`. No behaviour change, no new state, nothing
  registered, no JSON, no click handlers.
- The script builds two **unregistered** `DoorType`s, `DoorType(2882, 1562, DoorSide.LEFT, null, null, null)` and
  `DoorType(2883, 1563, DoorSide.RIGHT, null, null, null)`, and calls `Doors.doubleSwap(leaf, type, opening = true)` on each live
  leaf to get the new id, position and direction. Closing needs no calculation: the script keeps the two original objects and puts
  them back exactly as they were.

### What the script contains

- Constants: `TOLL = 10`, `COINS = 995`, guard ids 925/926, leaf ids 2882/2883 and 1562/1563, gate x 3268, rows 3227/3228, blocker
  id 83, `OPEN_TICKS = 3`.
- `fun isFree(plr: Player): Boolean`, a **stub that returns `false`** with a `TODO` (in the repo's `TODO@<version>` style, version
  chosen when it is written) saying it must return `true` once Prince Ali Rescue is complete. Prince Ali Rescue does not exist
  yet, so there is nothing to read. No attribute and no dev command are added now. The free branch of the dialogue and the `free`
  flag of the crossing are still wired up, so the quest only has to change this one function.
- `sideOf(position)`, the dialogue builder, the gate swap (open + restore), the crossing `LockedAction`, and the registrations
  `object1(2882)`, `object1(2883)`, `npc1(925)`, `npc1(926)`.
- A script-level busy flag for the single gate.

### The gate swap

`openGate()`:

1. Look up both closed leaves in the world (`world.objects.findAll(pos)` filtered by id 2882 / 2883). If either is missing (already
   open, or deleted) return `false` with no side effects, so nothing has been charged.
2. Compute the two open placements with `Doors.doubleSwap`.
3. Remove both closed leaves, add the two open leaves, and add the blockers `world.addObject(83, (3268,3227), STRAIGHT_WALL,
   WEST)` and the same at (3268,3228) on the original tiles. Keep references to the objects that were added.
4. Play `Sound.GRATE_OPEN`, set the busy flag, and schedule one task `OPEN_TICKS` later.

The restore task: remove the blockers and the open leaves, put the two original leaves back, play `Sound.GRATE_CLOSE`, clear the
busy flag. This is one task owned by the script, so the gate cannot be left half-open by anything else; it does not depend on any
player still being logged in.

### The crossing (a `LockedAction`)

Entry: the dialogue's agree branch (after a "Yes, ok." line, using `.then { }` so it fires when the player continues), or the
free-pass branch. State machine, one `run()` per tick (`Action` is instant by default, so the first run is on the click tick). The
action must **not** clear the walking queue on lock the way `ExactMovementAction` does, because step 1 uses it.

1. **APPROACH**: lane = player's y clamped to 3227..3228; start tile = (3267|3268, lane) by side; destination = the same lane on
   the other side. If not on the start tile, `navigator.navigate(start, async = false)` once, then poll until there. Give up
   after ~20 ticks with a message.
2. **WAIT**: while the gate is busy (another player is crossing), wait, up to ~10 ticks.
3. **CROSS**, all in one tick on the game thread, in this order: (a) unless free, check `plr.inventory.computeAmountForId(995) >=
   TOLL`, otherwise stop with the not-enough-money line; (b) `openGate()`, stop if it returns `false`; (c) unless free,
   `remove(Item(995, TOLL))` and send the payment message; (d) `plr.walking.addStep(Direction.EAST)` from the Lumbridge side or
   `Direction.WEST` from the Al Kharid side. **Do not rely on `remove`'s return value as the check:** `ItemContainer.remove(Item)`
   on a stackable with fewer than the requested amount clears the whole stack and still returns `true` (a player with 5 coins would
   lose all 5). Nothing can change between (a) and (c), so no refund path is needed.
4. **FINISH**: each following tick until `plr.position == dest` (a couple of ticks at most), then unlock.

The step goes straight through the blockers because `addStep` does not look at collision, and the blockers only stop everyone else
(pathfinding and other mobs). A step is exactly one tile: the gate line is the boundary between x=3267 and x=3268, so the whole
crossing is a single step. The player's own walk animation plays; no model override is needed.

Timeline with `OPEN_TICKS = 3`: tick 0 the leaves swap, the blockers go in and the step is walked (actions run before the walking
queue, so the step happens that same tick); tick 1 the player is on the far side and unlocks; tick 3 the gate closes and the
blockers go. That matches the 3 ticks of the reference with a tick to spare after the step; confirm visually and tune.

**The toll is taken only once the gate has really opened.** If the walk to the gate fails, the gate is busy for too long, or the
leaves are missing, nothing was charged, so no refund logic is needed.

Why blockers plus a forced step, rather than letting the player path through: collision is per-tile, not per-player, so there is
no way to let only the payer through an open gap. The blockers use the same wall type, direction and tile as the closed leaves, so
collision is identical to the closed gate, and the payer's forced step ignores collision on purpose.

### Dialogue

Opening: the gate click and the guard click both call the same function. Gate click picks the guard id by the player's side;
guard click uses the clicked guard. Both make a nearby guard of that side face the player if one exists (`npc.interact(plr)`), but
the conversation does not depend on finding one.

Flow (wording follows the reference file lines ~26-54 and the 377 game; it is not copied into this document):

```
player asks to come through
  isFree(plr)  -> guard waves them through -> cross(plr, free)
  else         -> guard states the 10 coin toll
                    options (3):
                      decline          -> player declines, guard accepts
                      where does it go -> player asks, guard answers (ends)
                      agree            -> player agrees
                                          coins < 10 -> "not enough money" line (ends)
                                          else       -> cross(plr, paid)
```

`options(...)` takes callbacks; each branch builds its own `plr.newDialogue()...open()` as `Slayer.kt` and `magicStoreOwner.kts`
do. The payment message is sent when the coins are actually taken (step 3 above), not during the dialogue.

### Edge cases

| Case | Handling |
|---|---|
| Two players pay at once | Second waits on the busy flag, then opens it itself. Never charged until its own open. |
| Gate leaves missing/deleted | `openGate()` returns `false` before changing anything or charging; the player is told the gate is stuck. |
| Player walks away mid-dialogue | Dialogue closes, nothing happened, nothing charged. |
| Player logs out mid-crossing | The restore task belongs to the script, not the player, so blockers and busy flag are always cleared. Verify what happens to an in-flight `LockedAction` on logout when testing (logout is blocked while locked). |
| Another player or NPC walking into the open gap | Blocked by the blockers for the whole window. |
| Player on the Al Kharid side at (3268,3226) or (3268,3229) | Reach rules allow it; lane is clamped to the nearest gate row and the player walks 1 tile first. |
| Running enabled | One queued step is walked as one step. `WalkingQueue.process` debits run energy before it checks for a second step and then restores it when there is none, exactly as for any single-tile walk while running; no special handling needed. |
| `addStep` walk history | `WalkingQueue.addFirst` searches recent history for the destination tile; the destination is never a tile the player just walked, so it falls through to a plain one-step path. Confirm in testing. |
| Client not drawing the new leaf under a standing player | `Doors` only special-cases diagonal doors for this; check the straight leaf on the start tile in manual testing. |
| Server restarts while the gate is open | Objects are not persisted, so the map's closed leaves return on startup. |

## Files

Create:
- `src/main/kotlin/game/game/npc/spawn/alKharid/borderGate.kts`

Modify:
- `src/main/kotlin/game/game/obj/doors/Doors.kt`: `Swap` and `doubleSwap` from `private` to `internal`.
- `src/main/kotlin/engine/engine/obj/deletePeskyObjects.kts`: remove the two "Al Kharid gates" positions. **Coordinate with the
  user:** that file has an uncommitted edit of theirs (loop commented out), so stage only my hunk, or let them commit theirs first.

Not touched: `DoorType.kt`, `registerDoors.kts`, `data/game/world/doors/*.json`, any `info.plugin.kts`, anything under bots.

## Phases

1. **Swing helper + skeleton**: visibility change in `Doors.kt` (own commit), then the script with constants, `sideOf`, the `isFree`
   stub, and handlers that only log. Confirm in a harness that `Doors.doubleSwap` on the two live leaves returns 1562 at
   (3267,3227) `SOUTH` and 1563 at (3267,3228) `NORTH`.
2. **Gate swap**: `openGate()`, blockers, restore task, busy flag. Test with a dev command or the handler, no dialogue yet.
3. **Crossing**: the action, the toll handling and the forced step. Test with `::item 995 10`, `::moveto 3267 3227`, then click
   the gate.
4. **Dialogue**: all branches, both entry points, both sides.
5. **Cleanup**: remove the `deletePeskyObjects.kts` entries, KDoc pass.

Each phase is a discrete commit (per `CONTRIBUTING.md`). I will not push.

## Testing

- **Collision unit test** (cheap, no cache): build `CollisionMatrix` state with `CollisionUpdate` for the four gate states (closed,
  open, open + blockers, closed again) and assert east-west `untraversable` on rows 3227 and 3228 is blocked, open, blocked,
  blocked. This pins down the claim that the open leaves leave the corridor free and the blockers close it. The analysis from
  `CollisionUpdate.object` already says it holds (wall west on the leaf tile plus wall east on its western neighbour; open leaves
  only wall the south/north edges), so this is regression cover.
- **Scratchpad harness** for the swing placement (phase 1). If the logic is split into `BorderGate.kt`, Mockito tests like
  `NpcCombatEngagementTest` for `sideOf`, lane clamp, and the toll rules (exactly 10 coins removed; none removed on
  decline, where-does-it-go, or not-enough, including a player holding 1..9 coins; toll taken only when the gate actually opened).
- **In-server checklist** (Corretto 21; if the user's server is running, build with an init script that redirects `buildDirectory`
  into the scratchpad so `build/` is untouched): pay from both sides and both rows; the payer walks through while the blockers are
  in place; with 9 coins; decline; two accounts crossing at once; a second player trying to
  walk through during the open window; walk animation looks right (and with running on); leaf visuals under a standing player;
  sound; gate state after the window (back to 2882/2883 at their original tiles, no stray object 83).
- Existing suites (`./gradlew test`) must stay green, in particular anything touching `Doors`.

## Bots (untouched, for information only)

No bot code changes. Because the gates are walls again once they are no longer deleted, bot walking routes through them fail to
find a path (for example `TeleportTravelStrategy(LUMBRIDGE)` for `Zone.AL_KHARID` teleports to Lumbridge and then calls
`WalkingTravelStrategy`, which cannot route to Al Kharid). That is accepted here; the webwalker plan's door/gate edges are where it
would be handled later.

## Decisions

Confirmed:
1. Reuse `Doors.doubleSwap` by making it (and `Swap`) `internal`.
2. Do not touch bots.
3. The player walks across with a forced walk step, not a slide or a teleport.

4. `isFree()` is a stub that returns `false` with a `TODO` until Prince Ali Rescue exists. No attribute, no dev command. Because of
   that the free branch cannot be reached in a running server, so it is only exercised by temporarily patching the stub while
   testing (or by a unit test if the logic is split into `BorderGate.kt`).

Still defaulted (say if you disagree):
5. **Out of scope**: the guards' idle "look around" behaviour, the "Pay-toll" extra option from later RS versions, combat with the
   guards (they only have "Talk-to").
