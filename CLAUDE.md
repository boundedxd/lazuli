# Lazuli — how this codebase works

Fabric client mod for **Minecraft 1.21.11** (Mojang mappings, Java 21 target): a faster, smarter Baritone alternative.
Repo: github.com/boundedxd/lazuli, branch `feat/lazuli-plan1` (main is `master`). Baritone is a reference only.

## Build, test, deploy (do this after every change)
```bash
export JAVA_HOME="/c/Program Files/Java/jdk-25.0.2"
./gradlew :core:test                 # full suite, ~2.5 min, must stay green
./gradlew :core:test --tests '*Name*' # one class
./gradlew :mod:build -x test
cp mod/build/libs/lazuli-0.1.0.jar "/c/Users/colin/AppData/Roaming/PrismLauncher/instances/Main/minecraft/mods/"
```
ALWAYS copy the jar to the Prism `Main` instance mods folder after a build. Commit + push to `feat/lazuli-plan1` when a feature is done.
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
- `dev/lazuli/api/Lazuli` — public API for other mods; each returns a `Task`: `goTo/mine/container/drop/craft/smelt/getItem/brew/trade/tradeSell/bucket/hunt/smith`,
  plus `stop/busy/current/command` (sort, build, portal, stronghold have no outcome to poll yet: use `command("sort")` etc.).
- `process/*` — `Process` = something running on top of pathing (one at a time, all listed in `LazuliClient.processes`): MineProcess (in control/),
  Follow, Explore, Tunnel, Farm, Breed (#breed), Fish, ContainerProcess (#deposit/#withdraw), CraftProcess (#craft), SmeltProcess (#smelt),
  SmithProcess (#smith, smithing table), BucketProcess (#bucket: fill water/lava/milk buckets, cast obsidian), HuntProcess (#hunt <item>: kills the
  mobs from `MobDrops`), GetItemProcess (#getitem), BrewProcess (#brew), TradeProcess (#trade), EnchantProcess (#enchant/#repair/#combine),
  ElytraProcess (far #goto / #fly), SortProcess (#sort), BuildProcess (#build/#sel), SleepProcess, DeathRecovery, RestockProcess, Guard (safety),
  Upkeep (auto eat/armor). `Trip` = a quiet trip. A new command needs: a case in `CommandHandler.run` (and the word in `COMMANDS`; `CommandTest` checks
  they match), a `Context` method, the override in `LazuliClient`, help text, and an API method if it makes sense.
- `core/craft/ToolChoice` — which tool mines a block (drops it, durability to spare, fastest). Full inventory while mining: `MineProcess` throws a
  junk stack out (`Tools.junkSlot`, setting `mineDropJunk`) instead of stopping.
- `inventory/Inventories` (game menu → core `Menu`, item keys like `minecraft:dirt#<componentsHash>`),
  `ClickDriver` (sends planned clicks, `clicksPerTick` per tick).
- `control/Aim` — legit looking: find a visible point, turn at rotationSpeed, act only when the crosshair is on it
  (`breakTick`, `useOn`).
- `config/LazuliConfig` — tiny: assembles the WalksyLib screen from one class per tab in `config/tabs/`
  (`MovementTab, BuildingTab, InventoryTab, SurvivalTab, ProcessesTab, ControlsTab, PlannerTab, VisualsTab`; Routines is
  built in LazuliConfig). Each tab is a list of sections (`Ui.group` open, `Ui.folded` collapsed: rare options, one per
  process in Processes, "Advanced: ..." last) made of `Ui.bool/num/dec/text/idList` widgets: one call per `Setting`, label
  from the name (`Ui.LABELS` for nicer ones), the description given there is stored on the `Setting` too (`#settings
  <filter>` shows it). Every new feature needs a `Setting` AND a `Ui` widget in the fitting tab/section; a setting with
  no widget is logged at startup ("Settings missing from the config screen").

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
- `#mine` -> `mod/control/MineProcess` (targets from `mod/world/OreIndex`, every ore seen as chunks load, kept after unload (oreMemory); strip-mines at `core/mine/OreDepth` via TunnelProcess when none known). Block searches go through `mod/world/BlockSearch` (skips chunk sections by palette, like Baritone).
- `#deposit`/`#withdraw`/`#drop`/`#find` -> `mod/process/ContainerProcess`, chest memory `core/storage/ChestMemory`
  + `mod/inventory/ChestWatcher`; taking from remembered chests for other jobs: `mod/process/ChestTake`.
- `#craft` -> `mod/process/CraftProcess` + `core/craft/CraftTree`/`Crafter`; recipes from the game jar `mod/craft/RecipeData`.
- `#smelt` -> `mod/process/SmeltProcess` + `core/craft/Smelting`. Tables/furnaces/stands/anvils: `mod/process/Station`.
- `#getitem` -> `mod/process/GetItemProcess` + `core/craft/ItemPlanner` (mine/smelt/craft/trade/kill/smith steps, plus `Kind.BUCKET` (water/lava/milk buckets) and `Kind.CAST` (obsidian) run by `BucketProcess`, `Drops`,
  `MobDrops`, `ItemSets`, `ItemRequest`).
- `#trade` -> `mod/process/TradeProcess`, `core/trade/*`, `mod/inventory/TradeWatcher`.
- `#brew` -> `mod/process/BrewProcess` + `core/craft/Brewing`. `#enchant`/`#repair`/`#combine` -> `mod/process/EnchantProcess`
  + `core/craft/Enchanting`.
- Swimming up water columns (waterfalls, still shafts, soul-sand bubble columns: all BlockKind.WATER) -> `MoveGraph.deep` (submerged water whose column reaches air within `swimUpMax` blocks, `withSwimUp`; vertical edges `RISE`/`SINK`, level `DEEP_FACTOR`), `Standing.depth/waterNode`, driven by `core/control/WaterRiser` (jump held to rise, before the Jumper/MPC in `PathExecutor`); settings `swimUp`, `swimUpMax` (Movement tab). Test: `WaterRiseCourseTest`. Air budget is just the depth cap, not tracked per path.
- Boats -> `mod/control/BoatRider` (place/mount waits out lag; drives with `IceRacer` over `TrackField.water`, a
  water-only field that skips 1-wide channels; `BoatCrossingTest`), `core/move/Boating.fits`. Water clutch -> `mod/control/Clutch`.
- Safety -> `mod/process/Guard` (creepers, fight, low HP, fight-or-flee via `core/control/Threat`), `mod/control/Combat`
  (bow with `core/control/Archery` aim, shield), `Upkeep` (eat, armor, totem/shield offhand), `SleepProcess`.
  Paths avoid mobs/lava edges: `core/move/Hazards` priced in `MoveGraph.danger`, fed by `Guard.hazards` every 10 ticks
  through `PathingSession.setHazards`.
- `#portal` / far `#goto` through the nether -> `mod/process/PortalProcess`, `core/portal/PortalFrame` (build site +
  frame), `NetherRoute` (worth it?), `PortalMemory` (portals seen, `config/lazuli/servers/<key>/portals.txt`).
- `#breed` -> `mod/process/BreedProcess`; `#fish` -> `mod/process/FishProcess` (bite flag via `FishingHookAccessor`).
- `#iceboat` -> `mod/process/IceBoatProcess`; `core/physics/BoatSim` (vanilla boat tick), `core/race/IceRacer` (beam
  search over BoatSim, one-tick key delay), tracks `core/race/TrackField` (from blocks: drops, void jumps, lap seam) or
  `RecordedLine` (driven once, `config/lazuli/servers/<key>/iceboat/`). Courses drawn as ASCII maps in `RaceCourseTest`.
- `#build <file>` -> `mod/process/BuildProcess.startSchematic` (per-spot block states), `core/schematic/Schematic`
  (.litematic/.schem/.nbt via its own `Nbt` reader). `#getitem ... dry` -> `GetItemProcess.dryRun` + `core/craft/PlanEstimate`.
- `#queue` / `#routine` / `a then b` -> `core/task/RoutineRunner` (step done = busy then idle; endless jobs like #farm hand over when the inventory is full, `RoutineRunner.endless`), `Routines` (`config/lazuli/routines.txt`); a forever routine whose whole round did nothing waits `routineIdleWaitSeconds` and gives up after `routineIdleRounds` such rounds; GUI: Routines tab (`LazuliConfig.routinePage`, WalksyLib page list).
- Routine language: a step is `command @retry 3 @timeout 5m @onfail stop|continue|skip-rest|goto <label> @times 3 @before 5s @after 5s @if/@unless/@until <cond> @with name=value @off`
  (`core/task/Step` record, parse/print lossless; a `@word` that isn't a modifier before the first modifier is a target name in the command). Conditions `core/task/Cond`
  (`inv.<item>>n chest.<item> inv.free inv.full has.<item> health hunger day night time dim busy`, and/or/not), step words `wait say notify skip end label jump` (no prefix).
  `RoutineRunner` runs `Step`s through a `Host` (run/stop/world/say/notify); only steps with @retry/@onfail react to `failStep` (chat errors), plain ones behave as before; `@with`
  overrides settings for that step and restores them. `Routine.check` validates (`#routine check`); `#routine show|edit|insert|remove|rename|copy|check|status|help`.
  GUI editor: `mod/config/RoutineEditor` (step page list, per-step widgets; model helpers `core/task/Forms` + `CondForm`), tab `config/tabs/RoutinesTab`.
- Routine control flow: steps stay a flat list; `core/task/Program` works out the blocks (`if <c>` / `else if <c>` / `else` / `while <c>` / `repeat <n>` closed by `end`, `break`, `continue`; a plain `end`
  outside any block is still the old "end routine"). `Step.Kind` has IF ELSE WHILE REPEAT BREAK CONTINUE SET ADD (bare words only: `set x = 1` is a variable, `#set name value` the settings command;
  `Step.isStepWord` decides prefixing). `Routine.compile` (errors say "step N:" = line), `Routine.check` also checks blocks + unknown `$vars`. `RoutineRunner` keeps loop `Frame`s, variables
  (`set`, `add`, `$name` in args, builtins `$round $fails $loop`), last-step result (`last step failed|succeeded`), per-loop limit `routineLoopLimit` and yields every 5000 control steps per tick.
  Expressions `core/task/Expr` (`+ - * / %`, brackets, `$vars`, numeric condition names). Conditions (`Cond`) accept natural wording (`free slots < 4`, `tool durability iron_pickaxe < 20`,
  `distance to @home < 30`, `hostile nearby`, `random 30`, `weather == rain`...) normalized to dotted names; `Cond.World` has default methods for the new ones (`mod/RoutineGame` implements them).
  Editor model (tested, pure): `core/task/RoutineModel` (rows, depth, collapse, block-aware move/duplicate/delete keep-children or delete-all, template add, add else, `reconcile` for the widget list);
  `CondForm.Subject` lists every condition kind for the click builder; command options as widgets: `core/trade/TradeForm` (bare trade words, tri-state options via `no-<word>`), `Forms.splitExtras`
  (`at x y z` for stations, `dry|deposit|then` for getitem). The editor reuses one condition builder for @if/@unless/@until and block conditions (`CondState` "block").
- Targets: `#pick [name]` toggles pick mode (`mod/process/PickMode`: shift+right-click a block/entity saves it, vanilla click cancelled), `#target list|show|del|rename|clear|goto|near`;
  `core/target/Target`, `Targets` (per-server file `targets.txt`, names lower-case, modifier words reserved). `@name` anywhere in a typed command or a routine step is expanded when it runs
  (`Targets.expand`: `x y z`, `at x y z` for craft/smelt/brew..., `--with <uuid>` for a villager in #trade); `#routine`/`#queue` keep it raw. Drawn by `LazuliRenderer` (renderTargets),
  tab `config/tabs/TargetsTab`, target dropdowns in the routine step editor.
- HUD -> `mod/render/LazuliRenderer` (Material 3 card via `Material`), `Minimap`.
- `#bucket` -> `mod/process/BucketProcess`; `#hunt <item> [count]` -> `HuntProcess` (mobs from `MobDrops`, like #getitem); `#smith <item>` -> `SmithProcess`.
- Command parsing helpers in `CommandHandler`: `integer/count/coords` (errors say "must be a number", `~` relative coordinates). `#stop` prints "Stopped" itself (only if something ran); `#kill` is the hard reset when `#stop` isn't enough.
- Others: Follow, Explore, Tunnel, Farm, Hunt processes in `mod/process/`; chat commands from other players `mod/ChatControl`.
- Item keys (`minecraft:dirt#hash`, potions `@minecraft:swiftness`) -> `core/inventory/ItemKeys`, `mod/inventory/Inventories`.
- `#sel pos1|pos2|fill|clear|reset` -> `core/area/Selection`, `mod/process/BuildProcess` (box drawn by `LazuliRenderer`).
- `#sort` -> `core/storage/SortPlan`, `mod/process/SortProcess` (reads each box via `ContainerProcess.onOpen`).
- Shulker boxes -> `mod/process/ShulkerUnpack` (place, take, pick up; `Station.startPlaced`), chest memory names
  `shulker>id` (`ItemKeys.inShulker`); used through `ChestTake`. Restock -> `core/inventory/Restock`,
  `mod/process/RestockProcess` (pauses the job like Guard; per-thing toggles in `Settings.restockSpec`, Survival tab: Restock sections).
- `#stronghold` -> `mod/process/StrongholdProcess` (two eye throws, `core/portal/Triangulation`, then the end portal frame via BlockSearch; waypoint `stronghold`).
- Death recovery -> `mod/process/DeathRecovery` (deathRecovery: back to the death spot after respawn, picks the items up before despawn).
- `#sort` labels -> `SortProcess.label` (sortLabels: item frame on each box front with its main item).
- Ghost view -> `core/control/GhostTrail` drawn by `LazuliRenderer.drawGhost` (renderGhost, ghostTicks).

## Trade flags, work blocks, per-server memory
- `#trade` flags (`--with --only-known --scan --max --keep --min-rate --wait-restock --wait-day --stay/--no-walk --range`) are
  parsed by `core/trade/TradeOptions` (also puts them over the `trade*` settings) and passed through `Context.trade/tradeSell/tradeScan(..., options)`
  to `TradeProcess.options(..)` (one run). Strangers are only scanned when no known villager has the offer at all; sold out ones are
  re-checked / waited for; unreachable villagers get a cooldown + retries (`COOLDOWN`, `RETRIES`).
- `craft|smelt|brew|enchant|repair|combine|smith ... at x y z`: `CommandHandler.locate` + `Context.stationAt`, each process has `station(BlockPos)`
  (consumed by its next `start`), `Station.exact()` makes it refuse any other block.
- What's remembered lives in `config/lazuli/servers/<key>/{chests,trades,portals,waypoints}.txt` and `iceboat/` (`core/storage/WorldKey`
  makes the key and does the one-time copy of the old `config/lazuli/<kind>/<server>.txt` files; setting `perServerMemory`).

## More gotchas
- Restart the game after every deploy: copying the jar over a running game crashes it on the next class load.
- Vanilla source when needed: decompile from the loom jar with vineflower (in `~/.gradle/caches/modules-2`).
