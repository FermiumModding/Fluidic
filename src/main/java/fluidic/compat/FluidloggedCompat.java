package fluidic.compat;

import fluidic.sim.Terrain;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.fml.common.Loader;

/**
 * Fluidlogged API keeps fluids inside blocks like stairs and fences as fluid states, apart from the block. With it
 * installed, Fluidic stores the water that settles inside such blocks as its fluid states too, instead of keeping it
 * simulated, so both mods see the same water there and it is drawn by its fluid model. Safe to call without it, the
 * bridge holding its classes is only loaded when it is installed.
 */
public final class FluidloggedCompat {

    private static Boolean loaded;

    private FluidloggedCompat() {}

    public static boolean isLoaded() {
        Boolean result = loaded;
        if(result == null) loaded = result = Loader.isModLoaded("fluidlogged_api");
        return result;
    }

    /** Eighths of a block of Fluidic water stored inside the block at a position, 0 for none */
    public static int waterLevel(IBlockAccess world, BlockPos pos) {
        return isLoaded() ? FluidloggedBridge.waterLevel(world, pos) : 0;
    }

    /** Same for a chunk being snapshot for the simulation */
    public static int waterLevel(Chunk chunk, BlockPos pos) {
        return isLoaded() ? FluidloggedBridge.waterLevel(chunk, pos) : 0;
    }

    /**
     * Whether water settling in a block, a block it can be inside like stairs or a ladder, is kept there as a fluid
     * state. Not plants that water washes away.
     */
    public static boolean canStore(World world, BlockPos pos, IBlockState state) {
        return isLoaded() && Terrain.storesWater(state) && FluidloggedBridge.canStoreWater(world, pos, state);
    }

    /** Sets the water stored inside the block at a position, 0 removes it, without reporting it to the simulation */
    public static void setWater(World world, BlockPos pos, IBlockState here, int level, int flags) {
        if(isLoaded()) FluidloggedBridge.setWater(world, pos, here, level, flags);
    }
}
