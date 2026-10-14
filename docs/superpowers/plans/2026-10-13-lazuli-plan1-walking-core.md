# Lazuli Plan 1 — Walking Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `#goal` / `#path` / `#goto` / `#stop` / `#set` work in game: Lazy D* Lite plans over a block cache with the freespace assumption, the bot sprints/jumps/falls along the path with smooth rotation, and repairs the path incrementally as chunks load and blocks change.

**Architecture:** `core/` (pure Java 21, JUnit 5) holds world cache, goals, LD*SL planner, rule-based move graph, pathing session (worker thread), path follower, rotation controller, settings, commands. `mod/` (Fabric, MC 1.21.11, Mojang mappings) is a thin adapter: chunk scanning, block-change mixin, chat event, key/rotation driving, particles.

**Tech Stack:** Gradle 9.7.1 wrapper, Fabric Loom `net.fabricmc.fabric-loom-remap` 1.18-SNAPSHOT (needs **JDK 25 to run Gradle**), Fabric Loader 0.19.5, Fabric API 0.141.6+1.21.11, Java 21 target, JUnit 5.11.4.

**Spec:** `docs/superpowers/specs/2026-10-13-lazuli-design.md`

## Scope of this plan (M1 is split into 4 plans)
- **Plan 1 (this):** walking core above. Maneuvers are rule-based (Traverse, Diagonal, Ascend, Descend/Fall); the lazy-evaluation machinery is built and tested with synthetic graphs.
- Plan 2: `PlayerSim` + `#record` golden traces.
- Plan 3: sim-evaluated maneuvers, break/place/pillar/bridge, parkour ≤3, climb, water, `heuristicWeight`.
- Plan 4: trajectory layer (sprint-jump chains, any-angle, head-hitters), MPC executor, per-frame rotation, line rendering.

## Global Constraints
- Mod id `lazuli`, packages `dev.lazuli.core.*` / `dev.lazuli.mod.*`, chat tag `[Lazuli]`.
- `core` has zero Minecraft or third-party runtime deps.
- Minecraft `1.21.11` exactly; all MC references isolated to `mod/`.
- Inputs only via vanilla `KeyMapping.setDown` and `Entity.turn` (the mouse path). No packets, no direct `setSprinting`.
- Commands are intercepted client-side and never sent to the server.
- All costs in ticks; every edge cost > 0.
- Run Gradle with `JAVA_HOME` = JDK 25 (`C:/Program Files/Java/jdk-25.0.2`).

## Review Focus
1. Goal inside an unloaded chunk → planner treats it as walkable and starts moving (freespace), repairs when loaded. Test: `PathingSessionTest.goalInUnknownTerrainIsPlanned`.
2. Player boxed in, goal outside → budget exceeded, not a hang; fallback picks nearest safe known spot. Test: `PathingSessionTest.enclosedPlayerUsesFallback`.
3. Block placed across the current path → incremental repair matches fresh Dijkstra. Test: `LazyDStarLiteTest.repairsMatchDijkstraUnderRandomChanges`.
4. `#goal` with garbage args / `~` relative coords / trailing `true` → clear errors or correct goal. Test: `CommandHandlerTest`.
5. User presses W/A/S/D/space while pathing → pathing cancels and keys are released. Covered in Task 11 manual check.

---

## File Structure
```
settings.gradle, build.gradle, gradle.properties, gradlew(.bat), gradle/wrapper/*
core/build.gradle
core/src/main/java/dev/lazuli/core/
  world/   Pos, BlockKind, BlockView, WorldCache
  goal/    Goal, GoalBlock, GoalXZ, GoalYLevel, GoalNear
  planner/ Graph, PlanResult, LazyDStarLite
  move/    Costs, MoveGraph, SafeSpotFinder
  session/ PathingSession
  control/ PlayerState, Controls, PathFollower, RotationController
  settings/Setting, Settings
  command/ GoalParser, CommandHandler
core/src/test/java/dev/lazuli/core/... (mirrors + testutil/GridWorld, testutil/GridGraph)
mod/build.gradle
mod/src/main/resources/ fabric.mod.json, lazuli.mixins.json
mod/src/main/java/dev/lazuli/mod/
  LazuliClient, SettingsStore
  world/   BlockClassifier, ChunkScanner
  mixin/   ClientLevelMixin
  control/ InputDriver, PathExecutor
```

---

### Task 1: Gradle scaffold

**Files:** Create root `settings.gradle`, `build.gradle`, `gradle.properties`, `.gitignore`; copy `gradlew`, `gradlew.bat`, `gradle/wrapper/*` from FabricMC/fabric-example-mod branch `1.21.11`; create `core/build.gradle`, `mod/build.gradle`, `mod/src/main/resources/fabric.mod.json`, `mod/src/main/java/dev/lazuli/mod/LazuliClient.java` (stub).

**Interfaces:** Produces `:core` (java-library) consumed by `:mod` via `implementation` + `shade` (classes copied into the mod jar).

- [ ] **Step 1: Fetch wrapper**
```bash
git clone -q --depth 1 -b 1.21.11 https://github.com/FabricMC/fabric-example-mod /tmp/fem && cp -r /tmp/fem/gradlew /tmp/fem/gradlew.bat /tmp/fem/gradle . && rm -rf /tmp/fem
```
- [ ] **Step 2: Write build files**

`settings.gradle`
```groovy
pluginManagement {
	repositories {
		maven { name = 'Fabric'; url = 'https://maven.fabricmc.net/' }
		mavenCentral()
		gradlePluginPortal()
	}
}
rootProject.name = 'lazuli'
include 'core', 'mod'
```
`gradle.properties`
```properties
org.gradle.jvmargs=-Xmx2G
org.gradle.parallel=true
minecraft_version=1.21.11
loader_version=0.19.5
loom_version=1.18-SNAPSHOT
fabric_api_version=0.141.6+1.21.11
mod_version=0.1.0
maven_group=dev.lazuli
```
`build.gradle`
```groovy
plugins { id 'net.fabricmc.fabric-loom-remap' version "${loom_version}" apply false }
subprojects { group = project.maven_group; version = project.mod_version }
```
`core/build.gradle`
```groovy
plugins { id 'java-library' }
repositories { mavenCentral() }
java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
dependencies {
	testImplementation platform('org.junit:junit-bom:5.11.4')
	testImplementation 'org.junit.jupiter:junit-jupiter'
	testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}
test { useJUnitPlatform() }
```
`mod/build.gradle`
```groovy
plugins { id 'net.fabricmc.fabric-loom-remap' }
base { archivesName = 'lazuli' }
configurations { shade }
dependencies {
	minecraft "com.mojang:minecraft:${project.minecraft_version}"
	mappings loom.officialMojangMappings()
	modImplementation "net.fabricmc:fabric-loader:${project.loader_version}"
	modImplementation "net.fabricmc.fabric-api:fabric-api:${project.fabric_api_version}"
	implementation project(':core')
	shade project(':core')
}
processResources {
	def version = project.version
	inputs.property 'version', version
	filesMatching('fabric.mod.json') { expand 'version': version }
}
tasks.withType(JavaCompile).configureEach { it.options.release = 21 }
java { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }
jar {
	dependsOn configurations.shade
	from { configurations.shade.collect { it.isDirectory() ? it : zipTree(it) } }
}
```
`mod/src/main/resources/fabric.mod.json`
```json
{
	"schemaVersion": 1,
	"id": "lazuli",
	"version": "${version}",
	"name": "Lazuli",
	"description": "Lazy D* Lite pathfinding that moves like a real player.",
	"environment": "client",
	"entrypoints": { "client": ["dev.lazuli.mod.LazuliClient"] },
	"mixins": [{ "config": "lazuli.mixins.json", "environment": "client" }],
	"depends": { "fabricloader": ">=0.19.5", "minecraft": "~1.21.11", "java": ">=21", "fabric-api": "*" }
}
```
`mod/src/main/resources/lazuli.mixins.json`
```json
{
	"required": true,
	"package": "dev.lazuli.mod.mixin",
	"compatibilityLevel": "JAVA_21",
	"client": [],
	"injectors": { "defaultRequire": 1 }
}
```
`LazuliClient.java` (stub, replaced in Task 11)
```java
package dev.lazuli.mod;

import net.fabricmc.api.ClientModInitializer;

public final class LazuliClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
	}
}
```
`.gitignore`: `.gradle/`, `build/`, `run/`, `.idea/`, `*.iml`, `out/`

- [ ] **Step 3: Build** — `JAVA_HOME="C:/Program Files/Java/jdk-25.0.2" ./gradlew build` → `BUILD SUCCESSFUL`, `mod/build/libs/lazuli-0.1.0.jar` exists.
- [ ] **Step 4: Commit** — `git add -A && git commit -m "build: core + Fabric 1.21.11 mod scaffold"`

---

### Task 2: World model (`Pos`, `BlockKind`, `BlockView`, `WorldCache`)

**Files:** Create `core/src/main/java/dev/lazuli/core/world/{Pos,BlockKind,BlockView,WorldCache}.java`; tests `core/src/test/java/dev/lazuli/core/world/{PosTest,WorldCacheTest}.java`, fixture `core/src/test/java/dev/lazuli/core/testutil/GridWorld.java`.

**Interfaces (produces):**
- `Pos.pack(int x,int y,int z): long`, `Pos.x/y/z(long): int`, `Pos.offset(long,int,int,int): long`, `Pos.chunkKey(int blockX,int blockZ): long`, `Pos.toString(long): String`
- `enum BlockKind { UNKNOWN, AIR, SOLID, WATER, DANGER, OBSTACLE }` + `byId(int)`
- `interface BlockView { BlockKind get(int x,int y,int z); }`
- `WorldCache(int minY,int height)`: `reset(minY,height)`, `minY()`, `maxY()`, `sectionCount()`, `putChunk(cx,cz,byte[][] sections)` (null section = all air), `removeChunk(cx,cz)`, `isLoaded(cx,cz)`, `set(x,y,z,kind)`, `get` (UNKNOWN if unloaded, DANGER below minY, AIR above maxY), `static index(x,y,z)`
- test `GridWorld(BlockKind fallback)`: `set`, `fill(x1,y1,z1,x2,y2,z2,kind)`, `static flatFloor(minX,minZ,maxX,maxZ)` (SOLID at y=0, AIR elsewhere)

- [ ] **Step 1: Write failing tests**
```java
// PosTest
@Test void roundTripsPositiveNegativeAndExtremeCoordinates() {
    int[][] cases = {{0,0,0},{1,64,-1},{-30_000_000,-64,30_000_000},{12345,319,-54321},{-1,-1,-1}};
    for (int[] c : cases) {
        long p = Pos.pack(c[0], c[1], c[2]);
        assertEquals(c[0], Pos.x(p)); assertEquals(c[1], Pos.y(p)); assertEquals(c[2], Pos.z(p));
    }
}
@Test void offsetMovesEachAxis() {
    assertEquals(Pos.pack(-1, 72, 1), Pos.offset(Pos.pack(5, 70, -3), -6, 2, 4));
}
// WorldCacheTest
@Test void unloadedChunksAreUnknown() { assertEquals(BlockKind.UNKNOWN, new WorldCache(-64, 384).get(0, 64, 0)); }
@Test void belowWorldIsDangerAndAboveIsAir() {
    WorldCache c = new WorldCache(-64, 384); c.putChunk(0, 0, new byte[24][]);
    assertEquals(BlockKind.DANGER, c.get(0, -65, 0)); assertEquals(BlockKind.AIR, c.get(0, 320, 0));
}
@Test void nullSectionsReadAsAirAndSetAllocatesThem() {
    WorldCache c = new WorldCache(-64, 384); c.putChunk(-1, 2, new byte[24][]);
    assertEquals(BlockKind.AIR, c.get(-5, 70, 40));
    c.set(-5, 70, 40, BlockKind.SOLID);
    assertEquals(BlockKind.SOLID, c.get(-5, 70, 40)); assertEquals(BlockKind.AIR, c.get(-5, 71, 40));
}
@Test void setOnUnloadedChunkIsIgnoredAndRemoveForgets() {
    WorldCache c = new WorldCache(-64, 384);
    c.set(100, 64, 100, BlockKind.SOLID); assertEquals(BlockKind.UNKNOWN, c.get(100, 64, 100));
    c.putChunk(0, 0, new byte[24][]); c.removeChunk(0, 0); assertEquals(BlockKind.UNKNOWN, c.get(1, 64, 1));
}
```
- [ ] **Step 2: Run** `./gradlew :core:test` → FAIL (classes missing).
- [ ] **Step 3: Implement**
```java
// Pos.java — same layout as vanilla BlockPos.asLong (x 26 bits, z 26 bits, y 12 bits)
public final class Pos {
    private static final int XZ_BITS = 26, Y_BITS = 12, X_SHIFT = Y_BITS + XZ_BITS, Z_SHIFT = Y_BITS;
    private static final long XZ_MASK = (1L << XZ_BITS) - 1, Y_MASK = (1L << Y_BITS) - 1;
    private Pos() {}
    public static long pack(int x, int y, int z) { return ((x & XZ_MASK) << X_SHIFT) | ((z & XZ_MASK) << Z_SHIFT) | (y & Y_MASK); }
    public static int x(long p) { return (int) (p >> X_SHIFT); }
    public static int y(long p) { return (int) (p << (64 - Y_BITS) >> (64 - Y_BITS)); }
    public static int z(long p) { return (int) (p << (64 - Z_SHIFT - XZ_BITS) >> (64 - XZ_BITS)); }
    public static long offset(long p, int dx, int dy, int dz) { return pack(x(p) + dx, y(p) + dy, z(p) + dz); }
    public static long chunkKey(int blockX, int blockZ) { return ((long) (blockX >> 4) << 32) | ((blockZ >> 4) & 0xFFFFFFFFL); }
    public static String toString(long p) { return x(p) + " " + y(p) + " " + z(p); }
}
```
```java
// BlockKind.java
/** What the planner needs to know about one block. */
public enum BlockKind {
    UNKNOWN,  // not loaded: freespace assumption (walkable floor and open air)
    AIR,      // no collision, harmless
    SOLID,    // full-cube collision, safe to stand on
    WATER,    // plan 1: impassable
    DANGER,   // lava, fire, magma, cactus, berry bush, cobweb, powder snow, campfire, wither rose, void
    OBSTACLE; // partial collision (slabs, stairs, fences, carpets, ladders): plan 1 avoids
    private static final BlockKind[] VALUES = values();
    public static BlockKind byId(int id) { return VALUES[id]; }
}
```
```java
// BlockView.java
/** Read-only, thread-safe view of block kinds. */
public interface BlockView { BlockKind get(int x, int y, int z); }
```
```java
// WorldCache.java
/** Written by the client thread, read by the planner thread. Single-block writes race benignly; each is followed by a change notification. */
public final class WorldCache implements BlockView {
    private final ConcurrentHashMap<Long, byte[][]> chunks = new ConcurrentHashMap<>();
    private volatile int minY, height;
    public WorldCache(int minY, int height) { this.minY = minY; this.height = height; }
    public void reset(int minY, int height) { chunks.clear(); this.minY = minY; this.height = height; }
    public int minY() { return minY; }
    public int maxY() { return minY + height - 1; }
    public int sectionCount() { return height >> 4; }
    public void putChunk(int cx, int cz, byte[][] sections) {
        if (sections.length != sectionCount()) throw new IllegalArgumentException("expected " + sectionCount() + " sections, got " + sections.length);
        chunks.put(key(cx, cz), sections);
    }
    public void removeChunk(int cx, int cz) { chunks.remove(key(cx, cz)); }
    public boolean isLoaded(int cx, int cz) { return chunks.containsKey(key(cx, cz)); }
    public void set(int x, int y, int z, BlockKind kind) {
        byte[][] sections = chunks.get(key(x >> 4, z >> 4));
        if (sections == null || y < minY || y > maxY()) return;
        int s = (y - minY) >> 4;
        if (sections[s] == null) {
            if (kind == BlockKind.AIR) return;
            byte[] fresh = new byte[4096];
            Arrays.fill(fresh, (byte) BlockKind.AIR.ordinal());
            sections[s] = fresh;
        }
        sections[s][index(x, y, z)] = (byte) kind.ordinal();
    }
    @Override public BlockKind get(int x, int y, int z) {
        if (y < minY) return BlockKind.DANGER;
        if (y > maxY()) return BlockKind.AIR;
        byte[][] sections = chunks.get(key(x >> 4, z >> 4));
        if (sections == null) return BlockKind.UNKNOWN;
        byte[] section = sections[(y - minY) >> 4];
        return section == null ? BlockKind.AIR : BlockKind.byId(section[index(x, y, z)]);
    }
    public static int index(int x, int y, int z) { return ((y & 15) << 8) | ((z & 15) << 4) | (x & 15); }
    private static long key(int cx, int cz) { return ((long) cx << 32) | (cz & 0xFFFFFFFFL); }
}
```
```java
// testutil/GridWorld.java
public final class GridWorld implements BlockView {
    private final Map<Long, BlockKind> blocks = new HashMap<>();
    private final BlockKind fallback;
    public GridWorld(BlockKind fallback) { this.fallback = fallback; }
    public static GridWorld flatFloor(int minX, int minZ, int maxX, int maxZ) { return new GridWorld(BlockKind.AIR).fill(minX, 0, minZ, maxX, 0, maxZ, BlockKind.SOLID); }
    public GridWorld set(int x, int y, int z, BlockKind k) { blocks.put(Pos.pack(x, y, z), k); return this; }
    public GridWorld fill(int x1, int y1, int z1, int x2, int y2, int z2, BlockKind k) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++)
            for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++)
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) set(x, y, z, k);
        return this;
    }
    @Override public BlockKind get(int x, int y, int z) { return blocks.getOrDefault(Pos.pack(x, y, z), fallback); }
}
```
- [ ] **Step 4: Run** `./gradlew :core:test` → PASS.
- [ ] **Step 5: Commit** `feat(core): block position packing and world cache`

---

### Task 3: Goals

**Files:** `core/src/main/java/dev/lazuli/core/goal/{Goal,GoalBlock,GoalXZ,GoalYLevel,GoalNear}.java`, test `goal/GoalTest.java`.

**Interfaces (produces):**
```java
public interface Goal {
    boolean isGoal(int x, int y, int z);
    /** Every packed position that could satisfy the goal (standability checked by planner); unbounded goals emit only near the player within [minY,maxY]. */
    void forEachCandidate(int playerX, int playerY, int playerZ, int minY, int maxY, LongConsumer out);
    /** Euclidean distance in blocks to the nearest goal position; 0 inside. */
    double distance(int x, int y, int z);
    String describe();
}
record GoalBlock(int x,int y,int z); record GoalXZ(int x,int z);
record GoalYLevel(int y) { static final int CANDIDATE_RADIUS = 64; }
record GoalNear(int x,int y,int z,int radius);
```

- [ ] **Step 1: Failing tests**
```java
private static List<Long> candidates(Goal g, int px, int py, int pz) { List<Long> out = new ArrayList<>(); g.forEachCandidate(px, py, pz, -64, 319, out::add); return out; }
@Test void goalBlockHasExactlyOneCandidate() {
    Goal g = new GoalBlock(10, 64, -5);
    assertTrue(g.isGoal(10, 64, -5)); assertFalse(g.isGoal(10, 65, -5));
    assertEquals(List.of(Pos.pack(10, 64, -5)), candidates(g, 0, 0, 0));
    assertEquals(5.0, g.distance(13, 64, -1), 1e-9);
}
@Test void goalXZCoversTheWholeColumn() {
    Goal g = new GoalXZ(3, 4);
    assertTrue(g.isGoal(3, -64, 4)); assertEquals(384, candidates(g, 0, 0, 0).size()); assertEquals(0.0, g.distance(3, 100, 4), 1e-9);
}
@Test void goalYLevelCandidatesAreBoundedAroundThePlayer() {
    int side = 2 * GoalYLevel.CANDIDATE_RADIUS + 1;
    assertEquals(side * side, candidates(new GoalYLevel(12), 100, 70, 100).size());
    assertEquals(0, candidates(new GoalYLevel(-100), 0, 0, 0).size());
    assertEquals(58.0, new GoalYLevel(12).distance(0, 70, 0), 1e-9);
}
@Test void goalNearIsASphere() {
    Goal g = new GoalNear(0, 64, 0, 2);
    assertTrue(g.isGoal(2, 64, 0)); assertFalse(g.isGoal(2, 65, 1));
    for (long c : candidates(g, 0, 0, 0)) assertTrue(g.isGoal(Pos.x(c), Pos.y(c), Pos.z(c)));
    assertEquals(3.0, g.distance(5, 64, 0), 1e-9);
}
```
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement** (each record implements `Goal`):
  - `GoalBlock`: isGoal exact match; one candidate; distance = euclidean; describe `"block x y z"`.
  - `GoalXZ`: isGoal x,z match; candidates every y in [minY,maxY]; distance horizontal; `"x X z Z"`.
  - `GoalYLevel`: isGoal y match; if y outside [minY,maxY] emit nothing, else all (x,z) within ±64 of player at y; distance `|y-this.y|`; `"y Y"`.
  - `GoalNear`: isGoal `dx²+dy²+dz² <= r²` (longs); candidates loop cube ±r (y clamped to range) filtered by isGoal; distance `max(0, euclid - r)`; `"within R of x y z"`.
- [ ] **Step 4: Run** → PASS. **Step 5: Commit** `feat(core): goal types`

---

### Task 4: Lazy D* Lite planner

**Files:** `core/src/main/java/dev/lazuli/core/planner/{Graph,PlanResult,LazyDStarLite}.java`; tests `planner/LazyDStarLiteTest.java`, fixture `testutil/GridGraph.java`.

**Interfaces (produces):**
```java
public interface Graph {
    boolean isNode(long u);
    void successors(long u, LongConsumer out);     // may emit infinite-cost candidates
    void predecessors(long v, LongConsumer out);
    double optimisticCost(long u, long v);         // admissible; +INF if certainly no edge
    default double evaluate(long u, long v) { return optimisticCost(u, v); }  // exact, >= optimistic
    double heuristic(long a, long b);              // consistent, symmetric
    int horizontalReach();                         // edge u->v depends only on blocks within this of u
    int verticalReach();
}
public record PlanResult(Status status, List<Long> path) { enum Status { FOUND, NO_PATH, BUDGET_EXCEEDED } static found/noPath/budgetExceeded }
public final class LazyDStarLite {
    public static final long GOAL = Long.MIN_VALUE;  // virtual goal node
    public LazyDStarLite(Graph graph, LongPredicate isGoal, long[] goalCandidates, long start);
    public long start();
    public double g(long u);
    public void moveStart(long newStart);
    public void regionChanged(int minX, int minY, int minZ, int maxX, int maxY, int maxZ);
    public PlanResult plan(int evalHorizon, long maxExpansions, int maxPathLength);
}
```

- [ ] **Step 1: Test fixture `GridGraph`** — 2D grid (y=0), 4-connected, true costs random in [1,5] or INF (blocked); optimistic cost = 1 for any non-blocked edge (lower bound), `evaluate` = true cost; heuristic = Manhattan (admissible since min cost 1); reach 1/0. Mutable: `setCost(u,v,c)`, `block(u,v)`. Plus `dijkstra(start, goal)` returning optimal true cost.
```java
public final class GridGraph implements Graph {
    public final int size; private final Map<Long, Double> cost = new HashMap<>();
    public GridGraph(int size, long seed, double blockedFraction) {
        this.size = size; Random r = new Random(seed);
        for (int x = 0; x < size; x++) for (int z = 0; z < size; z++) {
            long u = Pos.pack(x, 0, z);
            for (long v : neighbors(u)) cost.put(edgeKey(u, v), r.nextDouble() < blockedFraction ? Double.POSITIVE_INFINITY : 1 + r.nextInt(5));
        }
    }
    public List<Long> neighbors(long u) {
        List<Long> out = new ArrayList<>(); int x = Pos.x(u), z = Pos.z(u);
        int[][] d = {{1,0},{-1,0},{0,1},{0,-1}};
        for (int[] o : d) { int nx = x + o[0], nz = z + o[1]; if (nx >= 0 && nz >= 0 && nx < size && nz < size) out.add(Pos.pack(nx, 0, nz)); }
        return out;
    }
    private static String edgeKey(long u, long v) { return u + ">" + v; }
    public double trueCost(long u, long v) { return cost.getOrDefault(edgeKey(u, v), Double.POSITIVE_INFINITY); }
    public void setCost(long u, long v, double c) { cost.put(edgeKey(u, v), c); }
    @Override public boolean isNode(long u) { int x = Pos.x(u), z = Pos.z(u); return Pos.y(u) == 0 && x >= 0 && z >= 0 && x < size && z < size; }
    @Override public void successors(long u, LongConsumer out) { neighbors(u).forEach(out::accept); }
    @Override public void predecessors(long v, LongConsumer out) { neighbors(v).forEach(out::accept); }
    @Override public double optimisticCost(long u, long v) { return trueCost(u, v) == Double.POSITIVE_INFINITY ? Double.POSITIVE_INFINITY : 1; }
    @Override public double evaluate(long u, long v) { return trueCost(u, v); }
    @Override public double heuristic(long a, long b) { return Math.abs(Pos.x(a) - Pos.x(b)) + Math.abs(Pos.z(a) - Pos.z(b)); }
    @Override public int horizontalReach() { return 1; }
    @Override public int verticalReach() { return 0; }
    public double dijkstra(long start, long goal) {
        Map<Long, Double> dist = new HashMap<>(); PriorityQueue<double[]> pq = new PriorityQueue<>(Comparator.comparingDouble(a -> a[0]));
        dist.put(start, 0.0); pq.add(new double[]{0, start});
        while (!pq.isEmpty()) {
            double[] top = pq.poll(); long u = (long) top[1];
            if (top[0] > dist.get(u)) continue;
            if (u == goal) return top[0];
            for (long v : neighbors(u)) { double nd = top[0] + trueCost(u, v); if (nd < dist.getOrDefault(v, Double.POSITIVE_INFINITY)) { dist.put(v, nd); pq.add(new double[]{nd, v}); } }
        }
        return Double.POSITIVE_INFINITY;
    }
}
```
Note: `(long) top[1]` is exact because packed positions with y=0 and small x,z are < 2^53.

- [ ] **Step 2: Failing tests**
```java
private static double pathCost(GridGraph g, List<Long> p) { double c = 0; for (int i = 0; i + 1 < p.size(); i++) c += g.trueCost(p.get(i), p.get(i + 1)); return c; }
private static LazyDStarLite planner(GridGraph g, long start, long goal) { return new LazyDStarLite(g, u -> u == goal, new long[]{goal}, start); }

@Test void findsOptimalPathOnOpenGrid() {
    GridGraph g = new GridGraph(20, 1, 0.0);
    long s = Pos.pack(0, 0, 0), t = Pos.pack(19, 0, 19);
    PlanResult r = planner(g, s, t).plan(Integer.MAX_VALUE, 1_000_000, Integer.MAX_VALUE);
    assertEquals(PlanResult.Status.FOUND, r.status());
    assertEquals(s, r.path().get(0)); assertEquals(t, r.path().get(r.path().size() - 1));
    assertEquals(g.dijkstra(s, t), pathCost(g, r.path()), 1e-9);
}
@Test void reportsNoPathWhenGoalIsWalledOff() {
    GridGraph g = new GridGraph(10, 2, 0.0); long t = Pos.pack(5, 0, 5);
    for (long n : g.neighbors(t)) { g.setCost(n, t, Double.POSITIVE_INFINITY); }
    assertEquals(PlanResult.Status.NO_PATH, planner(g, Pos.pack(0, 0, 0), t).plan(Integer.MAX_VALUE, 1_000_000, Integer.MAX_VALUE).status());
}
@Test void budgetExceededInsteadOfHanging() {
    GridGraph g = new GridGraph(60, 3, 0.0);
    assertEquals(PlanResult.Status.BUDGET_EXCEEDED, planner(g, Pos.pack(0, 0, 0), Pos.pack(59, 0, 59)).plan(Integer.MAX_VALUE, 10, Integer.MAX_VALUE).status());
}
@Test void repairsMatchDijkstraUnderRandomChanges() {
    for (int trial = 0; trial < 25; trial++) {
        Random r = new Random(100 + trial);
        GridGraph g = new GridGraph(25, trial, 0.2);
        long start = Pos.pack(0, 0, 0), goal = Pos.pack(24, 0, 24);
        g.neighbors(start).forEach(n -> g.setCost(start, n, 1)); g.neighbors(goal).forEach(n -> g.setCost(n, goal, 1));
        LazyDStarLite p = planner(g, start, goal);
        long cur = start;
        for (int step = 0; step < 15; step++) {
            PlanResult res = p.plan(Integer.MAX_VALUE, 10_000_000, Integer.MAX_VALUE);
            double best = g.dijkstra(cur, goal);
            if (best == Double.POSITIVE_INFINITY) { assertEquals(PlanResult.Status.NO_PATH, res.status()); break; }
            assertEquals(PlanResult.Status.FOUND, res.status());
            assertEquals(best, pathCost(g, res.path()), 1e-9, "trial " + trial + " step " + step);
            if (res.path().size() > 1) { cur = res.path().get(1); p.moveStart(cur); }
            for (int k = 0; k < 8; k++) {  // random edge changes, then notify
                long u = Pos.pack(r.nextInt(25), 0, r.nextInt(25));
                for (long v : g.neighbors(u)) {
                    g.setCost(u, v, r.nextDouble() < 0.25 ? Double.POSITIVE_INFINITY : 1 + r.nextInt(5));
                    p.regionChanged(Math.min(Pos.x(u), Pos.x(v)), 0, Math.min(Pos.z(u), Pos.z(v)), Math.max(Pos.x(u), Pos.x(v)), 0, Math.max(Pos.z(u), Pos.z(v)));
                }
            }
        }
    }
}
@Test void evaluatedCostBelowOptimisticIsRejected() {
    GridGraph g = new GridGraph(5, 4, 0.0) { @Override public double evaluate(long u, long v) { return 0.5; } };
    assertThrows(IllegalStateException.class, () -> planner(g, Pos.pack(0, 0, 0), Pos.pack(4, 0, 4)).plan(Integer.MAX_VALUE, 1_000_000, Integer.MAX_VALUE));
}
@Test void moveStartKeepsPathsOptimal() {
    GridGraph g = new GridGraph(15, 5, 0.1);
    long goal = Pos.pack(14, 0, 14), s = Pos.pack(0, 0, 0);
    g.neighbors(goal).forEach(n -> g.setCost(n, goal, 1)); g.neighbors(s).forEach(n -> g.setCost(s, n, 1));
    LazyDStarLite p = planner(g, s, goal);
    List<Long> path = p.plan(Integer.MAX_VALUE, 1_000_000, Integer.MAX_VALUE).path();
    for (int i = 1; i < path.size(); i++) {
        p.moveStart(path.get(i));
        PlanResult r = p.plan(Integer.MAX_VALUE, 1_000_000, Integer.MAX_VALUE);
        assertEquals(g.dijkstra(path.get(i), goal), pathCost(g, r.path()), 1e-9);
    }
}
```
(`GridGraph` must not be `final` for the anonymous subclass — declare it `public class`.)

- [ ] **Step 3: Run** → FAIL.
- [ ] **Step 4: Implement `LazyDStarLite`**
```java
/** D* Lite (Koenig & Likhachev 2002, optimized Fig. 4) searching backward from a virtual goal node, with lazy edge
 *  evaluation: edges use optimistic costs until they lie within the first evalHorizon edges of the best path. */
public final class LazyDStarLite {
    public static final long GOAL = Long.MIN_VALUE;
    public static final double INF = Double.POSITIVE_INFINITY;

    private static final class Node { double g = INF, rhs = INF; Key key; }
    private record Key(double k1, double k2, long id) implements Comparable<Key> {
        int compareKeys(Key o) { int c = Double.compare(k1, o.k1); return c != 0 ? c : Double.compare(k2, o.k2); }
        @Override public int compareTo(Key o) { int c = compareKeys(o); return c != 0 ? c : Long.compare(id, o.id); }
    }

    private final Graph graph;
    private final LongPredicate isGoal;
    private final long[] goalCandidates;
    private final Map<Long, Node> nodes = new HashMap<>();
    private final Map<Long, List<Long>> nodesByChunk = new HashMap<>();
    private final TreeSet<Key> open = new TreeSet<>();
    private final Map<Long, Map<Long, Double>> evaluated = new HashMap<>();
    private long start;
    private double km;
    private long expansions;

    public LazyDStarLite(Graph graph, LongPredicate isGoal, long[] goalCandidates, long start) {
        this.graph = graph; this.isGoal = isGoal; this.goalCandidates = goalCandidates.clone(); this.start = start;
        Node goal = node(GOAL); goal.rhs = 0; updateVertex(GOAL, goal);
    }

    public long start() { return start; }
    public double g(long u) { Node n = nodes.get(u); return n == null ? INF : n.g; }

    /** Paper's k_m: offset future keys by the heuristic distance the start moved. */
    public void moveStart(long newStart) { km += h(start, newStart); start = newStart; }

    /** Blocks in the box changed: un-evaluate and repair every touched vertex whose edges may depend on them. */
    public void regionChanged(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        int hr = graph.horizontalReach() + 1, vr = graph.verticalReach() + 1;
        int x0 = minX - hr, x1 = maxX + hr, y0 = minY - vr, y1 = maxY + vr, z0 = minZ - hr, z1 = maxZ + hr;
        List<Long> affected = new ArrayList<>();
        for (int cx = x0 >> 4; cx <= x1 >> 4; cx++)
            for (int cz = z0 >> 4; cz <= z1 >> 4; cz++) {
                List<Long> inChunk = nodesByChunk.get(((long) cx << 32) | (cz & 0xFFFFFFFFL));
                if (inChunk == null) continue;
                for (long v : inChunk) {
                    int x = Pos.x(v), y = Pos.y(v), z = Pos.z(v);
                    if (x >= x0 && x <= x1 && y >= y0 && y <= y1 && z >= z0 && z <= z1) affected.add(v);
                }
            }
        for (long v : affected) {
            evaluated.remove(v); recompute(v);
            graph.predecessors(v, u -> { evaluated.remove(u); recompute(u); });
        }
    }

    public PlanResult plan(int evalHorizon, long maxExpansions, int maxPathLength) {
        expansions = 0;
        while (true) {
            if (!computeShortestPath(maxExpansions)) return PlanResult.budgetExceeded();
            if (g(start) == INF) return PlanResult.noPath();
            List<Long> path = extractPath(maxPathLength);
            boolean changed = false;
            for (int i = 0; i + 1 < path.size() && i < evalHorizon; i++) {
                long u = path.get(i), v = path.get(i + 1);
                Map<Long, Double> fromU = evaluated.computeIfAbsent(u, k -> new HashMap<>());
                if (fromU.containsKey(v)) continue;
                double opt = graph.optimisticCost(u, v), exact = graph.evaluate(u, v);
                if (exact < opt) throw new IllegalStateException("evaluated cost " + exact + " below optimistic " + opt + " for " + Pos.toString(u) + " -> " + Pos.toString(v));
                fromU.put(v, exact);
                if (exact != opt) { recompute(u); changed = true; }
            }
            if (!changed) return PlanResult.found(path);
        }
    }

    private boolean computeShortestPath(long maxExpansions) {
        while (!open.isEmpty()) {
            Key top = open.first();
            Node s = node(start);
            if (top.compareKeys(key(start, s)) >= 0 && s.rhs <= s.g) return true;
            if (expansions++ >= maxExpansions) return false;
            long u = top.id(); Node n = nodes.get(u);
            Key fresh = key(u, n);
            if (top.compareKeys(fresh) < 0) {
                open.remove(top); n.key = fresh; open.add(fresh);
            } else if (n.g > n.rhs) {
                n.g = n.rhs; open.remove(top); n.key = null;
                double gu = n.g;
                forEachPred(u, p -> {
                    if (p == GOAL) return;
                    double c = cost(p, u);
                    if (c == INF) return;
                    Node pn = node(p);
                    if (c + gu < pn.rhs) { pn.rhs = c + gu; updateVertex(p, pn); }
                });
            } else {
                double gOld = n.g; n.g = INF;
                forEachPred(u, p -> { Node pn = nodes.get(p); if (pn != null && p != GOAL && pn.rhs == cost(p, u) + gOld) recompute(p); });
                recompute(u);
            }
        }
        return true;
    }

    private List<Long> extractPath(int maxLength) {
        List<Long> path = new ArrayList<>(); long u = start; path.add(u);
        while (path.size() < maxLength) {
            long[] best = {0}; double[] bestCost = {INF}; long cur = u;
            forEachSucc(cur, s -> { double c = cost(cur, s) + g(s); if (c < bestCost[0]) { bestCost[0] = c; best[0] = s; } });
            if (bestCost[0] == INF || best[0] == GOAL) break;
            u = best[0]; path.add(u);
        }
        return path;
    }

    private void recompute(long u) {
        if (u == GOAL) return;
        double best = minSucc(u);
        Node n = nodes.get(u);
        if (n == null) { if (best == INF) return; n = node(u); }
        n.rhs = best; updateVertex(u, n);
    }

    private double minSucc(long u) {
        double[] best = {INF};
        forEachSucc(u, s -> { double c = cost(u, s); if (c != INF) best[0] = Math.min(best[0], c + g(s)); });
        return best[0];
    }

    private void updateVertex(long u, Node n) {
        if (n.key != null) { open.remove(n.key); n.key = null; }
        if (n.g != n.rhs) { n.key = key(u, n); open.add(n.key); }
    }

    private Key key(long u, Node n) { double m = Math.min(n.g, n.rhs); return new Key(m + h(start, u) + km, m, u); }

    private double cost(long u, long v) {
        if (v == GOAL) return isGoal.test(u) && graph.isNode(u) ? 0 : INF;
        Map<Long, Double> fromU = evaluated.get(u);
        if (fromU != null) { Double c = fromU.get(v); if (c != null) return c; }
        return graph.optimisticCost(u, v);
    }

    private double h(long a, long b) { return a == GOAL || b == GOAL ? 0 : graph.heuristic(a, b); }

    private void forEachSucc(long u, LongConsumer out) { if (u == GOAL) return; graph.successors(u, out); if (isGoal.test(u)) out.accept(GOAL); }

    private void forEachPred(long v, LongConsumer out) { if (v == GOAL) { for (long c : goalCandidates) out.accept(c); } else graph.predecessors(v, out); }

    private Node node(long u) {
        Node n = nodes.get(u);
        if (n == null) {
            n = new Node(); nodes.put(u, n);
            if (u != GOAL) nodesByChunk.computeIfAbsent(Pos.chunkKey(Pos.x(u), Pos.z(u)), k -> new ArrayList<>()).add(u);
        }
        return n;
    }
}
```
- [ ] **Step 5: Run** → PASS. If `repairsMatchDijkstraUnderRandomChanges` fails, debug with superpowers:systematic-debugging; do not loosen the assertion.
- [ ] **Step 6: Commit** `feat(core): Lazy D* Lite planner with incremental repair`

---

### Task 5: Move graph (rule-based maneuvers) + safe-spot finder

**Files:** `core/src/main/java/dev/lazuli/core/move/{Costs,MoveGraph,SafeSpotFinder}.java`; tests `move/{MoveGraphTest,SafeSpotFinderTest}.java`.

**Interfaces (produces):**
```java
public final class Costs { WALK_ONE = 20/4.317; SPRINT_ONE = 20/5.612; JUMP_PENALTY = 2.0; SQRT2; static double fallTicks(int blocks); }
public final class MoveGraph implements Graph {
    public MoveGraph(BlockView world, int maxFall, boolean sprint);
    public static boolean passable(BlockKind k);  // AIR || UNKNOWN
    public static boolean floor(BlockKind k);     // SOLID || UNKNOWN
    public static boolean isStandable(BlockView w, int x, int y, int z);
}
public final class SafeSpotFinder {
    /** Forward search from start over known terrain; nearest safe node to the goal, ties by path cost. Empty if none. */
    public static OptionalLong find(MoveGraph graph, BlockView world, long start, Goal goal, int maxNodes);
    public static boolean isSafe(BlockView w, int x, int y, int z); // standable, all 3 blocks known, no DANGER in 3x4x3 around feet
}
```

Rules (u = from, v = to, horizontal step must be one of 8 neighbours):
- node: `floor(y-1) && passable(y) && passable(y+1)`
- flat (dy=0): cardinal cost `flat`; diagonal requires both corner columns passable at y and y+1, cost `flat*SQRT2`
- ascend (dy=+1): cardinal only; needs `passable(u.x, u.y+2, u.z)`; cost `flat + JUMP_PENALTY`
- descend/fall (−maxFall ≤ dy ≤ −1): cardinal only; dest column passable from `v.y+2` to `u.y+1`; cost `flat + fallTicks(-dy)`
- `flat` = sprint ? SPRINT_ONE : WALK_ONE; heuristic = `flat * (max(|dx|,|dz|) + (SQRT2-1)*min(|dx|,|dz|))` (ignores y; consistent)
- successors: 8 dirs × dy ∈ [−maxFall, +1]; predecessors: 8 dirs × dy ∈ [−1, +maxFall] (offset from v); reach: horizontal 1, vertical maxFall+2

- [ ] **Step 1: Failing tests**
```java
private static final long O = Pos.pack(0, 1, 0);
@Test void flatSprintCost() {
    MoveGraph g = new MoveGraph(GridWorld.flatFloor(-5, -5, 5, 5), 3, true);
    assertEquals(Costs.SPRINT_ONE, g.optimisticCost(O, Pos.pack(1, 1, 0)), 1e-9);
    assertEquals(Costs.SPRINT_ONE * Costs.SQRT2, g.optimisticCost(O, Pos.pack(1, 1, 1)), 1e-9);
}
@Test void diagonalBlockedByCorner() {
    GridWorld w = GridWorld.flatFloor(-5, -5, 5, 5).set(1, 1, 0, BlockKind.SOLID);
    assertEquals(Double.POSITIVE_INFINITY, new MoveGraph(w, 3, true).optimisticCost(O, Pos.pack(1, 1, 1)));
}
@Test void ascendNeedsHeadroomAboveStart() {
    GridWorld w = GridWorld.flatFloor(-5, -5, 5, 5).set(1, 1, 0, BlockKind.SOLID);
    MoveGraph g = new MoveGraph(w, 3, true);
    assertEquals(Costs.SPRINT_ONE + Costs.JUMP_PENALTY, g.optimisticCost(O, Pos.pack(1, 2, 0)), 1e-9);
    w.set(0, 3, 0, BlockKind.SOLID);
    assertEquals(Double.POSITIVE_INFINITY, g.optimisticCost(O, Pos.pack(1, 2, 0)));
}
@Test void fallRespectsMaxFallAndColumn() {
    GridWorld w = new GridWorld(BlockKind.AIR).set(0, 10, 0, BlockKind.SOLID).set(1, 6, 0, BlockKind.SOLID).set(2, 5, 0, BlockKind.SOLID);
    MoveGraph g = new MoveGraph(w, 3, true);
    long top = Pos.pack(0, 11, 0);
    assertEquals(Costs.SPRINT_ONE + Costs.fallTicks(4), new MoveGraph(w, 4, true).optimisticCost(top, Pos.pack(1, 7, 0)), 1e-9);
    assertEquals(Double.POSITIVE_INFINITY, g.optimisticCost(top, Pos.pack(1, 7, 0))); // 4 > maxFall 3
}
@Test void dangerAndObstacleAreNotWalkable() {
    GridWorld w = GridWorld.flatFloor(-5, -5, 5, 5).set(1, 1, 0, BlockKind.DANGER).set(-1, 0, 0, BlockKind.DANGER);
    MoveGraph g = new MoveGraph(w, 3, true);
    assertFalse(g.isNode(Pos.pack(1, 1, 0))); assertFalse(g.isNode(Pos.pack(-1, 1, 0)));
}
@Test void unknownIsFreespace() {
    MoveGraph g = new MoveGraph(new GridWorld(BlockKind.UNKNOWN), 3, true);
    assertTrue(g.isNode(Pos.pack(100, 64, 100)));
    assertEquals(Costs.SPRINT_ONE, g.optimisticCost(Pos.pack(100, 64, 100), Pos.pack(101, 64, 100)), 1e-9);
}
@Test void fallTicksIncrease() { assertEquals(5.0, Costs.fallTicks(1), 0); assertTrue(Costs.fallTicks(3) > Costs.fallTicks(2)); }
@Test void heuristicIsAdmissibleOnEveryEdge() {
    MoveGraph g = new MoveGraph(new GridWorld(BlockKind.UNKNOWN), 3, true);
    g.successors(O, v -> { double c = g.optimisticCost(O, v); if (c != Double.POSITIVE_INFINITY) assertTrue(g.heuristic(O, v) <= c + 1e-9); });
}
@Test void predecessorsMirrorSuccessors() {
    MoveGraph g = new MoveGraph(new GridWorld(BlockKind.UNKNOWN), 3, true);
    Set<Long> preds = new HashSet<>(); g.predecessors(O, preds::add);
    g.successors(O, v -> { Set<Long> pv = new HashSet<>(); g.predecessors(v, pv::add); assertTrue(pv.contains(O)); });
}
// SafeSpotFinderTest
@Test void findsNearestSafeSpotInsideAClosedRoom() {
    GridWorld w = GridWorld.flatFloor(0, 0, 4, 4).fill(-1, 1, -1, 5, 2, -1, BlockKind.SOLID).fill(-1, 1, 5, 5, 2, 5, BlockKind.SOLID)
        .fill(-1, 1, -1, -1, 2, 5, BlockKind.SOLID).fill(5, 1, -1, 5, 2, 5, BlockKind.SOLID);
    MoveGraph g = new MoveGraph(w, 3, true);
    OptionalLong spot = SafeSpotFinder.find(g, w, Pos.pack(0, 1, 0), new GoalBlock(20, 1, 2), 10_000);
    assertEquals(Pos.pack(4, 1, 2), spot.orElseThrow());
}
@Test void lavaNeighbourIsUnsafe() {
    GridWorld w = GridWorld.flatFloor(0, 0, 4, 4).set(3, 1, 0, BlockKind.DANGER);
    assertFalse(SafeSpotFinder.isSafe(w, 2, 1, 0)); assertTrue(SafeSpotFinder.isSafe(w, 0, 1, 4));
}
```
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement**
```java
public final class Costs {
    public static final double WALK_ONE = 20 / 4.317, SPRINT_ONE = 20 / 5.612, JUMP_PENALTY = 2.0, SQRT2 = Math.sqrt(2);
    private static final double[] FALL = new double[257];
    static {  // vanilla vertical motion from rest: v = (v - 0.08) * 0.98 each tick
        double v = 0, d = 0; int t = 0;
        for (int n = 1; n < FALL.length; n++) { while (d < n) { v = (v - 0.08) * 0.98; d -= v; t++; } FALL[n] = t; }
    }
    private Costs() {}
    public static double fallTicks(int blocks) { return FALL[blocks]; }
}
```
```java
public final class MoveGraph implements Graph {
    private static final int[][] DIRS = {{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}};
    private final BlockView w; private final int maxFall; private final double flat;
    public MoveGraph(BlockView world, int maxFall, boolean sprint) { this.w = world; this.maxFall = maxFall; this.flat = sprint ? Costs.SPRINT_ONE : Costs.WALK_ONE; }
    public static boolean passable(BlockKind k) { return k == BlockKind.AIR || k == BlockKind.UNKNOWN; }
    public static boolean floor(BlockKind k) { return k == BlockKind.SOLID || k == BlockKind.UNKNOWN; }
    public static boolean isStandable(BlockView w, int x, int y, int z) { return floor(w.get(x, y - 1, z)) && passable(w.get(x, y, z)) && passable(w.get(x, y + 1, z)); }
    private boolean open2(int x, int y, int z) { return passable(w.get(x, y, z)) && passable(w.get(x, y + 1, z)); }
    @Override public boolean isNode(long u) { return isStandable(w, Pos.x(u), Pos.y(u), Pos.z(u)); }
    @Override public double optimisticCost(long u, long v) {
        int ux = Pos.x(u), uy = Pos.y(u), uz = Pos.z(u), dx = Pos.x(v) - ux, dy = Pos.y(v) - uy, dz = Pos.z(v) - uz;
        if (Math.abs(dx) > 1 || Math.abs(dz) > 1 || (dx == 0 && dz == 0)) return LazyDStarLite.INF;
        if (!isNode(u) || !isNode(v)) return LazyDStarLite.INF;
        boolean diag = dx != 0 && dz != 0;
        if (dy == 0) {
            if (diag && !(open2(ux + dx, uy, uz) && open2(ux, uy, uz + dz))) return LazyDStarLite.INF;
            return diag ? flat * Costs.SQRT2 : flat;
        }
        if (diag) return LazyDStarLite.INF;
        if (dy == 1) return passable(w.get(ux, uy + 2, uz)) ? flat + Costs.JUMP_PENALTY : LazyDStarLite.INF;
        if (dy < 0 && -dy <= maxFall) {
            for (int y = Pos.y(v) + 2; y <= uy + 1; y++) if (!passable(w.get(ux + dx, y, uz + dz))) return LazyDStarLite.INF;
            return flat + Costs.fallTicks(-dy);
        }
        return LazyDStarLite.INF;
    }
    @Override public void successors(long u, LongConsumer out) { for (int[] d : DIRS) for (int dy = -maxFall; dy <= 1; dy++) out.accept(Pos.offset(u, d[0], dy, d[1])); }
    @Override public void predecessors(long v, LongConsumer out) { for (int[] d : DIRS) for (int dy = -1; dy <= maxFall; dy++) out.accept(Pos.offset(v, d[0], dy, d[1])); }
    @Override public double heuristic(long a, long b) {
        int dx = Math.abs(Pos.x(a) - Pos.x(b)), dz = Math.abs(Pos.z(a) - Pos.z(b));
        return flat * (Math.max(dx, dz) + (Costs.SQRT2 - 1) * Math.min(dx, dz));
    }
    @Override public int horizontalReach() { return 1; }
    @Override public int verticalReach() { return maxFall + 2; }
}
```
```java
public final class SafeSpotFinder {
    private SafeSpotFinder() {}
    public static boolean isSafe(BlockView w, int x, int y, int z) {
        if (!MoveGraph.isStandable(w, x, y, z)) return false;
        for (int dy = -1; dy <= 1; dy++) if (w.get(x, y + dy, z) == BlockKind.UNKNOWN) return false;
        for (int ox = -1; ox <= 1; ox++) for (int oy = -1; oy <= 2; oy++) for (int oz = -1; oz <= 1; oz++)
            if (w.get(x + ox, y + oy, z + oz) == BlockKind.DANGER) return false;
        return true;
    }
    public static OptionalLong find(MoveGraph graph, BlockView w, long start, Goal goal, int maxNodes) {
        Map<Long, Double> dist = new HashMap<>(); PriorityQueue<Object[]> pq = new PriorityQueue<>(Comparator.comparingDouble(a -> (double) a[0]));
        dist.put(start, 0.0); pq.add(new Object[]{0.0, start});
        long best = 0; double bestDist = Double.POSITIVE_INFINITY, bestCost = Double.POSITIVE_INFINITY; boolean found = false; int popped = 0;
        while (!pq.isEmpty() && popped++ < maxNodes) {
            Object[] top = pq.poll(); double d = (double) top[0]; long u = (long) top[1];
            if (d > dist.get(u)) continue;
            int x = Pos.x(u), y = Pos.y(u), z = Pos.z(u);
            if (isSafe(w, x, y, z)) {
                double gd = goal.distance(x, y, z);
                if (gd < bestDist || (gd == bestDist && d < bestCost)) { best = u; bestDist = gd; bestCost = d; found = true; }
            }
            graph.successors(u, v -> {
                if (w.get(Pos.x(v), Pos.y(v) - 1, Pos.z(v)) == BlockKind.UNKNOWN) return;  // only proven terrain
                double c = graph.optimisticCost(u, v);
                if (c == Double.POSITIVE_INFINITY) return;
                double nd = d + c;
                if (nd < dist.getOrDefault(v, Double.POSITIVE_INFINITY)) { dist.put(v, nd); pq.add(new Object[]{nd, v}); }
            });
        }
        return found ? OptionalLong.of(best) : OptionalLong.empty();
    }
}
```
- [ ] **Step 4: Run** → PASS. **Step 5: Commit** `feat(core): rule-based move graph and safe-spot fallback`

---

### Task 6: Settings

**Files:** `core/src/main/java/dev/lazuli/core/settings/{Setting,Settings}.java`; test `settings/SettingsTest.java`.

**Interfaces (produces):**
```java
public final class Setting<T> { String name(); T get(); T defaultValue(); void set(T v); void parse(String s) /* IllegalArgumentException with readable message */; String format(); }
public final class Settings {
    public final Setting<Boolean> allowSprint;        // true
    public final Setting<Integer> maxFallHeight;      // 3, range 0..20
    public final Setting<Boolean> cancelOnUserInput;  // true
    public final Setting<Double>  rotationSpeed;      // 30.0 deg/tick max, range 1..180
    public final Setting<Integer> evalHorizon;        // 32, range 1..4096
    public final Setting<Boolean> renderPath;         // true
    public final Setting<String>  prefix;             // "#", must be non-empty and contain no spaces
    public Collection<Setting<?>> all();
    public Optional<Setting<?>> byName(String name);  // case-insensitive
    public Map<String, String> toMap();
    public void load(Map<String, String> values);     // ignores unknown names and invalid values
}
```
- [ ] **Step 1: Failing tests**
```java
@Test void defaults() { Settings s = new Settings(); assertTrue(s.allowSprint.get()); assertEquals(3, s.maxFallHeight.get()); assertEquals("#", s.prefix.get()); }
@Test void parsesAndRejects() {
    Settings s = new Settings();
    s.byName("MAXFALLHEIGHT").orElseThrow().parse("5"); assertEquals(5, s.maxFallHeight.get());
    assertThrows(IllegalArgumentException.class, () -> s.maxFallHeight.parse("99"));
    assertThrows(IllegalArgumentException.class, () -> s.allowSprint.parse("maybe"));
    assertThrows(IllegalArgumentException.class, () -> s.prefix.parse("a b"));
}
@Test void roundTripsThroughMapAndIgnoresJunk() {
    Settings a = new Settings(); a.rotationSpeed.parse("12.5"); a.allowSprint.parse("false");
    Map<String, String> m = new HashMap<>(a.toMap()); m.put("nope", "1"); m.put("evalHorizon", "x");
    Settings b = new Settings(); b.load(m);
    assertEquals(12.5, b.rotationSpeed.get()); assertFalse(b.allowSprint.get()); assertEquals(32, b.evalHorizon.get());
}
```
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement** — `Setting<T>` holds name, default, value (volatile), a `Function<String,T>` parser and `Predicate<T>` validator with message; `Settings` builds each via helpers `bool(name, def)`, `integer(name, def, min, max)`, `decimal(name, def, min, max)`, `string(name, def, validator)` into a `LinkedHashMap<String lowercased, Setting<?>>`. Boolean parsing accepts only `true`/`false` (case-insensitive). `load` wraps each `parse` in try/catch and skips failures.
- [ ] **Step 4: Run** → PASS. **Step 5: Commit** `feat(core): settings`

---

### Task 7: Pathing session (worker thread, fallback, goal reached)

**Files:** `core/src/main/java/dev/lazuli/core/session/PathingSession.java`; test `session/PathingSessionTest.java`.

**Interfaces:**
- Consumes: `LazyDStarLite`, `MoveGraph`, `SafeSpotFinder`, `Goal`, `Settings`, `BlockView`, `WorldCache.minY()/maxY()` via constructor args.
- Produces:
```java
public final class PathingSession {
    public enum State { IDLE, PATHING, ARRIVED, FAILED }
    public record Snapshot(State state, List<Long> path, Goal goal, long version) {}
    public static final long MAX_EXPANSIONS = 1_000_000;
    public static final int MAX_PATH_LENGTH = 512;
    public static final int FALLBACK_NODES = 200_000;
    public PathingSession(BlockView world, IntSupplier minY, IntSupplier maxY, Settings settings, Executor worker, Consumer<String> messages);
    public void start(Goal goal, boolean fallback, long playerPos);
    public void stop();
    public void playerMoved(long playerPos);              // call when feet block changes while on ground
    public void regionChanged(int minX, int minY, int minZ, int maxX, int maxY, int maxZ);
    public Snapshot snapshot();                           // volatile, safe from any thread
}
```
Behaviour (all mutation on `worker`):
- `start`: build `MoveGraph(world, maxFallHeight, allowSprint)`, collect goal candidates via `goal.forEachCandidate(px,py,pz,minY,maxY)`, create planner, message `"Pathing to " + goal.describe()`, replan.
- replan: if planner null → nothing. If `goal.isGoal(player)` → state ARRIVED, message `"Arrived at " + describe`, drop planner. Else `plan(evalHorizon, MAX_EXPANSIONS, MAX_PATH_LENGTH)`: FOUND → PATHING snapshot. NO_PATH/BUDGET_EXCEEDED → if `fallback` and not already falling back: `SafeSpotFinder.find(graph, world, player, goal, FALLBACK_NODES)`; present → message `"Goal unreachable; heading to nearest safe spot X Y Z"`, restart with `GoalBlock(spot)`, fallback disabled; absent or fallback=false → FAILED, message `"Goal unreachable"` (NO_PATH) or `"Gave up: search budget exceeded"` (BUDGET), planner dropped.
- `regionChanged` and `playerMoved` coalesce: they apply to the planner immediately on the worker and schedule a single replan (AtomicBoolean `replanQueued`).
- `stop`: planner null, state IDLE, message `"Stopped"`. Every snapshot increments `version`.

- [ ] **Step 1: Failing tests** (worker = `Runnable::run`)
```java
private final List<String> msgs = new ArrayList<>();
private PathingSession session(BlockView w) { return new PathingSession(w, () -> -64, () -> 319, new Settings(), Runnable::run, msgs::add); }

@Test void findsPathOnFlatGround() {
    PathingSession s = session(GridWorld.flatFloor(0, 0, 20, 20));
    s.start(new GoalBlock(15, 1, 3), false, Pos.pack(0, 1, 0));
    assertEquals(PathingSession.State.PATHING, s.snapshot().state());
    assertEquals(Pos.pack(15, 1, 3), s.snapshot().path().get(s.snapshot().path().size() - 1));
}
@Test void goalInUnknownTerrainIsPlanned() {
    GridWorld w = new GridWorld(BlockKind.UNKNOWN).fill(-2, 0, -2, 2, 0, 2, BlockKind.SOLID).fill(-2, 1, -2, 2, 3, 2, BlockKind.AIR);
    PathingSession s = session(w);
    s.start(new GoalBlock(200, 1, 0), false, Pos.pack(0, 1, 0));
    assertEquals(PathingSession.State.PATHING, s.snapshot().state());
}
@Test void blockOnPathTriggersRepairAroundIt() {
    GridWorld w = GridWorld.flatFloor(0, -5, 10, 5);
    PathingSession s = session(w);
    s.start(new GoalBlock(10, 1, 0), false, Pos.pack(0, 1, 0));
    w.fill(5, 1, -5, 5, 2, 4, BlockKind.SOLID);  // wall with gap at z=5
    s.regionChanged(5, 1, -5, 5, 2, 4);
    assertTrue(s.snapshot().path().contains(Pos.pack(5, 1, 5)));
}
@Test void enclosedPlayerUsesFallback() {
    GridWorld w = GridWorld.flatFloor(0, 0, 4, 4).fill(-1, 1, -1, 5, 3, -1, BlockKind.SOLID).fill(-1, 1, 5, 5, 3, 5, BlockKind.SOLID)
        .fill(-1, 1, -1, -1, 3, 5, BlockKind.SOLID).fill(5, 1, -1, 5, 3, 5, BlockKind.SOLID).fill(-1, 4, -1, 5, 4, 5, BlockKind.SOLID);
    PathingSession s = session(w);
    s.start(new GoalBlock(30, 1, 2), true, Pos.pack(0, 1, 0));
    assertEquals(new GoalBlock(4, 1, 2), s.snapshot().goal());
    assertTrue(msgs.stream().anyMatch(m -> m.contains("nearest safe spot")));
}
@Test void unreachableWithoutFallbackFails() {
    GridWorld w = GridWorld.flatFloor(0, 0, 4, 4);
    PathingSession s = session(w);
    s.start(new GoalBlock(30, 1, 2), false, Pos.pack(0, 1, 0));
    assertEquals(PathingSession.State.FAILED, s.snapshot().state());
    assertTrue(msgs.contains("Goal unreachable"));
}
@Test void arrivesWhenPlayerReachesGoal() {
    PathingSession s = session(GridWorld.flatFloor(0, 0, 5, 5));
    s.start(new GoalBlock(2, 1, 0), false, Pos.pack(0, 1, 0));
    s.playerMoved(Pos.pack(1, 1, 0)); s.playerMoved(Pos.pack(2, 1, 0));
    assertEquals(PathingSession.State.ARRIVED, s.snapshot().state());
}
```
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement** per behaviour above. **Step 4: Run** → PASS. **Step 5: Commit** `feat(core): pathing session with fallback`

---

### Task 8: Path follower + rotation controller

**Files:** `core/src/main/java/dev/lazuli/core/control/{PlayerState,Controls,PathFollower,RotationController}.java`; tests `control/{PathFollowerTest,RotationControllerTest}.java`.

**Interfaces (produces):**
```java
public record PlayerState(double x, double y, double z, float yaw, float pitch, boolean onGround, boolean horizontalCollision) {}
public record Controls(boolean forward, boolean jump, boolean sprint, float targetYaw, float targetPitch) {}
public final class PathFollower {
    /** path.get(0) is the player's current node. */
    public Controls tick(PlayerState p, List<Long> path, boolean allowSprint);
    public static float yawTo(double fromX, double fromZ, double toX, double toZ); // MC convention: atan2(dz,dx) deg - 90
}
public final class RotationController {
    /** Returns {dYaw, dPitch} for this tick. |d| <= maxSpeed; velocity changes by <= maxSpeed/3 per tick. */
    public float[] step(float yaw, float pitch, float targetYaw, float targetPitch, float maxSpeed);
    public void reset();
    public static float wrap(float deg); // to (-180,180]
}
```
Follower rules: target = center of `path[1]`; if horizontal distance to it < 0.6 and `path[2]` exists at the same y, blend target toward `path[2]` center by `t = 0.5*(1 - dist/0.6)` (early turn-in). `yawErr = wrap(targetYaw - p.yaw)`. forward if `|yawErr| < 60`; sprint if `allowSprint && |yawErr| < 15`; jump if `onGround && path[1].y > feetY && (horizDist < 1.3 || horizontalCollision)`; target pitch `10`. If `path.size() < 2` → all false, keep current yaw/pitch. On the last node, stop forward when horizDist < 0.25.
Rotation: `desired = clamp(err * 0.45, ±maxSpeed)`; `vel += clamp(desired - vel, ±maxSpeed/3)`; same per axis; pitch result clamped to [-90,90]; if `|err| < 0.05` and `|vel| < 0.5` snap to target and zero vel.

- [ ] **Step 1: Failing tests**
```java
// RotationControllerTest
@Test void wrapsShortestWay() { assertEquals(-20f, RotationController.wrap(340f), 1e-4); assertEquals(180f, RotationController.wrap(-180f), 1e-4); }
@Test void neverExceedsMaxSpeedAndConverges() {
    RotationController rc = new RotationController(); float yaw = 0, pitch = 0;
    for (int i = 0; i < 60; i++) { float[] d = rc.step(yaw, pitch, 179f, 30f, 30f); assertTrue(Math.abs(d[0]) <= 30f + 1e-4); yaw += d[0]; pitch += d[1]; }
    assertEquals(179f, yaw, 0.1); assertEquals(30f, pitch, 0.1);
}
@Test void turnsAcrossTheSeam() {
    RotationController rc = new RotationController(); float[] d = rc.step(170f, 0, -170f, 0, 30f); assertTrue(d[0] > 0);
}
// PathFollowerTest
private static List<Long> path(long... p) { List<Long> l = new ArrayList<>(); for (long x : p) l.add(x); return l; }
@Test void faceAndSprintTowardNextNode() {
    PlayerState s = new PlayerState(0.5, 1, 0.5, -90f, 0, true, false);  // facing +X
    Controls c = new PathFollower().tick(s, path(Pos.pack(0, 1, 0), Pos.pack(1, 1, 0), Pos.pack(2, 1, 0)), true);
    assertEquals(-90f, c.targetYaw(), 1.0); assertTrue(c.forward()); assertTrue(c.sprint()); assertFalse(c.jump());
}
@Test void jumpsForAscend() {
    PlayerState s = new PlayerState(0.5, 1, 0.5, -90f, 0, true, false);
    assertTrue(new PathFollower().tick(s, path(Pos.pack(0, 1, 0), Pos.pack(1, 2, 0)), true).jump());
}
@Test void noSprintWhileTurningHard() {
    PlayerState s = new PlayerState(0.5, 1, 0.5, 90f, 0, true, false);  // facing -X, target +X
    Controls c = new PathFollower().tick(s, path(Pos.pack(0, 1, 0), Pos.pack(1, 1, 0)), true);
    assertFalse(c.sprint()); assertFalse(c.forward());
}
@Test void idleWithoutPath() { assertFalse(new PathFollower().tick(new PlayerState(0, 1, 0, 0, 0, true, false), path(Pos.pack(0, 1, 0)), true).forward()); }
```
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement** per rules. **Step 4: Run** → PASS. **Step 5: Commit** `feat(core): path follower and smooth rotation`

---

### Task 9: Commands

**Files:** `core/src/main/java/dev/lazuli/core/command/{GoalParser,CommandHandler}.java`; tests `command/{GoalParserTest,CommandHandlerTest}.java`.

**Interfaces (produces):**
```java
public record ParsedGoal(Goal goal, boolean fallback) {}   // nested in GoalParser; goal == null means "clear"
public final class GoalParser { public static ParsedGoal parse(String[] args, int px, int py, int pz); } // throws IllegalArgumentException(usage/message)
public final class CommandHandler {
    public interface Context {
        int[] playerFeet();                 // {x,y,z}
        BlockView world();
        PathingSession session();
        Settings settings();
        void reply(String msg);
        void settingsChanged();             // persist
    }
    public CommandHandler(Context ctx);
    /** Returns true if the message was a Lazuli command (and must not be sent to the server). */
    public boolean handle(String message);
    public Goal goal();
}
```
Parsing: optional trailing `true`/`false` (case-insensitive) = fallback (default false). Remaining: `clear` → clear; 0 args → `GoalBlock(player)`; 1 → `GoalYLevel`; 2 → `GoalXZ`; 3 → `GoalBlock`; else usage error `Usage: goal [x y z | x z | y | clear] [true|false]`. Coordinates: integer, `~` (player axis), `~N`. Bad token → `"Not a coordinate: <tok>"`.
Commands (message must start with `settings.prefix`; otherwise return false):
- `goal …` → set; reply `"Goal set: <describe>" + (fallback ? " (fallback on)" : "")`; for `GoalBlock` with fallback=false whose 3 blocks are all known (non-UNKNOWN) and `!MoveGraph.isStandable` → reply `"Can't stand at x y z (use true to allow nearest safe spot)"` and don't set.
- `path` → no goal: `"No goal set"`; else `session.start(goal, fallback, Pos.pack(feet))`.
- `goto …` → goal then path.
- `stop`/`cancel` → `session.stop()`.
- `set <name>` → reply `"name = value"`; `set <name> <value>` → parse, reply `"name = value"`, `settingsChanged()`; unknown name → `"Unknown setting: name"`; parse error → message.
- `settings` → one reply line per setting `name = value`.
- `help` → command list. Unknown → `"Unknown command: X. Try <prefix>help"`.

- [ ] **Step 1: Failing tests**
```java
// GoalParserTest
@Test void forms() {
    assertEquals(new GoalBlock(1, 2, 3), GoalParser.parse(new String[]{"1", "2", "3"}, 0, 0, 0).goal());
    assertEquals(new GoalXZ(1, -3), GoalParser.parse(new String[]{"1", "-3"}, 0, 0, 0).goal());
    assertEquals(new GoalYLevel(12), GoalParser.parse(new String[]{"12"}, 0, 0, 0).goal());
    assertEquals(new GoalBlock(5, 6, 7), GoalParser.parse(new String[]{}, 5, 6, 7).goal());
    assertNull(GoalParser.parse(new String[]{"clear"}, 0, 0, 0).goal());
}
@Test void relativeAndFallback() {
    GoalParser.ParsedGoal p = GoalParser.parse(new String[]{"~", "~-2", "~10", "TRUE"}, 100, 64, -5);
    assertEquals(new GoalBlock(100, 62, 5), p.goal()); assertTrue(p.fallback());
    assertFalse(GoalParser.parse(new String[]{"1", "2", "3", "false"}, 0, 0, 0).fallback());
}
@Test void garbage() {
    assertThrows(IllegalArgumentException.class, () -> GoalParser.parse(new String[]{"a", "b", "c"}, 0, 0, 0));
    assertThrows(IllegalArgumentException.class, () -> GoalParser.parse(new String[]{"1", "2", "3", "4"}, 0, 0, 0));
}
// CommandHandlerTest — fake Context over GridWorld.flatFloor(0,0,20,20), feet (0,1,0), worker Runnable::run, collects replies
@Test void nonCommandsPassThrough() { assertFalse(handler().handle("hello")); }
@Test void gotoStartsPathing() { assertTrue(handler().handle("#goto 10 1 0")); assertEquals(PathingSession.State.PATHING, ctx.session().snapshot().state()); }
@Test void pathWithoutGoal() { handler().handle("#path"); assertTrue(replies.contains("No goal set")); }
@Test void rejectsUnstandableExactGoal() { handler().handle("#goal 5 0 5"); assertTrue(replies.get(0).startsWith("Can't stand at")); }
@Test void setPersists() { handler().handle("#set maxFallHeight 5"); assertEquals(5, ctx.settings().maxFallHeight.get()); assertEquals(1, saved); }
@Test void customPrefix() { CommandHandler h = handler(); h.handle("#set prefix !"); assertTrue(h.handle("!stop")); assertFalse(h.handle("#stop")); }
```
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement**. **Step 4: Run** `./gradlew :core:test` (whole suite) → PASS. **Step 5: Commit** `feat(core): baritone-style commands`

---

### Task 10: Mod world adapter (classifier, chunk scanner, block-change mixin)

**Files:** Create `mod/src/main/java/dev/lazuli/mod/world/{BlockClassifier,ChunkScanner}.java`, `mod/src/main/java/dev/lazuli/mod/mixin/ClientLevelMixin.java`; modify `lazuli.mixins.json` (`"client": ["ClientLevelMixin"]`).

**Interfaces:**
- Produces: `BlockClassifier.classify(BlockState): BlockKind` (identity-cached); `ChunkScanner.scan(LevelChunk, int sectionCount): byte[][]`; mixin calls `LazuliClient.onBlockChanged(ClientLevel, BlockPos, BlockState)` (static, defined in Task 11).
- Verified 1.21.11 APIs: `BlockState.isAir()`, `getFluidState().is(FluidTags.LAVA/WATER)`, `getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)`, `VoxelShape.isEmpty()`, `Block.isShapeFullBlock(VoxelShape)`, `ChunkAccess.getSections()`, `LevelChunkSection.hasOnlyAir()/getBlockState(x,y,z)`, `ClientLevel.setBlocksDirty(BlockPos, BlockState old, BlockState new)` (called on every client block change regardless of flags).

- [ ] **Step 1: Implement classifier**
```java
public final class BlockClassifier {
    private static final Map<BlockState, BlockKind> CACHE = new ConcurrentHashMap<>();
    private static final Set<Block> DANGER = Set.of(Blocks.FIRE, Blocks.SOUL_FIRE, Blocks.MAGMA_BLOCK, Blocks.CACTUS, Blocks.SWEET_BERRY_BUSH,
        Blocks.COBWEB, Blocks.POWDER_SNOW, Blocks.CAMPFIRE, Blocks.SOUL_CAMPFIRE, Blocks.WITHER_ROSE, Blocks.LAVA);
    private BlockClassifier() {}
    public static BlockKind classify(BlockState state) { return CACHE.computeIfAbsent(state, BlockClassifier::compute); }
    private static BlockKind compute(BlockState s) {
        if (DANGER.contains(s.getBlock()) || s.getFluidState().is(FluidTags.LAVA)) return BlockKind.DANGER;
        if (s.getFluidState().is(FluidTags.WATER)) return BlockKind.WATER;
        if (s.isAir()) return BlockKind.AIR;
        VoxelShape shape = s.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        if (shape.isEmpty()) return BlockKind.AIR;
        return Block.isShapeFullBlock(shape) ? BlockKind.SOLID : BlockKind.OBSTACLE;
    }
}
```
- [ ] **Step 2: Implement scanner**
```java
public final class ChunkScanner {
    private ChunkScanner() {}
    public static byte[][] scan(LevelChunk chunk, int sectionCount) {
        LevelChunkSection[] sections = chunk.getSections();
        byte[][] out = new byte[sectionCount][];
        for (int i = 0; i < Math.min(sectionCount, sections.length); i++) {
            LevelChunkSection sec = sections[i];
            if (sec == null || sec.hasOnlyAir()) continue;
            byte[] kinds = new byte[4096];
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
                kinds[WorldCache.index(x, y, z)] = (byte) BlockClassifier.classify(sec.getBlockState(x, y, z)).ordinal();
            out[i] = kinds;
        }
        return out;
    }
}
```
- [ ] **Step 3: Implement mixin**
```java
@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {
    @Inject(method = "setBlocksDirty", at = @At("HEAD"))
    private void lazuli$onBlockChanged(BlockPos pos, BlockState oldState, BlockState newState, CallbackInfo ci) {
        LazuliClient.onBlockChanged((ClientLevel) (Object) this, pos, newState);
    }
}
```
- [ ] **Step 4: Build** — will not compile until Task 11 adds `onBlockChanged`; do Tasks 10 and 11 back-to-back and build at the end of Task 11. Commit after Task 11.

---

### Task 11: Mod glue (entrypoint, input driver, executor, settings store)

**Files:** Replace `mod/src/main/java/dev/lazuli/mod/LazuliClient.java`; create `mod/src/main/java/dev/lazuli/mod/SettingsStore.java`, `mod/src/main/java/dev/lazuli/mod/control/{InputDriver,PathExecutor}.java`.

**Interfaces:**
- Consumes: everything from core; Fabric API `ClientSendMessageEvents.ALLOW_CHAT` (`boolean allowSendChatMessage(String)`), `ClientChunkEvents.CHUNK_LOAD/CHUNK_UNLOAD` (`(ClientLevel, LevelChunk)`), `ClientTickEvents.END_CLIENT_TICK` (`(Minecraft)`), `ClientPlayConnectionEvents.DISCONNECT`, `KeyBindingHelper.getBoundKeyOf(KeyMapping)`; MC `KeyMapping.setDown(boolean)`, `InputConstants.isKeyDown(Window, int)`, `Entity.turn(double,double)` (multiplies by 0.15), `Minecraft.gui.getChat().addMessage(Component)`, `Minecraft.execute(Runnable)`, `player.blockPosition()`, `onGround()`, `horizontalCollision`, `getFoodData().getFoodLevel()`, `ClientLevel.addParticle(ParticleTypes.HAPPY_VILLAGER, x,y,z,0,0,0)`.

Behaviour:
- `LazuliClient` (static singleton `INSTANCE`): creates `WorldCache(-64,384)`, `Settings` loaded by `SettingsStore` from `FabricLoader.getInstance().getConfigDir().resolve("lazuli.json")` (Gson `Map<String,String>`), a single daemon thread executor named `lazuli-planner`, `PathingSession` (messages → `mc.execute(() -> chat("[Lazuli] " + m))`), `CommandHandler`, `PathExecutor`.
  - `ALLOW_CHAT`: `return !commands.handle(message);` (wrap in try/catch; on exception reply `"Error: " + e.getMessage()` and return false).
  - `CHUNK_LOAD`: if `level != lastLevel` → `cache.reset(level.getMinY(), level.getHeight())`, `session.stop()`, `lastLevel = level`; then `cache.putChunk(cx, cz, ChunkScanner.scan(chunk, cache.sectionCount()))`; `session.regionChanged(cx*16, minY, cz*16, cx*16+15, maxY, cz*16+15)`.
  - `CHUNK_UNLOAD`: `cache.removeChunk`, same `regionChanged`.
  - `static onBlockChanged(level, pos, state)`: if level == lastLevel → `cache.set(...)` + `session.regionChanged(pos, pos)`.
  - `END_CLIENT_TICK`: `executor.tick(mc)`.
  - `DISCONNECT`: `session.stop()`, `executor.release()`.
- `InputDriver`: `apply(Minecraft, Controls, float[] rot)` sets `keyUp/keySprint/keyJump.setDown`, keeps `keyDown/keyLeft/keyRight/keyShift` false, applies `player.turn(dYaw / 0.15, dPitch / 0.15)`; `release(Minecraft)` restores every driven key to its physical state via `InputConstants.isKeyDown(window, KeyBindingHelper.getBoundKeyOf(k).getValue())` (only for keyboard-type keys); `userInterfering(Minecraft)`: any of up/down/left/right/jump physically pressed.
- `PathExecutor.tick(mc)`: snapshot state != PATHING → if was driving, `release` and reset rotation; return. If `settings.cancelOnUserInput` and `userInterfering` → `session.stop()`, `release`. Report `player.blockPosition()` to `session.playerMoved` when it changes and player is on ground. Build `PlayerState`, call `follower.tick(state, path, allowSprint && food > 6)`, `rotation.step(yaw, pitch, targetYaw, targetPitch, rotationSpeed)`, `input.apply`. If `renderPath` and `tickCount % 10 == 0`, spawn a `HAPPY_VILLAGER` particle at each of the first 64 path node centers (`x+0.5, y+0.1, z+0.5`).

- [ ] **Step 1: Implement the four classes** per behaviour above.
- [ ] **Step 2: Build** `./gradlew build` → `BUILD SUCCESSFUL`.
- [ ] **Step 3: Commit** `feat(mod): world adapter, commands, input and path executor`

---

### Task 12: In-game verification

- [ ] **Step 1:** `JAVA_HOME="C:/Program Files/Java/jdk-25.0.2" ./gradlew :mod:runClient`, create a Creative superflat world, switch to Survival.
- [ ] **Step 2: Checks** (record pass/fail in the PR/commit message):
  1. `#goto ~20 ~ ~5` on flat → sprints there, turns smoothly, says `Arrived at …`; chat message never appears for others (check: it isn't echoed as a sent chat line).
  2. Build a 1-high step and a 3-deep pit on the route → jumps up, drops down, never takes a 4+ drop.
  3. Wall across the route while walking → path reroutes without stopping.
  4. `#goto 2000 ~ 0` → starts immediately (freespace), keeps going as chunks load.
  5. Enclose yourself in glass, `#goto ~50 ~ ~ true` → walks to nearest inside spot with the fallback message; without `true` → `Goal unreachable`.
  6. Press W while pathing → stops, keys released (character doesn't keep running).
  7. Lava pool on the route → avoided.
  8. `#set rotationSpeed 8` → visibly slower turns; restart client → setting persisted in `config/lazuli.json`.
- [ ] **Step 3:** Fix any failures via superpowers:systematic-debugging (add a core test reproducing each bug first), then commit `test: plan 1 in-game verification`.
