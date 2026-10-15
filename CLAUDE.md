# Lazuli — how this codebase works

Fabric client mod for **Minecraft 1.21.11** (Mojang mappings, Java 21 target): a faster, smarter Baritone alternative.
Repo: github.com/boundedxd/lazuli, branch `feat/lazuli-plan1` (main is `master`). Baritone is a reference only.

## Build, test, deploy (do this after every change)
```bash
export JAVA_HOME="/c/Program Files/Java/jdk-25.0.2"
./gradlew :core:test                 # full suite, ~2.5 min, must stay green
./gradlew :core:test --tests '*Name*' # one class
./gradlew :mod:build -x test
cp mod/build/libs/lazuli-0.1.0.jar "/c/Users/colin/AppData/Roaming/PrismLauncher/instances/1.21.11(1)/minecraft/mods/"
```
ALWAYS copy the jar to the Prism mods folder after a build. Commit + push to `feat/lazuli-plan1` when a feature is done.
Gradle prints "BUILD SUCCESSFUL" fast (warm daemon); check `core/build/test-results/test/TEST-*.xml` if unsure tests ran.

## Two modules
- **`core/`** — pure Java, NO Minecraft classes. Everything that can be unit-tested lives here: planner, physics sim,
  movement controllers, goals, commands, settings, inventory click planning, tasks.
- **`mod/`** — the Fabric side: reads the game world into core's `BlockView`, turns core `Controls` into key presses
  and rotation, mixins, config screen (WalksyLib), processes that need the game (mining, chests...).
Keep MC-specific code in `mod/` (a port to 26.x is planned later).

## How a trip works
1. `#goto x y z` → `core/command/CommandHandler` → `core/session/PathingSession.start(goal, fallback, playerNode)`.
   `start` returns a **trip number**; every `Snapshot(state, path, goal, version, trip)` carries it.
2. `PathingSession` runs on a single worker thread (`lazuli-planner`). It owns `planner/LazyDStarLite`
   (backward D* Lite with lazy edge evaluation) over `move/MoveGraph` (nodes = standable block positions, edges =
   walk/jump/fall/climb/parkour/mine/place moves). Optimistic costs first, then the first `evalHorizon` edges are
   physics-checked (`move/JumpEvaluator` etc. simulate with `physics/PlayerSim`). Far goals go in 64-block segments.
   States: IDLE, PATHING, ARRIVED, FAILED. `fallback=true` retargets to the nearest safe spot if unreachable.
3. Each client tick `mod/control/PathExecutor` follows the published path: `core/control/PathTracker` finds where on
   the path the player is; specialists produce `Controls` — `Jumper` (hard/long jumps & neos, plays a proven script),
   `Climber` (ladders/vines), `Builder` (bridging incl. eagle/ninja, pillaring, mining in the way, via `mod/control/Interactor`),
   else `InputPlanner` (MPC: simulates candidate inputs a few ticks ahead and picks the best), `airBrake` mid-air.
   `EdgeWatch` reports a failed edge → `session.edgeFailed` (3 misses before it's impossible).
4. `mod/control/InputDriver` + `RotationDriver` apply `Controls` (keys via `KeyboardInputMixin`, smooth capped turning;
   `freeLook` turns server-side only).
5. World changes: `ClientLevelMixin`/chunk events → `world/WorldCache` (`BlockKind` + `BlockPhysics` per block, built by
   `mod/world/BlockClassifier` / `ChunkScanner`) → `session.regionChanged` → incremental repair.

## Key core pieces
- `physics/PlayerSim` — tick-accurate port of vanilla player movement (collision, sprint-jump, sneak edge, ice, water,
  ladders). `Trace` = recorded real-game ticks (`#record`, files in the Prism instance `config/lazuli/traces/`);
  `TraceReplayTest` checks the sim against them.
- `move/Standing` — what counts as a node and where the feet are (slabs, fences at +1.5, etc.).
- `move/JumpEvaluator` / `JumpLine` — parkour proof: from standing at the take-off block's center (Markov rule).
- `goal/*` — GoalBlock, GoalXZ, GoalYLevel, GoalNear, GoalAdjacent (next to a block), GoalAny.
- `settings/Settings` — every option (`Setting<T>`), also reachable with `#set name value`.
- `task/Task`, `Tasks` — one job at a time with a `CompletableFuture<Result>` (DONE/FAILED/CANCELLED).
  `Task.trip(...)` resolves from snapshot trip numbers. Built so `#getitem` can chain steps.
- `inventory/Stack, Click, Menu, Moves` — a model of an open menu with vanilla click rules; `Moves.transfer/drop` plan
  click lists (shift-click whole stacks, split remainders) and are tested in `MovesTest`. `ItemCount` = item + count.
- `craft/Recipes` (parses recipe/tag JSON, own tiny `Json`), `CraftTree.resolve` (steps in order, ingredients first,
  mixes plank kinds; `left()` chains requests, `missing()` says what's lacking), `Crafter.fill` (grid click plan).

## Key mod pieces
- `LazuliClient` — wires everything: session, executor, processes, commands context, tick order, the API impl (`Api`).
- `dev/lazuli/api/Lazuli` — public API for other mods: `Lazuli.get().goTo/mine/container/drop/craft/smelt/stop/busy/command`.
- `process/*` — `Process` = something running on top of pathing (one at a time): MineProcess (in control/),
  Follow, Explore, Tunnel, Farm, ContainerProcess (#deposit/#withdraw), CraftProcess (#craft), SmeltProcess (#smelt), Upkeep (auto eat/armor). `Trip` = a quiet trip.
- `inventory/Inventories` (game menu → core `Menu`, item keys like `minecraft:dirt#<componentsHash>`),
  `ClickDriver` (sends planned clicks, `clicksPerTick` per tick).
- `control/Aim` — legit looking: find a visible point, turn at rotationSpeed, act only when the crosshair is on it
  (`breakTick`, `useOn`).
- `config/LazuliConfig` — WalksyLib screen, tabs: Movement, Building, Inventory, Survival, Processes, Controls,
  Planner, Visuals. Every new feature needs a toggle here and in `Settings`.

## Testing style
- Closed-loop sim courses: `CourseTest.drive(...)`, `InteractionCourseTest.drive(...)` (with `SimHands` for
  place/mine), `ParkourCourseTest`, built on `testutil/GridWorld` (`fill(x1,y1,z1,x2,y2,z2,kind)`, `setPhysics`).
  Assert `arrived()`, `lowestY()` (never fell), ticks, `turned()`.
- `core/src/test/.../probe/*` and `RealWorld*Test`, `AnvilWorld` are **untracked scratch** — don't commit unless asked.
- Write the failing test first, then the fix.

## Gotchas
- Python edit scripts: a `\n` inside a Java string literal becomes a real newline — use `System.lineSeparator()`.
- Bash heredocs with apostrophes inside python blocks broke once; prefer the Write/Edit tools for big files.
- Real game: sneak slowdown lingers 1–2 ticks after release (Jumper waits 3 ticks after sneaking before a jump).
- Chat formatting: `Chat.ok/err/warn/info("text {}", arg)`.

## User preferences (important)
- Talks casually; wants short answers and **one short plan, not many** ("DONT WRITE 1293 PLANS FOR ONE THING").
- **At most ONE Sonnet subagent** if any.
- Don't over-optimize planning speed; ask before long benchmarks; don't build `#sel`/`#build` yet.
- Everything must be toggleable in config.

## Roadmap (the "mission"), in order — status
1. Eagle bridge — DONE. Tasks + public API + config tabs — DONE. Inventory clicks, `#drop`, `#deposit`/`#withdraw`
   (chests single/double, barrels, shulkers, ender chests) — DONE, tested in game; multi-item (`#drop chest 1 oak_log 3`).
2. Crafting — DONE, **not yet tested in game**: `#craft item [count]...`. Vanilla recipes + item tags are read from
   the game jar (`mod/craft/RecipeData`, `VanillaPackResources`; servers only send unlocked recipes). `CraftProcess`:
   2x2 in the inventory (clicks on menu 0, no screen), else walks to / places a crafting table.
   Smelting — DONE, **not yet tested in game**: `#smelt item [count]`, `#smelt collect`. `core/craft/Smelting`
   (job: input/fast furnace, fuel plan from the `smeltFuels` list), `mod/process/SmeltProcess`; `Station` = shared
   find/place/walk/open for crafting tables and furnaces. `openInventory` toggle: `ClickDriver` stands still and opens
   the inventory screen for own-inventory clicks (tool/armor swaps, #drop, 2x2 crafting).
3. `#getitem <item>`: resolve item → mine/craft/smelt tree, subtract current inventory (has stone pick → skip ahead),
   run as a chain of Tasks.
4. Water bucket clutch, boat clutch, auto boats over X water blocks (needs boat physics), minecarts.
5. Villager trading, chat/command use.
6. Extras approved: stash/chest memory, nether travel, survival safety (flee/fight, low-HP retreat, sleep, `#back`),
   waypoints.
Backlog: neo rework (double/triple neos, edge/run-up starts), parkour onto open trapdoors/fence 3-gap,
TraceRecorder should record sneak/pitch. Known bug (skipped, user thinks other mods): changing config sometimes stops
pathing from starting until restart.
