package fluidic.block;

import fluidic.Fluidic;
import fluidic.handlers.ForgeConfigHandler;
import fluidic.sim.SimManager;
import fluidic.sim.Terrain;
import fluidic.sim.Water;
import fluidic.sim.WaterSimulation;
import net.minecraft.block.Block;
import net.minecraft.block.BlockDirectional;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.PropertyBool;
import net.minecraft.block.properties.PropertyDirection;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.Mirror;
import net.minecraft.util.Rotation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import javax.annotation.Nonnull;
import java.util.Random;

/**
 * A builder's block that pours water out of its front while it gets a redstone signal, as much as it likes. It is an
 * endless spring for filling pools and making waterfalls: the water it pours flows like any other.
 */
public class BlockFountain extends Block {

    public static final PropertyDirection FACING = BlockDirectional.FACING;
    public static final PropertyBool POWERED = PropertyBool.create("powered");
    /** Ticks between pours while powered */
    private static final int POUR_TICKS = 2;

    public BlockFountain() {
        super(Material.ROCK);
        setHardness(3.5F);
        setSoundType(SoundType.STONE);
        setCreativeTab(CreativeTabs.REDSTONE);
        setTranslationKey(Fluidic.MODID + ".fountain");
        setRegistryName(Fluidic.MODID, "fountain");
        setDefaultState(blockState.getBaseState().withProperty(FACING, EnumFacing.NORTH).withProperty(POWERED, false));
    }

    @Override
    @Nonnull
    public IBlockState getStateForPlacement(@Nonnull World world, @Nonnull BlockPos pos, @Nonnull EnumFacing side, float hitX, float hitY, float hitZ, int meta, @Nonnull EntityLivingBase placer) {
        return getDefaultState().withProperty(FACING, EnumFacing.getDirectionFromEntityLiving(pos, placer));
    }

    @Override
    public void onBlockAdded(@Nonnull World world, @Nonnull BlockPos pos, @Nonnull IBlockState state) {
        updatePower(world, pos, state);
    }

    @Override
    public void neighborChanged(@Nonnull IBlockState state, @Nonnull World world, @Nonnull BlockPos pos, @Nonnull Block block, @Nonnull BlockPos fromPos) {
        updatePower(world, pos, state);
    }

    private void updatePower(World world, BlockPos pos, IBlockState state) {
        if(world.isRemote) return;
        boolean powered = world.isBlockPowered(pos);
        if(powered == state.getValue(POWERED)) return;
        world.setBlockState(pos, state.withProperty(POWERED, powered), 2);
        if(powered) world.scheduleUpdate(pos, this, 1);
    }

    /** Pours while powered and comes back to pour again, scheduled ticks are saved with the chunk */
    @Override
    public void updateTick(@Nonnull World world, @Nonnull BlockPos pos, @Nonnull IBlockState state, @Nonnull Random rand) {
        if(world.isRemote || !state.getValue(POWERED)) return;
        pour(world, pos.offset(state.getValue(FACING)));
        world.scheduleUpdate(pos, this, POUR_TICKS);
    }

    private static void pour(World world, BlockPos front) {
        WaterSimulation simulation = SimManager.get(world);
        if(simulation == null || !world.isBlockLoaded(front)) return;
        int amount = (int)Math.round(ForgeConfigHandler.server.fountainOutput * Water.FULL * POUR_TICKS / 20.0D);
        if(amount <= 0) return;
        IBlockState state = world.getBlockState(front);
        if(FluidicWater.isWater(state)) {
            // Into a pool, the water goes on top of it like a bucket poured into it
            BlockPos top = front;
            while(top.getY() < 255 && FluidicWater.isWater(world.getBlockState(top.up()))) top = top.up();
            simulation.addWater(top.up(), amount);
        }
        else if(Terrain.isOpen(state)) {
            simulation.addWater(front, amount);
        }
    }

    @Override
    @Nonnull
    protected BlockStateContainer createBlockState() {
        return new BlockStateContainer(this, FACING, POWERED);
    }

    @Override
    @Nonnull
    @SuppressWarnings("deprecation")
    public IBlockState getStateFromMeta(int meta) {
        return getDefaultState().withProperty(FACING, EnumFacing.byIndex(meta & 7)).withProperty(POWERED, (meta & 8) != 0);
    }

    @Override
    public int getMetaFromState(@Nonnull IBlockState state) {
        return state.getValue(FACING).getIndex() | (state.getValue(POWERED) ? 8 : 0);
    }

    @Override
    @Nonnull
    @SuppressWarnings("deprecation")
    public IBlockState withRotation(@Nonnull IBlockState state, @Nonnull Rotation rotation) {
        return state.withProperty(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    @Nonnull
    @SuppressWarnings("deprecation")
    public IBlockState withMirror(@Nonnull IBlockState state, @Nonnull Mirror mirror) {
        return state.withRotation(mirror.toRotation(state.getValue(FACING)));
    }
}
