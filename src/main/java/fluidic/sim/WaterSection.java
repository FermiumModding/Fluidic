package fluidic.sim;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A 16x16x16 block region of simulated water, matching a vanilla chunk section.
 * Owned by the simulation thread of its {@link WaterSimulation}; never touched from the server thread.
 */
final class WaterSection {

    // Terrain codes, a snapshot of the world blocks taken on the server thread
    static final byte SOLID = 0;
    static final byte OPEN = 1;
    /** Vanilla water, simulated water flowing into it merges with it and is removed */
    static final byte SINK = 2;
    /** A settled water block the server thread is turning back into simulated water */
    static final byte WAKING = 3;
    /** Leaves, water drips into and through them from above but never spreads into or settles in them */
    static final byte POROUS = 4;
    /** Flowers, torches and the like. Water flows through them but only settles there, washing them away, above half a block. */
    static final byte WASHABLE = 5;
    /** Solid blocks that slow water running over them, like grass and dirt */
    static final byte SOLID_ROUGH = 6;
    /** Solid blocks water runs off easily, like stone and glass */
    static final byte SOLID_SMOOTH = 7;
    /** Solid blocks that change when water touches them, like concrete powder */
    static final byte SOLID_REACTIVE = 8;
    /**
     * Ladders, signs, sugar cane and the like. Water runs through them at any depth without breaking them, but
     * can never settle there, so it stays simulated.
     */
    static final byte PERMEABLE = 9;
    /** Settled water block, SETTLED + level (1-16) */
    static final byte SETTLED = 16;

    // Pseudo codes for neighbours that have no usable terrain
    static final int MISSING = -1;
    static final int PENDING = -2;
    static final int FROZEN = -3;

    static final int STATE_PENDING = 0;
    static final int STATE_FROZEN = 1;
    static final int STATE_READY = 2;

    private static final byte FLAG_DRAINED = 1;

    /** Stand-in for sections above and below the world, solid and never written to */
    static final WaterSection OUT_OF_WORLD = new WaterSection(0, -1, 0);
    static {
        OUT_OF_WORLD.terrain = new byte[4096];
        OUT_OF_WORLD.state = STATE_READY;
    }

    final int sx, sy, sz;
    final long key;
    final int[] mass = new int[4096];
    private final byte[] flags = new byte[4096];
    byte[] terrain;
    int state = STATE_PENDING;
    final WaterSection[] neighbors = new WaterSection[6];

    /** Cells holding water */
    int filled;
    /** Cells that emptied and still need to check their settled neighbours */
    private int drained;
    int quietSteps;
    int idleSteps;
    int refreshedStep;
    /**
     * Settled, apart from shallow water left in washable cells that cannot become blocks without breaking them.
     * Not stepped until something disturbs it.
     */
    boolean dormant;
    /** Changed by a command this step, prevents settling */
    boolean touched;
    boolean dirtyNet;
    byte[] lastSent;
    List<Runnable> deferred;

    // Per step scratch, written only by the thread computing this section
    private int[] flowBuf = new int[512];
    private int flowCount;
    boolean pending;
    int maxFlow;
    final LongArrayList wakes = new LongArrayList();
    final LongArrayList missing = new LongArrayList();
    /** Keys of dormant neighbour sections whose water has to move again */
    final LongArrayList wakeSections = new LongArrayList();
    /** Washable cells under deep enough water to break the block */
    final IntArrayList washes = new IntArrayList();
    /** Reactive blocks, like concrete powder, that water touched this step */
    final LongArrayList touches = new LongArrayList();

    WaterSection(int sx, int sy, int sz) {
        this.sx = sx;
        this.sy = sy;
        this.sz = sz;
        this.key = Water.sectionKey(sx, sy, sz);
    }

    boolean needsStep() {
        return state == STATE_READY && !dormant && (filled > 0 || drained > 0);
    }

    void defer(Runnable command) {
        if(deferred == null) deferred = new ArrayList<>();
        deferred.add(command);
    }

    void addMass(int i, int amount) {
        if(mass[i] == 0) filled++;
        mass[i] += amount;
    }

    void removeMass(int i, int amount) {
        mass[i] -= amount;
        if(mass[i] == 0) {
            filled--;
            if(flags[i] == 0) {
                flags[i] = FLAG_DRAINED;
                drained++;
            }
        }
    }

    /**
     * Moves the compressed excess of every column up to its water surface, so water is not lost when the
     * section becomes blocks that hold at most one block each. Excess pushed out of the top of the section is
     * added to overflow as column index / mass pairs.
     */
    void decompress(IntArrayList overflow) {
        for(int column = 0; column < 256; column++) {
            int carry = 0;
            int lastOpen = -1;
            for(int y = 0; y < 16; y++) {
                int i = (y << 8) | column;
                if(!isOpen(terrain[i])) {
                    // Trapped under a ceiling, stays compressed
                    if(carry > 0 && lastOpen >= 0) mass[lastOpen] += carry;
                    carry = 0;
                    lastOpen = -1;
                    continue;
                }
                lastOpen = i;
                int m = mass[i] + carry;
                if(m == 0) continue;
                carry = Math.max(0, m - Water.FULL);
                if(mass[i] == 0) filled++;
                mass[i] = m - carry;
            }
            if(carry > 0) {
                overflow.add(column);
                overflow.add(carry);
            }
        }
    }

    void wakeUp() {
        if(dormant) {
            dormant = false;
            quietSteps = 0;
        }
    }

    /** After settling, keeps the water still in the section and lets it go dormant */
    void afterSettle() {
        Arrays.fill(flags, (byte)0);
        drained = 0;
        filled = 0;
        for(int m : mass) if(m > 0) filled++;
        dormant = filled > 0;
    }

    /** Shallow water left in a dormant section slowly dries up, returns whether anything changed */
    boolean evaporate(java.util.Random rand, double chance) {
        boolean changed = false;
        for(int i = 0; i < 4096; i++) {
            if(mass[i] > 0 && rand.nextDouble() < chance) {
                mass[i] = Math.max(0, mass[i] - Water.UNIT);
                if(mass[i] == 0) filled--;
                changed = true;
            }
        }
        if(filled == 0) dormant = false;
        return changed;
    }

    // ---------------------------------------------------------------- addressing

    static boolean crosses(int i, int dir) {
        switch(dir) {
            case Water.DOWN: return (i >> 8) == 0;
            case Water.UP: return (i >> 8) == 15;
            case Water.NORTH: return ((i >> 4) & 15) == 0;
            case Water.SOUTH: return ((i >> 4) & 15) == 15;
            case Water.WEST: return (i & 15) == 0;
            default: return (i & 15) == 15;
        }
    }

    static int neighborIndex(int i, int dir) {
        switch(dir) {
            case Water.DOWN: return (i >> 8) == 0 ? i | 0xF00 : i - 256;
            case Water.UP: return (i >> 8) == 15 ? i & 0xFF : i + 256;
            case Water.NORTH: return ((i >> 4) & 15) == 0 ? i | 0xF0 : i - 16;
            case Water.SOUTH: return ((i >> 4) & 15) == 15 ? i & ~0xF0 : i + 16;
            case Water.WEST: return (i & 15) == 0 ? i | 0xF : i - 1;
            default: return (i & 15) == 15 ? i & ~0xF : i + 1;
        }
    }

    WaterSection neighborSection(int i, int dir) {
        return crosses(i, dir) ? neighbors[dir] : this;
    }

    long neighborKey(int dir) {
        return Water.sectionKey(sx + Water.DX[dir], sy + Water.DY[dir], sz + Water.DZ[dir]);
    }

    /** Whether water can be in a cell with this terrain code */
    static boolean holds(int code) {
        return isOpen(code) || code == POROUS;
    }

    /** Whether water can flow freely into a cell with this terrain code */
    static boolean isOpen(int code) {
        return code == OPEN || code == WASHABLE || code == PERMEABLE;
    }

    static int codeOf(WaterSection s, int i) {
        if(s == null) return MISSING;
        if(s.state == STATE_READY) return s.terrain[i];
        return s.state == STATE_PENDING ? PENDING : FROZEN;
    }

    static long packCell(long key, int i) {
        return (key << 12) | i;
    }

    // ---------------------------------------------------------------- phase 1, parallel

    /**
     * Computes this step's outflows of every wet cell from the current masses. Only reads other sections and
     * only writes this section's scratch state, so sections can be computed in parallel.
     */
    void computeFlows(int step, double leafAbsorption) {
        flowCount = 0;
        pending = false;
        wakes.clear();
        missing.clear();
        wakeSections.clear();
        washes.clear();
        touches.clear();
        for(int i = 0; i < 4096; i++) {
            int m = mass[i];
            if(m == 0) {
                if(flags[i] != 0) checkDrained(i);
            }
            else if(holds(terrain[i])) {
                // Water over half a block deep washes away flowers and the like, as vanilla flowing water does
                if(terrain[i] == WASHABLE && m > Water.FULL / 2) washes.add(i);
                flowCell(i, m, step, leafAbsorption);
            }
        }
    }

    private void flowCell(int i, int m, int step, double leafAbsorption) {
        int r = m;

        // Leaves soak up some of the water dripping through them
        if(terrain[i] == POROUS && leafAbsorption > 0.0D && random(step, i) < leafAbsorption) {
            int f = Math.min(r, Water.UNIT);
            addFlow(i, Water.DOWN, f, true);
            r -= f;
            if(r == 0) return;
        }

        // Fall
        WaterSection ns = neighborSection(i, Water.DOWN);
        int ni = neighborIndex(i, Water.DOWN);
        int code = codeOf(ns, ni);
        if(holds(code)) {
            int below = ns.mass[ni];
            int f = Water.stableBelow(r + below) - below;
            if(f > 0) {
                f = Math.min(f, r);
                addFlow(i, Water.DOWN, f, false);
                r -= f;
            }
        }
        else if(code == SINK) {
            addFlow(i, Water.DOWN, r, true);
            return;
        }
        else if(code >= SETTLED) {
            if(code - SETTLED < Water.LEVELS) wake(ns, ni);
        }
        else {
            if(code == SOLID_REACTIVE) touches.add(packCell(ns.key, ni));
            blocked(code, Water.DOWN);
        }
        if(r == 0) return;
        // How easily water runs over what it rests on, in thousandths
        int flowRate = Water.flowRate(code);

        // Settled water resting on top of a cell that has room falls into it
        ns = neighborSection(i, Water.UP);
        ni = neighborIndex(i, Water.UP);
        int upCode = codeOf(ns, ni);
        if(upCode >= SETTLED && m < Water.FULL - Water.SPREAD_DIFF) wake(ns, ni);

        // Spread, wet neighbours equalize, dry neighbours only receive whole levels so thin films can't creep forever
        int r0 = r;
        int start = (i + step) & 3;
        int dryMask = 0;
        int out = 0;
        for(int k = 0; k < 4; k++) {
            int dir = 2 + ((start + k) & 3);
            ns = neighborSection(i, dir);
            ni = neighborIndex(i, dir);
            code = codeOf(ns, ni);
            if(isOpen(code)) {
                int n = ns.mass[ni];
                if(ns.dormant && n - r0 > Water.SPREAD_DIFF) wakeSections.add(ns.key);
                if(n == 0) dryMask |= 1 << dir;
                else if(n >= Water.FULL && r0 > n) {
                    // Pressurized water passes on every difference, otherwise rounding swallows the pressure in a few cells
                    int f = (r0 - n + 4) / 5;
                    addFlow(i, dir, f, false);
                    out += f;
                }
                else if(r0 - n > Water.SPREAD_DIFF) {
                    int f = (r0 - n) * flowRate / 5000;
                    addFlow(i, dir, f, false);
                    out += f;
                }
            }
            else if(code == SINK) {
                if(r0 > Water.SPREAD_DIFF) {
                    int f = r0 * flowRate / 5000;
                    addFlow(i, dir, f, true);
                    out += f;
                }
            }
            else if(code >= SETTLED) {
                int settled = (code - SETTLED) * Water.UNIT;
                if(Math.abs(r0 - settled) > Water.WAKE_DIFF) wake(ns, ni);
            }
            else {
                if(code == SOLID_REACTIVE) touches.add(packCell(ns.key, ni));
                blocked(code, dir);
            }
        }
        r -= out;
        if(dryMask != 0 && r > Water.UNIT) {
            int f = Math.max(r0 * flowRate / 5000, Water.UNIT);
            int budget = r - Water.UNIT;
            for(int k = 0; k < 4 && budget >= f; k++) {
                int dir = 2 + ((start + k) & 3);
                if((dryMask & (1 << dir)) != 0) {
                    addFlow(i, dir, f, false);
                    budget -= f;
                    r -= f;
                }
            }
        }

        // Pressure pushes compressed water up
        if(isOpen(upCode) && r > Water.FULL) {
            ns = neighborSection(i, Water.UP);
            ni = neighborIndex(i, Water.UP);
            int f = r - Water.stableBelow(r + ns.mass[ni]);
            if(f > 0) addFlow(i, Water.UP, Math.min(f, r), false);
        }
        else if(upCode < 0) blocked(upCode, Water.UP);
    }

    /** A cell that just emptied lets settled water next to and above it flow in */
    private void checkDrained(int i) {
        flags[i] = 0;
        drained--;
        if(!holds(terrain[i])) return;
        for(int dir = Water.UP; dir <= Water.EAST; dir++) {
            WaterSection ns = neighborSection(i, dir);
            int ni = neighborIndex(i, dir);
            int code = codeOf(ns, ni);
            if(code >= SETTLED && (dir == Water.UP || code - SETTLED >= 2)) wake(ns, ni);
            else if(ns != null && ns.dormant && ns.mass[ni] > 0) wakeSections.add(ns.key);
        }
    }

    /** Deterministic random number in [0, 1) per section, cell and step, safe to use from parallel phase 1 */
    private double random(int step, int i) {
        long h = key * 0x9E3779B97F4A7C15L + i * 0xC2B2AE3D27D4EB4FL + step * 0x165667B19E3779F9L;
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        return (h >>> 11) * 0x1.0p-53;
    }

    private void blocked(int code, int dir) {
        if(code == MISSING) {
            pending = true;
            missing.add(neighborKey(dir));
        }
        else if(code == PENDING || code == WAKING) pending = true;
    }

    private void wake(WaterSection s, int i) {
        wakes.add(packCell(s.key, i));
        pending = true;
    }

    private void addFlow(int i, int dir, int amount, boolean sink) {
        if(flowCount + 2 > flowBuf.length) flowBuf = Arrays.copyOf(flowBuf, flowBuf.length * 2);
        flowBuf[flowCount++] = (i << 4) | (sink ? 8 : 0) | dir;
        flowBuf[flowCount++] = amount;
    }

    // ---------------------------------------------------------------- phase 2, serial

    /** Moves the computed flows. Writes into neighbour sections, so must run on a single thread. */
    void applyFlows() {
        for(int k = 0; k < flowCount; k += 2) {
            int e = flowBuf[k];
            int f = flowBuf[k + 1];
            int i = e >> 4;
            int dir = e & 7;
            removeMass(i, f);
            noteFlow(f);
            if((e & 8) == 0) {
                WaterSection t = neighborSection(i, dir);
                t.addMass(neighborIndex(i, dir), f);
                t.noteFlow(f);
            }
        }
        flowCount = 0;
    }

    private void noteFlow(int f) {
        wakeUp();
        if(f > maxFlow) maxFlow = f;
        dirtyNet = true;
    }
}
