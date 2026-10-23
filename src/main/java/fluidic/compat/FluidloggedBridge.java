package fluidic.compat;

import fluidic.block.FluidicWater;
import git.jbredwards.fluidlogged_api.api.util.FluidState;
import git.jbredwards.fluidlogged_api.api.util.FluidloggedUtils;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.fluids.FluidRegistry;

/** The calls into Fluidlogged API, only loaded when it is installed, see {@link FluidloggedCompat} */
final class FluidloggedBridge {

    private FluidloggedBridge() {}

    static int waterLevel(IBlockAccess world, BlockPos pos) {
        return level(FluidState.get(world, pos));
    }

    static int waterLevel(Chunk chunk, BlockPos pos) {
        return level(FluidState.getFromProvider(chunk, pos));
    }

    private static int level(FluidState state) {
        return state.isEmpty() || !FluidicWater.isWater(state.getState()) ? 0 : FluidicWater.level(state.getState());
    }

    static boolean canStoreWater(World world, BlockPos pos, IBlockState state) {
        return FluidloggedUtils.isStateFluidloggable(state, world, pos, FluidRegistry.WATER);
    }

    static void setWater(World world, BlockPos pos, IBlockState here, int level, int flags) {
        FluidState fluid = level <= 0 ? FluidState.EMPTY : FluidState.of(FluidicWater.state(level));
        boolean was = FluidicWater.placing;
        FluidicWater.placing = true;
        try {
            FluidloggedUtils.setFluidState(world, pos, here, fluid, false, flags);
        }
        finally {
            FluidicWater.placing = was;
        }
    }
}
