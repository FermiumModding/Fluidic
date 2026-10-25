package fluidic.client;

import fluidic.block.FluidicWater;
import fluidic.handlers.ForgeConfigHandler;
import fluidic.sim.Water;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;

/**
 * Follows vanilla redrawing chunk sections, so simulated water that settled into blocks keeps being drawn until the
 * section is drawn with the new blocks. Redraws run on worker threads and can take many ticks in busy weather.
 */
public final class ChunkRedraws {

    private static final AtomicLong SEQUENCE = new AtomicLong();
    /** Section key to the sequence number of the newest block change in it */
    private static final ConcurrentHashMap<Long, Long> CHANGED = new ConcurrentHashMap<>();
    /** Section key to the sequence number of the redraw now on screen, taken when the redraw started */
    private static final ConcurrentHashMap<Long, Long> DRAWN = new ConcurrentHashMap<>();
    /**
     * Cells whose water block came or went, until their section is drawn again, client thread only: block position to
     * the sequence number of the change << 1 | whether it was a water block before
     */
    private static final Long2LongOpenHashMap WATER_CHANGES = new Long2LongOpenHashMap();
    /** The cells of WATER_CHANGES by section */
    /** Sections of water blocks to be redrawn for simulated water that changed, the sequence number of the change */
    private static final Long2LongOpenHashMap STALE = new Long2LongOpenHashMap();
    private static final Long2LongOpenHashMap STALE_SINCE = new Long2LongOpenHashMap();
    /** The block each cell of WATER_CHANGES had before, which the chunk on screen still shows */
    private static final Long2ObjectOpenHashMap<IBlockState> OLD_STATES = new Long2ObjectOpenHashMap<>();
    private static final Long2ObjectOpenHashMap<LongArrayList> WATER_CHANGE_CELLS = new Long2ObjectOpenHashMap<>();
    /** When a section first waited for a redraw of its water block changes, nanoTime */
    private static final Long2LongOpenHashMap WATER_CHANGE_SINCE = new Long2LongOpenHashMap();
    /** Longest a section waits, without the render mixins nothing ever says it was redrawn */
    private static final long WATER_CHANGE_TIMEOUT = 3_000_000_000L;

    /**
     * How far from simulated water the chunks leave water blocks to WaterRenderer, see Snapshot. The shape of a water
     * block depends on the water beside it and up to two blocks above it, see FluidicWater.surfaceCorner, so water
     * blocks the chunks draw further away never change with simulated water.
     */
    private static final int TAKE_REACH = 2;
    /** What the section on screen was drawn with, see beginRedraw */
    private static final ConcurrentHashMap<Long, Snapshot> DRAWN_SNAPSHOTS = new ConcurrentHashMap<>();
    /** What the redraw running on a thread is drawn with */
    private static final ThreadLocal<Snapshot> REDRAWING = new ThreadLocal<>();
    /** Client thread: the snapshot heights are read from instead of the current ones, see view */
    private static Snapshot viewing;
    /** A redraw running on the client thread, see clientView */
    private static Snapshot clientRedrawing;

    static {
        WATER_CHANGES.defaultReturnValue(-1L);
        STALE.defaultReturnValue(-1L);
    }

    /** Client thread: sections marked for redrawing now are marked for water alone, see RenderGlobalMixin */
    private static boolean waterRedraw;

    private ChunkRedraws() {}

    /** A chunk section, whether it waits for a redraw only because water in or beside it changed, see RenderGlobalMixin */
    public interface WaterRedraw {
        boolean fluidic_waterOnly();
    }

    /** Sets whether sections marked for redrawing from now on are marked because water changed */
    public static void setWaterRedraw(boolean water) {
        waterRedraw = water;
    }

    public static boolean isWaterRedraw() {
        return waterRedraw;
    }

    /** Whether a block change is only water coming, going or changing level, like rain, settling or evaporation */
    public static boolean waterOnlyChange(IBlockState old, IBlockState state) {
        boolean oldOpen = FluidicWater.isWater(old) || old.getMaterial() == Material.AIR;
        boolean open = FluidicWater.isWater(state) || state.getMaterial() == Material.AIR;
        return oldOpen && open && (FluidicWater.isWater(old) || FluidicWater.isWater(state));
    }

    public static long next() {
        return SEQUENCE.incrementAndGet();
    }

    static void blockChanged(BlockPos pos) {
        CHANGED.put(Water.sectionKey(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4), next());
    }

    /**
     * A water block came, went or changed level. Until its section is redrawn, the chunk on screen still shows it as it
     * was, simulated water there and beside it is drawn to match, see {@link #shownState}.
     */
    static void waterChanged(BlockPos pos, IBlockState oldState) {
        boolean wasWater = FluidicWater.isWater(oldState);
        long key = Water.sectionKey(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
        long seq = next();
        CHANGED.put(key, seq);
        long packed = pos.toLong();
        long old = WATER_CHANGES.get(packed);
        // Changed back and forth before a redraw, the screen still shows how it was before the first change
        boolean before = old >= 0L ? (old & 1L) != 0 : wasWater;
        if(old < 0L) {
            OLD_STATES.put(packed, oldState);
            WATER_CHANGE_CELLS.computeIfAbsent(key, k -> new LongArrayList()).add(packed);
            if(!WATER_CHANGE_SINCE.containsKey(key)) WATER_CHANGE_SINCE.put(key, System.nanoTime());
        }
        WATER_CHANGES.put(packed, seq << 1 | (before ? 1L : 0L));
    }

    /** The block the chunk on screen shows at a position, the given one it has now or a water block as it was before */
    static IBlockState shownState(int x, int y, int z, IBlockState now) {
        if(WATER_CHANGES.isEmpty()) return now;
        long packed = ((long)x & 0x3FFFFFFL) << 38 | ((long)y & 0xFFFL) << 26 | ((long)z & 0x3FFFFFFL);
        long change = WATER_CHANGES.get(packed);
        if(change < 0L) return now;
        Long drawn = DRAWN.get(Water.sectionKey(x >> 4, y >> 4, z >> 4));
        if(drawn != null && drawn >= change >> 1) return now;
        IBlockState old = OLD_STATES.get(packed);
        return old != null ? old : now;
    }

    /**
     * Passes the sections now drawn with all their water block changes, their simulated water is drawn again to
     * match, and forgets the changes. Sections drawn again against simulated water that changed, see {@link #shows}, go
     * to redrawn.
     */
    static void caughtUp(LongConsumer sections, LongConsumer redrawn) {
        // A redraw on this thread that failed would leave its heights read here from then on
        REDRAWING.remove();
        clientRedrawing = null;
        long now = System.nanoTime();
        for(ObjectIterator<Long2LongMap.Entry> it = STALE.long2LongEntrySet().fastIterator(); it.hasNext(); ) {
            Long2LongMap.Entry entry = it.next();
            long key = entry.getLongKey();
            if(!shows(key) && now - STALE_SINCE.get(key) < WATER_CHANGE_TIMEOUT) continue;
            it.remove();
            STALE_SINCE.remove(key);
            redrawn.accept(key);
        }
        if(WATER_CHANGE_CELLS.isEmpty()) return;
        for(ObjectIterator<Long2ObjectMap.Entry<LongArrayList>> it = WATER_CHANGE_CELLS.long2ObjectEntrySet().fastIterator(); it.hasNext(); ) {
            Long2ObjectMap.Entry<LongArrayList> entry = it.next();
            Long drawn = DRAWN.get(entry.getLongKey());
            LongArrayList cells = entry.getValue();
            boolean all = drawn != null;
            for(int n = 0; n < cells.size() && all; n++) all = drawn >= WATER_CHANGES.get(cells.getLong(n)) >> 1;
            if(!all && now - WATER_CHANGE_SINCE.get(entry.getLongKey()) < WATER_CHANGE_TIMEOUT) continue;
            WATER_CHANGE_SINCE.remove(entry.getLongKey());
            // Simulated water beside the changed blocks shares its corners with them, across section borders too
            LongOpenHashSet around = new LongOpenHashSet();
            for(int n = 0; n < cells.size(); n++) {
                WATER_CHANGES.remove(cells.getLong(n));
                IBlockState old = OLD_STATES.remove(cells.getLong(n));
                BlockPos cell = BlockPos.fromLong(cells.getLong(n));
                // The water block drawn there until now, simulated water taking its place moves on from it
                if(FluidicWater.isWater(old)) WaterMotion.uncovered(cell, FluidicWater.level(old) * 255 / Water.LEVELS);
                for(int dx = -1; dx <= 1; dx++) {
                    for(int dy = -1; dy <= 1; dy++) {
                        for(int dz = -1; dz <= 1; dz++) {
                            int y = cell.getY() + dy;
                            if(y >= 0 && y <= 255) around.add(Water.sectionKey((cell.getX() + dx) >> 4, y >> 4, (cell.getZ() + dz) >> 4));
                        }
                    }
                }
            }
            it.remove();
            for(long section : around) sections.accept(section);
        }
    }

    /**
     * Water blocks in a section are drawn against simulated water, which changed, so the section is redrawn. Until it is
     * on screen, simulated water resting on those blocks is drawn against them as they were, see WaterRenderer.
     */
    static void simulatedChanged(long key) {
        STALE.put(key, next());
        if(!STALE_SINCE.containsKey(key)) STALE_SINCE.put(key, System.nanoTime());
    }

    /** Whether the section on screen was drawn after the simulated water it is drawn against last changed */
    static boolean shows(long key) {
        if(STALE.isEmpty()) return true;
        long stale = STALE.get(key);
        if(stale < 0L) return true;
        Long drawn = DRAWN.get(key);
        return drawn != null && drawn >= stale;
    }

    /** A redraw that started with the given sequence number is now on screen */
    public static void drawn(BlockPos position, long started) {
        DRAWN.merge(Water.sectionKey(position.getX() >> 4, position.getY() >> 4, position.getZ() >> 4), started, Math::max);
    }

    /**
     * A redraw that started with the given sequence number and was drawn as in this snapshot, see {@link #beginRedraw},
     * is now on screen. Simulated water around it, and the water blocks it left to WaterRenderer, are drawn to match it
     * in this same frame.
     */
    public static void drawn(BlockPos position, long started, @Nullable Snapshot snapshot) {
        long key = Water.sectionKey(position.getX() >> 4, position.getY() >> 4, position.getZ() >> 4);
        Long before = DRAWN.get(key);
        if(before != null && before > started) return;
        DRAWN.put(key, started);
        if(snapshot == null) return;
        Snapshot old = DRAWN_SNAPSHOTS.put(key, snapshot);
        // Water blocks it still draws are further from simulated water than its shape depends on, see TAKE_REACH, the
        // simulated water around only switches with it where it left other water blocks to WaterRenderer
        if(old != null && Arrays.equals(old.taken, snapshot.taken)) return;
        // The sections beside it only meet the water blocks that switched where they are near their border
        int borders = 1 << 13;
        boolean[] takenBefore = old == null ? null : old.taken, takenAfter = snapshot.taken;
        for(int i = 0; i < 4096; i++) {
            boolean was = takenBefore != null && takenBefore[i], is = takenAfter != null && takenAfter[i];
            if(was != is) borders |= WaterMotion.nearBorders(i & 15, i >> 8, (i >> 4) & 15);
        }
        WaterRenderer.chunkRedrawn(key, borders);
    }

    /**
     * What a redraw of a section was drawn with. Its water blocks are shaped by the simulated water around them, which
     * changes while the redraw runs and again before it is on screen, so the redraw reads the heights of the moment it
     * began throughout. Water blocks near simulated water are left to WaterRenderer, which draws them with the
     * simulated water: two renderers meeting there would not agree while the water moves, see TAKE_REACH.
     */
    public static final class Snapshot {
        final long key;
        /** The simulated heights of the 27 sections around it, by offset */
        final byte[][] heights;
        /** Cells whose water block WaterRenderer draws, null for none */
        @Nullable
        final boolean[] taken;

        Snapshot(long key, byte[][] heights, @Nullable boolean[] taken) {
            this.key = key;
            this.heights = heights;
            this.taken = taken;
        }

        /** Height 0-255, -1 further than the snapshot reaches */
        int height(int x, int y, int z) {
            if(y < 0 || y > 255) return 0;
            int dx = (x >> 4) - Water.keyX(key), dy = (y >> 4) - Water.keyY(key), dz = (z >> 4) - Water.keyZ(key);
            if(dx < -1 || dx > 1 || dy < -1 || dy > 1 || dz < -1 || dz > 1) return -1;
            byte[] section = heights[(dx + 1) * 9 + (dy + 1) * 3 + dz + 1];
            return section == null ? 0 : section[Water.cellIndex(x & 15, y & 15, z & 15)] & 255;
        }
    }

    /** A section starts being redrawn on this thread, see {@link Snapshot}. Returns what it is drawn with. */
    public static Snapshot beginRedraw(BlockPos position) {
        int sx = position.getX() >> 4, sy = position.getY() >> 4, sz = position.getZ() >> 4;
        byte[][] heights = new byte[27][];
        boolean any = false;
        for(int i = 0; i < 27; i++) {
            int y = sy + i / 3 % 3 - 1;
            if(y >= 0 && y <= 15) heights[i] = ClientWaterCache.section(Water.sectionKey(sx + i / 9 - 1, y, sz + i % 3 - 1));
            any |= heights[i] != null;
        }
        long key = Water.sectionKey(sx, sy, sz);
        boolean[] taken = null;
        // Drawn by the chunks themselves, WaterRenderer draws nothing
        if(any && !ChunkWater.isActive() && ForgeConfigHandler.client.renderWater) {
            Snapshot reading = new Snapshot(key, heights, null);
            int ox = sx * 16, oy = sy * 16, oz = sz * 16;
            for(int y = Math.max(0, oy - TAKE_REACH); y <= Math.min(255, oy + 15 + TAKE_REACH); y++) {
                for(int z = oz - TAKE_REACH; z <= oz + 15 + TAKE_REACH; z++) {
                    for(int x = ox - TAKE_REACH; x <= ox + 15 + TAKE_REACH; x++) {
                        if(reading.height(x, y, z) <= 0) continue;
                        if(taken == null) taken = new boolean[4096];
                        for(int ty = Math.max(oy, y - TAKE_REACH); ty <= Math.min(oy + 15, y + TAKE_REACH); ty++) {
                            for(int tz = Math.max(oz, z - TAKE_REACH); tz <= Math.min(oz + 15, z + TAKE_REACH); tz++) {
                                for(int tx = Math.max(ox, x - TAKE_REACH); tx <= Math.min(ox + 15, x + TAKE_REACH); tx++) {
                                    taken[Water.cellIndex(tx & 15, ty & 15, tz & 15)] = true;
                                }
                            }
                        }
                    }
                }
            }
        }
        Snapshot snapshot = new Snapshot(key, heights, taken);
        REDRAWING.set(snapshot);
        if(WaterMotion.onClientThread()) clientRedrawing = snapshot;
        return snapshot;
    }

    public static void endRedraw() {
        REDRAWING.remove();
        if(WaterMotion.onClientThread()) clientRedrawing = null;
    }

    /** Whether the redraw on this thread leaves the water block at a position in its section to WaterRenderer */
    public static boolean takenInRedraw(BlockPos pos) {
        Snapshot snapshot = REDRAWING.get();
        return snapshot != null && snapshot.taken != null && snapshot.taken[Water.cellIndex(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15)];
    }

    /** Whether WaterRenderer draws the water block at a position, as the section on screen left it, client thread */
    static boolean taken(int x, int y, int z) {
        if(y < 0 || y > 255) return false;
        Snapshot snapshot = DRAWN_SNAPSHOTS.get(Water.sectionKey(x >> 4, y >> 4, z >> 4));
        return snapshot != null && snapshot.taken != null && snapshot.taken[Water.cellIndex(x & 15, y & 15, z & 15)];
    }

    /** Whether the section on screen leaves any water blocks to WaterRenderer */
    static boolean anyTaken(long key) {
        Snapshot snapshot = DRAWN_SNAPSHOTS.get(key);
        return snapshot != null && snapshot.taken != null;
    }

    /** The cells of the section on screen whose water blocks WaterRenderer draws, null for none, by cell index */
    @Nullable
    static boolean[] takenCells(long key) {
        Snapshot snapshot = DRAWN_SNAPSHOTS.get(key);
        return snapshot == null ? null : snapshot.taken;
    }

    /**
     * Until {@link #endView}, heights on the client thread are read as the section with the block at a position was
     * last drawn with, when it is known. Returns the view before, for {@link #endView}.
     */
    static Snapshot view(int x, int y, int z) {
        Snapshot before = viewing;
        // Not drawn since it was known, as it is now
        viewing = DRAWN_SNAPSHOTS.get(Water.sectionKey(x >> 4, y >> 4, z >> 4));
        return before;
    }

    static void endView(@Nullable Snapshot before) {
        viewing = before;
    }

    /** Whether a view is active on the client thread, see {@link #view} */
    static boolean viewingDrawn() {
        return viewing != null;
    }

    /**
     * The snapshot heights on the client thread are read from: a chunk redraw running on it, vanilla redraws some on the
     * client thread, or a view, see view. Null for the heights now.
     */
    @Nullable
    static Snapshot clientView() {
        Snapshot snapshot = clientRedrawing;
        return snapshot != null ? snapshot : viewing;
    }

    /** Height 0-255 at a position as the redraw running on this thread sees it, -1 if none runs or it does not reach */
    static int redrawHeight(int x, int y, int z) {
        Snapshot snapshot = REDRAWING.get();
        return snapshot == null ? -1 : snapshot.height(x, y, z);
    }

    /** Whether the section on screen includes every block change made in it */
    static boolean upToDate(long key) {
        Long changed = CHANGED.get(key);
        if(changed == null) return true;
        Long drawn = DRAWN.get(key);
        if(drawn == null || drawn < changed) return false;
        CHANGED.remove(key, changed);
        return true;
    }

    /** Forgets the sections of a chunk the client dropped */
    static void unloadChunk(int cx, int cz) {
        for(int sy = 0; sy < 16; sy++) {
            long key = Water.sectionKey(cx, sy, cz);
            CHANGED.remove(key);
            DRAWN.remove(key);
            DRAWN_SNAPSHOTS.remove(key);
            LongArrayList cells = WATER_CHANGE_CELLS.remove(key);
            if(cells != null) {
                for(int n = 0; n < cells.size(); n++) {
                    WATER_CHANGES.remove(cells.getLong(n));
                    OLD_STATES.remove(cells.getLong(n));
                }
            }
            WATER_CHANGE_SINCE.remove(key);
            STALE.remove(key);
            STALE_SINCE.remove(key);
        }
    }

    static void clear() {
        CHANGED.clear();
        DRAWN.clear();
        DRAWN_SNAPSHOTS.clear();
        WATER_CHANGES.clear();
        OLD_STATES.clear();
        STALE.clear();
        STALE_SINCE.clear();
        WATER_CHANGE_CELLS.clear();
        WATER_CHANGE_SINCE.clear();
    }
}
