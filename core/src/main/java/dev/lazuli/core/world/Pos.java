package dev.lazuli.core.world;

/** Packs a block position into a long, same layout as vanilla BlockPos.asLong (x 26 bits, z 26 bits, y 12 bits). */
public final class Pos {
    private static final int XZ_BITS = 26;
    private static final int Y_BITS = 12;
    private static final int X_SHIFT = Y_BITS + XZ_BITS;
    private static final int Z_SHIFT = Y_BITS;
    private static final long XZ_MASK = (1L << XZ_BITS) - 1;
    private static final long Y_MASK = (1L << Y_BITS) - 1;

    private Pos() {}

    public static long pack(int x, int y, int z) {
        return ((x & XZ_MASK) << X_SHIFT) | ((z & XZ_MASK) << Z_SHIFT) | (y & Y_MASK);
    }

    public static int x(long p) {
        return (int) (p >> X_SHIFT);
    }

    public static int y(long p) {
        return (int) (p << (64 - Y_BITS) >> (64 - Y_BITS));
    }

    public static int z(long p) {
        return (int) (p << (64 - Z_SHIFT - XZ_BITS) >> (64 - XZ_BITS));
    }

    public static long offset(long p, int dx, int dy, int dz) {
        return pack(x(p) + dx, y(p) + dy, z(p) + dz);
    }

    public static long chunkKey(int blockX, int blockZ) {
        return ((long) (blockX >> 4) << 32) | ((blockZ >> 4) & 0xFFFFFFFFL);
    }

    public static String toString(long p) {
        return x(p) + " " + y(p) + " " + z(p);
    }
}
