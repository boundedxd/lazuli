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
	private dev.lazuli.mod.process.Guard guard;
	private ContainerProcess containers;
	private CraftProcess crafts;
	private SmeltProcess smelts;
	private GetItemProcess getItem;
	private dev.lazuli.mod.process.HuntProcess hunt;
	private dev.lazuli.mod.process.TradeProcess trades;
	private dev.lazuli.mod.inventory.TradeWatcher tradeWatcher;
	private final java.util.Map<String, dev.lazuli.core.trade.TradeMemory> tradesByWorld = new java.util.HashMap<>();
	private Upkeep upkeep;
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
		recorder = new TraceRecorder(FabricLoader.getInstance().getConfigDir().resolve("lazuli").resolve("traces"), this::chat);

		session = new PathingSession(cache, cache::minY, cache::maxY, settings, worker, this::chat,
			() -> Tools.snapshot(Minecraft.getInstance().player, settings));
		executor = new PathExecutor(session, settings, cache, this::chat);
		mine = new MineProcess(session, settings, cache, this::chat);
		follow = new FollowProcess(session, settings, this::chat);
		explore = new ExploreProcess(session, this::chat);
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
		getItem.useHunt(hunt);
		chestWatcher = new dev.lazuli.mod.inventory.ChestWatcher(settings.chestMemory::get, this::chests, LazuliClient::dimension);
		trades = new dev.lazuli.mod.process.TradeProcess(session, settings, clicks, mine, crafts, smelts, containers, this::tradeMemory,
			this::chests, LazuliClient::dimension, this::chat);
		tradeWatcher = new dev.lazuli.mod.inventory.TradeWatcher(settings.tradeMemory::get, this::tradeMemory, LazuliClient::dimension);
		trades.watch(tradeWatcher);
		getItem.useTrades(trades);
		RecipeData.drops();
		RecipeData.get(); // read the recipes in the background now, not on the first #craft
		upkeep = new Upkeep(settings, this::chat);
		processes = java.util.List.of(mine, follow, explore, tunnel, farm, containers, crafts, smelts, hunt, getItem, trades, sleeps, guard);
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
			public void container(boolean deposit, java.util.List<ItemCount> items, int[] at) {
				LazuliClient.this.stopProcesses(false);
				containers.start(Minecraft.getInstance(), deposit, items, at == null ? null : new BlockPos(at[0], at[1], at[2]));
			}

			@Override
			public void drop(java.util.List<ItemCount> items) {
				LazuliClient.this.drop(items);
			}

			@Override
			public void craft(java.util.List<ItemCount> items) {
				LazuliClient.this.stopProcesses(false);
				crafts.start(Minecraft.getInstance(), items);
			}

			@Override
			public void smelt(ItemCount item) {
				LazuliClient.this.stopProcesses(false);
				smelts.start(Minecraft.getInstance(), item);
			}

			@Override
			public void getItem(dev.lazuli.core.craft.ItemRequest request) {
				LazuliClient.this.stopProcesses(false);
				getItem.start(Minecraft.getInstance(), request);
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
				trades.start(Minecraft.getInstance(), wants);
			}

			@Override
			public void tradeSell(String item, int count) {
				LazuliClient.this.stopProcesses(false);
				trades.sell(Minecraft.getInstance(), item, count);
			}

			@Override
			public void tradeScan() {
				LazuliClient.this.stopProcesses(false);
				trades.scan(Minecraft.getInstance());
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
			for (Process p : processes) if (p == guard || !dev.lazuli.mod.process.Guard.paused()) p.tick(mc);
		});
		ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("lazuli", "status"), renderer::renderHud);
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
		public void drop(java.util.List<ItemCount> items) {
			LazuliClient.this.drop(items);
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

	/** Handles a chat line as a command (false: not one). */
	private boolean runCommand(String line) {
		return commands.handle(line);
	}

	private int ticks;

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
		chestWatcher.tick(mc);
		tradeWatcher.tick(mc);
		boolean dead = mc.player != null && mc.player.isDeadOrDying();
		if (dead && !wasDead && settings.deathWaypoint.get()) {
			BlockPos b = mc.player.blockPosition();
			waypoints(mc).set(Waypoints.DEATH, dimension(mc), b.getX(), b.getY(), b.getZ());
			chat(Chat.info("Died at {} ({}back to go there)", b.getX() + " " + b.getY() + " " + b.getZ(), settings.prefix.get()));
		}
		wasDead = dead;
		clicks.tick(mc, settings.clicksPerTick.get());
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
		return waypointsByWorld.computeIfAbsent(worldFile(mc), f -> new Waypoints(
			FabricLoader.getInstance().getConfigDir().resolve("lazuli").resolve("waypoints").resolve(f)));
	}

	/** What's in this server's (or world's) chests, as last seen. */
	private dev.lazuli.core.storage.ChestMemory chests(Minecraft mc) {
		return chestsByWorld.computeIfAbsent(worldFile(mc), f -> new dev.lazuli.core.storage.ChestMemory(
			FabricLoader.getInstance().getConfigDir().resolve("lazuli").resolve("chests").resolve(f)));
	}

	/** Every villager's trades seen on this server (or world). */
	private dev.lazuli.core.trade.TradeMemory tradeMemory(Minecraft mc) {
		return tradesByWorld.computeIfAbsent(worldFile(mc), f -> new dev.lazuli.core.trade.TradeMemory(
			FabricLoader.getInstance().getConfigDir().resolve("lazuli").resolve("trades").resolve(f)));
	}

	/** A file name for the server (or singleplayer world) being played on. */
	private static String worldFile(Minecraft mc) {
		String id;
		if (mc.getCurrentServer() != null) id = "server-" + mc.getCurrentServer().ip;
		else if (mc.getSingleplayerServer() != null) id = "world-" + mc.getSingleplayerServer().getWorldData().getLevelName();
		else id = "unknown";
		return id.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9._-]", "_") + ".txt";
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
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> mc.gui.getChat().addMessage(Component.literal(PREFIX + msg)));
	}

	private void onChunkLoad(ClientLevel level, LevelChunk chunk) {
		if (level != lastLevel) {
			lastLevel = level;
			cache.reset(level.getMinY(), level.getHeight());
			if (session.snapshot().state() == PathingSession.State.PATHING) session.stop();
		}
		int cx = chunk.getPos().x, cz = chunk.getPos().z;
		ChunkScanner.Scan scan = ChunkScanner.scan(chunk, cache.sectionCount());
		cache.putChunk(cx, cz, scan.kinds(), scan.physics());
		session.regionChanged(cx * 16, cache.minY(), cz * 16, cx * 16 + 15, cache.maxY(), cz * 16 + 15);
		mine.onChunkLoad(chunk);
		explore.onChunkLoad(chunk);
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
		self.mine.onBlockChanged(pos, state);
		if (!self.cache.set(pos.getX(), pos.getY(), pos.getZ(), BlockClassifier.classify(state), BlockClassifier.physicsId(state))) return;
		self.session.regionChanged(pos.getX(), pos.getY(), pos.getZ(), pos.getX(), pos.getY(), pos.getZ());
	}
}
