package dev.lazuli.mod;

import dev.lazuli.api.Lazuli;
import dev.lazuli.core.command.CommandHandler;
import dev.lazuli.core.goal.Goal;
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
import dev.lazuli.mod.process.FarmProcess;
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
	private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "lazuli-planner");
		t.setDaemon(true);
		return t;
	});
	private TraceRecorder recorder;
	private LazuliRenderer renderer;
	private boolean openConfigNextTick;
	private PathingSession session;
	private CommandHandler commands;
	private PathExecutor executor;
	private MineProcess mine;
	private FollowProcess follow;
	private ExploreProcess explore;
	private TunnelProcess tunnel;
	private FarmProcess farm;
	private ContainerProcess containers;
	private Upkeep upkeep;
	/** Every process; at most one runs at a time. */
	private java.util.List<Process> processes;
	/** "[Lazuli] " in blue. */
	private static final String PREFIX = Chat.fmt(Chat.DARK_GRAY, "[") + "\u00a79Lazuli" + Chat.DARK_GRAY + "] " + Chat.GRAY;
	private ClientLevel lastLevel;
	/** The job started through the API (see {@link Lazuli}). */
	private final Tasks tasks = new Tasks();
	private final ClickDriver clicks = new ClickDriver();

	@Override
	public void onInitializeClient() {
		instance = this;
		LazuliConfig.stopAction = () -> {
			tasks.cancel();
			stopProcesses(false);
			session.stop();
		};
		recorder = new TraceRecorder(FabricLoader.getInstance().getConfigDir().resolve("lazuli").resolve("traces"), this::chat);

		session = new PathingSession(cache, cache::minY, cache::maxY, settings, worker, this::chat,
			() -> Tools.snapshot(Minecraft.getInstance().player, settings));
		executor = new PathExecutor(session, settings, cache, this::chat);
		mine = new MineProcess(session, settings, cache, this::chat);
		follow = new FollowProcess(session, settings, this::chat);
		explore = new ExploreProcess(session, this::chat);
		tunnel = new TunnelProcess(session, settings, this::chat);
		farm = new FarmProcess(session, settings, this::chat);
		containers = new ContainerProcess(session, settings, clicks, this::chat);
		upkeep = new Upkeep(settings, this::chat);
		processes = java.util.List.of(mine, follow, explore, tunnel, farm, containers);
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
			public void stopProcesses() {
				LazuliClient.this.stopProcesses(true);
			}

			@Override
			public void follow(String mode, java.util.List<String> args) {
				LazuliClient.this.stopProcesses(false);
				follow.start(FollowProcess.Mode.valueOf(mode.toUpperCase(java.util.Locale.ROOT)), args);
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
			public void container(boolean deposit, String item, int count, int[] at) {
				LazuliClient.this.stopProcesses(false);
				containers.start(Minecraft.getInstance(), deposit, item, count, at == null ? null : new BlockPos(at[0], at[1], at[2]));
			}

			@Override
			public void drop(String item, int count) {
				LazuliClient.this.drop(item, count);
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

		ClientSendMessageEvents.ALLOW_CHAT.register(message -> {
			if (Minecraft.getInstance().player == null) return true;
			try {
				return !commands.handle(message);
			} catch (RuntimeException e) {
				chat(Chat.err("Error: {}", e.getMessage()));
				return false;
			}
		});
		ClientChunkEvents.CHUNK_LOAD.register(this::onChunkLoad);
		ClientChunkEvents.CHUNK_UNLOAD.register(this::onChunkUnload);
		// order matters: the recorder logs this tick's movement before the executor turns the view for the next one
		ClientTickEvents.END_CLIENT_TICK.register(recorder::tick);
		ClientTickEvents.END_CLIENT_TICK.register(executor::tick);
		// after the executor, which clears the mining target; nothing moves while eating
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (mc.player == null || mc.level == null) return;
			upkeep.tick(mc, busy());
			if (Upkeep.eating()) return;
			for (Process p : processes) p.tick(mc);
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
		public Task container(boolean deposit, String item, int count, int[] at) {
			return tasks.run(() -> {
				stopProcesses(false);
				containers.start(Minecraft.getInstance(), deposit, item, count, at == null ? null : new BlockPos(at[0], at[1], at[2]));
				return new Task((deposit ? "deposit " : "withdraw ") + item, () -> switch (containers.outcome()) {
					case NONE -> null;
					case DONE -> Task.Result.DONE;
					case FAILED -> Task.Result.FAILED;
					case CANCELLED -> Task.Result.CANCELLED;
				}, () -> containers.stop(false));
			});
		}

		@Override
		public void drop(String item, int count) {
			LazuliClient.this.drop(item, count);
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
			tasks.cancel();
			stopProcesses(false);
			session.stop(true);
		}

		@Override
		public void command(String line) {
			String prefix = settings.prefix.get();
			commands.handle(line.startsWith(prefix) ? line : prefix + line);
		}
	}

	/** Throws out {@code count} of {@code item} ("all": anything) from the main inventory and hotbar; negative: all of it. */
	private void drop(String item, int count) {
		var p = Minecraft.getInstance().player;
		if (p == null) return;
		if (p.containerMenu != p.inventoryMenu) {
			chat(Chat.err("Close the open menu first"));
			return;
		}
		var match = Inventories.matcher(item);
		var menu = Inventories.read(p.inventoryMenu, p);
		var plan = Moves.drop(menu, Inventories.PLAYER_MAIN, match, count);
		if (plan.isEmpty()) {
			chat(Chat.err("No {} to drop", item));
			return;
		}
		var sim = menu.copy();
		sim.applyAll(plan);
		clicks.play(p.inventoryMenu.containerId, plan);
		chat(Chat.ok("Dropping {} {}", sim.dropped(), item));
	}

	private void stopProcesses(boolean announce) {
		for (Process p : processes) p.stop(announce);
	}

	/** Pathing, or a process running. */
	private boolean busy() {
		if (session.snapshot().state() == PathingSession.State.PATHING) return true;
		for (Process p : processes) if (p.active()) return true;
		return false;
	}

	private void onTick(Minecraft mc) {
		tasks.tick();
		clicks.tick(mc, settings.clicksPerTick.get());
		renderer.tick(mc);
		FreeCam.tick(mc);
		while (LazuliConfig.FREECAM.consumeClick()) toggleFreecam(mc);
		if (mc.player == null) FreeCam.off();
		// the view (or, with free look, the server-side rotation) is Lazuli's for the whole trip, not just while keys are held
		if (!busy()) RotationDriver.end();
		while (LazuliConfig.STOP.consumeClick()) {
			tasks.cancel();
			stopProcesses(true);
			if (session.snapshot().state() == PathingSession.State.PATHING) session.stop();
		}
		boolean open = openConfigNextTick;
		openConfigNextTick = false;
		while (LazuliConfig.OPEN_CONFIG.consumeClick()) open = mc.screen == null;
		if (open) mc.setScreen(new WalksyLibPanelScreen(mc.screen, LazuliConfig.ID));
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
