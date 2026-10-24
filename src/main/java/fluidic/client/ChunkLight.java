package fluidic.client;

/**
 * Simulated water is no block, the world's light passes it as if it were air, so the ground under a pool of it would
 * be lit like dry ground while the same pool settled darkens it. While chunks are drawn, sky light inside simulated
 * water is dimmed the way water blocks dim it, 3 levels per block of water, partly filled cells by their share. Only
 * for drawing, the world's light stays as it is.
 */
public final class ChunkLight {

    /** Light water blocks take away per block, their light opacity */
    private static final float DIM_PER_BLOCK = 3.0F;
    /** Water deeper than this has taken all sky light, 15 / 3 */
    private static final int MAX_DEPTH = 5;
    /** Per drawing thread: whether a chunk is being drawn */
    private static final ThreadLocal<boolean[]> DRAWING = ThreadLocal.withInitial(() -> new boolean[1]);

    private ChunkLight() {}

    public static void beginRedraw() {
        DRAWING.get()[0] = true;
    }

    public static void endRedraw() {
        DRAWING.get()[0] = false;
    }

    /**
     * Sky light at a block as a chunk being drawn sees it, in simulated water dimmed by the simulated water above it. A
     * water block gives the blocks around it the light above it when that is brighter, BlockLiquid.getPackedLightmapCoords,
     * so the bottom of a pool one block deep is lit like dry ground and only deeper water darkens it.
     */
    public static int sky(int sky, int x, int y, int z) {
        if(!DRAWING.get()[0]) return sky;
        return drawnSky(sky, x, y, z);
    }

    /** {@link #sky} for a block access that only draws chunks */
    public static int drawnSky(int sky, int x, int y, int z) {
        if(sky == 0 || y < 0 || y >= 255 || ClientWaterCache.heightAnyThread(x, y, z) == 0) return sky;
        return dimmed(sky, x, y + 1, z);
    }

    /**
     * Sky light inside simulated water, dimmed by it and the simulated water above it in its column. Water blocks above
     * already dimmed the world's light.
     */
    public static int dimmed(int sky, int x, int y, int z) {
        if(sky == 0) return sky;
        float dim = 0.0F;
        for(int dy = 0; dy < MAX_DEPTH && y + dy <= 255; dy++) {
            int height = ClientWaterCache.heightAnyThread(x, y + dy, z);
            if(height == 0) break;
            dim += DIM_PER_BLOCK * height / 255.0F;
        }
        return Math.max(0, sky - Math.round(dim));
    }

    /** How much simulated water of a height dims the light in it, in whole levels */
    static int dimOf(int height) {
        return Math.round(DIM_PER_BLOCK * height / 255.0F);
    }

    /** Blocks below simulated water as deep as it dims the light, they are redrawn when it changes */
    static int reach() {
        return MAX_DEPTH;
    }
}
