package dev.lazuli.mod.control;

import dev.lazuli.core.chat.Chat;
import dev.lazuli.core.physics.Trace;
import dev.lazuli.core.world.BlockKind;
import dev.lazuli.core.world.BlockPhysics;
import dev.lazuli.mod.world.BlockClassifier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * {@code #record}: logs the player's inputs and resulting state every tick, plus the blocks around where they went,
 * as a {@link Trace} for the sim's replay tests. Must tick after the player moves and before anything changes the
 * view for the next tick.
 */
public final class TraceRecorder {
    private static final int MAX_TICKS = 20 * 60 * 5;
    private static final int MARGIN = 3;

    private final Path dir;
    private final Consumer<String> chat;
    private Trace trace;
    private String name;
    private int minX, minY, minZ, maxX, maxY, maxZ;

    public TraceRecorder(Path dir, Consumer<String> chat) {
        this.dir = dir;
        this.chat = chat;
    }

    public boolean recording() {
        return trace != null;
    }

    public void start(String name) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return;
        trace = new Trace();
        this.name = name;
        BlockPos b = p.blockPosition();
        minX = maxX = b.getX();
        minY = maxY = b.getY();
        minZ = maxZ = b.getZ();
        trace.ticks.add(state(p, 0, 0, false));
        chat.accept(Chat.ok("Recording {}; #record stop to save", name));
    }

    public void tick(Minecraft mc) {
        if (trace == null) return;
        LocalPlayer p = mc.player;
        if (p == null) {
            trace = null;
            return;
        }
        Input in = p.input.keyPresses;
        int fwd = (in.forward() ? 1 : 0) - (in.backward() ? 1 : 0);
        int strafe = (in.left() ? 1 : 0) - (in.right() ? 1 : 0);
        trace.ticks.add(state(p, fwd, strafe, in.jump()));
        BlockPos b = p.blockPosition();
        minX = Math.min(minX, b.getX());
        minY = Math.min(minY, b.getY());
        minZ = Math.min(minZ, b.getZ());
        maxX = Math.max(maxX, b.getX());
        maxY = Math.max(maxY, b.getY());
        maxZ = Math.max(maxZ, b.getZ());
        if (trace.ticks.size() > MAX_TICKS || maxX - minX > 96 || maxZ - minZ > 96 || maxY - minY > 48) {
            chat.accept(Chat.warn("Recording too long or too far; saving"));
            stop(mc);
        }
    }

    private static Trace.Tick state(LocalPlayer p, int fwd, int strafe, boolean jump) {
        Vec3 v = p.getDeltaMovement();
        // the rotation the player moved with: with free look that is not the camera's
        return new Trace.Tick(fwd, strafe, jump, p.isSprinting(), RotationDriver.yaw(p), p.getX(), p.getY(), p.getZ(),
            v.x, v.y, v.z, p.onGround());
    }

    public void stop(Minecraft mc) {
        if (trace == null) {
            chat.accept(Chat.err("Not recording"));
            return;
        }
        Trace t = trace;
        trace = null;
        if (mc.level != null) {
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int x = minX - MARGIN; x <= maxX + MARGIN; x++) {
                for (int y = minY - MARGIN; y <= maxY + MARGIN; y++) {
                    for (int z = minZ - MARGIN; z <= maxZ + MARGIN; z++) {
                        BlockState s = mc.level.getBlockState(pos.set(x, y, z));
                        BlockKind k = BlockClassifier.classify(s);
                        short id = BlockClassifier.physicsId(s);
                        if (k == BlockKind.AIR && id == BlockPhysics.DERIVED) continue;
                        BlockPhysics phys = id == BlockPhysics.DERIVED ? BlockPhysics.forKind(k) : BlockPhysics.byId(id);
                        t.putBlock(x, y, z, k, phys);
                    }
                }
            }
        }
        Path file = dir.resolve(name + ".trace");
        try {
            Files.createDirectories(dir);
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                t.write(w);
            }
            Trace.Divergence d = t.replay();
            chat.accept(Chat.GREEN + String.format("Saved %d ticks to %s (sim error: %.4f per tick, worst at tick %d; %.3f drift)",
                t.ticks.size() - 1, file.getFileName(), d.maxStep(), d.worstStep(), d.maxDrift()));
        } catch (IOException e) {
            chat.accept(Chat.err("Could not save trace: {}", e.getMessage()));
        }
    }
}
