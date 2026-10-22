package fluidic.block;

import fluidic.Fluidic;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fluids.BlockFluidFinite;
import net.minecraftforge.fluids.Fluid;

import javax.annotation.Nonnull;
import java.util.Random;

/**
 * The settled water block of earlier Fluidic versions, holding LEVEL + 1 sixteenths of a block. It is only kept so
 * worlds that contain it load; every block is converted to Fluidic water when its chunk loads, see
 * {@link fluidic.handlers.LegacyWaterMigration}, or when anything happens to it.
 */
public class BlockLegacySettledWater extends BlockFluidFinite {

    public BlockLegacySettledWater(Fluid fluid) {
        super(fluid, Material.WATER);
        setQuantaPerBlock(16);
        setTickRandomly(true);
        setLightOpacity(3);
        setHardness(100.0F);
        setRegistryName(Fluidic.MODID, "settled_water");
        setTranslationKey(Fluidic.MODID + ".settled_water");
    }

    /** The Fluidic water block holding the same water, rounded to eighths */
    public static IBlockState convert(IBlockState state) {
        int sixteenths = state.getValue(LEVEL) + 1;
        return FluidicWater.state((sixteenths + 1) / 2);
    }

    public static void convert(World world, BlockPos pos, IBlockState state) {
        if(world.isRemote || !(state.getBlock() instanceof BlockLegacySettledWater)) return;
        FluidicWater.placing = true;
        world.setBlockState(pos, convert(state), 2);
        FluidicWater.placing = false;
    }

    @Override
    public void onBlockAdded(@Nonnull World world, @Nonnull BlockPos pos, @Nonnull IBlockState state) {
        convert(world, pos, state);
    }

    @Override
    public void neighborChanged(@Nonnull IBlockState state, @Nonnull World world, @Nonnull BlockPos pos, @Nonnull Block neighborBlock, @Nonnull BlockPos neighbourPos) {
        convert(world, pos, state);
    }

    @Override
    public void updateTick(@Nonnull World world, @Nonnull BlockPos pos, @Nonnull IBlockState state, @Nonnull Random rand) {
        convert(world, pos, state);
    }
}
