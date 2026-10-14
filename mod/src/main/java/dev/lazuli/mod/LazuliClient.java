package dev.lazuli.mod;

import dev.lazuli.core.command.CommandHandler;
import dev.lazuli.core.session.PathingSession;
import dev.lazuli.core.settings.Settings;
import dev.lazuli.core.world.BlockView;
import dev.lazuli.core.world.WorldCache;
import dev.lazuli.mod.config.LazuliConfig;
import dev.lazuli.mod.control.PathExecutor;
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
	private ClientLevel lastLevel;

	@Override
	public void onInitializeClient() {
		instance = this;
		LazuliConfig.stopAction = () -> session.stop();
		recorder = new TraceRecorder(FabricLoader.getInstance().getConfigDir().resolve("lazuli").resolve("traces"), this::chat);

		session = new PathingSession(cache, cache::minY, cache::maxY, settings, worker, this::chat);
		executor = new PathExecutor(session, settings, cache);
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
			public void openConfig() {
				openConfigNextTick = true; // the chat screen is still closing
			}
		});
		renderer = new LazuliRenderer(session, settings, executor::predicted, commands::goal);

		ClientSendMessageEvents.ALLOW_CHAT.register(message -> {
			if (Minecraft.getInstance().player == null) return true;
			try {
				return !commands.handle(message);
			} catch (RuntimeException e) {
				chat("Error: " + e.getMessage());
				return false;
			}
		});
		ClientChunkEvents.CHUNK_LOAD.register(this::onChunkLoad);
		ClientChunkEvents.CHUNK_UNLOAD.register(this::onChunkUnload);
		// order matters: the recorder logs this tick's movement before the executor turns the view for the next one
		ClientTickEvents.END_CLIENT_TICK.register(recorder::tick);
		ClientTickEvents.END_CLIENT_TICK.register(executor::tick);
		ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("lazuli", "status"), renderer::renderHud);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> {
			lastLevel = null;
			session.stop();
			executor.release(mc);
		});
	}

	private void onTick(Minecraft mc) {
		renderer.tick(mc);
		while (LazuliConfig.STOP.consumeClick()) {
			if (session.snapshot().state() == PathingSession.State.PATHING) {
				session.stop();
				chat("Stopped");
			}
		}
		boolean open = openConfigNextTick;
		openConfigNextTick = false;
		while (LazuliConfig.OPEN_CONFIG.consumeClick()) open = mc.screen == null;
		if (open) mc.setScreen(new WalksyLibPanelScreen(mc.screen, LazuliConfig.ID));
	}

	private void chat(String msg) {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> mc.gui.getChat().addMessage(Component.literal("[Lazuli] " + msg)));
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
		self.cache.set(pos.getX(), pos.getY(), pos.getZ(), BlockClassifier.classify(state), BlockClassifier.physicsId(state));
		self.session.regionChanged(pos.getX(), pos.getY(), pos.getZ(), pos.getX(), pos.getY(), pos.getZ());
	}
}
