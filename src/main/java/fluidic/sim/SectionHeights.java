package fluidic.sim;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Water heights per section, one byte per block (255 = full), written by one thread and read from any. Lookups go
 * through a per thread cache of the last section, entity physics and chunk redraws ask for the same section over and
 * over, and a map lookup would box its key every time. The arrays are never changed once put.
 */
public final class SectionHeights {

    private final ConcurrentHashMap<Long, byte[]> sections = new ConcurrentHashMap<>();
    /** Changed after every write, a cached section of an older version is looked up again */
    private volatile int version;
    private final ThreadLocal<Lookup> lookup = ThreadLocal.withInitial(Lookup::new);
    /** The writing thread's last section looked up, see heightOnWriter */
    private long writerKey = Long.MIN_VALUE;
    private int writerVersion = -1;
    private byte[] writerSection;

    public byte[] get(long key) {
        Lookup last = lookup.get();
        int current = version;
        if(last.key == key && last.version == current) return last.section;
        byte[] section = sections.get(key);
        last.key = key;
        last.version = current;
        last.section = section;
        return section;
    }

    /** Height 0-255 at a block position */
    public int height(int x, int y, int z) {
        if(y < 0 || y > 255) return 0;
        byte[] section = get(Water.sectionKey(x >> 4, y >> 4, z >> 4));
        return section == null ? 0 : section[Water.cellIndex(x & 15, y & 15, z & 15)] & 255;
    }

    /**
     * Height 0-255 at a block position, only from the writing thread. Its own last section is kept in plain fields, a mesh
     * build looks up heights by the thousand and the per thread lookup costs more than the rest of it.
     */
    public int heightOnWriter(int x, int y, int z) {
        if(y < 0 || y > 255) return 0;
        long key = Water.sectionKey(x >> 4, y >> 4, z >> 4);
        if(key != writerKey || writerVersion != version) {
            writerKey = key;
            writerVersion = version;
            writerSection = sections.get(key);
        }
        return writerSection == null ? 0 : writerSection[Water.cellIndex(x & 15, y & 15, z & 15)] & 255;
    }

    /** Only from the writing thread */
    public byte[] put(long key, byte[] section) {
        byte[] old = sections.put(key, section);
        version++;
        return old;
    }

    /** Only from the writing thread */
    public byte[] remove(long key) {
        byte[] old = sections.remove(key);
        if(old != null) version++;
        return old;
    }

    /** Only from the writing thread */
    public void clear() {
        sections.clear();
        version++;
    }

    public boolean containsKey(long key) {
        return get(key) != null;
    }

    public Set<Long> keySet() {
        return sections.keySet();
    }

    private static final class Lookup {
        long key = Long.MIN_VALUE;
        int version = -1;
        byte[] section;
    }
}
