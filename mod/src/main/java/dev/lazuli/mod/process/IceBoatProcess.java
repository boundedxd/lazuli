package dev.lazuli.mod.process;

import dev.lazuli.core.chat.Chat;
import dev.lazuli.core.control.Controls;
import dev.lazuli.core.physics.BoatSim;
import dev.lazuli.core.race.IceRacer;
import dev.lazuli.core.race.RaceLine;
import dev.lazuli.core.race.RecordedLine;
import dev.lazuli.core.race.TrackField;
import dev.lazuli.core.settings.Settings;
import dev.lazuli.core.world.BlockView;
import dev.lazuli.mod.control.InputDriver;
import dev.lazuli.mod.control.RotationDriver;
import dev.lazuli.mod.mixin.AbstractBoatAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * #iceboat: races the boat the player sits in, with {@link IceRacer} (a look-ahead search over the game's own boat
 * physics) along a track: worked out from the blocks ({@link TrackField}, to a goal or round laps from here), or a
 * line driven once and recorded ({@link RecordedLine}) for courses the blocks don't explain (slime, odd jumps).
 * <p>
 * The track is worked out off the game thread and again every few seconds as more of it loads in. While recording,
 * the player drives; a line that comes back round to its start is a lap.
 */
public final class IceBoatProcess implements Process {
    private enum Phase { IDLE, BUILDING, RACING, RECORDING }

    /** How many cells of track a field may take in. */
    private static final int MAX_CELLS = 600_000;
    /** Ticks between working the track out again (more chunks loaded); between clearing the racer's block cache. */
    private static final int REBUILD = 100, RECACHE = 40;

    private final Settings settings;
    private final BlockView world;
    private final Path dir;
    private final Consumer<String> chat;
    private final InputDriver input = new InputDriver();
    private IceRacer racer;
    private Phase phase = Phase.IDLE;

    private RaceLine line;
    private RaceLine.Cursor cursor;
    private CompletableFuture<TrackField> building;
    /** What the field is built for: goal (or NaN y for any height), or a lap from (start, yaw). */
    private boolean lapMode;
    private int laps, startTick, builtTick;
    private int[] goal, start;
    private float startYaw;
    /** The recorded line in use (null: work it out from the blocks). */
    private RecordedLine recorded;
    private String recordName;
    private final List<double[]> recording = new ArrayList<>();
    private double travelled;

    public IceBoatProcess(Settings settings, BlockView world, Path dir, Consumer<String> chat) {
        this.settings = settings;
        this.world = world;
        this.dir = dir;
        this.chat = chat;
    }

    @Override
    public boolean active() {
        return phase != Phase.IDLE;
    }

    @Override
    public String status() {
        return switch (phase) {
            case IDLE -> null;
            case BUILDING -> "Working out the track";
            case RACING -> "Racing" + (lapMode ? " (lap " + Math.min(laps, cursor.laps + 1) + "/" + laps + ")" : "");
            case RECORDING -> "Recording a line (" + recording.size() + " points)";
        };
    }

    /** Races to (x, y, z); y null: at any height. */
    public void toGoal(Minecraft mc, int x, Integer y, int z) {
        if (!begin(mc)) return;
        lapMode = false;
        goal = new int[]{x, y == null ? Integer.MIN_VALUE : y, z};
        if (recorded != null && !recorded.loop()) {
            chat.accept(Chat.warn("Using the blocks, not the recorded line, to get to a point ({}line off to drop it)", settings.prefix.get()));
        }
        build(mc);
    }

    /** Races {@code n} laps, from here the way the boat faces (or round the recorded line, if it's a loop). */
    public void lap(Minecraft mc, int n) {
        if (!begin(mc)) return;
        lapMode = true;
        laps = Math.max(1, n);
        AbstractBoat b = boat(mc.player);
        start = new int[]{Mth.floor(b.getX()), Mth.floor(b.getY() + 0.01), Mth.floor(b.getZ())};
        startYaw = b.getYRot();
        if (recorded != null && recorded.loop()) {
            line = recorded.laps(laps);
            go(mc);
        } else {
            build(mc);
        }
    }

    /** Races the recorded line from end to end (one lap, if it's a loop). */
    public void followLine(Minecraft mc) {
        if (recorded == null) {
            chat.accept(Chat.err("No line: {}iceboat record first, or {}iceboat line <name>", settings.prefix.get(), settings.prefix.get()));
            return;
        }
        if (!begin(mc)) return;
        lapMode = recorded.loop();
        laps = 1;
        line = recorded.laps(1);
        go(mc);
    }

    /** Starts (or, recording, stops) recording a line as the player drives. */
    public void record(Minecraft mc, String name) {
        if (phase == Phase.RECORDING) {
            finishRecording(false);
            return;
        }
        if (!begin(mc)) return;
        recordName = name == null ? "last" : name;
        recording.clear();
        travelled = 0;
        startTick = mc.player.tickCount;
        phase = Phase.RECORDING;
        chat.accept(Chat.info("Recording: drive the course (back round to the start makes it a lap; {}iceboat record again to stop)",
            settings.prefix.get()));
    }

    /** Uses a saved line (null or "off": back to working the track out from the blocks). */
    public void useLine(String name) {
        if (name == null || name.equalsIgnoreCase("off")) {
            recorded = null;
            chat.accept(Chat.ok("Working tracks out from the blocks again"));
            return;
        }
        try {
            recorded = RecordedLine.load(file(name));
            chat.accept(Chat.ok("Line {}: {} blocks{}", name, (int) recorded.length(), recorded.loop() ? ", a lap" : ""));
        } catch (Exception e) {
            chat.accept(Chat.err("No line called {} ({})", name, e.getMessage()));
        }
    }

    private boolean begin(Minecraft mc) {
        stop(false);
        LocalPlayer p = mc.player;
        if (p == null || boat(p) == null) {
            chat.accept(Chat.err("Get in a boat first"));
            return false;
        }
        racer = new IceRacer(world).beam(settings.iceboatBeam.get()).budget(settings.iceboatBudgetMs.get() * 1_000_000L);
        return true;
    }

    private void build(Minecraft mc) {
        phase = Phase.BUILDING;
        AbstractBoat b = boat(mc.player);
        int bx = Mth.floor(b.getX()), by = Mth.floor(b.getY() + 0.01), bz = Mth.floor(b.getZ());
        building = CompletableFuture.supplyAsync(() -> field(bx, by, bz));
        builtTick = mc.player.tickCount;
    }

    /** The track from the boat's cell (worked out off the game thread). */
    private TrackField field(int x, int y, int z) {
        if (lapMode) return TrackField.lap(world, start[0], start[1], start[2], startYaw, MAX_CELLS);
        return TrackField.toGoal(world, x, y, z, goal[0], goal[1], goal[2], 1.5, MAX_CELLS);
    }

    private void go(Minecraft mc) {
        AbstractBoat b = boat(mc.player);
        RaceLine.Cursor old = cursor;
        cursor = line.start(b.getX(), b.getY(), b.getZ());
        if (old != null && phase == Phase.RACING) {
            cursor.laps = old.laps; // a rebuilt track: the laps done still count
            cursor.offset = old.offset;
        }
        if (phase != Phase.RACING) startTick = mc.player.tickCount;
        phase = Phase.RACING;
    }

    @Override
    public void stop(boolean announce) {
        if (phase == Phase.IDLE) return;
        if (phase == Phase.RECORDING) {
            finishRecording(false);
            return;
        }
        end(announce ? Chat.warn("Stopped racing") : null);
    }

    private void end(String msg) {
        phase = Phase.IDLE;
        if (building != null) building.cancel(true);
        building = null;
        cursor = null;
        line = null;
        input.release();
        if (msg != null) chat.accept(msg);
    }

    @Override
    public void tick(Minecraft mc) {
        if (phase == Phase.IDLE) return;
        LocalPlayer p = mc.player;
        AbstractBoat b = p == null ? null : boat(p);
        if (b == null) {
            if (phase == Phase.RECORDING) finishRecording(false);
            else end(Chat.err("Out of the boat: stopped racing"));
            return;
        }
        switch (phase) {
            case RECORDING -> recordTick(b);
            case BUILDING -> {
                if (!building.isDone()) return;
                TrackField f = building.getNow(null);
                building = null;
                if (f == null) {
                    end(Chat.err(lapMode ? "No way round back to the start from here (facing the way to race?)"
                        : "No way there along the track (is it loaded?)"));
                    return;
                }
                line = lapMode ? f.laps(laps) : f;
                chat.accept(Chat.info("Track: {} cells{}; go!", f.cellCount(), lapMode ? ", lap " + (int) f.lapLength() + " blocks" : ""));
                go(mc);
            }
            case RACING -> race(mc, p, b);
            case IDLE -> { }
        }
    }

    private void race(Minecraft mc, LocalPlayer p, AbstractBoat b) {
        double toGo = line.toGo(cursor, b.getX(), b.getY(), b.getZ());
        if (!Double.isNaN(toGo) && line.finished(cursor, toGo)) {
            end(Chat.ok("Finished in {}s", String.format(Locale.ROOT, "%.2f", (p.tickCount - startTick) / 20.0)));
            return;
        }
        if (p.tickCount % RECACHE == 0) racer.reset();
        // more of a long track loads in as it goes: work it out again now and then (in the background)
        if (line instanceof TrackField && p.tickCount - builtTick > REBUILD && building == null) {
            int bx = Mth.floor(b.getX()), by = Mth.floor(b.getY() + 0.01), bz = Mth.floor(b.getZ());
            building = CompletableFuture.supplyAsync(() -> field(bx, by, bz));
            builtTick = p.tickCount;
        }
        if (building != null && building.isDone()) {
            TrackField f = building.getNow(null);
            building = null;
            if (f != null) {
                line = lapMode ? f.laps(laps) : f;
                go(mc);
            }
        }
        AbstractBoatAccessor acc = (AbstractBoatAccessor) b;
        var v = b.getDeltaMovement();
        BoatSim sim = new BoatSim(racer.view(), b.getX(), b.getY(), b.getZ(), v.x, v.y, v.z, b.getYRot(), acc.lazuli$spin());
        IceRacer.Keys pending = new IceRacer.Keys(acc.lazuli$left(), acc.lazuli$right(), acc.lazuli$forward(), acc.lazuli$back());
        IceRacer.Keys k = racer.next(sim, pending, line, cursor);
        // look the way the boat's going, turning at the usual speed
        float maxTurn = settings.rotationSpeed.get().floatValue();
        float yaw0 = RotationDriver.yaw(p), pitch0 = RotationDriver.pitch(p);
        float want = b.getYRot();
        float yaw = yaw0 + Mth.clamp(Mth.wrapDegrees(want - yaw0), -maxTurn, maxTurn);
        float pitch = pitch0 + Mth.clamp(20 - pitch0, -maxTurn, maxTurn);
        int strafe = k.left() ? 1 : k.right() ? -1 : 0;
        input.apply(mc, new Controls(k.forward(), k.back(), false, false, want, 20, strafe), yaw, pitch, settings.freeLook.get());
    }

    private void recordTick(AbstractBoat b) {
        double[] pt = {b.getX(), b.getY(), b.getZ()};
        if (!recording.isEmpty()) {
            double[] last = recording.get(recording.size() - 1);
            travelled += Math.hypot(pt[0] - last[0], pt[2] - last[2]);
        }
        recording.add(pt);
        double[] first = recording.get(0);
        // back round to where it started, having been somewhere: a lap
        if (travelled > 50 && Math.hypot(pt[0] - first[0], pt[2] - first[2]) < 4 && Math.abs(pt[1] - first[1]) < 2) {
            finishRecording(true);
        }
    }

    private void finishRecording(boolean lap) {
        phase = Phase.IDLE;
        if (recording.size() < 10) {
            chat.accept(Chat.warn("Too short to keep"));
            return;
        }
        try {
            recorded = new RecordedLine(new ArrayList<>(recording), lap);
            recorded.save(file(recordName));
            chat.accept(Chat.ok("Saved line {}: {} blocks{} ({}iceboat to race it{})", recordName, (int) recorded.length(),
                lap ? ", a lap" : "", settings.prefix.get(), lap ? ", " + settings.prefix.get() + "iceboat lap <n> for laps" : ""));
        } catch (Exception e) {
            chat.accept(Chat.err("Couldn't save the line: {}", e.getMessage()));
        }
    }

    private Path file(String name) {
        return dir.resolve(name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_") + ".txt");
    }

    private static AbstractBoat boat(LocalPlayer p) {
        return p != null && p.getVehicle() instanceof AbstractBoat b ? b : null;
    }
}
