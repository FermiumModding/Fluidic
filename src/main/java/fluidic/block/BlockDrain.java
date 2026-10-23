package fluidic.block;

import fluidic.Fluidic;
import net.minecraft.block.Block;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import javax.annotation.Nonnull;
import java.util.Random;

/**
 * A builder's block that takes water away like a sponge that never fills: water touching it on any side flows into
 * it and is gone, and pools drain into it. The simulation does the work, see WaterSection.DRAIN. In dimensions keeping
 * vanilla water it deletes the water blocks touching it instead, again and again while water keeps flowing in.
 */
public class BlockDrain extends Block {

    /** Ticks between emptying the water around it in dimensions keeping vanilla water */
    private static final int DRAIN_TICKS = 2;

    public BlockDrain() {
        super(Material.ROCK);
        setHardness(3.5F);
        setSoundType(SoundType.STONE);
        setCreativeTab(CreativeTabs.REDSTONE);
        setTranslationKey(Fluidic.MODID + ".drain");
        setRegistryName(Fluidic.MODID, "drain");
    }

    @Override
    public void onBlockAdded(@Nonnull World world, @Nonnull BlockPos pos, @Nonnull IBlockState state) {
        if(!world.isRemote && !FluidicWater.isFinite(world)) world.scheduleUpdate(pos, this, DRAIN_TICKS);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void neighborChanged(@Nonnull IBlockState state, @Nonnull World world, @Nonnull BlockPos pos, @Nonnull Block block, @Nonnull BlockPos fromPos) {
        if(!world.isRemote && !FluidicWater.isFinite(world)) world.scheduleUpdate(pos, this, DRAIN_TICKS);
    }

    @Override
    public void updateTick(@Nonnull World world, @Nonnull BlockPos pos, @Nonnull IBlockState state, @Nonnull Random rand) {
        if(world.isRemote || FluidicWater.isFinite(world)) return;
        boolean drained = false;
        for(EnumFacing facing : EnumFacing.values()) {
            BlockPos side = pos.offset(facing);
            if(world.isBlockLoaded(side) && FluidicWater.isWater(world.getBlockState(side))) {
                world.setBlockToAir(side);
                drained = true;
            }
        }
        // Water flowing in from a source keeps coming
        if(drained) world.scheduleUpdate(pos, this, DRAIN_TICKS);
    }
}
