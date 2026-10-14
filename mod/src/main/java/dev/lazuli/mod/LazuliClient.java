package dev.lazuli.mod;

import dev.lazuli.core.command.CommandHandler;
import dev.lazuli.core.session.PathingSession;
import dev.lazuli.core.settings.Settings;
import dev.lazuli.core.world.BlockView;
import dev.lazuli.core.world.WorldCache;
import dev.lazuli.mod.control.PathExecutor;
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

import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class LazuliClient implements ClientModInitializer {
	private static LazuliClient instance;

	private final WorldCache cache = new WorldCache(-64, 384);
	private final Settings settings = new Settings();
	private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "lazuli-planner");
		t.setDaemon(true);
		return t;
	});
	private Path configFile;
	private PathingSession session;
	private CommandHandler commands;
	private PathExecutor executor;
	private ClientLevel lastLevel;

	@Override
	public void onInitializeClient() {
		instance = this;
		configFile = FabricLoader.getInstance().getConfigDir().resolve("lazuli.json");
		SettingsStore.load(configFile, settings);

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
				SettingsStore.save(configFile, settings);
			}
		});

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
		ClientTickEvents.END_CLIENT_TICK.register(executor::tick);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> {
			lastLevel = null;
			session.stop();
			executor.release(mc);
		});
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
		cache.putChunk(cx, cz, ChunkScanner.scan(chunk, cache.sectionCount()));
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
		self.cache.set(pos.getX(), pos.getY(), pos.getZ(), BlockClassifier.classify(state));
		self.session.regionChanged(pos.getX(), pos.getY(), pos.getZ(), pos.getX(), pos.getY(), pos.getZ());
	}
}
