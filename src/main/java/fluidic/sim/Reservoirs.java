package fluidic.sim;

import fluidic.block.FluidicWater;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Finds the bodies of water that are endless oceans and rivers. Biomes alone do not tell: oceans and rivers run
 * past the edge of their biome, under beaches and into other biomes, and pools in caves below sea level sit in
 * ocean biomes without being part of the sea. A body of water is an ocean or river when it is large and reaches
 * the open surface at sea level in an ocean, river or beach biome. Server thread only.
 */
public final class Reservoirs {

    /** Fewest connected water blocks below sea level that make an ocean or river, smaller pools are just pools */
    private static final int MIN_BODY = 256;
    /** Most water blocks searched for a body, a huge lake that never reaches the sea is not one */
    private static final int MAX_SEARCH = 16384;
    /** Ticks an answer is remembered, bodies of water rarely join or split */
    private static final long REMEMBER_TICKS = 2400L;
    /** Ticks a no is remembered when the search ran into unloaded chunks */
    private static final long RECHECK_TICKS = 200L;
    /** Answers remembered at most, forgotten all at once past this */
    private static final int MAX_REMEMBERED = 1 << 20;

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
        long start = pos.toLong();
        long known = remembered.get(start);
        if(isFresh(known, now)) return (known & 1L) != 0;

        int seaLevel = world.getSeaLevel();
        LongArrayList body = new LongArrayList();
        LongOpenHashSet visited = new LongOpenHashSet();
        body.add(start);
        visited.add(start);
        boolean surface = false;
        Boolean answer = null;
        boolean cutShort = false;
        BlockPos.MutableBlockPos cell = new BlockPos.MutableBlockPos();
        // Most water below sea level is open water, straight up is the fastest way to its surface. Deep in an ocean
        // the search around would run out before reaching it.
        for(int y = pos.getY() + 1; y <= seaLevel; y++) {
            cell.setPos(pos.getX(), y, pos.getZ());
            if(isKnownReservoir(remembered.get(cell.toLong()), now)) {
                answer = true;
                break;
            }
            boolean water = FluidicWater.isWater(world.getBlockState(cell));
            if(y == seaLevel) {
                surface = !water && FluidicWater.isReservoirBiome(world.getBiome(cell.down()));
                break;
            }
            if(!water) break;
        }
        for(int n = 0; n < body.size() && answer == null; n++) {
            long packed = body.getLong(n);
            // Joined an ocean or river looked up already. Water known not to be one is searched again, that answer may
            // come from a search cut short by unloaded chunks, and taking it over would keep it alive for ever.
            if(n > 0 && isKnownReservoir(remembered.get(packed), now)) {
                answer = true;
                break;
            }
            BlockPos at = BlockPos.fromLong(packed);
            if(!surface && at.getY() == seaLevel - 1 && !world.getBlockState(at.up()).getMaterial().isLiquid()
                    && FluidicWater.isReservoirBiome(world.getBiome(at))) {
                surface = true;
            }
            if(surface && body.size() >= MIN_BODY) {
                answer = true;
                break;
            }
            if(body.size() >= MAX_SEARCH) break;
            for(EnumFacing facing : FACINGS) {
                cell.setPos(at.getX() + facing.getXOffset(), at.getY() + facing.getYOffset(), at.getZ() + facing.getZOffset());
                if(cell.getY() < 0 || cell.getY() >= seaLevel) continue;
                if(!world.isBlockLoaded(cell)) {
                    cutShort = true;
                    continue;
                }
                long next = cell.toLong();
                if(visited.contains(next) || !FluidicWater.isWater(world.getBlockState(cell))) continue;
                visited.add(next);
                body.add(next);
            }
        }
        boolean reservoir = answer != null && answer;
        if(remembered.size() > MAX_REMEMBERED) remembered.clear();
        // Not knowing what lies in unloaded chunks, a no is only kept a little while
        long stamp = !reservoir && cutShort ? now - REMEMBER_TICKS + RECHECK_TICKS : now;
        long value = (stamp << 1) | (reservoir ? 1L : 0L);
        for(int n = 0; n < body.size(); n++) remembered.put(body.getLong(n), value);
        return reservoir;
    }

    private static boolean isKnownReservoir(long known, long now) {
        return isFresh(known, now) && (known & 1L) != 0;
    }

    private static boolean isFresh(long known, long now) {
        return known >= 0L && now - (known >> 1) < REMEMBER_TICKS;
    }
}
