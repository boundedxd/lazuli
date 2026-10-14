# Lazuli — Lazy D* Lite pathfinding mod for Minecraft (design spec, M1)

## Context
Baritone is the standard Minecraft pathfinding bot, but it's slow and dumb: it walks block-center to block-center, never sprint-jumps on flat ground, turns by snapping, and can't do real parkour. Lazuli is a from-scratch Baritone alternative that:
- Plans with **Lazy D\* Lite** (Koenig & Likhachev D\* Lite + lazy edge evaluation) for incremental, long-range planning that repairs instead of replanning.
- Moves like a skilled player everywhere: sprint-jump chains, head-hitters, any-angle lines, corner cutting, smooth anticipatory turning — and eventually precise parkour up to neos.
- Uses only vanilla-legal inputs (keys + mouse deltas), smooth human-like rotation.

Working dir is empty — greenfield. Target **Fabric, Minecraft 1.21.11**, Java 21, Mojang mappings. Port to 26.x later (keep MC code isolated).

## Architecture
Gradle multi-project:
- **`core/`** — pure Java, zero MC deps, fast JUnit tests: LD\*SL planner, graph/goal interfaces, `PlayerSim`, maneuvers, trajectory layer, against a `CollisionWorld`/`BlockView` interface.
- **`mod/`** — Fabric mod (`lazuli`, package `dev.lazuli`): world-cache adapter, mixins (chat intercept, input injection), executor, rotation controller, commands, settings, path rendering. Everything version-specific lives here.

### Planner — Lazy D\* Lite (`core/planner`)
- Backward search from goal per Koenig paper: g/rhs, priority keys, `k_m` shift as player moves, `UpdateVertex`, `ComputeShortestPath`.
- Node = standable block position. Edge = maneuver with **footprint** (blocks it depends on), **optimistic cost** (admissible lower bound) and lazily **evaluated cost** (ticks, or infeasible) + cached input script.
- Lazy loop: solve with optimistic costs → walk path from player → evaluate first `evalHorizon` (~32 blocks) edges → if any differ, UpdateVertex + repair → repeat until near path fully verified.
- World changes (chunk load, block update, capability epoch e.g. food ≤ 6) → spatial index over footprints → dirty edges → incremental repair.
- Unloaded/unknown terrain: **freespace assumption** (optimistic) → one continuous plan for long trips, repaired as chunks load.
- Goals via virtual goal node with 0-cost links: `GoalBlock`, `GoalXZ` (column), `GoalYLevel` (bounded to loaded area), `GoalNear`.
- Unreachable handling (goal proven unreachable in known terrain or search budget hit): fallback=false → stop + chat error; fallback=true → retarget to nearest **safe** reachable node (solid floor, 2-high clearance, no lava adjacency, not a fall/suffocation spot; min euclidean distance to goal, tiebreak path cost) without restarting the search. Non-standable exact goal with fallback=false → error at `#goal` time.
- `heuristicWeight` setting (ε-inflation) for speed on huge trips.
- Runs on a worker thread; consumes a world-change queue; publishes immutable path snapshots.

### Physics — `PlayerSim` (`core/physics`)
Tick-accurate port of vanilla 1.21.11 player movement: input vector (0.98 scale, sneak 0.3), sprint, ground friction/slipperiness, air drag, gravity + vertical drag, jump velocity + sprint-jump boost along yaw, AABB collision in vanilla axis order, 0.6 step-up, sneak edge-guard, ice/slime/soul sand/honey, basic water + ladders. Constants taken from 1.21.11 source at implementation time, validated by golden traces.

### Maneuvers (`core/movement`), M1 set
Traverse (cardinal + diagonal), Ascend, Descend, Fall (≤ `maxFallHeight`, more into water), Climb (ladder/vine), Mine (vanilla mining-time: hardness, best hotbar tool, Efficiency, Haste, water/airborne penalties), Pillar (jump + place), Bridge (sneak edge + place), Parkour gaps ≤ 3 (pipeline proof).
- **Markov rule:** every maneuver is evaluated from a conservative entry state (near block center, ≤ walk speed); required run-up blocks are part of the footprint → costs stay path-independent.
- Evaluation = sim search over input scripts (jump tick, sprint start, yaw offset, W release), pick fastest that's robust to ±0.05 position / small velocity perturbations.
- Place/break require throwaway blocks / tools in inventory; otherwise infeasible.

### Trajectory layer (`core/trajectory`) — "actually smart, actually fast"
Turns the LD\*SL block route into a continuous fast motion plan, every step sim-verified:
- Sprint-jump chains on flat ground (~7.1 b/s vs ~5.6 sprint); head-hitter use under 2-high ceilings.
- Any-angle straight lines across open terrain (line-of-sight shortcutting), corner cutting with early turn-in.
- Momentum carry across consecutive maneuvers; jump timing so landings line up with the next obstacle.
- Traverse edge costs in the planner use cruise speed (sprint-jump when headroom + food allow) so routes that are *faster* win, not just shorter.

### Executor + rotation (`mod/control`)
- Per client tick: follow trajectory; re-sim a few ticks ahead from real state; if predicted miss → small corrections (jump tick shift, yaw nudge, early W release); unrecoverable (knockback/lag/fall) → report new node to planner (k_m update, fast repair).
- Mining: aim face, select best tool, hold attack. Placing: select throwaway, aim support face, use.
- Rotation: all view changes as smoothed mouse deltas, capped angular velocity/acceleration, look-ahead target along trajectory; sim models yaw over time (mid-air yaw matters for neos).
- Inputs injected via mixin on movement input + sprint/attack/use keys. User movement input cancels pathing (`cancelOnUserInput`).
- Safety: no lava paths/adjacency, respect `maxFallHeight`, sprint only when food > 6.

### Commands (`mod/command`)
Client-side chat intercept, prefix `#` (configurable), `~` relative coords. Messages tagged `[Lazuli]`.
- `#goal x y z [bool]`, `#goal x z [bool]`, `#goal y [bool]`, `#goal [bool]` (current pos), `#goal clear` — bool = fallback to nearest safe spot (default false)
- `#path`, `#goto <goal args>`, `#stop` / `#cancel`
- `#set <name> [value]`, `#settings` — persisted `config/lazuli.json`: allowBreak, allowPlace, allowParkour, allowSprint, maxFallHeight, throwawayBlocks, cancelOnUserInput, rotationSpeed, renderPath, evalHorizon, heuristicWeight
- `#help`; dev: `#record start|stop <name>` (input + position per tick → golden trace JSON)

### Rendering
Path line, trajectory preview, goal box (debug, `renderPath`).

## Milestones
- **M1 (this spec):** scaffold, world cache, LD\*SL, PlayerSim + golden traces, M1 maneuvers incl. break/place + gaps ≤ 3, trajectory layer (sprint-jump chains, any-angle, head-hitters), executor, rotation, commands/settings, rendering.
- **M2:** full parkour — 4-gaps, up/down jumps, diagonals, ladder jumps, better momentum chaining.
- **M3:** neos (1–3+), 1-block-runup momentum jumps, slime/ice tech.
- **Later:** port `mod/` (+ sim constants) to 26.x.

## Verification
- `core` JUnit 5: LD\*SL property tests (random 3D grids + random cost changes → repaired path cost == fresh Dijkstra), optimistic ≤ evaluated assertion, goal types, safe-fallback selection.
- PlayerSim reproduces `#record` golden traces from 1.21.11 (tight tolerance).
- Maneuver/trajectory tests on hand-built fake worlds (gaps, ceilings, slabs, ice, water); trajectory must beat block-center sprint time on flat fixtures.
- In-game: `./gradlew :mod:runClient`, test world with numbered course (flats, jumps, bridges, tunnels, unreachable goals); log success rate + ticks; compare flat-ground travel time vs Baritone.

