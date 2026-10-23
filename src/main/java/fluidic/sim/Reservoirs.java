package fluidic.sim;

import fluidic.Fluidic;
import fluidic.block.FluidicWater;
import fluidic.handlers.ForgeConfigHandler;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.common.BiomeDictionary;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Finds the bodies of water that are endless oceans, rivers and swamps. Biomes alone do not tell: oceans and rivers run
 * past the edge of their biome, under beaches and into other biomes, and pools in caves below sea level sit in
 * ocean biomes without being part of the sea. A body of water is an ocean or river when its surface at sea level
 * is large and reaches into an ocean, river, beach or swamp biome. Server thread only.
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
    /** Most surface blocks searched for a reservoir biome, a huge lake that never reaches one is not one */
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
    /** The configured reservoir biomes, parsed on first use */
    private static volatile Set<Biome> biomes;
    /** Counts config changes, remembered answers from before one are forgotten */
    private static volatile int generation;

    private Reservoirs() {}

    /** The config changed, maybe on another thread: biomes are parsed again and every answer is looked up again */
    public static void reload() {
        biomes = null;
        generation++;
    }

    /** Whether large bodies of water in a biome are endless, see the Reservoir Biomes config */
    public static boolean isReservoirBiome(Biome biome) {
        Set<Biome> parsed = biomes;
        if(parsed == null) biomes = parsed = parseBiomes(ForgeConfigHandler.server.reservoirBiomes);
        return parsed.contains(biome);
    }

    /** Entries are biome ids like minecraft:swampland, or biome dictionary tags like OCEAN */
    private static Set<Biome> parseBiomes(String[] entries) {
        Set<Biome> parsed = Collections.newSetFromMap(new IdentityHashMap<>());
        for(String raw : entries) {
            String entry = raw.trim();
            if(entry.isEmpty()) continue;
            if(entry.indexOf(':') >= 0) {
                Biome biome = ForgeRegistries.BIOMES.getValue(new ResourceLocation(entry));
                if(biome != null) parsed.add(biome);
                else Fluidic.LOGGER.warn("Reservoir Biomes: no biome " + entry);
                continue;
            }
            BiomeDictionary.Type tag = null;
            for(BiomeDictionary.Type type : BiomeDictionary.Type.getAll()) {
                if(type.getName().equalsIgnoreCase(entry)) tag = type;
            }
            if(tag != null) parsed.addAll(BiomeDictionary.getBiomes(tag));
            else Fluidic.LOGGER.warn("Reservoir Biomes: no biome tag " + entry + ", biome ids need their mod id, like minecraft:" + entry.toLowerCase());
        }
        return parsed;
    }

    /** The remembered answers of a world, forgotten when the config changed since */
    private static Memory memory(World world) {
        Memory remembered = REMEMBERED.computeIfAbsent(world, w -> new Memory());
        int current = generation;
        if(remembered.generation != current) {
            remembered.answers.clear();
            remembered.changes.clear();
            remembered.generation = current;
        }
        return remembered;
    }

    /** Whether the water block at a position, full and below sea level, belongs to an ocean or river */
    public static boolean isReservoir(World world, BlockPos pos) {
        // Without searching, the biome alone decides
        if(!ForgeConfigHandler.server.searchWaterBodies) return isReservoirBiome(world.getBiome(pos));
        Memory remembered = memory(world);
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
        if(!ForgeConfigHandler.server.searchWaterBodies) {
            Biome biome = world.getBiome(pos);
            return String.format("endless %s, by biome alone (connected water bodies are not searched), biome %s is %sa reservoir biome",
                    isReservoirBiome(biome) ? "yes" : "no", biome.getRegistryName(), isReservoirBiome(biome) ? "" : "not ");
        }
        long known = memory(world).known(pos.toLong(), world.getTotalWorldTime());
        String cached = known >= 0L ? ((known & 1L) != 0 ? "yes" : "no") : "not looked up";
        Search search = new Search(world, new Memory(), world.getTotalWorldTime());
        boolean reservoir = search.search(pos);
        String surface = search.surfaceFound == NONE ? "none" : "y" + BlockPos.fromLong(search.surfaceFound).getY();
        BlockPos top = pos;
        while(top.getY() < world.getSeaLevel() - 1 && FluidicWater.isWater(world.getBlockState(top.up()))) top = top.up();
        String details = String.format(", sea level %d, water top y%d, above it %s with sky light %d",
                world.getSeaLevel(), top.getY(), world.getBlockState(top.up()).getBlock().getRegistryName(), world.getLightFor(EnumSkyBlock.SKY, top.up()));
        return String.format("endless %s (remembered %s), surface %s, %d surface columns searched, reservoir biome %s%s",
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
        // Ice freezing on the sea and melting again, Frost Walker on every step, leaves the sea as it was
        Material material = other.getMaterial();
        if(material == Material.AIR || material == Material.ICE || material == Material.PACKED_ICE) return;
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
        /** The config generation the answers were found under */
        private int generation = Reservoirs.generation;

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
        /** Every water block looked at, they all share the answer */
        private final LongArrayList seen = new LongArrayList(256);
        /**
         * Every block looked at, water or not, so none is read twice: a bit per block in 16x16x16 sections. A search
         * stays in a section for many blocks, most checks are a bit test without a hash lookup.
         */
        private final Long2ObjectOpenHashMap<long[]> visited = new Long2ObjectOpenHashMap<>();
        private long visitedKey = Long.MIN_VALUE;
        private long[] visitedBits;
        private final BlockPos.MutableBlockPos cell = new BlockPos.MutableBlockPos();
        /**
         * The chunk blocks were last read from, null when it is not loaded. Blocks are read from their chunk directly:
         * through the world, every block costs a lookup of its chunk to check it is loaded and another to read it.
         */
        private Chunk chunk;
        private int chunkX = Integer.MIN_VALUE, chunkZ = Integer.MIN_VALUE;
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
            int x = pos.getX(), z = pos.getZ();
            visit(pos.toLong());
            long surface = NONE;
            // Most water below sea level is open water, straight up is the fastest way to its surface
            int top = pos.getY();
            while(top < surfaceY) {
                long above = pack(x, top + 1, z);
                if(remembered.isKnownReservoir(above, now)) return true;
                if(!isWater(x, top + 1, z)) break;
                visit(above);
                top++;
            }
            if(isSurface(x, top, z)) surface = pack(x, top, z);
            if(surface == NONE) surface = findSurface(pos.toLong());
            surfaceFound = surface;
            // Water that never reaches the surface is a pool in a cave
            return surface != NONE && searchSurface(surface);
        }

        /** The loaded chunk a block is in, null if it is not loaded */
        private Chunk chunk(int x, int z) {
            int cx = x >> 4, cz = z >> 4;
            if(cx != chunkX || cz != chunkZ) {
                chunkX = cx;
                chunkZ = cz;
                chunk = world.getChunkProvider().getLoadedChunk(cx, cz);
            }
            return chunk;
        }

        /** Whether a block in a loaded chunk is water, false when it is not loaded */
        private boolean isWater(int x, int y, int z) {
            Chunk at = chunk(x, z);
            return at != null && FluidicWater.isWater(at.getBlockState(x, y, z));
        }

        /**
         * Top of a body of water. Usually at sea level, but a river that lost some water still counts while it lies
         * open to the sky a little below it, so it fills up again. Water under a cave roof does not count, or a lake
         * in a cave below an ocean would fill up to sea level.
         */
        private boolean isSurface(int x, int y, int z) {
            if(y == surfaceY) return true;
            if(y < surfaceY - MAX_LOWERED) return false;
            return !isWater(x, y + 1, z) && FluidicWater.isUnderSky(world, cell.setPos(x, y + 1, z));
        }

        /** Same as BlockPos.toLong, without making a BlockPos */
        private static long pack(int x, int y, int z) {
            return ((long)x & 0x3FFFFFFL) << 38 | ((long)y & 0xFFFL) << 26 | ((long)z & 0x3FFFFFFL);
        }

        private static int unpackX(long packed) {
            return (int)(packed >> 38);
        }

        private static int unpackY(long packed) {
            return (int)(packed << 26 >> 52);
        }

        private static int unpackZ(long packed) {
            return (int)(packed << 38 >> 38);
        }

        /**
         * Spreads through the water below sea level until it reaches the surface layer, NONE if it does not. The
         * highest water is followed first: water under a block placed deep in the sea goes around it and straight up,
         * spreading evenly would fill a ball of water around it and stop long before the surface.
         */
        private long findSurface(long start) {
            LongArrayList[] levels = new LongArrayList[surfaceY + 1];
            int top = unpackY(start);
            levels[top] = new LongArrayList();
            levels[top].add(start);
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
                long at = level.removeLong(level.size() - 1);
                int ax = unpackX(at), ay = unpackY(at), az = unpackZ(at);
                for(EnumFacing facing : FACINGS) {
                    int x = ax + facing.getXOffset(), y = ay + facing.getYOffset(), z = az + facing.getZOffset();
                    long next = water(x, y, z);
                    if(next == NONE) continue;
                    if(isSurface(x, y, z)) return next;
                    if(levels[y] == null) levels[y] = new LongArrayList();
                    levels[y].add(next);
                    top = Math.max(top, y);
                }
            }
            // Every connected block below the surface layer was searched, the water is shut off from it
            return NONE;
        }

        /**
         * Spreads over the surface looking for a reservoir biome, a column at a time. The surface need not be
         * flat: a river that is losing water has thin blocks left on top here and there, so the top of each column
         * is followed up and down from the one beside it.
         */
        private boolean searchSurface(long start) {
            LongArrayList queue = new LongArrayList(MIN_SURFACE * 2);
            LongOpenHashSet columns = new LongOpenHashSet(MIN_SURFACE * 4);
            columns.add(column(unpackX(start), unpackZ(start)));
            visit(start);
            queue.add(start);
            boolean biome = false;
            for(int n = 0; n < queue.size(); n++) {
                long packed = queue.getLong(n);
                if(remembered.isKnownReservoir(packed, now)) return true;
                int ax = unpackX(packed), ay = unpackY(packed), az = unpackZ(packed);
                if(!biome) {
                    Chunk at = chunk(ax, az);
                    biome = at != null && isReservoirBiome(at.getBiome(cell.setPos(ax, ay, az), world.getBiomeProvider()));
                }
                columnsSearched = queue.size();
                biomeFound = biome;
                if(biome && queue.size() >= MIN_SURFACE) return true;
                if(queue.size() >= MAX_SURFACE_SEARCH) return false;
                for(EnumFacing facing : EnumFacing.HORIZONTALS) {
                    int x = ax + facing.getXOffset(), z = az + facing.getZOffset();
                    if(!columns.add(column(x, z))) continue;
                    long top = topBeside(x, ay, z);
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
            Chunk at = chunk(x, z);
            if(at == null) {
                cutShort = true;
                return NONE;
            }
            // The column is in one chunk
            if(FluidicWater.isWater(at.getBlockState(x, y, z))) {
                // Rises to the top of the water here
                while(y < surfaceY && FluidicWater.isWater(at.getBlockState(x, y + 1, z))) y++;
                return pack(x, y, z);
            }
            // Lower here, the surface steps down where thin water on top ran out
            for(int down = 0; down < MAX_STEP_DOWN && y > 0; down++) {
                if(at.getBlockState(x, y, z).getMaterial().blocksMovement()) return NONE;
                y--;
                if(FluidicWater.isWater(at.getBlockState(x, y, z))) return y < surfaceY - MAX_LOWERED ? NONE : pack(x, y, z);
            }
            return NONE;
        }

        private static long column(int x, int z) {
            return ((long)x << 32) | (z & 0xFFFFFFFFL);
        }

        /**
         * The water block at a position not looked at yet, at or below the surface layer, or NONE. Blocks that are not
         * water are marked looked at too, they are not read again.
         */
        private long water(int x, int y, int z) {
            if(y < 0 || y > surfaceY) return NONE;
            if(!markVisited(x, y, z)) return NONE;
            long next = pack(x, y, z);
            Chunk at = chunk(x, z);
            if(at == null) {
                cutShort = true;
                return NONE;
            }
            if(!FluidicWater.isWater(at.getBlockState(x, y, z))) return NONE;
            seen.add(next);
            return next;
        }

        private void visit(long packed) {
            if(markVisited(unpackX(packed), unpackY(packed), unpackZ(packed))) seen.add(packed);
        }

        /** Marks a block looked at, false when it was already */
        private boolean markVisited(int x, int y, int z) {
            long key = Water.sectionKey(x >> 4, y >> 4, z >> 4);
            long[] bits = visitedBits;
            if(key != visitedKey) {
                bits = visited.get(key);
                if(bits == null) visited.put(key, bits = new long[64]);
                visitedKey = key;
                visitedBits = bits;
            }
            int i = Water.cellIndex(x & 15, y & 15, z & 15);
            long mask = 1L << (i & 63);
            if((bits[i >> 6] & mask) != 0) return false;
            bits[i >> 6] |= mask;
            return true;
        }
    }
}
