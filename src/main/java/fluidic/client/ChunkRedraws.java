package fluidic.client;

import fluidic.sim.Water;
import net.minecraft.util.math.BlockPos;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

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

    private ChunkRedraws() {}

    public static long next() {
        return SEQUENCE.incrementAndGet();
    }

    static void blockChanged(BlockPos pos) {
        CHANGED.put(Water.sectionKey(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4), next());
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
        }
    }

    static void clear() {
        CHANGED.clear();
        DRAWN.clear();
    }
}
