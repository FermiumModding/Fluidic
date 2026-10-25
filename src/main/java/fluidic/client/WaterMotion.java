package fluidic.client;

import fluidic.handlers.ForgeConfigHandler;
import fluidic.sim.Water;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;

import javax.annotation.Nullable;

/**
 * Smooths simulated water between the heights the server sends. Each update moves the water a step at once, at most 20
 * times a second and not evenly spaced, which looks like a low frame rate. Instead each section eases its heights from
 * where the water is shown to the heights sent, over about the time between its updates, and WaterRenderer builds it from
 * the eased heights every frame, the same way it draws water at rest. Every frame is a whole surface, faces come and go
 * where the eased water crosses the heights they depend on. Client thread only, apart from {@link #easing}.
 */
final class WaterMotion {

    /** Shortest and longest time the water takes to reach the heights sent, in nanoseconds */
    private static final long MIN_NANOS = 50_000_000L;
    private static final long MAX_NANOS = 500_000_000L;
    private static final byte[] NO_WATER = new byte[4096];
    /** Least change of a cell that is eased, 1/32 of a block. Less is shown at once, see show. */
    private static final int MIN_EASED = 8;

    private static final Long2ObjectOpenHashMap<Motion> SECTIONS = new Long2ObjectOpenHashMap<>();
    /** Sections easing toward new heights, WaterRenderer rebuilds them and the sections around them every frame */
    private static final LongOpenHashSet MOVING = new LongOpenHashSet();
    /** Set while WaterRenderer builds a mesh, height lookups on the client thread then read the eased heights */
    static boolean easing;
    /** Time of the frame being drawn */
    private static long now = System.nanoTime();
    private static long lastKey = Long.MIN_VALUE;
    private static Motion last;

    private WaterMotion() {}

    private static final class Motion {
        /** Heights eased from and to, from is null at rest */
        byte[] from, to;
        /** The sections around it the cells easing reach, see nearBorders */
        int borders;
        long start, duration;
        /** When the last update arrived, and the time between updates, smoothed */
        long arrived;
        long interval = MIN_NANOS;
    }

    /** A frame starts, the water moving is shown as it is at this time */
    static void frame() {
        now = System.nanoTime();
    }

    /** New heights of a section arrived, the time since the last ones is how long the water takes to reach them */
    static void arrived(long key) {
        long time = System.nanoTime();
        Motion motion = SECTIONS.get(key);
        if(motion == null) {
            motion = new Motion();
            SECTIONS.put(key, motion);
            forget();
        }
        else {
            long gap = Math.max(MIN_NANOS, Math.min(MAX_NANOS, time - motion.arrived));
            // Packets arriving in bunches would cut it short, smoothed it stays near the rate they are sent at
            motion.interval = (motion.interval * 3 + gap) / 4;
        }
        motion.arrived = time;
    }

    /**
     * A section shows new heights, null when it has none. It eases there from where it is shown now. A section shown for
     * the first time, far away, or with smoothing off shows them at once.
     */
    static void show(long key, @Nullable byte[] heights) {
        Motion motion = SECTIONS.get(key);
        if(motion == null) {
            if(heights == null) return;
            motion = new Motion();
            motion.arrived = now;
            SECTIONS.put(key, motion);
            forget();
        }
        byte[] to = heights != null ? heights : NO_WATER;
        if(motion.to == null || !ForgeConfigHandler.client.smoothWater || ChunkWater.isActive() || far(key)) {
            motion.to = to;
            motion.from = null;
            MOVING.remove(key);
            return;
        }
        byte[] from = new byte[4096];
        for(int i = 0; i < 4096; i++) from[i] = (byte)shown(motion, i);
        // Water falling into a cell from the cell above, like a pool into a hole under it, fills it at once. It is under
        // the surface, while the water above goes down. Eased both, the water above would float over a half empty cell.
        for(int i = 0; i < 4096; i++) {
            if((to[i] & 255) > (from[i] & 255) && drainsAbove(key, i, from, to)) from[i] = to[i];
        }
        // Too little moves to see, like rain landing on a pool, it is shown at once. Eased, the section and the ones around it
        // would be rebuilt every frame for nothing.
        int most = 0, borders = 0;
        for(int i = 0; i < 4096; i++) {
            int change = Math.abs((to[i] & 255) - (from[i] & 255));
            if(change == 0) continue;
            most = Math.max(most, change);
            borders |= nearBorders(i & 15, i >> 8, (i >> 4) & 15);
        }
        motion.to = to;
        if(most < MIN_EASED) {
            motion.from = null;
            MOVING.remove(key);
            return;
        }
        motion.from = from;
        motion.borders = borders;
        motion.start = now;
        motion.duration = motion.interval;
        MOVING.add(key);
    }

    /** Whether the cell above a cell of a section loses water, as shown and sent */
    private static boolean drainsAbove(long key, int i, byte[] from, byte[] to) {
        if((i >> 8) < 15) return (to[i + 256] & 255) < (from[i + 256] & 255);
        int sy = Water.keyY(key);
        if(sy >= 15) return false;
        Motion motion = SECTIONS.get(Water.sectionKey(Water.keyX(key), sy + 1, Water.keyZ(key)));
        int j = i & 255;
        return motion != null && motion.from != null && (motion.to[j] & 255) < (motion.from[j] & 255);
    }

    /**
     * The sections around a section whose meshes a change of a cell in it reaches, as bits by offset, see
     * WaterRenderer.markDirty. The corners and faces along a border look one cell into the section beside it, and the
     * cell beside that tells whether it is a trace or a thin layer.
     */
    private static int nearBorders(int x, int y, int z) {
        int bits = 0;
        for(int dx = x <= 1 ? -1 : 0; dx <= (x >= 14 ? 1 : 0); dx++) {
            for(int dy = y <= 1 ? -1 : 0; dy <= (y >= 14 ? 1 : 0); dy++) {
                for(int dz = z <= 1 ? -1 : 0; dz <= (z >= 14 ? 1 : 0); dz++) bits |= 1 << ((dx + 1) * 9 + (dy + 1) * 3 + dz + 1);
            }
        }
        return bits;
    }

    /** Whether a section is too far from the camera to ease, further than its water is drawn in full detail */
    private static boolean far(long key) {
        Entity view = Minecraft.getMinecraft().getRenderViewEntity();
        if(view == null) return true;
        int cx = MathHelper.floor(view.posX) >> 4, cz = MathHelper.floor(view.posZ) >> 4;
        return Math.max(Math.abs(Water.keyX(key) - cx), Math.abs(Water.keyZ(key) - cz)) > ForgeConfigHandler.client.fullDetailDistance;
    }

    /**
     * The chunk stopped drawing a water block of this height, 0-255. Simulated water there was not drawn while it was, or
     * had none yet, and eases on from the block.
     */
    static void uncovered(BlockPos pos, int height) {
        if(!ForgeConfigHandler.client.smoothWater) return;
        long key = Water.sectionKey(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
        Motion motion = SECTIONS.get(key);
        if(motion == null || motion.to == null || far(key)) return;
        byte[] from = new byte[4096];
        for(int i = 0; i < 4096; i++) from[i] = (byte)shown(motion, i);
        from[Water.cellIndex(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15)] = (byte)Math.max(1, height);
        motion.borders |= nearBorders(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
        motion.from = from;
        motion.start = now;
        motion.duration = motion.interval;
        MOVING.add(key);
    }

    static void unload(long key) {
        SECTIONS.remove(key);
        MOVING.remove(key);
        forget();
    }

    static void clear() {
        SECTIONS.clear();
        MOVING.clear();
        forget();
    }

    private static void forget() {
        lastKey = Long.MIN_VALUE;
        last = null;
    }

    private static Motion motion(long key) {
        if(key != lastKey) {
            lastKey = key;
            last = SECTIONS.get(key);
        }
        return last;
    }

    private static float progress(Motion motion) {
        return motion.from == null || motion.duration <= 0 ? 1.0F : Math.min(1.0F, (float)(now - motion.start) / motion.duration);
    }

    /** Height 0-255 of a cell of a section as eased this frame */
    private static int shown(Motion motion, int i) {
        int to = motion.to[i] & 255;
        float t = progress(motion);
        if(t >= 1.0F) return to;
        int from = motion.from[i] & 255;
        return Math.round(from + (to - from) * t);
    }

    /** Height 0-255 of the water at a block position as eased this frame, as sent where nothing eases */
    static int height(int x, int y, int z) {
        if(y < 0 || y > 255) return 0;
        Motion motion = motion(Water.sectionKey(x >> 4, y >> 4, z >> 4));
        if(motion == null || motion.to == null) return ClientWaterCache.sentHeight(x, y, z);
        return shown(motion, Water.cellIndex(x & 15, y & 15, z & 15));
    }

    /** The heights of a section as eased this frame, null when it has no water and none easing out */
    @Nullable
    static byte[] section(long key) {
        Motion motion = SECTIONS.get(key);
        if(motion == null || motion.to == null) return ClientWaterCache.section(key);
        if(progress(motion) >= 1.0F) return motion.to == NO_WATER ? null : motion.to;
        byte[] heights = new byte[4096];
        for(int i = 0; i < 4096; i++) heights[i] = (byte)shown(motion, i);
        return heights;
    }

    /**
     * The sections to build this frame: those easing and the ones around them the easing cells reach, which share their
     * corners. Those that reached their heights are built once more there and are at rest after.
     */
    static LongOpenHashSet moving() {
        LongOpenHashSet sections = new LongOpenHashSet();
        for(LongIterator it = MOVING.iterator(); it.hasNext(); ) {
            long key = it.nextLong();
            Motion motion = SECTIONS.get(key);
            int sx = Water.keyX(key), sy = Water.keyY(key), sz = Water.keyZ(key);
            int borders = motion == null ? 0 : motion.borders;
            sections.add(key);
            for(int bit = 0; bit < 27; bit++) {
                if((borders & (1 << bit)) == 0 || bit == 13) continue;
                int ny = sy + bit / 3 % 3 - 1;
                if(ny >= 0 && ny <= 15) sections.add(Water.sectionKey(sx + bit / 9 - 1, ny, sz + bit % 3 - 1));
            }
            if(motion == null || progress(motion) >= 1.0F) {
                it.remove();
                if(motion != null) motion.from = null;
            }
        }
        return sections;
    }

    /** Whether lookups now are on the client thread, chunk render threads always read the heights sent */
    static boolean onClientThread() {
        return Minecraft.getMinecraft().isCallingFromMinecraftThread();
    }
}
