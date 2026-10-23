package fluidic.sim;

import fluidic.block.FluidicWater;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
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
    /** Answers remembered at most, forgotten all at once past this */
    private static final int MAX_REMEMBERED = 1 << 20;

    /** No block position. Packed positions west of x 0 are negative, so -1 cannot stand for none */
    private static final long NONE = Long.MIN_VALUE;
    private static final EnumFacing[] FACINGS = EnumFacing.values();
    /** Block position to the tick of the answer << 1 | whether it is an ocean or river, per world */
    private static final Map<World, Long2LongOpenHashMap> REMEMBERED = new WeakHashMap<>();

    private Reservoirs() {}

    /** Whether the water block at a position, full and below sea level, belongs to an ocean or river */
    public static boolean isReservoir(World world, BlockPos pos) {
        Long2LongOpenHashMap remembered = REMEMBERED.computeIfAbsent(world, w -> {
            Long2LongOpenHashMap map = new Long2LongOpenHashMap();
            map.defaultReturnValue(-1L);
            return map;
        });
        long now = world.getTotalWorldTime();
        long known = remembered.get(pos.toLong());
        if(isFresh(known, now)) return (known & 1L) != 0;
        return new Search(world, remembered, now).run(pos);
    }

    /**
     * Why a water block is or is not part of an ocean or river, for /fluidic inspect. Searches afresh without
     * remembering the answer.
     */
    public static String describe(World world, BlockPos pos) {
        Long2LongOpenHashMap remembered = REMEMBERED.get(world);
        long known = remembered == null ? -1L : remembered.get(pos.toLong());
        String cached = isFresh(known, world.getTotalWorldTime()) ? ((known & 1L) != 0 ? "yes" : "no") : "not looked up";
        Long2LongOpenHashMap scratch = new Long2LongOpenHashMap();
        scratch.defaultReturnValue(-1L);
        Search search = new Search(world, scratch, world.getTotalWorldTime());
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
     * join a pool to it, so everything is looked up again. The simulation turning water into air and back does
     * not count, it does that all the time.
     */
    public static void blockChanged(World world, BlockPos pos, IBlockState oldState, IBlockState newState) {
        if(pos.getY() >= world.getSeaLevel()) return;
        boolean wasWater = FluidicWater.isWater(oldState), isWater = FluidicWater.isWater(newState);
        if(wasWater == isWater) return;
        IBlockState other = wasWater ? newState : oldState;
        if(other.getMaterial() == Material.AIR) return;
        Long2LongOpenHashMap remembered = REMEMBERED.get(world);
        if(remembered != null) remembered.clear();
    }

    private static boolean isFresh(long known, long now) {
        return known >= 0L && now - (known >> 1) < REMEMBER_TICKS;
    }

    /** Only a yes is taken over from earlier searches, a no may come from one cut short by unloaded chunks */
    private static boolean isKnownReservoir(long known, long now) {
        return isFresh(known, now) && (known & 1L) != 0;
    }

    private static final class Search {

        private final World world;
        private final Long2LongOpenHashMap remembered;
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

        Search(World world, Long2LongOpenHashMap remembered, long now) {
            this.world = world;
            this.remembered = remembered;
            this.now = now;
            this.surfaceY = world.getSeaLevel() - 1;
        }

        boolean run(BlockPos pos) {
            boolean reservoir = search(pos);
            if(remembered.size() > MAX_REMEMBERED) remembered.clear();
            // Not knowing what lies in unloaded chunks, a no is only kept a little while
            long stamp = !reservoir && cutShort ? now - REMEMBER_TICKS + RECHECK_TICKS : now;
            long value = (stamp << 1) | (reservoir ? 1L : 0L);
            for(int n = 0; n < seen.size(); n++) remembered.put(seen.getLong(n), value);
            return reservoir;
        }

        private boolean search(BlockPos pos) {
            visit(pos.toLong());
            long surface = NONE;
            // Most water below sea level is open water, straight up is the fastest way to its surface
            int top = pos.getY();
            while(top < surfaceY) {
                cell.setPos(pos.getX(), top + 1, pos.getZ());
                if(isKnownReservoir(remembered.get(cell.toLong()), now)) return true;
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

        /** Spreads through the water below sea level until it reaches the surface layer, NONE if it does not */
        private long findSurface(BlockPos pos) {
            LongArrayList queue = new LongArrayList();
            queue.add(pos.toLong());
            for(int n = 0; n < queue.size() && queue.size() < MAX_DEPTH_SEARCH; n++) {
                BlockPos at = BlockPos.fromLong(queue.getLong(n));
                for(EnumFacing facing : FACINGS) {
                    long next = water(at, facing);
                    if(next == NONE) continue;
                    if(isSurface(BlockPos.fromLong(next))) return next;
                    queue.add(next);
                }
            }
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
                if(isKnownReservoir(remembered.get(packed), now)) return true;
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
