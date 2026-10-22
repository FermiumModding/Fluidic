package fluidic.client;

import fluidic.handlers.ForgeConfigHandler;
import fluidic.handlers.ModRegistry;
import fluidic.sim.Water;
import net.minecraft.client.Minecraft;
import net.minecraft.util.math.BlockPos;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Heights of simulated water received from the server, one byte per block (255 = full). Written on the client
 * thread; {@link #heightAnyThread} may also be read from chunk render threads, which draw settled water.
 */
public final class ClientWaterCache {

    private static final int ALL_BORDERS = 0x3F;

    private static final ConcurrentHashMap<Long, byte[]> SECTIONS = new ConcurrentHashMap<>();
    private static int dimension = Integer.MIN_VALUE;

    // Single entry lookup cache for the client thread, mesh building asks for the same section over and over
    private static long lastKey = Long.MIN_VALUE;
    private static byte[] lastSection;

    private ClientWaterCache() {}

    public static void update(int packetDimension, long key, byte[] heights) {
        Minecraft mc = Minecraft.getMinecraft();
        if(mc.world == null || mc.world.provider.getDimension() != packetDimension) return;
        if(dimension != packetDimension) {
            clear();
            dimension = packetDimension;
        }
        byte[] old = heights == null ? SECTIONS.remove(key) : SECTIONS.put(key, heights);
        if(old == null && heights == null) return;
        lastKey = Long.MIN_VALUE;

        // Work out what changed, so only what depends on it is redrawn
        int ox = Water.keyX(key) * 16, oy = Water.keyY(key) * 16, oz = Water.keyZ(key) * 16;
        int borders = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for(int i = 0; i < 4096; i++) {
            int a = old == null ? 0 : old[i] & 255, b = heights == null ? 0 : heights[i] & 255;
            if(a == b) continue;
            int x = i & 15, y = i >> 8, z = (i >> 4) & 15;
            if(y == 0) borders |= 1 << Water.DOWN;
            if(y == 15) borders |= 1 << Water.UP;
            if(z == 0) borders |= 1 << Water.NORTH;
            if(z == 15) borders |= 1 << Water.SOUTH;
            if(x == 0) borders |= 1 << Water.WEST;
            if(x == 15) borders |= 1 << Water.EAST;
            // Settled water culls its faces against simulated water, redraw it when water appears or vanishes
            if(ForgeConfigHandler.client.renderWater && (a == 0) != (b == 0) && besideSettledWater(mc, pos, ox + x, oy + y, oz + z)) {
                SettledRedraws.add(ox + x, oy + y, oz + z);
            }
        }
        WaterRenderer.markDirty(key, borders);
    }

    private static boolean besideSettledWater(Minecraft mc, BlockPos.MutableBlockPos pos, int x, int y, int z) {
        for(int dir = 0; dir < 6; dir++) {
            pos.setPos(x + Water.DX[dir], y + Water.DY[dir], z + Water.DZ[dir]);
            if(mc.world.getBlockState(pos).getBlock() == ModRegistry.SETTLED_WATER) return true;
        }
        return false;
    }

    public static void unloadChunk(int cx, int cz) {
        for(int sy = 0; sy < 16; sy++) {
            long key = Water.sectionKey(cx, sy, cz);
            if(SECTIONS.remove(key) != null) WaterRenderer.markDirty(key, ALL_BORDERS);
        }
        lastKey = Long.MIN_VALUE;
    }

    public static void clear() {
        SECTIONS.clear();
        lastKey = Long.MIN_VALUE;
        lastSection = null;
        dimension = Integer.MIN_VALUE;
        WaterRenderer.clear();
    }

    static byte[] section(long key) {
        return SECTIONS.get(key);
    }

    static Iterable<Long> keys() {
        return SECTIONS.keySet();
    }

    static boolean hasSection(long key) {
        return SECTIONS.containsKey(key);
    }

    /** Height 0-255 of the water at a block position, client thread only */
    public static int height(int x, int y, int z) {
        if(y < 0 || y > 255) return 0;
        long key = Water.sectionKey(x >> 4, y >> 4, z >> 4);
        if(key != lastKey) {
            lastKey = key;
            lastSection = SECTIONS.get(key);
        }
        return lastSection == null ? 0 : lastSection[Water.cellIndex(x & 15, y & 15, z & 15)] & 255;
    }

    /** Height 0-255 of the water at a block position, safe from any thread */
    public static int heightAnyThread(int x, int y, int z) {
        if(y < 0 || y > 255) return 0;
        byte[] section = SECTIONS.get(Water.sectionKey(x >> 4, y >> 4, z >> 4));
        return section == null ? 0 : section[Water.cellIndex(x & 15, y & 15, z & 15)] & 255;
    }

}
