package fluidic.sim;

import fluidic.handlers.ModRegistry;
import net.minecraft.block.Block;
import net.minecraft.block.BlockBanner;
import net.minecraft.block.BlockConcretePowder;
import net.minecraft.block.BlockDoor;
import net.minecraft.block.BlockLadder;
import net.minecraft.block.BlockReed;
import net.minecraft.block.BlockSign;
import net.minecraft.block.material.Material;
import net.minecraft.init.Blocks;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraftforge.fluids.BlockFluidBase;

import java.util.Arrays;

/**
 * Converts world blocks into the terrain codes the simulation works on. Server thread only.
 */
public final class Terrain {

    /** Facings in simulation direction order */
    private static final EnumFacing[] FACINGS = {EnumFacing.DOWN, EnumFacing.UP, EnumFacing.NORTH, EnumFacing.SOUTH, EnumFacing.WEST, EnumFacing.EAST};

    private Terrain() {}

    public static byte classify(IBlockState state) {
        if(state.getBlock() == ModRegistry.SETTLED_WATER) {
            return (byte)(WaterSection.SETTLED + state.getValue(BlockFluidBase.LEVEL) + 1);
        }
        if(state.getBlock() instanceof BlockConcretePowder) return WaterSection.SOLID_REACTIVE;
        if(isPermeable(state)) return WaterSection.PERMEABLE;
        Material material = state.getMaterial();
        if(material == Material.AIR) return WaterSection.OPEN;
        if(material == Material.WATER) return WaterSection.SINK;
        if(material.isLiquid()) return WaterSection.SOLID;
        // Tall grass, double plants, snow layers and fire wash away like flowers
        if(material.isReplaceable()) return WaterSection.WASHABLE;
        if(material == Material.LEAVES) return WaterSection.POROUS;
        return isWashable(state) ? WaterSection.WASHABLE : solid(material);
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

    public static boolean isOpen(IBlockState state) {
        return WaterSection.isOpen(classify(state));
    }

    /** Thin blocks water runs through without breaking them: ladders, signs, sugar cane and banners */
    public static boolean isPermeable(IBlockState state) {
        Block block = state.getBlock();
        return block instanceof BlockLadder || block instanceof BlockSign || block instanceof BlockReed || block instanceof BlockBanner;
    }

    /** Blocks vanilla water flows through and breaks, like flowers and torches. Same rule as BlockDynamicLiquid. */
    public static boolean isWashable(IBlockState state) {
        Block block = state.getBlock();
        Material material = state.getMaterial();
        if(block instanceof BlockDoor || block == Blocks.STANDING_SIGN || block == Blocks.LADDER || block == Blocks.REEDS) return false;
        if(material == Material.AIR || material == Material.PORTAL || material == Material.STRUCTURE_VOID || material.isLiquid()) return false;
        return !material.blocksMovement();
    }

    public static boolean isSink(IBlockState state) {
        return classify(state) == WaterSection.SINK;
    }

    /** Terrain codes of the six neighbours of a block, in simulation direction order */
    static byte[] neighbors(WorldServer world, BlockPos pos) {
        byte[] codes = new byte[6];
        for(int dir = 0; dir < 6; dir++) {
            BlockPos n = pos.offset(FACINGS[dir]);
            codes[dir] = n.getY() < 0 || n.getY() > 255 || !world.isBlockLoaded(n) ? WaterSection.SOLID : classify(world.getBlockState(n));
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
        for(int y = 0; y < 16; y++) {
            for(int z = 0; z < 16; z++) {
                for(int x = 0; x < 16; x++) {
                    terrain[Water.cellIndex(x, y, z)] = classify(storage.get(x, y, z));
                }
            }
        }
        return terrain;
    }
}
