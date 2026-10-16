# Lazuli — how this codebase works

Fabric client mod for **Minecraft 1.21.11** (Mojang mappings, Java 21 target): a faster, smarter Baritone alternative.
Repo: github.com/boundedxd/lazuli, branch `feat/lazuli-plan1` (main is `master`). Baritone is a reference only.

## Build, test, deploy (do this after every change)
```bash
export JAVA_HOME="/c/Program Files/Java/jdk-25.0.2"
./gradlew :core:test                 # full suite, ~2.5 min, must stay green
./gradlew :core:test --tests '*Name*' # one class
./gradlew :mod:build -x test
cp mod/build/libs/lazuli-0.1.0.jar "/c/Users/colin/AppData/Roaming/PrismLauncher/instances/1.21.11(2)/minecraft/mods/"
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
- `dev/lazuli/api/Lazuli` — public API for other mods: `Lazuli.get().goTo/mine/container/drop/craft/smelt/getItem/stop/busy/command`.
- `process/*` — `Process` = something running on top of pathing (one at a time): MineProcess (in control/),
  Follow, Explore, Tunnel, Farm, ContainerProcess (#deposit/#withdraw), CraftProcess (#craft), SmeltProcess (#smelt), GetItemProcess (#getitem), Upkeep (auto eat/armor). `Trip` = a quiet trip.
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
- Don't over-optimize planning speed; ask before long benchmarks.
- Everything must be toggleable in config.

## Goal
A legit-looking, fast Baritone replacement that can do whole survival jobs on its own: get any item from scratch
(mine, craft, smelt, trade, hunt, brew, enchant), move items between chests, travel (walk, parkour, bridge, boat,
elytra) and stay alive (eat, armor, flee/fight, clutch). Port to MC 26.x later.

## Where features live (command -> code)
- `#goto`/`#path` -> PathingSession + PathExecutor (above). Far `#goto` may fly: `mod/process/ElytraProcess`,
  `core/control/ElytraPilot`, `core/physics/ElytraSim`.
- `#mine` -> `mod/control/MineProcess` (strip-mines at `core/mine/OreDepth` via TunnelProcess when none known).
- `#deposit`/`#withdraw`/`#drop`/`#find` -> `mod/process/ContainerProcess`, chest memory `core/storage/ChestMemory`
  + `mod/inventory/ChestWatcher`; taking from remembered chests for other jobs: `mod/process/ChestTake`.
- `#craft` -> `mod/process/CraftProcess` + `core/craft/CraftTree`/`Crafter`; recipes from the game jar `mod/craft/RecipeData`.
- `#smelt` -> `mod/process/SmeltProcess` + `core/craft/Smelting`. Tables/furnaces/stands/anvils: `mod/process/Station`.
- `#getitem` -> `mod/process/GetItemProcess` + `core/craft/ItemPlanner` (mine/smelt/craft/trade/kill steps, `Drops`,
  `MobDrops`, `ItemSets`, `ItemRequest`).
- `#trade` -> `mod/process/TradeProcess`, `core/trade/*`, `mod/inventory/TradeWatcher`.
- `#brew` -> `mod/process/BrewProcess` + `core/craft/Brewing`. `#enchant`/`#repair`/`#combine` -> `mod/process/EnchantProcess`
  + `core/craft/Enchanting`.
- Boats -> `mod/control/BoatRider` (place/mount waits out lag; drives with `IceRacer` over `TrackField.water`, a
  water-only field that skips 1-wide channels; `BoatCrossingTest`), `core/move/Boating.fits`. Water clutch -> `mod/control/Clutch`.
- Safety -> `mod/process/Guard` (creepers, fight, low HP, fight-or-flee via `core/control/Threat`), `mod/control/Combat`
  (bow with `core/control/Archery` aim, shield), `Upkeep` (eat, armor, totem/shield offhand), `SleepProcess`.
  Paths avoid mobs/lava edges: `core/move/Hazards` priced in `MoveGraph.danger`, fed by `Guard.hazards` every 10 ticks
  through `PathingSession.setHazards`.
- `#portal` / far `#goto` through the nether -> `mod/process/PortalProcess`, `core/portal/PortalFrame` (build site +
  frame), `NetherRoute` (worth it?), `PortalMemory` (portals seen, `config/lazuli/portals/`).
- `#breed` -> `mod/process/BreedProcess`; `#fish` -> `mod/process/FishProcess` (bite flag via `FishingHookAccessor`).
- `#iceboat` -> `mod/process/IceBoatProcess`; `core/physics/BoatSim` (vanilla boat tick), `core/race/IceRacer` (beam
  search over BoatSim, one-tick key delay), tracks `core/race/TrackField` (from blocks: drops, void jumps, lap seam) or
  `RecordedLine` (driven once, `config/lazuli/iceboat/`). Courses drawn as ASCII maps in `RaceCourseTest`.
- `#build <file>` -> `mod/process/BuildProcess.startSchematic` (per-spot block states), `core/schematic/Schematic`
  (.litematic/.schem/.nbt via its own `Nbt` reader). `#getitem ... dry` -> `GetItemProcess.dryRun` + `core/craft/PlanEstimate`.
- `#queue` / `#routine` -> `core/task/RoutineRunner` (step done = busy then idle), `Routines` (`config/lazuli/routines.txt`).
- HUD -> `mod/render/LazuliRenderer` (Material 3 card via `Material`), `Minimap`.
- Others: Follow, Explore, Tunnel, Farm, Hunt processes in `mod/process/`; chat commands from other players `mod/ChatControl`.
- Item keys (`minecraft:dirt#hash`, potions `@minecraft:swiftness`) -> `core/inventory/ItemKeys`, `mod/inventory/Inventories`.
- `#sel pos1|pos2|fill|clear|reset` -> `core/area/Selection`, `mod/process/BuildProcess` (box drawn by `LazuliRenderer`).
- `#sort` -> `core/storage/SortPlan`, `mod/process/SortProcess` (reads each box via `ContainerProcess.onOpen`).
- Shulker boxes -> `mod/process/ShulkerUnpack` (place, take, pick up; `Station.startPlaced`), chest memory names
  `shulker>id` (`ItemKeys.inShulker`); used through `ChestTake`. Restock -> `core/inventory/Restock`,
  `mod/process/RestockProcess` (pauses the job like Guard).

## More gotchas
- Restart the game after every deploy: copying the jar over a running game crashes it on the next class load.
- Vanilla source when needed: decompile from the loom jar with vineflower (in `~/.gradle/caches/modules-2`).
