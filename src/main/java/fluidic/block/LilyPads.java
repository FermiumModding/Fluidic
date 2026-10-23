package fluidic.block;

import fluidic.sim.SimulatedWater;
import fluidic.sim.Water;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Lily pads float on the water's surface wherever it is. Vanilla only lets them sit on full water and breaks them as
 * soon as it changes, Fluidic water rises and falls an eighth at a time, so they move with it instead.
 */
public final class LilyPads {

    /** Blocks a lily pad sinks with water that went down */
    private static final int MAX_SINK = 4;

    private LilyPads() {}

    /** Whether water at a position carries a lily pad on top of it: more than half a block of it */
    public static boolean floats(World world, BlockPos water) {
        IBlockState state = world.getBlockState(water);
        if(FluidicWater.isWater(state)) return FluidicWater.level(state) > Water.LEVELS / 2;
        return state.getMaterial().isReplaceable() && SimulatedWater.height(world, water.getX(), water.getY(), water.getZ()) > 127;
    }

    /**
     * Moves a lily pad that lost its footing to the water's surface: up when water rose over it, down when the water
     * under it went down. Returns false when there is no water near enough, then it breaks as in vanilla.
     */
    public static boolean follow(World world, BlockPos pos, IBlockState pad) {
        // Water rose into the pad's block, it floats up onto it
        if(floats(world, pos)) return floatUp(world, pos, pad);
        for(int down = 1; down <= MAX_SINK; down++) {
            BlockPos at = pos.down(down);
            if(!world.isAirBlock(at) || floats(world, at)) return false;
            if(floats(world, at.down())) {
                move(world, pos, at, pad);
                return true;
            }
        }
        return false;
    }

    /** Moves a lily pad up a block onto water that rose into its block, false when something is in the way */
    public static boolean floatUp(World world, BlockPos pos, IBlockState pad) {
        BlockPos up = pos.up();
        if(!world.isAirBlock(up) || floats(world, up)) return false;
        move(world, pos, up, pad);
        return true;
    }

    private static void move(World world, BlockPos from, BlockPos to, IBlockState pad) {
        world.setBlockState(from, Blocks.AIR.getDefaultState(), 2);
        world.setBlockState(to, pad, 3);
    }
}
