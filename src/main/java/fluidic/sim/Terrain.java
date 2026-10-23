package fluidic.sim;

import fluidic.Fluidic;
import fluidic.handlers.ForgeConfigHandler;
import fluidic.block.BlockDrain;
import fluidic.block.FluidicWater;
import net.minecraft.block.Block;
import net.minecraft.block.BlockConcretePowder;
import net.minecraft.block.BlockSponge;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraftforge.common.IPlantable;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Converts world blocks into the terrain codes the simulation works on. Blocks listed in the Block Classifications
 * config use their listed class, other blocks are classified from their material. Results are cached per block
 * state, since this runs for every block change and every terrain snapshot.
 */
public final class Terrain {

    /** Facings in simulation direction order */
    private static final EnumFacing[] FACINGS = {EnumFacing.DOWN, EnumFacing.UP, EnumFacing.NORTH, EnumFacing.SOUTH, EnumFacing.WEST, EnumFacing.EAST};

    /** Class names usable in the config */
    private static final Map<String, Byte> CLASSES = new HashMap<>();
    static {
        CLASSES.put("open", WaterSection.OPEN);
        CLASSES.put("washable", WaterSection.WASHABLE);
        CLASSES.put("permeable", WaterSection.PERMEABLE);
        CLASSES.put("waterloggable", WaterSection.WATERLOGGABLE);
        CLASSES.put("porous", WaterSection.POROUS);
        CLASSES.put("solid", WaterSection.SOLID);
        CLASSES.put("rough", WaterSection.SOLID_ROUGH);
        CLASSES.put("smooth", WaterSection.SOLID_SMOOTH);
        CLASSES.put("reactive", WaterSection.SOLID_REACTIVE);
        CLASSES.put("sink", WaterSection.SINK);
    }

    /** Index in a rule array for entries without metadata */
    private static final int ANY_META = 16;
    private static final byte UNSET = -1;

    /** Parsed config, per block a class per metadata plus one for any metadata, built on first use */
    private static volatile Map<Block, byte[]> rules;
    /** Final class per block state, states are unique objects so they can be keys */
    private static final ConcurrentHashMap<IBlockState, Byte> CACHE = new ConcurrentHashMap<>();

    private Terrain() {}

    /** Drops the parsed config and cached classes, the next lookup parses the config again */
    public static void reload() {
        rules = null;
        CACHE.clear();
    }

    /** Terrain code of a block state, without the position, so reservoir water counts as plain settled water */
    public static byte classify(IBlockState state) {
        if(FluidicWater.isWater(state)) return (byte)(WaterSection.SETTLED + FluidicWater.level(state));
        if(state.getBlock() == Blocks.SPONGE && !state.getValue(BlockSponge.WET)) return WaterSection.SPONGE;
        if(state.getBlock() instanceof BlockDrain) return WaterSection.DRAIN;
        Byte cached = CACHE.get(state);
        if(cached != null) return cached;
        byte code = configured(state);
        if(code == UNSET) code = fallback(state);
        CACHE.put(state, code);
        return code;
    }

    /** Terrain code of the block at a position, telling ocean and river reservoirs apart from other water */
    public static byte classify(World world, BlockPos pos, IBlockState state) {
        return FluidicWater.isReservoir(world, pos, state) ? WaterSection.RESERVOIR : classify(state);
    }

    public static boolean isOpen(IBlockState state) {
        return WaterSection.isOpen(classify(state));
    }

    /** Whether simulated water can be inside the block alongside it, like flowers, ladders, stairs and leaves */
    public static boolean holdsWater(IBlockState state) {
        byte code = classify(state);
        return code == WaterSection.WASHABLE || code == WaterSection.PERMEABLE || code == WaterSection.WATERLOGGABLE
                || code == WaterSection.POROUS;
    }

    /** Whether rain falls through the block to what lies below, waterloggable blocks like slabs keep it out */
    public static boolean letsRainThrough(IBlockState state) {
        byte code = classify(state);
        return code == WaterSection.POROUS || WaterSection.isOpen(code) && code != WaterSection.WATERLOGGABLE;
    }

    public static boolean isWashable(IBlockState state) {
        return classify(state) == WaterSection.WASHABLE;
    }

    public static boolean isPorous(IBlockState state) {
        return classify(state) == WaterSection.POROUS;
    }

    public static boolean isSink(IBlockState state) {
        return classify(state) == WaterSection.SINK;
    }

    private static byte configured(IBlockState state) {
        byte[] rule = rules().get(state.getBlock());
        if(rule == null) return UNSET;
        int meta = state.getBlock().getMetaFromState(state);
        return meta >= 0 && meta < ANY_META && rule[meta] != UNSET ? rule[meta] : rule[ANY_META];
    }

    private static Map<Block, byte[]> rules() {
        Map<Block, byte[]> parsed = rules;
        if(parsed == null) {
            synchronized(Terrain.class) {
                parsed = rules;
                if(parsed == null) rules = parsed = parse(ForgeConfigHandler.server.blockClassifications);
            }
        }
        return parsed;
    }

    /** Parses entries like "minecraft:ladder=permeable" or "minecraft:stone:0=smooth" */
    private static Map<Block, byte[]> parse(String[] entries) {
        Map<Block, byte[]> parsed = new IdentityHashMap<>();
        for(String entry : entries) {
            String[] parts = entry.trim().split("=");
            if(parts.length != 2) {
                Fluidic.LOGGER.warn("Ignoring block classification \"{}\", expected block=class", entry);
                continue;
            }
            Byte code = CLASSES.get(parts[1].trim().toLowerCase(Locale.ROOT));
            if(code == null) {
                Fluidic.LOGGER.warn("Ignoring block classification \"{}\", unknown class, use one of {}", entry, CLASSES.keySet());
                continue;
            }
            String[] id = parts[0].trim().split(":");
            int meta = ANY_META;
            if(id.length == 3) {
                try {
                    meta = Integer.parseInt(id[2]);
                }
                catch(NumberFormatException ex) {
                    meta = -1;
                }
                if(meta < 0 || meta > 15) {
                    Fluidic.LOGGER.warn("Ignoring block classification \"{}\", metadata must be 0-15", entry);
                    continue;
                }
            }
            else if(id.length != 2) {
                Fluidic.LOGGER.warn("Ignoring block classification \"{}\", expected modid:block or modid:block:meta", entry);
                continue;
            }
            ResourceLocation name = new ResourceLocation(id[0], id[1]);
            Block block = ForgeRegistries.BLOCKS.containsKey(name) ? ForgeRegistries.BLOCKS.getValue(name) : null;
            if(block == null) {
                // Blocks of mods that are not installed are skipped quietly, defaults may name them
                Fluidic.LOGGER.debug("Skipping block classification \"{}\", no such block", entry);
                continue;
            }
            byte[] rule = parsed.computeIfAbsent(block, b -> {
                byte[] codes = new byte[ANY_META + 1];
                Arrays.fill(codes, UNSET);
                return codes;
            });
            rule[meta] = code;
        }
        return parsed;
    }

    /** Classes blocks that are not in the config from their material */
    private static byte fallback(IBlockState state) {
        Block block = state.getBlock();
        Material material = state.getMaterial();
        if(material == Material.AIR) return WaterSection.OPEN;
        if(material == Material.WATER) return WaterSection.SINK;
        // Lava hardens where water touches it
        if(material == Material.LAVA) return WaterSection.SOLID_REACTIVE;
        if(material.isLiquid() || material == Material.PORTAL || material == Material.STRUCTURE_VOID) return WaterSection.SOLID;
        if(block instanceof BlockConcretePowder) return WaterSection.SOLID_REACTIVE;
        if(material == Material.LEAVES) return WaterSection.POROUS;
        // Tall grass, double plants, snow layers and fire
        if(material.isReplaceable()) return WaterSection.WASHABLE;
        if(!material.blocksMovement()) {
            // Plants and small fixtures like torches and redstone wash away like vanilla water breaks them,
            // anything else water can pass through, like cobwebs, it runs through
            boolean plant = block instanceof IPlantable || material == Material.PLANTS || material == Material.VINE;
            return plant || material == Material.CIRCUITS ? WaterSection.WASHABLE : WaterSection.PERMEABLE;
        }
        return solid(material);
    }

    /** Solid blocks by how much they hold back water running over them */
    private static byte solid(Material material) {
        if(material == Material.GRASS || material == Material.GROUND || material == Material.SAND || material == Material.CLAY
                || material == Material.SNOW || material == Material.CRAFTED_SNOW || material == Material.CLOTH || material == Material.CARPET) {
            return WaterSection.SOLID_ROUGH;
        }
        if(material == Material.ROCK || material == Material.IRON || material == Material.GLASS || material == Material.ICE || material == Material.PACKED_ICE) {
            return WaterSection.SOLID_SMOOTH;
        }
        return WaterSection.SOLID;
    }

    /** Terrain codes of the six neighbours of a block, in simulation direction order */
    static byte[] neighbors(WorldServer world, BlockPos pos) {
        byte[] codes = new byte[6];
        for(int dir = 0; dir < 6; dir++) {
            BlockPos n = pos.offset(FACINGS[dir]);
            codes[dir] = n.getY() < 0 || n.getY() > 255 || !world.isBlockLoaded(n) ? WaterSection.SOLID : classify(world, n, world.getBlockState(n));
        }
        return codes;
    }

    /** Terrain snapshot of a chunk section, null if the chunk is not loaded */
    static byte[] snapshot(WorldServer world, int sx, int sy, int sz) {
        Chunk chunk = world.getChunkProvider().getLoadedChunk(sx, sz);
        if(chunk == null) return null;
        byte[] terrain = new byte[4096];
        ExtendedBlockStorage storage = chunk.getBlockStorageArray()[sy];
        if(storage == Chunk.NULL_BLOCK_STORAGE || storage.isEmpty()) {
            Arrays.fill(terrain, WaterSection.OPEN);
            return terrain;
        }
        // Full water below sea level may be part of an ocean or river
        boolean belowSea = sy * 16 < world.getSeaLevel();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for(int z = 0; z < 16; z++) {
            for(int x = 0; x < 16; x++) {
                // Columns go down from the top, full water right under the sea is the sea too without looking it up
                boolean seaAbove = false;
                for(int y = 15; y >= 0; y--) {
                    byte code = classify(storage.get(x, y, z));
                    if(belowSea && code == WaterSection.SETTLED + Water.LEVELS && sy * 16 + y < world.getSeaLevel()
                            && (seaAbove || Reservoirs.isReservoir(world, pos.setPos(sx * 16 + x, sy * 16 + y, sz * 16 + z)))) {
                        code = WaterSection.RESERVOIR;
                    }
                    seaAbove = code == WaterSection.RESERVOIR;
                    terrain[Water.cellIndex(x, y, z)] = code;
                }
            }
        }
        return terrain;
    }
}
