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
    /**
     * Full water of oceans and rivers, see FluidicWater.isReservoir. It is never woken or drained: water flowing
     * in disappears, and open space next to or below it fills up from it.
     */
    static final byte RESERVOIR = 10;
    /** A dry sponge, soaks up water flowing into it until it is full, see {@link WaterSimulation} */
    static final byte SPONGE = 11;
    /**
     * A block water can be in, like modern waterlogged stairs and slabs: water flows in and out sideways and stays
     * simulated inside it, but it is sealed top and bottom, so a roof of slabs does not leak.
     */
    static final byte WATERLOGGABLE = 12;
    /** A drain, takes in water like a sponge but never fills, see fluidic.block.BlockDrain */
    static final byte DRAIN = 13;
    /**
     * Doors, DOOR + the side the door blocks less NORTH, so four codes. Water flows in and through them like a ladder,
     * but never across the side the door is on, so a closed door holds water back and an open one lets it pass.
     */
    static final byte DOOR = 14;
    /** Water a sponge soaks up per side and step */
    private static final int SPONGE_RATE = Water.UNIT / 2;

    /** Most water a cell takes in from a reservoir per step */
    static final int RESERVOIR_FLOW = Water.FULL / 4;
    private static final int FLOW_SINK = 8;
    private static final int FLOW_SOURCE = 16;
    /** Settled water block, SETTLED + level (1-8) */
    static final byte SETTLED = 20;

    // Pseudo codes for neighbours that have no usable terrain
    static final int MISSING = -1;
    static final int PENDING = -2;
    static final int FROZEN = -3;

    static final int STATE_PENDING = 0;
    static final int STATE_FROZEN = 1;
    static final int STATE_READY = 2;

    private static final byte FLAG_DRAINED = 1;

    /** Stand-in for sections above the world, solid and never written to */
    static final WaterSection OUT_OF_WORLD = new WaterSection(0, 16, 0);
    /**
     * Stand-in for sections below the world, the void: water falling into it is gone, like vanilla water falling out of
     * the End or through a hole in the bedrock. Never written to.
     */
    static final WaterSection VOID = new WaterSection(0, -1, 0);
    static {
        OUT_OF_WORLD.terrain = new byte[4096];
        OUT_OF_WORLD.state = STATE_READY;
        VOID.terrain = new byte[4096];
        Arrays.fill(VOID.terrain, SINK);
        VOID.state = STATE_READY;
    }

    final int sx, sy, sz;
    final long key;
    final int[] mass = new int[4096];
    private final byte[] flags = new byte[4096];
    byte[] terrain;
    /**
     * Cells of blocks that keep settled water inside them as a Fluidlogged API fluid state, like stairs, null without
     * Fluidlogged API. From the last terrain snapshot, the server checks again before storing water.
     */
    boolean[] storable;

    boolean canStore(int i) {
        return storable != null && storable[i];
    }
    int state = STATE_PENDING;
    final WaterSection[] neighbors = new WaterSection[6];

    /** Cells holding water */
    int filled;
    /** Cells that emptied and still need to check their settled neighbours */
    private int drained;
    /** Sea level of the world, for the step being computed */
    private int seaLevel;
    int quietSteps;
    /** Times settling waited for neighbouring sections a pool surface reaches */
    int levelWaits;
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
    /** Water soaked into sponges this step, pairs of the sponge cell (packCell) and the source cell << 32 | amount */
    final LongArrayList soaks = new LongArrayList();
    /** Cells on top of or beside an ocean below sea level, they join it once full */
    final IntArrayList merges = new IntArrayList();

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

    /** Rain held back per cell, see {@link #bankRain}, null while there is none */
    int[] rain;

    /**
     * Holds back rain that fell into water still in motion. Every drop would set the water rippling and keep the
     * section from settling for the whole storm, so the rain is added when the section settles, or now and then.
     */
    void bankRain(int i, int amount) {
        if(rain == null) rain = new int[4096];
        rain[i] += amount;
    }

    /** Adds the held back rain to the water, returns whether there was any */
    boolean releaseRain() {
        if(rain == null) return false;
        for(int i = 0; i < 4096; i++) {
            if(rain[i] > 0) addMass(i, rain[i]);
        }
        rain = null;
        dirtyNet = true;
        return true;
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

    /**
     * Water left in a dormant section, shallow water around plants and water held in leaves, slowly dries up.
     * Not under more water, leaves inside a pool stay full. Returns whether anything changed.
     */
    boolean evaporate(java.util.Random rand, double evaporation, double absorption, int seaLevel, boolean filmsOnly) {
        boolean changed = false;
        for(int i = 0; i < 4096; i++) {
            if(mass[i] == 0 || underWater(i)) continue;
            if(filmsOnly && !isFilm(i)) continue;
            // Nothing evaporates below sea level, the ground still soaks water up
            double chance = sy * 16 + (i >> 8) < seaLevel ? absorption : evaporation + absorption;
            if(rand.nextDouble() < chance) {
                mass[i] = Math.max(0, mass[i] - Water.UNIT);
                if(mass[i] == 0) filled--;
                changed = true;
            }
        }
        if(filled == 0) dormant = false;
        return changed;
    }

    /** Whether water above a cell is falling into it */
    private boolean isDrippedOn(int i) {
        WaterSection up = neighborSection(i, Water.UP);
        int ui = neighborIndex(i, Water.UP);
        return holds(codeOf(up, ui)) && up.mass[ui] > 0;
    }

    /** Half a block or less of water resting on the ground rather than on more water */
    private boolean isFilm(int i) {
        if(mass[i] > Water.FULL / 2) return false;
        WaterSection down = neighborSection(i, Water.DOWN);
        int di = neighborIndex(i, Water.DOWN);
        int code = codeOf(down, di);
        return !(code >= SETTLED || code == RESERVOIR || code == SINK || holds(code) && down.mass[di] > 0);
    }

    private boolean underWater(int i) {
        WaterSection up = neighborSection(i, Water.UP);
        int ui = neighborIndex(i, Water.UP);
        int code = codeOf(up, ui);
        return code >= SETTLED || holds(code) && up.mass[ui] > 0;
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

    static boolean isDoor(int code) {
        return code >= DOOR && code < DOOR + 4;
    }

    /** Whether water in or beside a cell with this code cannot cross its side in direction dir, the side of a door */
    static boolean blocksSide(int code, int dir) {
        return isDoor(code) && dir == code - DOOR + Water.NORTH;
    }

    /** Whether water can flow freely into a cell with this terrain code */
    static boolean isOpen(int code) {
        return code == OPEN || code == WASHABLE || code == PERMEABLE || code == WATERLOGGABLE || isDoor(code);
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
    void computeFlows(int step, double leafAbsorption, int seaLevel) {
        this.seaLevel = seaLevel;
        flowCount = 0;
        pending = false;
        wakes.clear();
        missing.clear();
        wakeSections.clear();
        washes.clear();
        touches.clear();
        soaks.clear();
        merges.clear();
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

        // Leaves soak up some of the water dripping into them from above. Not water that ran in from the side, leaves
        // in the top layer of a pool would drain it, and not the water of a pool they are under.
        if(terrain[i] == POROUS && m < Water.FULL - Water.SPREAD_DIFF && leafAbsorption > 0.0D && isDrippedOn(i) && random(step, i) < leafAbsorption) {
            int f = Math.min(r, Water.FULL / 16);
            addFlow(i, Water.DOWN, f, true);
            r -= f;
            if(r == 0) return;
        }

        // Fall
        WaterSection ns = neighborSection(i, Water.DOWN);
        int ni = neighborIndex(i, Water.DOWN);
        int code = codeOf(ns, ni);
        // Waterloggable blocks are sealed at the bottom and top
        if(code == WATERLOGGABLE || terrain[i] == WATERLOGGABLE) code = SOLID;
        if(holds(code)) {
            int below = ns.mass[ni];
            int f = Water.stableBelow(r + below) - below;
            // Between compressed cells only half the difference moves, a cell is evened out with the cells above and
            // below it at once and full steps overshoot, so deep water would slosh up and down without ever settling
            if(below >= Water.FULL && r >= Water.FULL) f /= 2;
            if(f > 0) {
                f = Math.min(f, r);
                addFlow(i, Water.DOWN, f, false);
                r -= f;
            }
        }
        else if(code == RESERVOIR && sy * 16 + (i >> 8) < seaLevel) {
            // Below sea level this is part of the ocean, like a hole scooped out with a bucket: the ocean around it
            // fills it up and it settles into ocean again. Draining it into the ocean below would empty it as fast as
            // it refills. Water poured onto the ocean above sea level still vanishes into it.
            if(r > Water.FULL) {
                addFlow(i, Water.DOWN, r - Water.FULL, true);
                r = Water.FULL;
            }
            else if(r < Water.FULL - Water.SPREAD_DIFF) {
                // Filled from below too, a wide sheet would only fill slowly from where it touches the ocean's side
                addSource(i, Math.min(RESERVOIR_FLOW, Water.FULL - r));
            }
            // Once full it is ocean, see WaterSimulation.mergeIntoReservoirs
            merges.add(i);
        }
        else if(code == SINK || code == RESERVOIR) {
            addFlow(i, Water.DOWN, r, true);
            return;
        }
        else if(code == SPONGE || code == DRAIN) {
            r -= soak(i, Water.DOWN, Math.min(r, SPONGE_RATE));
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
        if(upCode == WATERLOGGABLE || terrain[i] == WATERLOGGABLE) upCode = SOLID;
        if(upCode >= SETTLED && m < Water.FULL - Water.SPREAD_DIFF) wake(ns, ni);
        // An ocean above pours in without ever draining
        if(upCode == RESERVOIR && m < Water.FULL - Water.SPREAD_DIFF) addSource(i, Math.min(Water.FULL - m, RESERVOIR_FLOW));

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
            // Nothing crosses the side a door is on, of this cell or the one beside it
            if(blocksSide(terrain[i], dir) || blocksSide(code, dir ^ 1)) code = SOLID;
            // Leaves only take water from the side when they hold some already or this water is full, like leaves under
            // the surface of a pool. Otherwise thin water would creep through tree tops.
            if(isOpen(code) || code == POROUS && (ns.mass[ni] > 0 || r0 >= Water.FULL - Water.SPREAD_DIFF)) {
                int n = ns.mass[ni];
                if(ns.dormant && n - r0 > Water.SPREAD_DIFF) wakeSections.add(ns.key);
                if(n == 0) dryMask |= 1 << dir;
                else if(n >= Water.FULL && r0 - n > 1) {
                    // Pressurized water passes on small differences too, otherwise rounding swallows the pressure in a few
                    // cells. Rounded down, rounding up overshoots and full pools swing back and forth without ever settling.
                    int f = Math.max(1, (r0 - n) / 5);
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
            else if(code == RESERVOIR) {
                // Water beside an ocean is part of it: it fills up from it and joins it once full, see
                // WaterSimulation.mergeIntoReservoirs. Draining the slight pressure of deep water into it instead would
                // keep the water above pushing down to replace it, and the edge of a river would never settle.
                // The ocean pushes in at full rate whatever the ground, a shallow edge would otherwise creep full over
                // rough ground a unit at a time.
                if(r0 < Water.FULL - Water.SPREAD_DIFF) addSource(i, Math.min(RESERVOIR_FLOW, Water.FULL - r0));
                merges.add(i);
            }
            else if(code == SPONGE || code == DRAIN) {
                int f = Math.min(Math.min(SPONGE_RATE, Math.max(1, r0 / 4)), r - out);
                if(f > 0) out += soak(i, dir, f);
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
        int spreadMask = 0;
        if(dryMask != 0 && r > Water.UNIT) {
            int f = Math.max(r0 * flowRate / 5000, Water.UNIT);
            int budget = r - Water.UNIT;
            for(int k = 0; k < 4 && budget >= f; k++) {
                int dir = 2 + ((start + k) & 3);
                if((dryMask & (1 << dir)) != 0) {
                    addFlow(i, dir, f, false);
                    spreadMask |= 1 << dir;
                    budget -= f;
                    r -= f;
                }
            }
        }
        // Thin water stays put on flat ground, but at a ledge it runs over the edge however thin it is
        for(int k = 0; k < 4 && r > 0; k++) {
            int dir = 2 + ((start + k) & 3);
            if((dryMask & ~spreadMask & (1 << dir)) == 0) continue;
            ns = neighborSection(i, dir);
            ni = neighborIndex(i, dir);
            if(!isDrop(ns, ni)) continue;
            int f = Math.min(r, Math.max(r0 * flowRate / 5000, Water.SPREAD_DIFF));
            addFlow(i, dir, f, false);
            r -= f;
        }

        // A sponge or drain above soaks water up too
        if((upCode == SPONGE || upCode == DRAIN) && r > 0) r -= soak(i, Water.UP, Math.min(r, SPONGE_RATE));

        // Pressure pushes compressed water up
        if(isOpen(upCode) && r > Water.FULL) {
            ns = neighborSection(i, Water.UP);
            ni = neighborIndex(i, Water.UP);
            int f = r - Water.stableBelow(r + ns.mass[ni]);
            if(ns.mass[ni] >= Water.FULL) f /= 2;
            if(f > 0) addFlow(i, Water.UP, Math.min(f, r), false);
        }
        else if(upCode < 0) blocked(upCode, Water.UP);
    }

    /**
     * Whether water put into a dry cell would fall on, so the cell is the edge of a ledge. Only looks below cells
     * of this section and below cells inside a neighbour, whose own neighbours are not linked.
     */
    private boolean isDrop(WaterSection ns, int ni) {
        WaterSection below;
        if(!crosses(ni, Water.DOWN)) below = ns;
        else if(ns == this) below = neighbors[Water.DOWN];
        else return false;
        int bi = neighborIndex(ni, Water.DOWN);
        int code = codeOf(below, bi);
        if(code == SINK || code == RESERVOIR) return true;
        if(code == WATERLOGGABLE) return false;
        if(holds(code)) return below.mass[bi] < Water.FULL - Water.SPREAD_DIFF;
        return code >= SETTLED && code - SETTLED < Water.LEVELS;
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
            if(dir >= Water.NORTH && (blocksSide(terrain[i], dir) || blocksSide(code, dir ^ 1))) continue;
            if(code == RESERVOIR) addSource(i, RESERVOIR_FLOW);
            else if(code >= SETTLED && (dir == Water.UP && terrain[i] != WATERLOGGABLE || code - SETTLED >= 2)) wake(ns, ni);
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
        flowBuf[flowCount++] = (i << 5) | (sink ? FLOW_SINK : 0) | dir;
        flowBuf[flowCount++] = amount;
    }

    /** Water soaking into a sponge, removed here and counted against the sponge by the simulation */
    private int soak(int i, int dir, int amount) {
        addFlow(i, dir, amount, true);
        soaks.add(packCell(neighborSection(i, dir).key, neighborIndex(i, dir)));
        soaks.add(((long)i << 32) | amount);
        return amount;
    }

    /** Water a cell takes in from a reservoir, which is not simulated and never runs out */
    private void addSource(int i, int amount) {
        if(amount <= 0) return;
        if(flowCount + 2 > flowBuf.length) flowBuf = Arrays.copyOf(flowBuf, flowBuf.length * 2);
        flowBuf[flowCount++] = (i << 5) | FLOW_SOURCE;
        flowBuf[flowCount++] = amount;
    }

    // ---------------------------------------------------------------- phase 2, serial

    /** Moves the computed flows. Writes into neighbour sections, so must run on a single thread. */
    void applyFlows() {
        for(int k = 0; k < flowCount; k += 2) {
            int e = flowBuf[k];
            int f = flowBuf[k + 1];
            int i = e >> 5;
            int dir = e & 7;
            if((e & FLOW_SOURCE) != 0) {
                addMass(i, f);
                noteFlow(f);
                continue;
            }
            if((e & FLOW_SINK) != 0) {
                removeMass(i, f);
                noteFlow(f);
                continue;
            }
            WaterSection t = neighborSection(i, dir);
            int ti = neighborIndex(i, dir);
            // Both cells stay full, so the flow only moves compression around below the surface
            int noted = mass[i] - f >= Water.FULL && t.mass[ti] >= Water.FULL && f <= Water.QUIET_PRESSURE_FLOW ? Math.min(f, Water.QUIET_FLOW) : f;
            removeMass(i, f);
            noteFlow(noted);
            t.addMass(ti, f);
            t.noteFlow(noted);
        }
        flowCount = 0;
    }

    private void noteFlow(int f) {
        wakeUp();
        if(f > maxFlow) maxFlow = f;
        dirtyNet = true;
    }
}
