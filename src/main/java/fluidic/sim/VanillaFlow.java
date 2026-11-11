package fluidic.sim;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;

import java.util.List;

/**
 * Vanilla's water rules on the simulation's grid, the Vanilla Water Physics server option. Water does not keep its
 * volume: a source is endless, the water flowing from it is a level lower with every block and dries up without it,
 * water under water is falling water, and two sources beside a cell over solid ground or a source make it a source.
 * As BlockDynamicLiquid works it out, but on cells instead of blocks, so the water goes where a water block cannot:
 * through stairs, ladders and open doors, see Terrain. A cell's mass is its level in eighths of a block, sources and
 * falling water fill it, {@link WaterSection#kind} tells them apart.
 *
 * Every wet cell is worked out from the cells around it as they were before the step, then the changes are made, so
 * the order of the cells does not matter. Wet cells take their level from their neighbours, dry cells are flowed into.
 * Runs on the simulation thread, one section after the other: finding the way to the nearest drop looks further than
 * the cells beside a cell.
 */
final class VanillaFlow {

    static final byte FLOWING = 0;
    static final byte SOURCE = 1;
    static final byte FALLING = 2;

    /** Simulation steps between flow steps, the ticks between updates of vanilla water */
    static final int INTERVAL = 5;
    /** Blocks water looks for a drop to flow towards, as vanilla */
    private static final int SLOPE_DISTANCE = 4;
    private static final int NO_SLOPE = 1000;
    /** Marks a change that flows into a dry cell, the others set the cell they were worked out for */
    private static final int PUSH = 1 << 24;

    private final Long2ObjectOpenHashMap<WaterSection> sections;
    /** Changes of the step, cells as WaterSection.packCell, and the mass, kind << 16 and PUSH of each */
    private final LongArrayList changeCells = new LongArrayList();
    private final IntArrayList changeValues = new IntArrayList();

    /** The section whose cells are being worked out, null while a settled block is checked */
    private WaterSection current;
    /** The cell last looked up with code, null when its section has no terrain */
    private WaterSection at;
    private int ati;
    private long lastKey;
    private WaterSection last;
    private boolean hasLast;

    /** A settled block being checked, its code and those around it come from the world where no section has them */
    private boolean overlay;
    private int overlayX, overlayY, overlayZ;
    private byte overlaySelf;
    private byte[] overlayCodes;

    // What evaluate worked out
    private int newMass;
    private byte newKind;
    /** A cell around it is not known yet, it is not lowered until it is */
    private boolean unknown;
    /** A cell around it will be known soon, its section does not settle until then */
    private boolean pending;
    /** Directions it flows into dry cells, bits by direction, down as falling water and sideways at pushLevel */
    private int pushes;
    private int pushLevel;

    VanillaFlow(Long2ObjectOpenHashMap<WaterSection> sections) {
        this.sections = sections;
    }

    /** Mass of water of a vanilla level, 0 a source, 1 to 7 flowing and 8 and up falling */
    static int massOf(int meta) {
        return meta <= 0 || meta >= 8 ? Water.FULL : (Water.LEVELS - meta) * Water.UNIT;
    }

    static byte kindOf(int meta) {
        return meta <= 0 ? SOURCE : meta >= 8 ? FALLING : FLOWING;
    }

    /** Vanilla level of a cell's water */
    static int metaOf(int mass, byte kind) {
        if(kind == SOURCE) return 0;
        if(kind == FALLING) return 8;
        return Water.LEVELS - eighths(mass);
    }

    /** Terrain code of a cell's water once it is a block */
    static byte settledCode(int mass, byte kind) {
        if(kind == SOURCE) return (byte)(WaterSection.SETTLED + Water.LEVELS);
        if(kind == FALLING) return WaterSection.SETTLED_FALLING;
        return (byte)(WaterSection.SETTLED + eighths(mass));
    }

    /** Eighths of flowing water, 1 to 7 */
    private static int eighths(int mass) {
        return Math.max(1, Math.min(Water.LEVELS - 1, mass / Water.UNIT));
    }

    /** How high water stands for the cells beside it, 0 to 8, sources and falling water count as full */
    private static int levelOf(int mass, byte kind) {
        return mass == 0 ? 0 : kind != FLOWING ? Water.LEVELS : eighths(mass);
    }

    // ---------------------------------------------------------------- the grid

    private WaterSection section(long key) {
        if(hasLast && key == lastKey) return last;
        lastKey = key;
        hasLast = true;
        return last = sections.get(key);
    }

    /** Terrain code of a block, leaves at and ati on its cell when its section has terrain */
    private int code(int x, int y, int z) {
        at = null;
        // Water does not flow out of the world
        if(y < 0) return WaterSection.SINK;
        if(y > 255) return WaterSection.SOLID;
        long key = Water.sectionKey(x >> 4, y >> 4, z >> 4);
        WaterSection s = section(key);
        if(s != null && s.state == WaterSection.STATE_READY) {
            at = s;
            ati = Water.cellIndex(x & 15, y & 15, z & 15);
            return s.terrain[ati];
        }
        if(overlay) {
            int dx = x - overlayX, dy = y - overlayY, dz = z - overlayZ;
            if(dx == 0 && dy == 0 && dz == 0) return overlaySelf;
            if(Math.abs(dx) + Math.abs(dy) + Math.abs(dz) == 1) {
                return overlayCodes[dy < 0 ? Water.DOWN : dy > 0 ? Water.UP : dz < 0 ? Water.NORTH : dz > 0 ? Water.SOUTH : dx < 0 ? Water.WEST : Water.EAST];
            }
        }
        if(s == null) {
            if(current != null) current.missing.add(key);
            return WaterSection.MISSING;
        }
        return s.state == WaterSection.STATE_PENDING ? WaterSection.PENDING : WaterSection.FROZEN;
    }

    /** Code of the block beside a cell with the given code, solid across the side of a door */
    private int side(int x, int y, int z, int self, int dir) {
        int code = code(x + Water.DX[dir], y, z + Water.DZ[dir]);
        if(WaterSection.blocksSide(self, dir) || WaterSection.blocksSide(code, dir ^ 1)) {
            at = null;
            return WaterSection.SOLID;
        }
        return code;
    }

    /** Level of the water in the cell last looked up, a settled block or simulated water */
    private int level(int code) {
        if(code >= WaterSection.SETTLED) return code == WaterSection.SETTLED_FALLING ? Water.LEVELS : code - WaterSection.SETTLED;
        if(at == null || !WaterSection.isOpen(code)) return 0;
        return levelOf(at.mass[ati], at.kind(ati));
    }

    private boolean isSource(int code) {
        if(code == WaterSection.SETTLED + Water.LEVELS) return true;
        return WaterSection.isOpen(code) && wet() && at.kind(ati) == SOURCE;
    }

    /** Whether the cell last looked up holds simulated water, a block only known from the world holds none */
    private boolean wet() {
        return at != null && at.mass[ati] > 0;
    }

    private static boolean isUnknown(int code) {
        return code < 0 || code == WaterSection.WAKING;
    }

    private void noteUnknown(int code) {
        unknown = true;
        if(code != WaterSection.FROZEN) pending = true;
    }

    /** Ground a source forms over, vanilla asks for a solid material */
    private static boolean isSolid(int code) {
        return code == WaterSection.SOLID || code == WaterSection.SOLID_ROUGH || code == WaterSection.SOLID_SMOOTH || code == WaterSection.SPONGE;
    }

    private boolean nearDrain(int x, int y, int z) {
        for(int dir = 0; dir < 6; dir++) {
            if(code(x + Water.DX[dir], y + Water.DY[dir], z + Water.DZ[dir]) == WaterSection.DRAIN) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- the rules

    /**
     * Works out what the water of a cell becomes and where it flows, from the cells around it. The cell has the given
     * terrain code, a settled block its settled code.
     */
    private void evaluate(int x, int y, int z, int self, int mass, byte kind) {
        unknown = false;
        pending = false;
        pushes = 0;
        pushLevel = 0;
        newMass = mass;
        newKind = kind;
        // Waterloggable blocks are sealed at the bottom and top
        boolean sealed = self == WaterSection.WATERLOGGABLE;

        int best = 0, sources = 0;
        boolean drain = false;
        for(int dir = Water.NORTH; dir <= Water.EAST; dir++) {
            int code = side(x, y, z, self, dir);
            if(code == WaterSection.DRAIN) drain = true;
            if(isUnknown(code)) {
                noteUnknown(code);
                continue;
            }
            best = Math.max(best, level(code));
            if(isSource(code)) sources++;
        }
        int up = code(x, y + 1, z);
        boolean above = false;
        if(up == WaterSection.DRAIN) drain = true;
        if(isUnknown(up)) noteUnknown(up);
        else if(!sealed && up != WaterSection.WATERLOGGABLE) above = level(up) > 0;

        int down = code(x, y - 1, z);
        boolean downUnknown = isUnknown(down);
        if(down == WaterSection.DRAIN) drain = true;
        if(downUnknown) noteUnknown(down);
        boolean sealedDown = sealed || down == WaterSection.WATERLOGGABLE;
        boolean downDry = !downUnknown && WaterSection.isOpen(down) && !wet();
        boolean downSource = !downUnknown && isSource(down);

        // A drain takes the water touching it
        if(drain) {
            newMass = 0;
            newKind = FLOWING;
            return;
        }
        if(kind != SOURCE) {
            int m = 0;
            byte k = FLOWING;
            if(above) {
                m = Water.FULL;
                k = FALLING;
            }
            else if(best > 1) m = (best - 1) * Water.UNIT;
            if(sources >= 2 && !downUnknown && (sealedDown || isSolid(down) || downSource)) {
                m = Water.FULL;
                k = SOURCE;
            }
            // What it took its level from may only be on its way into the grid
            if(!(unknown && m < mass)) {
                newMass = m;
                newKind = k;
            }
        }
        if(newMass == 0 || downUnknown) return;

        // Down as falling water, and only where it cannot, sideways
        if(!sealedDown && downDry) {
            if(!nearDrain(x, y - 1, z)) pushes |= 1 << Water.DOWN;
            return;
        }
        // Flowing water resting on water goes no further, a source does
        boolean rests = !sealedDown && (down >= WaterSection.SETTLED || down == WaterSection.SINK || WaterSection.isOpen(down));
        if(rests && newKind != SOURCE) return;
        int out = newKind != FLOWING ? Water.LEVELS - 1 : eighths(newMass) - 1;
        if(out < 1) return;
        int dirs = flowDirections(x, y, z, self);
        for(int dir = Water.NORTH; dir <= Water.EAST; dir++) {
            if((dirs & 1 << dir) == 0) continue;
            int code = side(x, y, z, self, dir);
            if(WaterSection.isOpen(code) && !wet() && !nearDrain(x + Water.DX[dir], y, z + Water.DZ[dir])) pushes |= 1 << dir;
        }
        pushLevel = out;
    }

    /** Whether water flows from a cell with the given code to the cell beside it: open or water, but no source */
    private boolean passable(int x, int y, int z, int self, int dir) {
        int code = side(x, y, z, self, dir);
        if(code >= WaterSection.SETTLED) return code != WaterSection.SETTLED + Water.LEVELS;
        if(code == WaterSection.WAKING) return true;
        return WaterSection.isOpen(code) && !(wet() && at.kind(ati) == SOURCE);
    }

    /** Whether water in a cell cannot go down, it flows on sideways */
    private boolean blockedBelow(int x, int y, int z) {
        if(code(x, y, z) == WaterSection.WATERLOGGABLE) return true;
        int below = code(x, y - 1, z);
        if(below == WaterSection.WATERLOGGABLE) return true;
        return !(below >= WaterSection.SETTLED || below == WaterSection.WAKING || WaterSection.isOpen(below));
    }

    /** The sides water flows to from a cell, the ones nearest to a drop, bits by direction. As vanilla picks them. */
    private int flowDirections(int x, int y, int z, int self) {
        int nearest = NO_SLOPE, dirs = 0;
        for(int dir = Water.NORTH; dir <= Water.EAST; dir++) {
            if(!passable(x, y, z, self, dir)) continue;
            int nx = x + Water.DX[dir], nz = z + Water.DZ[dir];
            int distance = blockedBelow(nx, y, nz) ? slope(nx, y, nz, 1, dir ^ 1) : 0;
            if(distance < nearest) dirs = 0;
            if(distance <= nearest) {
                dirs |= 1 << dir;
                nearest = distance;
            }
        }
        return dirs;
    }

    /** Blocks from a cell to the nearest drop, not back the way it came */
    private int slope(int x, int y, int z, int distance, int from) {
        int nearest = NO_SLOPE;
        int self = code(x, y, z);
        for(int dir = Water.NORTH; dir <= Water.EAST; dir++) {
            if(dir == from || !passable(x, y, z, self, dir)) continue;
            int nx = x + Water.DX[dir], nz = z + Water.DZ[dir];
            if(!blockedBelow(nx, y, nz)) return distance;
            if(distance < SLOPE_DISTANCE) nearest = Math.min(nearest, slope(nx, y, nz, distance + 1, dir ^ 1));
        }
        return nearest;
    }

    // ---------------------------------------------------------------- stepping

    /** One flow step of the sections that have water moving */
    void step(List<WaterSection> list) {
        hasLast = false;
        overlay = false;
        changeCells.clear();
        changeValues.clear();
        for(WaterSection section : list) {
            section.pending = false;
            section.wakes.clear();
            section.missing.clear();
            section.wakeSections.clear();
            section.washes.clear();
            section.touches.clear();
            section.soaks.clear();
            section.merges.clear();
        }
        for(WaterSection section : list) {
            current = section;
            int bx = section.sx * 16, by = section.sy * 16, bz = section.sz * 16;
            for(int i = 0; i < 4096; i++) {
                int x = bx + (i & 15), y = by + (i >> 8), z = bz + ((i >> 4) & 15);
                int mass = section.mass[i];
                byte self = section.terrain[i];
                if(mass == 0) {
                    if(section.takeDrained(i)) emptied(x, y, z, self);
                    continue;
                }
                if(!WaterSection.isOpen(self)) continue;
                // Flowers and the like break under more than half a block of water, as the washable class says, like under
                // the simulation's own rules, see WaterSection. Sources and falling water fill their cell. Shallower flowing
                // water runs through them.
                if(self == WaterSection.WASHABLE && mass > Water.FULL / 2) section.washes.add(i);
                byte kind = section.kind(i);
                evaluate(x, y, z, self, mass, kind);
                if(pending) section.pending = true;
                int toMass = newMass, toPushes = pushes, toLevel = pushLevel;
                byte toKind = newKind;
                if(toMass != mass || toKind != kind) {
                    changeCells.add(WaterSection.packCell(section.key, i));
                    changeValues.add(toMass | toKind << 16);
                }
                for(int dir = 0; dir < 6; dir++) {
                    if((toPushes & 1 << dir) == 0) continue;
                    code(x + Water.DX[dir], y + Water.DY[dir], z + Water.DZ[dir]);
                    changeCells.add(WaterSection.packCell(at.key, ati));
                    changeValues.add((dir == Water.DOWN ? Water.FULL | FALLING << 16 : toLevel * Water.UNIT) | PUSH);
                }
                around(x, y, z, self, levelOf(mass, kind), levelOf(toMass, toKind));
            }
        }
        current = null;

        for(int n = 0; n < changeCells.size(); n++) {
            long cell = changeCells.getLong(n);
            int value = changeValues.getInt(n);
            WaterSection target = sections.get(cell >>> 12);
            if(target == null || target.state != WaterSection.STATE_READY) continue;
            int i = (int)(cell & 0xFFF), mass = value & 0xFFFF;
            // Flowed into from several sides, the most water counts
            if((value & PUSH) != 0 && (target.mass[i] >= mass || !WaterSection.isOpen(target.terrain[i]))) continue;
            target.setCell(i, mass, (byte)(value >> 16 & 3));
        }
    }

    /**
     * Has the water around a cell follow it: settled blocks that take their level from it are woken, and so are
     * sections that stopped being stepped. Simulated water beside it works itself out. Blocks that react to water
     * touching them are reported.
     */
    private void around(int x, int y, int z, int self, int oldLevel, int newLevel) {
        boolean lowered = newLevel < oldLevel;
        for(int dir = Water.NORTH; dir <= Water.EAST; dir++) {
            int code = side(x, y, z, self, dir);
            if(code == WaterSection.SOLID_REACTIVE && at != null) current.touches.add(WaterSection.packCell(at.key, ati));
            else if(code > WaterSection.SETTLED && code < WaterSection.SETTLED + Water.LEVELS) {
                int level = code - WaterSection.SETTLED;
                if(level < newLevel - 1 || lowered && level < oldLevel) wake();
            }
            else if(at != null && at.dormant && at.mass[ati] > 0 && at.kind(ati) == FLOWING) {
                int level = levelOf(at.mass[ati], FLOWING);
                if(level < newLevel - 1 || lowered && level < oldLevel) current.wakeSections.add(at.key);
            }
        }
        int down = code(x, y - 1, z);
        if(down == WaterSection.SOLID_REACTIVE && at != null) current.touches.add(WaterSection.packCell(at.key, ati));
        if(self == WaterSection.WATERLOGGABLE || down == WaterSection.WATERLOGGABLE) return;
        // Water under water is falling water, and dries up without it
        if(down == WaterSection.SETTLED_FALLING ? newLevel == 0 : down > WaterSection.SETTLED && down < WaterSection.SETTLED + Water.LEVELS && newLevel > 0) wake();
        else if(at != null && at.dormant && at.mass[ati] > 0 && WaterSection.isOpen(down)) {
            byte kind = at.kind(ati);
            if(kind == FALLING ? newLevel == 0 : kind == FLOWING && newLevel > 0) current.wakeSections.add(at.key);
        }
    }

    /** A cell emptied without being worked out, like a block placed into it: the water around it follows */
    private void emptied(int x, int y, int z, int self) {
        if(!WaterSection.isOpen(self)) return;
        int up = code(x, y + 1, z);
        if(self != WaterSection.WATERLOGGABLE) {
            if(up >= WaterSection.SETTLED) wake();
            else if(at != null && at.dormant && at.mass[ati] > 0) current.wakeSections.add(at.key);
        }
        for(int dir = Water.NORTH; dir <= Water.EAST; dir++) {
            int code = side(x, y, z, self, dir);
            if(code > WaterSection.SETTLED && code < WaterSection.SETTLED + Water.LEVELS) wake();
            else if(at != null && at.dormant && at.mass[ati] > 0) current.wakeSections.add(at.key);
        }
        int down = code(x, y - 1, z);
        if(self == WaterSection.WATERLOGGABLE) return;
        if(down == WaterSection.SETTLED_FALLING) wake();
        else if(at != null && at.dormant && at.mass[ati] > 0) current.wakeSections.add(at.key);
    }

    /** Wakes the settled block last looked up */
    private void wake() {
        if(at == null) return;
        current.wakes.add(WaterSection.packCell(at.key, ati));
        current.pending = true;
    }

    /**
     * Whether a settled water block would change or flow on, so it has to be woken. The codes of the blocks around it
     * come from the world, in simulation direction order, for where no section has them.
     */
    boolean wouldChange(int x, int y, int z, byte self, byte[] codes) {
        hasLast = false;
        current = null;
        overlay = true;
        overlayX = x;
        overlayY = y;
        overlayZ = z;
        overlaySelf = self;
        overlayCodes = codes;
        int mass = self == WaterSection.SETTLED_FALLING || self == WaterSection.SETTLED + Water.LEVELS ? Water.FULL : (self - WaterSection.SETTLED) * Water.UNIT;
        byte kind = self == WaterSection.SETTLED_FALLING ? FALLING : self == WaterSection.SETTLED + Water.LEVELS ? SOURCE : FLOWING;
        evaluate(x, y, z, self, mass, kind);
        overlay = false;
        return newMass != mass || newKind != kind || pushes != 0;
    }
}
