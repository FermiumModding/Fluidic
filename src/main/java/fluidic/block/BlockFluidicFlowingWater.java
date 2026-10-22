package fluidic.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockDynamicLiquid;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

import javax.annotation.Nonnull;
import java.util.Random;

/**
 * Replaces vanilla flowing water, registered as minecraft:flowing_water. Vanilla places flowing water when a
 * bucket is emptied or a spring generates. Flowing is up to the simulation, so this turns itself into still
 * water as soon as anything happens to it, which hands it to the simulation.
 */
public class BlockFluidicFlowingWater extends BlockDynamicLiquid {

    public BlockFluidicFlowingWater() {
        super(Material.WATER);
        setHardness(100.0F);
        setLightOpacity(3);
        setTranslationKey("water");
        disableStats();
        setTickRandomly(true);
        setRegistryName("minecraft", "flowing_water");
    }

    private static void settle(World world, BlockPos pos, IBlockState state) {
        if(!world.isRemote) world.setBlockState(pos, FluidicWater.state(FluidicWater.level(state)), 3);
    }

    @Override
    public void onBlockAdded(@Nonnull World world, @Nonnull BlockPos pos, @Nonnull IBlockState state) {
        settle(world, pos, state);
    }

    @Override
    public void neighborChanged(@Nonnull IBlockState state, @Nonnull World world, @Nonnull BlockPos pos, @Nonnull Block block, @Nonnull BlockPos fromPos) {
        settle(world, pos, state);
    }

    @Override
    public void updateTick(@Nonnull World world, @Nonnull BlockPos pos, @Nonnull IBlockState state, @Nonnull Random rand) {
        settle(world, pos, state);
    }

    @Override
    public void randomTick(@Nonnull World world, @Nonnull BlockPos pos, @Nonnull IBlockState state, @Nonnull Random rand) {
        settle(world, pos, state);
    }

    @Override
    @Nonnull
    protected Vec3d getFlow(@Nonnull IBlockAccess world, @Nonnull BlockPos pos, @Nonnull IBlockState state) {
        return Vec3d.ZERO;
    }
}
