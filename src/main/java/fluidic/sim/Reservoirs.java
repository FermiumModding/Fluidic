package fluidic.sim;

import fluidic.block.FluidicWater;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Finds the bodies of water that are endless oceans and rivers. Biomes alone do not tell: oceans and rivers run
 * past the edge of their biome, under beaches and into other biomes, and pools in caves below sea level sit in
 * ocean biomes without being part of the sea. A body of water is an ocean or river when its surface at sea level
 * is large and reaches into an ocean, river or beach biome. Server thread only.
 *
 * The search first finds the body's surface, straight up or around through the water below it, then spreads over
 * the surface. Spreading through the whole body instead would run out long before reaching the river in a wide,
 * deep lake beside one.
 */
public final class Reservoirs {

    /** Fewest connected columns of surface of an ocean or river, a smaller pond at sea level is just a pond */
    private static final int MIN_SURFACE = 128;
    /** Most water blocks searched below the surface to reach it, water further down in a cave is not the sea */
    private static final int MAX_DEPTH_SEARCH = 4096;
    /** Blocks below sea level the surface of an ocean or river may have sunk and still count */
    private static final int MAX_LOWERED = 8;
    /** Blocks the surface may step down from one column to the next */
    private static final int MAX_STEP_DOWN = 2;
    /** Most surface blocks searched for an ocean or river biome, a huge lake that never reaches one is not one */
    private static final int MAX_SURFACE_SEARCH = 65536;
    /** Ticks an answer is remembered, bodies of water rarely join or split */
    private static final long REMEMBER_TICKS = 2400L;
    /** Ticks a no is remembered when the search ran into unloaded chunks */
    private static final long RECHECK_TICKS = 200L;
    /** Answers remembered at most, past this old ones are forgotten, all of them if that is not enough */
    private static final int MAX_REMEMBERED = 1 << 20;
    /** Chunks around a changed block whose answers are looked up again, damming a river can change them */
    private static final int CHANGE_RADIUS = 2;

    /** No block position. Packed positions west of x 0 are negative, so -1 cannot stand for none */
    private static final long NONE = Long.MIN_VALUE;
    private static final EnumFacing[] FACINGS = EnumFacing.values();
    private static final Map<World, Memory> REMEMBERED = new WeakHashMap<>();

    private Reservoirs() {}

    /** Whether the water block at a position, full and below sea level, belongs to an ocean or river */
    public static boolean isReservoir(World world, BlockPos pos) {
        Memory remembered = REMEMBERED.computeIfAbsent(world, w -> new Memory());
        long now = world.getTotalWorldTime();
        long known = remembered.known(pos.toLong(), now);
        if(known >= 0L) return (known & 1L) != 0;
        return new Search(world, remembered, now).run(pos);
    }

    /**
     * Why a water block is or is not part of an ocean or river, for /fluidic inspect. Searches afresh without
     * remembering the answer.
     */
    public static String describe(World world, BlockPos pos) {
        Memory remembered = REMEMBERED.get(world);
        long known = remembered == null ? -1L : remembered.known(pos.toLong(), world.getTotalWorldTime());
        String cached = known >= 0L ? ((known & 1L) != 0 ? "yes" : "no") : "not looked up";        Search search = new Search(world, new Memory(), world.getTotalWorldTime());
        boolean reservoir = search.search(pos);
        String surface = search.surfaceFound == NONE ? "none" : "y" + BlockPos.fromLong(search.surfaceFound).getY();
        BlockPos top = pos;
        while(top.getY() < world.getSeaLevel() - 1 && FluidicWater.isWater(world.getBlockState(top.up()))) top = top.up();
        String details = String.format(", sea level %d, water top y%d, above it %s with sky light %d",
                world.getSeaLevel(), top.getY(), world.getBlockState(top.up()).getBlock().getRegistryName(), world.getLightFor(EnumSkyBlock.SKY, top.up()));
        return String.format("endless %s (remembered %s), surface %s, %d surface columns searched, ocean or river biome %s%s",
                reservoir ? "yes" : "no", cached, surface, search.columnsSearched, search.biomeFound ? "found" : "not found",
                search.cutShort ? ", ran into unloaded chunks" : "") + details;
    }

    /**
     * A block took the place of water below sea level, or water of a block. That may dam off part of the sea or
     * join a pool to it, so answers around it are looked up again. Further away they are only looked up again once
     * they are old, clearing every answer of the world for each block placed in the sea would search it all over.
     * The simulation turning water into air and back does not count, it does that all the time.
     */
    public static void blockChanged(World world, BlockPos pos, IBlockState oldState, IBlockState newState) {
        if(pos.getY() >= world.getSeaLevel()) return;
        boolean wasWater = FluidicWater.isWater(oldState), isWater = FluidicWater.isWater(newState);
        if(wasWater == isWater) return;
        IBlockState other = wasWater ? newState : oldState;
        if(other.getMaterial() == Material.AIR) return;
        Memory remembered = REMEMBERED.get(world);
        if(remembered != null) remembered.changed(pos.getX() >> 4, pos.getZ() >> 4, world.getTotalWorldTime());
    }

    /**
     * Answers per world: block position to the tick of the answer << 1 | whether it is an ocean or river, and the
     * ticks blocks last changed around chunks, answers from before that are stale.
     */
    private static final class Memory {

        private final Long2LongOpenHashMap answers = new Long2LongOpenHashMap();
        private final Long2LongOpenHashMap changes = new Long2LongOpenHashMap();

        Memory() {
            answers.defaultReturnValue(-1L);
            changes.defaultReturnValue(Long.MIN_VALUE);
        }

        /** The answer remembered for a block position, -1 when there is none or it is stale */
        long known(long pos, long now) {
            long known = answers.get(pos);
            if(known < 0L) return -1L;
            long tick = known >> 1;
            if(now - tick >= REMEMBER_TICKS) return -1L;
            // Packed block positions keep x in the top 26 bits and z in the bottom 26
            int cx = (int)(pos >> 38) >> 4, cz = (int)(pos << 38 >> 38) >> 4;
            return tick <= changes.get(ChunkPos.asLong(cx, cz)) ? -1L : known;
        }

        /** Only a yes is taken over from earlier searches, a no may come from one cut short by unloaded chunks */
        boolean isKnownReservoir(long pos, long now) {
            long known = known(pos, now);
            return known >= 0L && (known & 1L) != 0;
        }

        void remember(LongArrayList positions, long value, long now) {
            if(answers.size() + positions.size() > MAX_REMEMBERED) forgetOld(answers, 1, now);
            if(answers.size() + positions.size() > MAX_REMEMBERED) answers.clear();
            for(int n = 0; n < positions.size(); n++) answers.put(positions.getLong(n), value);
        }

        void changed(int cx, int cz, long now) {
            if(changes.size() > 4096) forgetOld(changes, 0, now);
            for(int dx = -CHANGE_RADIUS; dx <= CHANGE_RADIUS; dx++) {
                for(int dz = -CHANGE_RADIUS; dz <= CHANGE_RADIUS; dz++) changes.put(ChunkPos.asLong(cx + dx, cz + dz), now);
            }
        }

        /** Drops entries too old to matter, their values hold the tick shifted left by the given bits */
        private static void forgetOld(Long2LongOpenHashMap map, int shift, long now) {
            for(ObjectIterator<Long2LongMap.Entry> it = map.long2LongEntrySet().fastIterator(); it.hasNext(); ) {
                if(now - (it.next().getLongValue() >> shift) >= REMEMBER_TICKS) it.remove();
            }
        }
    }

    private static final class Search {

        private final World world;
        private final Memory remembered;
        private final long now;
        private final int surfaceY;
        /** Every block looked at, they all share the answer */
        private final LongArrayList seen = new LongArrayList();
        private final LongOpenHashSet visited = new LongOpenHashSet();
        private final BlockPos.MutableBlockPos cell = new BlockPos.MutableBlockPos();
        /** Ran into unloaded chunks, a no may be wrong */
        private boolean cutShort;
        /** What the search found, for {@link #describe} */
        private long surfaceFound = NONE;
        private int columnsSearched;
        private boolean biomeFound;

        Search(World world, Memory remembered, long now) {
            this.world = world;
            this.remembered = remembered;
            this.now = now;
            this.surfaceY = world.getSeaLevel() - 1;
        }

        boolean run(BlockPos pos) {
            boolean reservoir = search(pos);
            if(!reservoir && cutShort) {
                // Not knowing what lies in unloaded chunks or past where the search stopped, a no is only kept a little
                // while, and only for this block. The blocks it passed may well reach the surface on their own, water
                // around a hole dug deep in the sea would otherwise all count as cut off from it and wake up.
                LongArrayList start = new LongArrayList();
                start.add(pos.toLong());
                remembered.remember(start, ((now - REMEMBER_TICKS + RECHECK_TICKS) << 1), now);
                return false;
            }
            remembered.remember(seen, (now << 1) | (reservoir ? 1L : 0L), now);
            return reservoir;
        }

        private boolean search(BlockPos pos) {
            visit(pos.toLong());
            long surface = NONE;
            // Most water below sea level is open water, straight up is the fastest way to its surface
            int top = pos.getY();
            while(top < surfaceY) {
                cell.setPos(pos.getX(), top + 1, pos.getZ());
                if(remembered.isKnownReservoir(cell.toLong(), now)) return true;
                if(!FluidicWater.isWater(world.getBlockState(cell))) break;
                visit(cell.toLong());
                top++;
            }
            BlockPos column = new BlockPos(pos.getX(), top, pos.getZ());
            if(isSurface(column)) surface = column.toLong();
            if(surface == NONE) surface = findSurface(pos);
            surfaceFound = surface;
            // Water that never reaches the surface is a pool in a cave
            return surface != NONE && searchSurface(surface);
        }

        /**
         * Top of a body of water. Usually at sea level, but a river that lost some water still counts while it lies
         * open to the sky a little below it, so it fills up again. Water under a cave roof does not count, or a lake
         * in a cave below an ocean would fill up to sea level.
         */
        private boolean isSurface(BlockPos at) {
            if(at.getY() == surfaceY) return true;
            if(at.getY() < surfaceY - MAX_LOWERED) return false;
            return !FluidicWater.isWater(world.getBlockState(at.up())) && FluidicWater.isUnderSky(world, at.up());
        }

        /**
         * Spreads through the water below sea level until it reaches the surface layer, NONE if it does not. The
         * highest water is followed first: water under a block placed deep in the sea goes around it and straight up,
         * spreading evenly would fill a ball of water around it and stop long before the surface.
         */
        private long findSurface(BlockPos pos) {
            LongArrayList[] levels = new LongArrayList[surfaceY + 1];
            int top = pos.getY();
            levels[top] = new LongArrayList();
            levels[top].add(pos.toLong());
            int searched = 0;
            while(top >= 0) {
                LongArrayList level = levels[top];
                if(level == null || level.isEmpty()) {
                    top--;
                    continue;
                }
                if(++searched > MAX_DEPTH_SEARCH) {
                    cutShort = true;
                    return NONE;
                }
                BlockPos at = BlockPos.fromLong(level.removeLong(level.size() - 1));
                for(EnumFacing facing : FACINGS) {
                    long next = water(at, facing);
                    if(next == NONE) continue;
                    BlockPos found = BlockPos.fromLong(next);
                    if(isSurface(found)) return next;
                    int y = found.getY();
                    if(levels[y] == null) levels[y] = new LongArrayList();
                    levels[y].add(next);
                    top = Math.max(top, y);
                }
            }
            // Every connected block below the surface layer was searched, the water is shut off from it
            return NONE;
        }

        /**
         * Spreads over the surface looking for an ocean or river biome, a column at a time. The surface need not be
         * flat: a river that is losing water has thin blocks left on top here and there, so the top of each column
         * is followed up and down from the one beside it.
         */
        private boolean searchSurface(long start) {
            LongArrayList queue = new LongArrayList();
            LongOpenHashSet columns = new LongOpenHashSet();
            BlockPos first = BlockPos.fromLong(start);
            columns.add(column(first.getX(), first.getZ()));
            visit(start);
            queue.add(start);
            boolean biome = false;
            for(int n = 0; n < queue.size(); n++) {
                long packed = queue.getLong(n);
                if(remembered.isKnownReservoir(packed, now)) return true;
                BlockPos at = BlockPos.fromLong(packed);
                if(!biome && FluidicWater.isReservoirBiome(world.getBiome(at))) biome = true;
                columnsSearched = queue.size();
                biomeFound = biome;
                if(biome && queue.size() >= MIN_SURFACE) return true;
                if(queue.size() >= MAX_SURFACE_SEARCH) return false;
                for(EnumFacing facing : EnumFacing.HORIZONTALS) {
                    int x = at.getX() + facing.getXOffset(), z = at.getZ() + facing.getZOffset();
                    if(!columns.add(column(x, z))) continue;
                    long top = topBeside(x, at.getY(), z);
                    if(top != NONE) {
                        visit(top);
                        queue.add(top);
                    }
                }
            }
            return false;
        }

        /** Top of the water in a column next to a surface block at the given height, NONE if there is none near it */
        private long topBeside(int x, int y, int z) {
            cell.setPos(x, y, z);
            if(!world.isBlockLoaded(cell)) {
                cutShort = true;
                return NONE;
            }
            if(FluidicWater.isWater(world.getBlockState(cell))) {
                // Rises to the top of the water here
                while(cell.getY() < surfaceY && FluidicWater.isWater(world.getBlockState(cell.up()))) cell.setY(cell.getY() + 1);
                return cell.toLong();
            }
            // Lower here, the surface steps down where thin water on top ran out
            for(int down = 0; down < MAX_STEP_DOWN && cell.getY() > 0; down++) {
                if(world.getBlockState(cell).getMaterial().blocksMovement()) return NONE;
                cell.setY(cell.getY() - 1);
                if(FluidicWater.isWater(world.getBlockState(cell))) return cell.getY() < surfaceY - MAX_LOWERED ? NONE : cell.toLong();
            }
            return NONE;
        }

        private static long column(int x, int z) {
            return ((long)x << 32) | (z & 0xFFFFFFFFL);
        }

        /** The unvisited water block beside a block, at or below the surface layer, marked visited, or NONE */
        private long water(BlockPos at, EnumFacing facing) {
            cell.setPos(at.getX() + facing.getXOffset(), at.getY() + facing.getYOffset(), at.getZ() + facing.getZOffset());
            if(cell.getY() < 0 || cell.getY() > surfaceY) return NONE;
            long next = cell.toLong();
            if(visited.contains(next)) return NONE;
            if(!world.isBlockLoaded(cell)) {
                cutShort = true;
                return NONE;
            }
            if(!FluidicWater.isWater(world.getBlockState(cell))) return NONE;
            visit(next);
            return next;
        }

        private void visit(long packed) {
            if(visited.add(packed)) seen.add(packed);
        }
    }
}
