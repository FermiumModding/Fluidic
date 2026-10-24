package fluidic.sim;

/**
 * Shared constants and helpers for the voxel water model.
 *
 * Water is stored as an integer mass per block cell so that the simulation conserves water exactly while
 * it flows. {@link #FULL} is the mass of one full block, which is split into {@link #LEVELS} visible levels
 * when water settles into real blocks.
 */
public final class Water {

    /** Mass of one full block of water. */
    public static final int FULL = 1024;
    /** Number of block levels settled water is quantized to, the eighths of vanilla water levels. */
    public static final int LEVELS = 8;
    /** Surface of a full block of water as vanilla renders it. */
    public static final float RENDER_HEIGHT = LEVELS / (LEVELS + 1.0F);
    /** Mass of one settled level. */
    public static final int UNIT = FULL / LEVELS;
    /** Extra mass a cell may hold per cell of water above it, gives pressure so U-bends equalize. */
    public static final int COMPRESS = 16;
    /** Minimum mass difference before two wet cells exchange water, stops endless dithering. */
    public static final int SPREAD_DIFF = 8;
    /** Level difference between active and settled water before the settled cell is woken. */
    public static final int WAKE_DIFF = UNIT;
    /** A section counts as quiet for the step when no flow exceeds this. */
    public static final int QUIET_FLOW = 2;
    /**
     * The same for flows between two full cells. Compression rounding keeps deep water trading a little mass
     * underneath forever, which settling decompresses away anyway.
     */
    public static final int QUIET_PRESSURE_FLOW = UNIT / 8;
    /**
     * Water a quiet section may move up, down or across its sides on the whole before it settles, so water rising
     * slowly through pressure, like up a U bend, keeps going until it nearly levels.
     */
    public static final int SETTLE_DRIFT = UNIT / 2;
    /** Quiet periods in a row settling is put off for while water keeps drifting, in case it never stops. */
    public static final int MAX_DRIFT_WINDOWS = 30;
    /** Cells holding less than this when settling evaporate instead of becoming a block. */
    public static final int SETTLE_MIN = UNIT / 4;

    public static final int DOWN = 0, UP = 1, NORTH = 2, SOUTH = 3, WEST = 4, EAST = 5;
    public static final int[] DX = {0, 0, 0, 0, -1, 1};
    public static final int[] DY = {-1, 1, 0, 0, 0, 0};
    public static final int[] DZ = {0, 0, -1, 1, 0, 0};

    private Water() {}

    /** How much mass the lower of two vertically stacked cells holds when stable, given their combined mass. */
    public static int stableBelow(int total) {
        if(total <= FULL) return FULL;
        if(total < 2 * FULL + COMPRESS) return (FULL * FULL + total * COMPRESS) / (FULL + COMPRESS);
        return (total + COMPRESS) / 2;
    }

    /**
     * How freely water runs sideways over a surface, in thousandths, from the terrain code of the cell below it.
     * Water on water barely slows down, rough ground like grass catches it.
     */
    static int flowRate(int codeBelow) {
        switch(codeBelow) {
            case WaterSection.SOLID_ROUGH: return 500;
            case WaterSection.SOLID:
            case WaterSection.SOLID_REACTIVE: return 700;
            case WaterSection.SOLID_SMOOTH: return 850;
            default: return 1000;
        }
    }

    /** Settled block level (0 = nothing) for a cell mass. */
    public static int settleLevel(int mass) {
        if(mass < SETTLE_MIN) return 0;
        return Math.max(1, Math.min(LEVELS, (mass + UNIT / 2) / UNIT));
    }

    /** Height byte sent to clients for rendering, 255 = full block. */
    public static byte quantize(int mass) {
        return (byte)Math.min(255, (mass * 255 + FULL / 2) / FULL);
    }

    public static int cellIndex(int x, int y, int z) {
        return (y << 8) | (z << 4) | x;
    }

    public static long sectionKey(int sx, int sy, int sz) {
        return ((long)(sx & 0x3FFFFF) << 26) | ((long)(sz & 0x3FFFFF) << 4) | (sy & 0xF);
    }

    public static int keyX(long key) {
        return (int)(key << 16 >> 42);
    }

    public static int keyZ(long key) {
        return (int)(key << 38 >> 42);
    }

    public static int keyY(long key) {
        return (int)(key & 0xF);
    }
}
