package fluidic.client;

import fluidic.block.FluidicWater;
import fluidic.sim.Water;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;

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
    /** The block each cell of WATER_CHANGES had before, which the chunk on screen still shows */
    private static final Long2ObjectOpenHashMap<IBlockState> OLD_STATES = new Long2ObjectOpenHashMap<>();
    private static final Long2ObjectOpenHashMap<LongArrayList> WATER_CHANGE_CELLS = new Long2ObjectOpenHashMap<>();
    /** When a section first waited for a redraw of its water block changes, nanoTime */
    private static final Long2LongOpenHashMap WATER_CHANGE_SINCE = new Long2LongOpenHashMap();
    /** Longest a section waits, without the render mixins nothing ever says it was redrawn */
    private static final long WATER_CHANGE_TIMEOUT = 3_000_000_000L;

    static {
        WATER_CHANGES.defaultReturnValue(-1L);
    }

    private ChunkRedraws() {}

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
     * match, and forgets the changes
     */
    static void caughtUp(LongConsumer sections) {
        if(WATER_CHANGE_CELLS.isEmpty()) return;
        long now = System.nanoTime();
        for(ObjectIterator<Long2ObjectMap.Entry<LongArrayList>> it = WATER_CHANGE_CELLS.long2ObjectEntrySet().fastIterator(); it.hasNext(); ) {
            Long2ObjectMap.Entry<LongArrayList> entry = it.next();
            Long drawn = DRAWN.get(entry.getLongKey());
            LongArrayList cells = entry.getValue();
            boolean all = drawn != null;
            for(int n = 0; n < cells.size() && all; n++) all = drawn >= WATER_CHANGES.get(cells.getLong(n)) >> 1;
            if(!all && now - WATER_CHANGE_SINCE.get(entry.getLongKey()) < WATER_CHANGE_TIMEOUT) continue;
            WATER_CHANGE_SINCE.remove(entry.getLongKey());
            for(int n = 0; n < cells.size(); n++) {
                WATER_CHANGES.remove(cells.getLong(n));
                OLD_STATES.remove(cells.getLong(n));
            }
            long key = entry.getLongKey();
            it.remove();
            sections.accept(key);
        }
    }

    /** A redraw that started with the given sequence number is now on screen */
    public static void drawn(BlockPos position, long started) {
        DRAWN.merge(Water.sectionKey(position.getX() >> 4, position.getY() >> 4, position.getZ() >> 4), started, Math::max);
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
            LongArrayList cells = WATER_CHANGE_CELLS.remove(key);
            if(cells != null) {
                for(int n = 0; n < cells.size(); n++) {
                    WATER_CHANGES.remove(cells.getLong(n));
                    OLD_STATES.remove(cells.getLong(n));
                }
            }
            WATER_CHANGE_SINCE.remove(key);
        }
    }

    static void clear() {
        CHANGED.clear();
        DRAWN.clear();
        WATER_CHANGES.clear();
        OLD_STATES.clear();
        WATER_CHANGE_CELLS.clear();
        WATER_CHANGE_SINCE.clear();
    }
}
