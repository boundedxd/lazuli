package dev.lazuli.mod;

import dev.lazuli.api.Lazuli;
import dev.lazuli.core.command.CommandHandler;
import dev.lazuli.core.goal.Goal;
import dev.lazuli.core.inventory.ItemCount;
import dev.lazuli.core.inventory.Moves;
import dev.lazuli.core.task.Task;
import dev.lazuli.mod.inventory.ClickDriver;
import dev.lazuli.mod.inventory.Inventories;
import dev.lazuli.core.task.Tasks;
import dev.lazuli.core.world.Pos;
import dev.lazuli.core.session.PathingSession;
import dev.lazuli.core.settings.Settings;
import dev.lazuli.core.world.BlockView;
import dev.lazuli.core.world.WorldCache;
import dev.lazuli.mod.config.LazuliConfig;
import dev.lazuli.core.chat.Chat;
import dev.lazuli.mod.control.ClickGoal;
import dev.lazuli.mod.control.FreeCam;
import dev.lazuli.mod.control.MineProcess;
import dev.lazuli.mod.process.ExploreProcess;
import dev.lazuli.mod.process.ContainerProcess;
import dev.lazuli.mod.process.CraftProcess;
import dev.lazuli.mod.process.SmeltProcess;
import dev.lazuli.mod.process.GetItemProcess;
import dev.lazuli.mod.craft.RecipeData;
import dev.lazuli.mod.process.FarmProcess;
import dev.lazuli.mod.process.SleepProcess;
import dev.lazuli.mod.process.FollowProcess;
import dev.lazuli.mod.process.Process;
import dev.lazuli.mod.process.TunnelProcess;
import dev.lazuli.mod.process.Upkeep;
import dev.lazuli.mod.control.PathExecutor;
import dev.lazuli.mod.control.RotationDriver;
import dev.lazuli.mod.control.Tools;
import dev.lazuli.mod.control.TraceRecorder;
import dev.lazuli.mod.render.LazuliRenderer;
import main.walksy.lib.core.gui.WalksyLibPanelScreen;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.resources.Identifier;
import dev.lazuli.mod.world.BlockClassifier;
import dev.lazuli.mod.world.ChunkScanner;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import java.nio.file.Path;
import dev.lazuli.core.waypoint.Waypoints;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class LazuliClient implements ClientModInitializer {
	private static LazuliClient instance;

	private final WorldCache cache = new WorldCache(-64, 384);
	private final Settings settings = LazuliConfig.SETTINGS;
	/** The planner thread; #kill swaps in a new one if it hangs. */
	private volatile ExecutorService workerPool = newWorker();
	private final java.util.concurrent.Executor worker = r -> workerPool.execute(r);

	/** The planner thread itself, to say where it hung. */
	private static volatile Thread plannerThread;

	private static ExecutorService newWorker() {
		return Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, "lazuli-planner");
			t.setDaemon(true);
			plannerThread = t;
			return t;
		});
	}

	/** Swaps in a new planner thread (the old one is interrupted; if it was busy, where it was goes in the log). */
	private void freshWorker(String why) {
		Thread t = plannerThread;
		if (t != null && t.getState() == Thread.State.RUNNABLE) {
			StringBuilder b = new StringBuilder("planner thread busy when " + why + ":");
			for (StackTraceElement e : t.getStackTrace()) b.append(System.lineSeparator()).append("	at ").append(e);
			org.slf4j.LoggerFactory.getLogger("lazuli").warn(b.toString());
		}
		ExecutorService old = workerPool;
		workerPool = newWorker();
		old.shutdownNow(); // interrupts a hung search; whatever it finds is stale and never published
	}
	private TraceRecorder recorder;
	private LazuliRenderer renderer;
	private final java.util.Map<String, Waypoints> waypointsByWorld = new java.util.HashMap<>();
	private boolean wasDead;
	private final java.util.Map<String, dev.lazuli.core.storage.ChestMemory> chestsByWorld = new java.util.HashMap<>();
	private dev.lazuli.mod.inventory.ChestWatcher chestWatcher;
	private boolean openConfigNextTick;
	private PathingSession session;
	private CommandHandler commands;
	private PathExecutor executor;
	private MineProcess mine;
	private FollowProcess follow;
	private ExploreProcess explore;
	private TunnelProcess tunnel;
	private FarmProcess farm;
	private SleepProcess sleeps;
	private dev.lazuli.mod.process.DeathRecovery recovery;
	private dev.lazuli.mod.process.StrongholdProcess strongholds;
	private dev.lazuli.mod.process.Guard guard;
	private ContainerProcess containers;
	private CraftProcess crafts;
	private SmeltProcess smelts;
	private dev.lazuli.mod.process.SmithProcess smiths;
	private dev.lazuli.mod.process.BucketProcess buckets;
	private dev.lazuli.mod.process.BrewProcess brews;
	private dev.lazuli.mod.process.EnchantProcess enchants;
	private dev.lazuli.mod.process.ElytraProcess elytra;
	private GetItemProcess getItem;
	private dev.lazuli.mod.process.HuntProcess hunt;
	private dev.lazuli.mod.process.TradeProcess trades;
	private dev.lazuli.mod.inventory.TradeWatcher tradeWatcher;
	private final java.util.Map<String, dev.lazuli.core.trade.TradeMemory> tradesByWorld = new java.util.HashMap<>();
	private Upkeep upkeep;
	private dev.lazuli.mod.process.RestockProcess restock;
	/** Every ore seen in this world (kept after its chunk unloads): #mine looks here first. */
	private final dev.lazuli.mod.world.OreIndex ores = new dev.lazuli.mod.world.OreIndex();
	private dev.lazuli.mod.process.SortProcess sorts;
	private dev.lazuli.mod.process.BuildProcess builds;
	private dev.lazuli.mod.process.BreedProcess breeds;
	private dev.lazuli.mod.process.FishProcess fishing;
	private dev.lazuli.mod.process.PortalProcess portals;
	private dev.lazuli.mod.process.IceBoatProcess iceboat;
	private final dev.lazuli.core.task.RoutineRunner routine = new dev.lazuli.core.task.RoutineRunner();
	private final dev.lazuli.core.task.Routines routines = new dev.lazuli.core.task.Routines(
		FabricLoader.getInstance().getConfigDir().resolve("lazuli").resolve("routines.txt"));
	private final java.util.Map<String, dev.lazuli.core.portal.PortalMemory> portalsByWorld = new java.util.HashMap<>();
	/** Every process; at most one runs at a time. */
	private java.util.List<Process> processes;
	/** "[Lazuli] " in blue. */
	private static final String PREFIX = Chat.fmt(Chat.DARK_GRAY, "[") + "\u00a79Lazuli" + Chat.DARK_GRAY + "] " + Chat.GRAY;
	private ClientLevel lastLevel;
	/** The job started through the API (see {@link Lazuli}). */
	private final Tasks tasks = new Tasks();
	private ClickDriver clicks;

	@Override
	public void onInitializeClient() {
		instance = this;
		LazuliConfig.stopAction = () -> stopAll(true);
		LazuliConfig.routines = routines;
		routine.setSettings(settings);
		LazuliConfig.runRoutine = (name, times) -> {
			java.util.List<String> steps = routines.get(name);
			if (steps == null || steps.stream().allMatch(String::isBlank)) chat(Chat.err("Routine {} has no steps", name));
			else startRoutine(name, steps, times);
		};
		dev.lazuli.mod.config.RoutineEditor.status = () -> routine.detail();
		dev.lazuli.mod.config.RoutineEditor.reply = m -> chat(Chat.info("{}", m));
		dev.lazuli.mod.config.RoutineEditor.playerPos = () -> {
			var p = Minecraft.getInstance().player;
			return p == null ? null : new int[]{p.blockPosition().getX(), p.blockPosition().getY(), p.blockPosition().getZ()};
		};
		recorder = new TraceRecorder(FabricLoader.getInstance().getConfigDir().resolve("lazuli").resolve("traces"), this::chat);

		session = new PathingSession(cache, cache::minY, cache::maxY, settings, worker, this::chat,
			() -> Tools.snapshot(Minecraft.getInstance().player, settings));
		executor = new PathExecutor(session, settings, cache, this::chat);
		mine = new MineProcess(session, settings, cache, this::chat);
		mine.useOreIndex(ores);
		follow = new FollowProcess(session, settings, this::chat);
		explore = new ExploreProcess(session, settings, this::chat);
		tunnel = new TunnelProcess(session, settings, this::chat);
		farm = new FarmProcess(session, settings, this::chat);
		sleeps = new SleepProcess(session, settings, this::chat);
		guard = new dev.lazuli.mod.process.Guard(session, settings, this::chat, () -> {
			for (Process p : processes) if (p != guard && p.active()) return true;
			return false;
		});
		clicks = new ClickDriver(settings);
		containers = new ContainerProcess(session, settings, clicks, this::chat);
		crafts = new CraftProcess(session, settings, clicks, this::chat);
		smelts = new SmeltProcess(session, settings, clicks, crafts, this::chat);
		getItem = new GetItemProcess(session, settings, mine, crafts, smelts, this::chat);
		containers.useMemory(this::chests, LazuliClient::dimension);
		getItem.useChests(containers, this::chests, LazuliClient::dimension);
		hunt = new dev.lazuli.mod.process.HuntProcess(session, settings, this::chat);
		hunt.useContainers(containers);
		farm.useContainers(containers);
		tunnel.useContainers(containers);
		getItem.useHunt(hunt);
		smiths = new dev.lazuli.mod.process.SmithProcess(session, settings, clicks, this::chat);
		getItem.useSmith(smiths);
		buckets = new dev.lazuli.mod.process.BucketProcess(session, settings, this::chat);
		getItem.useBuckets(buckets);
		chestWatcher = new dev.lazuli.mod.inventory.ChestWatcher(settings.chestMemory::get, this::chests, LazuliClient::dimension);
		trades = new dev.lazuli.mod.process.TradeProcess(session, settings, clicks, mine, crafts, smelts, containers, this::tradeMemory,
			this::chests, LazuliClient::dimension, this::chat);
		tradeWatcher = new dev.lazuli.mod.inventory.TradeWatcher(settings.tradeMemory::get, this::tradeMemory, LazuliClient::dimension);
		trades.watch(tradeWatcher);
		getItem.useTrades(trades);
		brews = new dev.lazuli.mod.process.BrewProcess(session, settings, clicks, crafts, this::chat);
		brews.useGetItem(getItem);
		brews.useChests(containers, this::chests, LazuliClient::dimension);
		crafts.useChests(containers, this::chests, LazuliClient::dimension);
		smelts.useChests(containers, this::chests, LazuliClient::dimension);
		smiths.useChests(containers, this::chests, LazuliClient::dimension);
		enchants = new dev.lazuli.mod.process.EnchantProcess(session, settings, clicks, this::chat);
		enchants.useChests(containers, this::chests, LazuliClient::dimension);
		elytra = new dev.lazuli.mod.process.ElytraProcess(session, settings, clicks, this::chat);
		RecipeData.drops();
		RecipeData.get(); // read the recipes in the background now, not on the first #craft
		upkeep = new Upkeep(settings, this::chat);
		sorts = new dev.lazuli.mod.process.SortProcess(session, settings, clicks, this::chat);
		builds = new dev.lazuli.mod.process.BuildProcess(session, settings, clicks, this::chat);
		builds.useChests(containers, this::chests, LazuliClient::dimension);
		restock = new dev.lazuli.mod.process.RestockProcess(session, settings, clicks, this::chests, LazuliClient::dimension, () -> {
			for (Process p : processes) if (p != guard && p != restock && p.active()) return true;
			return false;
		}, this::chat);
		restock.skipWhen(() -> follow.active() && !settings.restockFollow.get());
		breeds = new dev.lazuli.mod.process.BreedProcess(session, settings, this::chat);
		breeds.useChests(containers, this::chests, LazuliClient::dimension);
		fishing = new dev.lazuli.mod.process.FishProcess(session, settings, this::chat);
		fishing.useContainers(containers);
		portals = new dev.lazuli.mod.process.PortalProcess(session, settings, cache, this::portalMemory, LazuliClient::dimension, this::chat);
		portals.useGetItem(getItem);
		strongholds = new dev.lazuli.mod.process.StrongholdProcess(session, settings,
			(mc, b) -> waypoints(mc).set("stronghold", dimension(mc), b.getX(), b.getY(), b.getZ()), this::chat);
		strongholds.useChests(containers, this::chests, LazuliClient::dimension);
		recovery = new dev.lazuli.mod.process.DeathRecovery(session, settings, LazuliClient::dimension, this::chat);
		iceboat = new dev.lazuli.mod.process.IceBoatProcess(settings, cache,
			() -> memoryDir(Minecraft.getInstance(), "iceboat"), this::chat);
		processes = java.util.List.of(iceboat, portals, strongholds, mine, follow, explore, tunnel, farm, breeds, fishing, containers, crafts, smelts, smiths, buckets, hunt, getItem,
			trades, brews, enchants, elytra, sorts, builds, sleeps, recovery, restock, guard);
		commands = new CommandHandler(new CommandHandler.Context() {
			@Override
			public int[] playerFeet() {
				BlockPos p = Minecraft.getInstance().player.blockPosition();
				return new int[]{p.getX(), p.getY(), p.getZ()};
			}

			@Override
			public BlockView world() {
				return cache;
			}

			@Override
			public PathingSession session() {
				return session;
			}

			@Override
			public Settings settings() {
				return settings;
			}

			@Override
			public void reply(String msg) {
				chat(msg);
			}

			@Override
			public void settingsChanged() {
				LazuliConfig.save();
			}

			@Override
			public void recordStart(String name) {
				recorder.start(name);
			}

			@Override
			public void recordStop() {
				recorder.stop(Minecraft.getInstance());
			}

			@Override
			public void mine(int quantity, java.util.List<String> blocks) {
				LazuliClient.this.stopProcesses(false);
				mine.start(Minecraft.getInstance(), quantity, blocks);
			}

			@Override
			public void kill() {
				LazuliClient.this.kill(Minecraft.getInstance());
			}

			@Override
			public void stopProcesses() {
				// #stop and #come: the job, every process and its clicks (the trip is stopped by the command)
				if (!runningRoutineStep) routine.stop();
				tasks.cancel();
				clicks.cancel();
				LazuliClient.this.stopProcesses(false);
			}

			@Override
			public void follow(String mode, java.util.List<String> args, int distance) {
				LazuliClient.this.stopProcesses(false);
				follow.start(FollowProcess.Mode.valueOf(mode.toUpperCase(java.util.Locale.ROOT)), args, distance);
			}

			@Override
			public void explore(int[] origin) {
				LazuliClient.this.stopProcesses(false);
				explore.start(Minecraft.getInstance(), origin);
			}

			@Override
			public void tunnel(int height, int width, int depth) {
				LazuliClient.this.stopProcesses(false);
				tunnel.start(Minecraft.getInstance(), height, width, depth);
			}

			@Override
			public void farm(int range) {
				LazuliClient.this.stopProcesses(false);
				farm.start(Minecraft.getInstance(), range);
			}

			@Override
			public void schematic(String mode, String file, int[] at) {
				Minecraft mc = Minecraft.getInstance();
				java.util.List<java.nio.file.Path> dirs = java.util.List.of(
					FabricLoader.getInstance().getConfigDir().resolve("lazuli").resolve("schematics"),
					FabricLoader.getInstance().getGameDir().resolve("schematics"));
				if (mode.equals("list")) {
					int n = 0;
					for (var d : dirs) {
						if (!java.nio.file.Files.isDirectory(d)) continue;
						try (var files = java.nio.file.Files.list(d)) {
							for (var f : (Iterable<java.nio.file.Path>) files::iterator) {
								String name = f.getFileName().toString();
								if (name.endsWith(".litematic") || name.endsWith(".schem") || name.endsWith(".nbt")) {
									reply(Chat.info("{}", name));
									n++;
								}
							}
						} catch (java.io.IOException ignored) {
							// unreadable folder: skip it
						}
					}
					if (n == 0) reply(Chat.info("No schematics in {} or {}", dirs.get(0), dirs.get(1)));
					return;
				}
				java.nio.file.Path path = null;
				for (var d : dirs)
					for (String ext : new String[]{"", ".litematic", ".schem", ".nbt"})
						if (path == null && java.nio.file.Files.isRegularFile(d.resolve(file + ext))) path = d.resolve(file + ext);
				if (path == null) {
					reply(Chat.err("No schematic {} ({}build list)", file, settings.prefix.get()));
					return;
				}
				dev.lazuli.core.schematic.Schematic s;
				try {
					s = dev.lazuli.core.schematic.Schematic.load(path);
				} catch (Exception e) {
					reply(Chat.err("Can't read {}: {}", path.getFileName(), e.getMessage()));
					return;
				}
				if (mode.equals("materials")) {
					reply(Chat.info("{}: {}x{}x{}, {} blocks", path.getFileName(), s.sizeX(), s.sizeY(), s.sizeZ(), s.blocks().size()));
					for (String line : dev.lazuli.mod.process.BuildProcess.materials(mc, s)) reply(line);
					return;
				}
				if (!settings.buildEnabled.get()) {
					reply(Chat.err("#build is off (buildEnabled)"));
					return;
				}
				int[] o = at != null ? at : playerFeet();
				LazuliClient.this.stopProcesses(false);
				builds.startSchematic(mc, path.getFileName().toString(), s, new BlockPos(o[0], o[1], o[2]));
			}

			@Override
			public dev.lazuli.core.task.Routines routines() {
				return routines;
			}

			@Override
			public void runRoutine(String name, java.util.List<String> steps, int times) {
				startRoutine(name, steps, times);
			}

			@Override
			public String routineStatus() {
				return routine.detail();
			}

			@Override
			public void stopRoutine() {
				if (!routine.running()) return;
				routine.stop();
				chat(Chat.warn("Routine stopped"));
			}

			@Override
			public void iceboat(String mode, int[] target, int laps, String name) {
				Minecraft mc = Minecraft.getInstance();
				switch (mode) {
					case "use" -> iceboat.useLine(name);
					case "record" -> {
						if (!iceboat.active()) LazuliClient.this.stopProcesses(false);
						iceboat.record(mc, name);
					}
					default -> {
						LazuliClient.this.stopProcesses(false);
						if (session.snapshot().state() == PathingSession.State.PATHING || session.planning()) session.stop(true);
						if (mode.equals("lap")) iceboat.lap(mc, laps);
						else if (mode.equals("line")) iceboat.followLine(mc);
						else iceboat.toGoal(mc, target[0], target[1] == Integer.MIN_VALUE ? null : target[1], target[2]);
					}
				}
			}

			@Override
			public void stronghold() {
				LazuliClient.this.stopProcesses(false);
				if (session.snapshot().state() == PathingSession.State.PATHING || session.planning()) session.stop(true);
				strongholds.start(Minecraft.getInstance());
			}

			@Override
			public void portal(boolean list) {
				Minecraft mc = Minecraft.getInstance();
				if (list) {
					var known = portalMemory(mc).list(LazuliClient.dimension(mc));
					if (known.isEmpty()) reply(Chat.info("No portals known in {}", LazuliClient.dimension(mc)));
					for (var k : known) reply(Chat.info("Portal at {} {} {}", k.x(), k.y(), k.z()));
					return;
				}
				LazuliClient.this.stopProcesses(false);
				if (session.snapshot().state() == PathingSession.State.PATHING || session.planning()) session.stop(true);
				portals.hop(mc);
			}

			@Override
			public boolean netherInstead(dev.lazuli.core.goal.Goal goal) {
				Minecraft mc = Minecraft.getInstance();
				if (mc.player == null || portals.active()) return false;
				int[] at = goal instanceof dev.lazuli.core.goal.GoalBlock b ? new int[]{b.x(), b.z()}
					: goal instanceof dev.lazuli.core.goal.GoalXZ g ? new int[]{g.x(), g.z()}
					: goal instanceof dev.lazuli.core.goal.GoalNear n ? new int[]{n.x(), n.z()} : null;
				if (at == null) return false;
				if (!portals.route(mc, goal, at[0], at[1])) return false;
				if (session.snapshot().state() == PathingSession.State.PATHING || session.planning()) session.stop(true);
				return true;
			}

			@Override
			public void breed(java.util.List<String> kinds, int pairs) {
				LazuliClient.this.stopProcesses(false);
				breeds.start(Minecraft.getInstance(), kinds, pairs);
			}

			@Override
			public void fish(int minutes) {
				LazuliClient.this.stopProcesses(false);
				fishing.start(Minecraft.getInstance(), minutes);
			}

			@Override
			public void container(boolean deposit, java.util.List<ItemCount> items, int[] at) {
				LazuliClient.this.stopProcesses(false);
				containers.start(Minecraft.getInstance(), deposit, items, at == null ? null : new BlockPos(at[0], at[1], at[2]));
			}

			@Override
			public void drop(java.util.List<ItemCount> items) {
				LazuliClient.this.drop(items);
			}

			@Override
			public void stationAt(int[] at) {
				pendingStation = at == null ? null : new BlockPos(at[0], at[1], at[2]);
			}

			@Override
			public void craft(java.util.List<ItemCount> items) {
				LazuliClient.this.stopProcesses(false);
				crafts.station(takeStation());
				crafts.start(Minecraft.getInstance(), items);
			}

			@Override
			public void smelt(ItemCount item) {
				LazuliClient.this.stopProcesses(false);
				smelts.station(takeStation());
				smelts.start(Minecraft.getInstance(), item);
			}

			@Override
			public boolean busy() {
				return LazuliClient.this.busy();
			}

			@Override
			public void bucket(String item, int n) {
				LazuliClient.this.stopProcesses(false);
				buckets.start(Minecraft.getInstance(), item, n);
			}

			@Override
			public void hunt(String item, int count) {
				LazuliClient.this.stopProcesses(false);
				startHunt(item, count);
			}

			@Override
			public void smith(ItemCount item) {
				LazuliClient.this.stopProcesses(false);
				smiths.station(takeStation());
				smiths.start(Minecraft.getInstance(), item);
			}

			@Override
			public void getItem(dev.lazuli.core.craft.ItemRequest request) {
				LazuliClient.this.stopProcesses(false);
				getItem.start(Minecraft.getInstance(), request);
			}

			@Override
			public void getItemDry(java.util.List<ItemCount> items) {
				getItem.dryRun(Minecraft.getInstance(), items);
			}

			@Override
			public void brew(String potion, dev.lazuli.core.craft.Brewing.Form form, int count) {
				LazuliClient.this.stopProcesses(false);
				brews.station(takeStation());
				brews.start(Minecraft.getInstance(), potion, form, count);
			}

			@Override
			public void enchant(String item, int count, int option) {
				LazuliClient.this.stopProcesses(false);
				enchants.station(takeStation());
				enchants.enchant(Minecraft.getInstance(), item, count, option);
			}

			@Override
			public void anvil(String item, String with) {
				LazuliClient.this.stopProcesses(false);
				enchants.station(takeStation());
				if (with == null) enchants.repair(Minecraft.getInstance(), item);
				else enchants.combine(Minecraft.getInstance(), item, with);
			}

			@Override
			public void sort(int range) {
				if (!settings.sortEnabled.get()) {
					reply(Chat.err("#sort is off (sortEnabled)"));
					return;
				}
				LazuliClient.this.stopProcesses(false);
				sorts.start(Minecraft.getInstance(), range);
			}

			@Override
			public void selClear(dev.lazuli.core.area.Selection.Box box) {
				if (!settings.selEnabled.get()) {
					reply(Chat.err("#sel is off (selEnabled)"));
					return;
				}
				LazuliClient.this.stopProcesses(false);
				builds.start(Minecraft.getInstance(), box, null);
			}

			@Override
			public void selFill(dev.lazuli.core.area.Selection.Box box, String block) {
				if (!settings.selEnabled.get()) {
					reply(Chat.err("#sel is off (selEnabled)"));
					return;
				}
				LazuliClient.this.stopProcesses(false);
				builds.start(Minecraft.getInstance(), box, block);
			}

			@Override
			public void fly(int x, Integer y, int z) {
				LazuliClient.this.stopProcesses(false);
				if (session.snapshot().state() == PathingSession.State.PATHING || session.planning()) session.stop(true);
				elytra.start(Minecraft.getInstance(), x, y, z);
			}

			@Override
			public boolean flyInstead(dev.lazuli.core.goal.Goal goal) {
				Minecraft mc = Minecraft.getInstance();
				if (!settings.elytraGoto.get() || mc.player == null || elytra.active()) return false;
				int[] at = goal instanceof dev.lazuli.core.goal.GoalBlock b ? new int[]{b.x(), b.y(), b.z()}
					: goal instanceof dev.lazuli.core.goal.GoalXZ g ? new int[]{g.x(), Integer.MIN_VALUE, g.z()}
					: goal instanceof dev.lazuli.core.goal.GoalNear n ? new int[]{n.x(), n.y(), n.z()} : null;
				if (at == null || Math.hypot(at[0] + 0.5 - mc.player.getX(), at[2] + 0.5 - mc.player.getZ()) < settings.elytraMinDistance.get()) return false;
				if (dev.lazuli.mod.process.ElytraProcess.cannot(mc, mc.player) != null) return false;
				fly(at[0], at[1] == Integer.MIN_VALUE ? null : at[1], at[2]);
				return true;
			}

			@Override
			public void smeltCollect() {
				LazuliClient.this.stopProcesses(false);
				smelts.collect(Minecraft.getInstance());
			}

			@Override
			public int[] cameraFeet() {
				return FreeCam.active() ? FreeCam.feet() : playerFeet();
			}

			@Override
			public void toggleFreecam() {
				LazuliClient.this.toggleFreecam(Minecraft.getInstance());
			}

			@Override
			public void sleep() {
				LazuliClient.this.stopProcesses(false);
				sleeps.start(Minecraft.getInstance());
			}

			@Override
			public boolean isEnchantment(String id) {
				return dev.lazuli.mod.inventory.TradeWatcher.isEnchantment(Minecraft.getInstance(), id);
			}

			@Override
			public void trade(java.util.List<dev.lazuli.core.trade.Want> wants) {
				LazuliClient.this.stopProcesses(false);
				// #trade emerald 3: three more than you have, not three in all
				var p = Minecraft.getInstance().player;
				trades.start(Minecraft.getInstance(), wants.stream().map(w -> new dev.lazuli.core.trade.Want(w.item(), w.enchant(), w.level(),
					w.count() + dev.lazuli.mod.inventory.TradeWatcher.count(p, w))).toList());
			}

			@Override
			public void trade(java.util.List<dev.lazuli.core.trade.Want> wants, dev.lazuli.core.trade.TradeOptions options) {
				LazuliClient.this.stopProcesses(false);
				trades.options(options);
				trade(wants);
			}

			@Override
			public void tradeSell(String item, int count) {
				LazuliClient.this.stopProcesses(false);
				trades.sell(Minecraft.getInstance(), item, count);
			}

			@Override
			public void tradeSell(String item, int count, dev.lazuli.core.trade.TradeOptions options) {
				LazuliClient.this.stopProcesses(false);
				trades.options(options);
				tradeSell(item, count);
			}

			@Override
			public void tradeScan() {
				LazuliClient.this.stopProcesses(false);
				trades.scan(Minecraft.getInstance());
			}

			@Override
			public void tradeScan(dev.lazuli.core.trade.TradeOptions options) {
				LazuliClient.this.stopProcesses(false);
				trades.options(options);
				tradeScan();
			}

			@Override
			public dev.lazuli.core.trade.TradeMemory trades() {
				return settings.tradeMemory.get() ? LazuliClient.this.tradeMemory(Minecraft.getInstance()) : null;
			}

			@Override
			public dev.lazuli.core.storage.ChestMemory chests() {
				return settings.chestMemory.get() ? LazuliClient.this.chests(Minecraft.getInstance()) : null;
			}

			@Override
			public Waypoints waypoints() {
				return LazuliClient.this.waypoints(Minecraft.getInstance());
			}

			@Override
			public String dimension() {
				return LazuliClient.this.dimension(Minecraft.getInstance());
			}

			@Override
			public void startClick() {
				ClickGoal.arm();
			}

			@Override
			public void openConfig() {
				openConfigNextTick = true; // the chat screen is still closing
			}
		});
		ClickGoal.init(g -> commands.setGoal(g), g -> {
			stopProcesses(false);
			commands.goTo(g, true);
		}, this::chat);
		Lazuli.Holder.set(new Api());
		renderer = new LazuliRenderer(session, settings, executor::predicted, commands::goal, cache);
		renderer.processes(processes);
		renderer.selection(commands::selection);
		renderer.waypoints(() -> {
			Minecraft mc = Minecraft.getInstance();
			Waypoints w = mc.level == null ? null : waypoints(mc);
			return w == null || !settings.renderWaypoints.get() ? java.util.List.of() : w.list(dimension(mc));
		});

		// stuck: what #kill fixed (a fresh planner thread, all trip state forgotten), but the job carries on
		executor.onStuck(block -> {
			freshWorker("stuck");
			session.restart(block);
		});

		ClientSendMessageEvents.ALLOW_CHAT.register(message -> {
			if (Minecraft.getInstance().player == null) return true;
			try {
				return !runCommand(message);
			} catch (RuntimeException e) {
				chat(Chat.err("Error: {}", e.getMessage()));
				return false;
			}
		});
		dev.lazuli.mod.ChatControl.register(settings, line -> {
			try {
				runCommand(line);
			} catch (RuntimeException e) {
				chat(Chat.err("Error: {}", e.getMessage()));
			}
		}, this::chat);
		ClientChunkEvents.CHUNK_LOAD.register(this::onChunkLoad);
		ClientChunkEvents.CHUNK_UNLOAD.register(this::onChunkUnload);
		// order matters: the recorder logs this tick's movement before the executor turns the view for the next one
		ClientTickEvents.END_CLIENT_TICK.register(recorder::tick);
		// while the inventory screen is open for clicks the player stands still and the view stays put
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (!ClickDriver.holding()) executor.tick(mc);
		});
		// after the executor, which clears the mining target; nothing moves while eating
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (mc.player == null || mc.level == null) return;
			// moving cancels everything Lazuli is doing, a path still being searched for included
			if (settings.cancelOnUserInput.get() && mc.screen == null && busy() && userKeys.userInterfering(mc)) stopAll(true);
			upkeep.tick(mc, busy());
			if (Upkeep.eating()) return;
			// the guard dodging or healing: the job waits
			// restocking: the job waits too
			for (Process p : processes) {
				if (p == guard || p == restock && !dev.lazuli.mod.process.Guard.paused()
					|| !dev.lazuli.mod.process.Guard.paused() && !dev.lazuli.mod.process.RestockProcess.paused()) p.tick(mc);
			}
		});
		ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("lazuli", "status"), renderer::renderHud);
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("lazuli", "minimap"), renderer::renderMinimap);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> {
			lastLevel = null;
			FreeCam.off();
			stopProcesses(false);
			session.stop();
			executor.release(mc);
		});
	}

	private final class Api implements Lazuli {
		@Override
		public Settings settings() {
			return settings;
		}

		@Override
		public Task goTo(Goal goal, boolean fallback) {
			return tasks.run(() -> {
				stopProcesses(false);
				BlockPos f = Minecraft.getInstance().player.blockPosition();
				return Task.trip("go to " + goal.describe(), session, session.start(goal, fallback, Pos.pack(f.getX(), f.getY(), f.getZ())));
			});
		}

		@Override
		public Task mine(int quantity, String... blocks) {
			return tasks.run(() -> {
				stopProcesses(false);
				mine.start(Minecraft.getInstance(), quantity, java.util.List.of(blocks));
				boolean started = mine.active();
				return new Task("mine " + String.join(", ", blocks), () -> {
					if (!started) return Task.Result.FAILED; // unknown block
					if (mine.active()) return null;
					if (mine.cancelled()) return Task.Result.CANCELLED;
					return mine.mined() > 0 ? Task.Result.DONE : Task.Result.FAILED;
				}, () -> mine.stop(false));
			});
		}

		@Override
		public Task container(boolean deposit, java.util.List<ItemCount> items, int[] at) {
			return tasks.run(() -> {
				stopProcesses(false);
				containers.start(Minecraft.getInstance(), deposit, items, at == null ? null : new BlockPos(at[0], at[1], at[2]));
				String names = String.join(", ", items.stream().map(ItemCount::item).toList());
				return new Task((deposit ? "deposit " : "withdraw ") + names, () -> switch (containers.outcome()) {
					case NONE -> null;
					case DONE -> Task.Result.DONE;
					case FAILED -> Task.Result.FAILED;
					case CANCELLED -> Task.Result.CANCELLED;
				}, () -> containers.stop(false));
			});
		}

		@Override
		public Task drop(java.util.List<ItemCount> items) {
			return tasks.run(() -> {
				stopProcesses(false);
				LazuliClient.this.drop(items);
				// the clicks are played over the next ticks
				return new Task("drop", () -> clicks.busy() ? null : Task.Result.DONE, clicks::cancel);
			});
		}

		@Override
		public Task craft(java.util.List<ItemCount> items) {
			return tasks.run(() -> {
				stopProcesses(false);
				crafts.start(Minecraft.getInstance(), items);
				String names = String.join(", ", items.stream().map(ItemCount::item).toList());
				return new Task("craft " + names, () -> switch (crafts.outcome()) {
					case NONE -> null;
					case DONE -> Task.Result.DONE;
					case FAILED -> Task.Result.FAILED;
					case CANCELLED -> Task.Result.CANCELLED;
				}, () -> crafts.stop(false));
			});
		}

		@Override
		public Task smelt(String item, int count) {
			return tasks.run(() -> {
				stopProcesses(false);
				smelts.start(Minecraft.getInstance(), new ItemCount(item, count));
				return new Task("smelt " + item, () -> switch (smelts.outcome()) {
					case NONE -> null;
					case DONE -> Task.Result.DONE;
					case FAILED -> Task.Result.FAILED;
					case CANCELLED -> Task.Result.CANCELLED;
				}, () -> smelts.stop(false));
			});
		}

		@Override
		public Task getItem(String item, int count) {
			return tasks.run(() -> {
				stopProcesses(false);
				getItem.start(Minecraft.getInstance(), dev.lazuli.core.craft.ItemRequest.of(item, count));
				return new Task("get " + item, () -> switch (getItem.outcome()) {
					case NONE -> null;
					case DONE -> Task.Result.DONE;
					case FAILED -> Task.Result.FAILED;
					case CANCELLED -> Task.Result.CANCELLED;
				}, () -> getItem.stop(false));
			});
		}

		@Override
		public Task brew(String potion, dev.lazuli.core.craft.Brewing.Form form, int count) {
			return tasks.run(() -> {
				stopProcesses(false);
				brews.start(Minecraft.getInstance(), potion, form, count);
				return new Task("brew " + potion, () -> switch (brews.outcome()) {
					case NONE -> null;
					case DONE -> Task.Result.DONE;
					case FAILED -> Task.Result.FAILED;
					case CANCELLED -> Task.Result.CANCELLED;
				}, () -> brews.stop(false));
			});
		}

		@Override
		public Task trade(java.util.List<dev.lazuli.core.trade.Want> wants) {
			return tasks.run(() -> {
				stopProcesses(false);
				trades.start(Minecraft.getInstance(), wants);
				return new Task("trade", () -> switch (trades.outcome()) {
					case NONE -> null;
					case DONE -> Task.Result.DONE;
					case FAILED -> Task.Result.FAILED;
					case CANCELLED -> Task.Result.CANCELLED;
				}, () -> trades.stop(false));
			});
		}

		@Override
		public Task tradeSell(String item, int count) {
			return tasks.run(() -> {
				stopProcesses(false);
				trades.sell(Minecraft.getInstance(), item, count);
				return new Task("sell " + item, () -> switch (trades.outcome()) {
					case NONE -> null;
					case DONE -> Task.Result.DONE;
					case FAILED -> Task.Result.FAILED;
					case CANCELLED -> Task.Result.CANCELLED;
				}, () -> trades.stop(false));
			});
		}

		@Override
		public Task bucket(String item, int n) {
			return tasks.run(() -> {
				stopProcesses(false);
				buckets.start(Minecraft.getInstance(), item, n);
				return new Task("bucket " + item, () -> switch (buckets.outcome()) {
					case NONE -> null;
					case DONE -> Task.Result.DONE;
					case FAILED -> Task.Result.FAILED;
					case CANCELLED -> Task.Result.CANCELLED;
				}, () -> buckets.stop(false));
			});
		}

		@Override
		public Task hunt(String item, int count) {
			return tasks.run(() -> {
				stopProcesses(false);
				startHunt(item, count);
				return new Task("hunt " + item, () -> switch (hunt.outcome()) {
					case NONE -> null;
					case DONE -> Task.Result.DONE;
					case FAILED -> Task.Result.FAILED;
					case CANCELLED -> Task.Result.CANCELLED;
				}, () -> hunt.stop(false));
			});
		}

		@Override
		public Task smith(String item, int count) {
			return tasks.run(() -> {
				stopProcesses(false);
				smiths.start(Minecraft.getInstance(), new ItemCount(item, count));
				return new Task("smith " + item, () -> switch (smiths.outcome()) {
					case NONE -> null;
					case DONE -> Task.Result.DONE;
					case FAILED -> Task.Result.FAILED;
					case CANCELLED -> Task.Result.CANCELLED;
				}, () -> smiths.stop(false));
			});
		}

		@Override
		public Task current() {
			return tasks.current();
		}

		@Override
		public boolean busy() {
			return LazuliClient.this.busy();
		}

		@Override
		public void stop() {
			stopAll(false);
		}

		@Override
		public void command(String line) {
			String prefix = settings.prefix.get();
			runCommand(line.startsWith(prefix) ? line : prefix + line);
		}
	}

	/** Starts a routine (or a queue, name null); says why when its steps don't parse. */
	private void startRoutine(String name, java.util.List<String> steps, int times) {
		try {
			routine.start(name, steps, times);
		} catch (IllegalArgumentException e) {
			chat(Chat.err("{}: {}", name == null ? "Queue" : "Routine " + name, e.getMessage()));
			return;
		}
		chat(Chat.ok("{}: {} steps{}", name == null ? "Queue" : "Routine " + name, steps.size(),
			times <= 0 ? ", forever" : times > 1 ? ", " + times + " times" : ""));
	}

	/** What the routine runner needs from the game. */
	private final dev.lazuli.core.task.RoutineRunner.Host routineHost = new dev.lazuli.core.task.RoutineRunner.Host() {
		private final RoutineGame game = new RoutineGame(() -> settings.chestMemory.get() ? chests(Minecraft.getInstance()) : null,
			() -> dimension(Minecraft.getInstance()), LazuliClient.this::busy);

		@Override
		public boolean run(String line) {
			// a step's own command (#come, #stop inside a routine) mustn't end the routine running it
			runningRoutineStep = true;
			stepErrored = false;
			try {
				runCommand(line);
			} catch (RuntimeException e) {
				chat(Chat.err("Routine step {} failed: {}", line, e.getMessage()));
				return false;
			} finally {
				runningRoutineStep = false;
			}
			return !stepErrored;
		}

		@Override
		public void stop() {
			// an endless step handing over (inventory full), or one that timed out: stop it, the routine goes on
			tasks.cancel();
			clicks.cancel();
			stopProcesses(false);
			var session = LazuliClient.this.session;
			if (session.snapshot().state() == PathingSession.State.PATHING || session.planning()) session.stop(true);
		}

		@Override
		public dev.lazuli.core.task.Cond.World world() {
			return game;
		}

		@Override
		public void say(String text) {
			var mc = Minecraft.getInstance();
			if (mc.player == null) return;
			if (text.startsWith("/")) mc.player.connection.sendCommand(text.substring(1));
			else mc.player.connection.sendChat(text);
		}

		@Override
		public void notify(String text) {
			chat(Chat.ok("{}", text));
			var mc = Minecraft.getInstance();
			mc.execute(() -> {
				if (mc.player != null) mc.player.playSound(net.minecraft.sounds.SoundEvents.EXPERIENCE_ORB_PICKUP, 1f, 1f);
			});
		}
	};

	/** An error went to chat while a step ran (set by {@link #chat}); read right after the command starts. */
	private volatile boolean stepErrored;

	/** Handles a chat line as a command (false: not one). */
	private boolean runCommand(String line) {
		return commands.handle(line);
	}

	private int ticks;
	/** A routine is running one of its steps right now. */
	private boolean runningRoutineStep;

	/** Throws out items ("all": anything) from the main inventory and hotbar; a negative count: all of it. */
	private void drop(java.util.List<ItemCount> items) {
		var p = Minecraft.getInstance().player;
		if (p == null) return;
		if (p.containerMenu != p.inventoryMenu) {
			chat(Chat.err("Close the open menu first"));
			return;
		}
		// each item is planned on the menu as the ones before it leave it
		var sim = Inventories.read(p.inventoryMenu, p);
		var plan = new java.util.ArrayList<dev.lazuli.core.inventory.Click>();
		var dropped = new java.util.ArrayList<ItemCount>();
		for (ItemCount i : items) {
			int before = sim.dropped();
			var part = Moves.drop(sim, Inventories.PLAYER_MAIN, Inventories.matcher(i.item()), i.count());
			sim.applyAll(part);
			plan.addAll(part);
			if (part.isEmpty()) chat(Chat.err("No {} to drop", i.item()));
			else dropped.add(new ItemCount(i.item(), sim.dropped() - before));
		}
		if (plan.isEmpty()) return;
		clicks.play(p.inventoryMenu.containerId, plan);
		chat(Chat.ok("Dropping {}", ItemCount.describe(dropped)));
	}

	/** #hunt: kills the mobs that drop {@code item} (as #getitem would pick them) until {@code count} more are in the inventory. */
	private void startHunt(String item, int count) {
		Minecraft mc = Minecraft.getInstance();
		String id = Inventories.id(item);
		dev.lazuli.core.craft.MobDrops drops;
		try {
			drops = dev.lazuli.mod.craft.RecipeData.mobDrops().join();
		} catch (RuntimeException e) {
			chat(Chat.err("Could not read the loot tables: {}", e.getMessage()));
			return;
		}
		var who = new java.util.ArrayList<>(drops.sources(id).stream()
			.filter(s -> dev.lazuli.core.craft.MobDrops.rarity(s.mob()) < dev.lazuli.core.craft.Drops.UNKNOWN).toList());
		who.sort(java.util.Comparator.comparingDouble(s -> dev.lazuli.core.craft.MobDrops.rarity(s.mob()) / s.perKill()));
		if (who.isEmpty()) {
			chat(Chat.err("No mob worth hunting drops {}", id.replace("minecraft:", "")));
			return;
		}
		int have = Inventories.plainCounts(mc.player).getOrDefault(id, 0);
		hunt.start(mc, id, have + Math.max(1, count), who.stream().map(dev.lazuli.core.craft.MobDrops.Source::mob).toList());
		chat(Chat.info("Hunting {} for {} {}", who.get(0).mob().replace("minecraft:", ""), Math.max(1, count), id.replace("minecraft:", "")));
	}

	private void stopProcesses(boolean announce) {
		for (Process p : processes) p.stop(announce);
	}

	private final dev.lazuli.mod.control.InputDriver userKeys = new dev.lazuli.mod.control.InputDriver();

	/** #kill: everything stopped and reset, a fresh planner thread, keys and view let go. */
	private void kill(Minecraft mc) {
		tasks.cancel();
		clicks.cancel();
		for (Process p : processes) {
			try {
				p.stop(false);
			} catch (RuntimeException e) {
				org.slf4j.LoggerFactory.getLogger("lazuli").warn("stopping a process failed", e);
			}
		}
		freshWorker("killed");
		session.kill();
		executor.release(mc);
		new dev.lazuli.mod.control.InputDriver().release();
		RotationDriver.end();
		Tools.keepNothing();
		upkeep.reset(mc);
		if (mc.gameMode != null && mc.player != null) mc.gameMode.stopDestroyBlock();
		mc.options.keyAttack.setDown(false);
		mc.options.keyUse.setDown(false);
		chat(Chat.ok("Killed everything; ready for new commands"));
	}

	/** Stops the job, every process and the trip (or the search for one). */
	private void stopAll(boolean announce) {
		routine.stop();
		tasks.cancel();
		boolean any = busy();
		clicks.cancel();
		stopProcesses(false);
		if (session.snapshot().state() == PathingSession.State.PATHING || session.planning()) session.stop(true);
		if (announce && any) chat(Chat.warn("Stopped"));
	}

	/** Pathing, or a process running. */
	private boolean busy() {
		if (session.snapshot().state() == PathingSession.State.PATHING || session.planning()) return true;
		for (Process p : processes) if (p.active()) return true;
		return false;
	}

	private void onTick(Minecraft mc) {
		ticks++;
		tasks.tick();
		boolean full = mc.player != null && mc.player.getInventory().getFreeSlot() < 0;
		routine.setIdlePolicy(settings.routineIdleWaitSeconds.get() * 20, settings.routineIdleRounds.get(), m -> chat(Chat.info(m)));
		routine.setStepTimeout(settings.routineStepTimeoutMinutes.get() * 60 * 20);
		String routineDone = routine.tick(busy(), full, routineHost);
		if (routineDone != null) chat(Chat.ok(routineDone));
		chestWatcher.tick(mc);
		tradeWatcher.tick(mc);
		boolean dead = mc.player != null && mc.player.isDeadOrDying();
		if (dead && !wasDead && settings.deathWaypoint.get()) {
			BlockPos b = mc.player.blockPosition();
			waypoints(mc).set(Waypoints.DEATH, dimension(mc), b.getX(), b.getY(), b.getZ());
			chat(Chat.info("Died at {} ({}back to go there)", b.getX() + " " + b.getY() + " " + b.getZ(), settings.prefix.get()));
		}
		if (dead && !wasDead) recovery.died(mc, mc.player.blockPosition());
		recovery.watch(mc);
		wasDead = dead;
		clicks.tick(mc, settings.clicksPerTick.get());
		if (ticks % 10 == 0) session.setHazards(mc.player == null || !settings.avoidMobs.get() || hunt.active() || !busy()
			? dev.lazuli.core.move.Hazards.NONE : dev.lazuli.mod.process.Guard.hazards(mc, mc.player, settings));
		renderer.tick(mc);
		renderer.hudTick(mc);
		FreeCam.tick(mc);
		while (LazuliConfig.FREECAM.consumeClick()) toggleFreecam(mc);
		if (mc.player == null) FreeCam.off();
		// the view (or, with free look, the server-side rotation) is Lazuli's for the whole trip, not just while keys are held
		if (!busy()) RotationDriver.end();
		while (LazuliConfig.STOP.consumeClick()) stopAll(true);
		boolean open = openConfigNextTick;
		openConfigNextTick = false;
		while (LazuliConfig.OPEN_CONFIG.consumeClick()) open = mc.screen == null;
		if (open) mc.setScreen(new WalksyLibPanelScreen(mc.screen, LazuliConfig.ID));
	}

	/** This server's (or singleplayer world's) waypoints, each in its own file. */
	private Waypoints waypoints(Minecraft mc) {
		Path file = memoryFile(mc, "waypoints");
		return waypointsByWorld.computeIfAbsent(file.toString(), f -> new Waypoints(file));
	}

	/** What's in this server's (or world's) chests, as last seen. */
	private dev.lazuli.core.storage.ChestMemory chests(Minecraft mc) {
		Path file = memoryFile(mc, "chests");
		return chestsByWorld.computeIfAbsent(file.toString(), f -> new dev.lazuli.core.storage.ChestMemory(file));
	}

	/** The nether portals seen on this server (or world); kept in memory only with portalMemory off. */
	private dev.lazuli.core.portal.PortalMemory portalMemory(Minecraft mc) {
		Path file = settings.portalMemory.get() ? memoryFile(mc, "portals") : null;
		return portalsByWorld.computeIfAbsent(file == null ? "memory-only:" + worldKey(mc) : file.toString(),
			f -> new dev.lazuli.core.portal.PortalMemory(file));
	}

	/** Remembers the portal blocks in a chunk just loaded (only sections that have any are looked through). */
	private void scanPortals(ClientLevel level, LevelChunk chunk) {
		if (!settings.portalMemory.get()) return;
		Minecraft mc = Minecraft.getInstance();
		var sections = chunk.getSections();
		for (int si = 0; si < sections.length; si++) {
			var s = sections[si];
			if (s == null || s.hasOnlyAir() || !s.getStates().maybeHas(st -> st.is(net.minecraft.world.level.block.Blocks.NETHER_PORTAL))) continue;
			int baseY = level.getMinY() + si * 16;
			for (int y = 0; y < 16; y++)
				for (int z = 0; z < 16; z++)
					for (int x = 0; x < 16; x++) {
						if (!s.getBlockState(x, y, z).is(net.minecraft.world.level.block.Blocks.NETHER_PORTAL)) continue;
						portalMemory(mc).add(dimension(mc), chunk.getPos().getMinBlockX() + x, baseY + y, chunk.getPos().getMinBlockZ() + z);
					}
		}
	}

	/** Every villager's trades seen on this server (or world). */
	private dev.lazuli.core.trade.TradeMemory tradeMemory(Minecraft mc) {
		Path file = memoryFile(mc, "trades");
		return tradesByWorld.computeIfAbsent(file.toString(), f -> new dev.lazuli.core.trade.TradeMemory(file));
	}

	/** The work block named by {@code at x y z} in the command being run (see {@code CommandHandler.Context.stationAt}). */
	private BlockPos pendingStation;

	private BlockPos takeStation() {
		BlockPos at = pendingStation;
		pendingStation = null;
		return at;
	}

	private static Path lazuliDir() {
		return FabricLoader.getInstance().getConfigDir().resolve("lazuli");
	}

	/** The old id of the server (or singleplayer world) being played on: server-<ip>, world-<name>, unknown. */
	private static String legacyId(Minecraft mc) {
		if (mc.getCurrentServer() != null) return "server-" + mc.getCurrentServer().ip;
		if (mc.getSingleplayerServer() != null) return "world-" + mc.getSingleplayerServer().getWorldData().getLevelName();
		return "unknown";
	}

	/** The key of the server (or singleplayer world) being played on (see {@link dev.lazuli.core.storage.WorldKey}). */
	private static String worldKey(Minecraft mc) {
		if (mc.getCurrentServer() != null) return dev.lazuli.core.storage.WorldKey.server(mc.getCurrentServer().ip);
		if (mc.getSingleplayerServer() != null) return dev.lazuli.core.storage.WorldKey.world(mc.getSingleplayerServer().getWorldData().getLevelName());
		return "unknown";
	}

	private static final java.util.List<String> FILE_KINDS = java.util.List.of("chests", "trades", "portals", "waypoints");
	private final java.util.Set<String> migratedWorlds = new java.util.HashSet<>();

	/** Brings the old per-kind files over to this server's folder, once per server per session (and for good, by marker). */
	private void migrateMemory(Minecraft mc) {
		String key = worldKey(mc);
		if (migratedWorlds.add(key)) {
			dev.lazuli.core.storage.WorldKey.migrate(lazuliDir(), FILE_KINDS, java.util.List.of("iceboat"), key,
				dev.lazuli.core.storage.WorldKey.legacyName(legacyId(mc)));
		}
	}

	/** Where a kind of memory is kept for this server (perServerMemory), else in the old shared per-world files. */
	private Path memoryFile(Minecraft mc, String kind) {
		if (!settings.perServerMemory.get()) return lazuliDir().resolve(kind).resolve(dev.lazuli.core.storage.WorldKey.legacyName(legacyId(mc)));
		migrateMemory(mc);
		return dev.lazuli.core.storage.WorldKey.file(lazuliDir(), kind, worldKey(mc));
	}

	private Path memoryDir(Minecraft mc, String kind) {
		if (!settings.perServerMemory.get()) return lazuliDir().resolve(kind);
		migrateMemory(mc);
		return dev.lazuli.core.storage.WorldKey.dir(lazuliDir(), kind, worldKey(mc));
	}

	private static String dimension(Minecraft mc) {
		return mc.level == null ? "overworld" : mc.level.dimension().identifier().getPath();
	}

	private void toggleFreecam(Minecraft mc) {
		FreeCam.toggle(mc);
		chat(FreeCam.active() ? Chat.ok("Freecam on: fly with WASD, space, shift (sprint: faster); left-click a block to go there")
			: Chat.warn("Freecam off"));
	}

	private void chat(String msg) {
		if (msg.startsWith(Chat.RED)) {
			// an error while a routine step runs: steps that asked to care (@retry, @onfail) take it as a failure
			stepErrored = true;
			if (routine.running()) routine.failStep(Chat.strip(msg));
		}
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> mc.gui.getChat().addMessage(Component.literal(PREFIX + msg)));
	}

	private void onChunkLoad(ClientLevel level, LevelChunk chunk) {
		if (level != lastLevel) {
			lastLevel = level;
			cache.reset(level.getMinY(), level.getHeight());
			ores.clear();
			if (session.snapshot().state() == PathingSession.State.PATHING) session.stop();
		}
		int cx = chunk.getPos().x, cz = chunk.getPos().z;
		ChunkScanner.Scan scan = ChunkScanner.scan(chunk, cache.sectionCount(), settings.oreMemory.get());
		cache.putChunk(cx, cz, scan.kinds(), scan.physics());
		if (settings.oreMemory.get()) ores.putChunk(cx, cz, scan.ores(), scan.oreBlocks());
		session.regionChanged(cx * 16, cache.minY(), cz * 16, cx * 16 + 15, cache.maxY(), cz * 16 + 15);
		mine.onChunkLoad(chunk);
		explore.onChunkLoad(chunk);
		scanPortals(level, chunk);
	}

	private void onChunkUnload(ClientLevel level, LevelChunk chunk) {
		if (level != lastLevel) return;
		int cx = chunk.getPos().x, cz = chunk.getPos().z;
		cache.removeChunk(cx, cz);
		session.regionChanged(cx * 16, cache.minY(), cz * 16, cx * 16 + 15, cache.maxY(), cz * 16 + 15);
	}

	/** Called by ClientLevelMixin whenever the client's copy of a block changes. */
	public static void onBlockChanged(ClientLevel level, BlockPos pos, BlockState state) {
		LazuliClient self = instance;
		if (self == null || level != self.lastLevel) return;
		if (self.settings.oreMemory.get()) self.ores.set(dev.lazuli.core.world.Pos.pack(pos.getX(), pos.getY(), pos.getZ()), pos.getX(), pos.getZ(), state);
		self.mine.onBlockChanged(pos, state);
		if (self.settings.portalMemory.get() && state.is(net.minecraft.world.level.block.Blocks.NETHER_PORTAL)) {
			Minecraft mc = Minecraft.getInstance();
			self.portalMemory(mc).add(dimension(mc), pos.getX(), pos.getY(), pos.getZ());
		}
		if (!self.cache.set(pos.getX(), pos.getY(), pos.getZ(), BlockClassifier.classify(state), BlockClassifier.physicsId(state))) return;
		self.session.regionChanged(pos.getX(), pos.getY(), pos.getZ(), pos.getX(), pos.getY(), pos.getZ());
	}
}
