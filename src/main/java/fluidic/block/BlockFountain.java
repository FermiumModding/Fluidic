package fluidic.block;

import fluidic.Fluidic;
import fluidic.handlers.ForgeConfigHandler;
import fluidic.sim.SimManager;
import fluidic.sim.Terrain;
import fluidic.sim.Water;
import fluidic.sim.WaterSimulation;
import net.minecraft.block.Block;
import net.minecraft.block.BlockDirectional;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.PropertyBool;
import net.minecraft.block.properties.PropertyDirection;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.init.Blocks;
import net.minecraft.init.SoundEvents;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.Mirror;
import net.minecraft.util.Rotation;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

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
        else if(!FluidicWater.isFinite(world)) {
            // The vanilla water source it placed goes, the water flowing from it dries up
            BlockPos front = pos.offset(state.getValue(FACING));
            IBlockState water = world.getBlockState(front);
            if(FluidicWater.isWater(water) && water.getValue(BlockLiquid.LEVEL) == 0) world.setBlockToAir(front);
        }
    }

    /** Pours while powered and comes back to pour again, scheduled ticks are saved with the chunk */
    @Override
    public void updateTick(@Nonnull World world, @Nonnull BlockPos pos, @Nonnull IBlockState state, @Nonnull Random rand) {
        if(world.isRemote || !state.getValue(POWERED)) return;
        pour(world, pos.offset(state.getValue(FACING)));
        if(!world.provider.doesWaterVaporize()) splash((WorldServer)world, pos, state.getValue(FACING));
        world.scheduleUpdate(pos, this, POUR_TICKS);
    }

    /** Water splashing out of the front, and the sound of running water now and then */
    private static void splash(WorldServer world, BlockPos pos, EnumFacing facing) {
        double x = pos.getX() + 0.5D + facing.getXOffset() * 0.55D;
        double y = pos.getY() + 0.5D + facing.getYOffset() * 0.55D;
        double z = pos.getZ() + 0.5D + facing.getZOffset() * 0.55D;
        for(int n = 0; n < 4; n++) {
            // A count of 0 sends one particle moving by the offsets, out of the front and a little up
            double spread = 0.15D;
            world.spawnParticle(EnumParticleTypes.WATER_SPLASH, x + (world.rand.nextDouble() - 0.5D) * 0.4D, y + (world.rand.nextDouble() - 0.5D) * 0.4D,
                    z + (world.rand.nextDouble() - 0.5D) * 0.4D, 0, facing.getXOffset() * 0.3D + (world.rand.nextDouble() - 0.5D) * spread,
                    facing.getYOffset() * 0.3D + 0.1D + world.rand.nextDouble() * spread, facing.getZOffset() * 0.3D + (world.rand.nextDouble() - 0.5D) * spread, 1.0D);
        }
        if(world.rand.nextInt(12) == 0) {
            world.playSound(null, x, y, z, SoundEvents.BLOCK_WATER_AMBIENT, SoundCategory.BLOCKS, 0.4F + world.rand.nextFloat() * 0.2F, 0.8F + world.rand.nextFloat() * 0.4F);
        }
    }

    private static void pour(World world, BlockPos front) {
        if(world.provider.doesWaterVaporize()) {
            // Water turns to steam here, with the hiss and smoke of a bucket emptied in the Nether, now and then
            if(world.rand.nextInt(10) == 0 && world instanceof WorldServer) {
                world.playSound(null, front, SoundEvents.BLOCK_FIRE_EXTINGUISH, SoundCategory.BLOCKS, 0.5F, 2.6F + (world.rand.nextFloat() - world.rand.nextFloat()) * 0.8F);
                ((WorldServer)world).spawnParticle(EnumParticleTypes.SMOKE_LARGE, front.getX() + 0.5D, front.getY() + 0.5D, front.getZ() + 0.5D, 8, 0.3D, 0.3D, 0.3D, 0.0D);
            }
            return;
        }
        if(!FluidicWater.isFinite(world)) {
            // Vanilla water is endless already, a source in front pours as much as it likes
            if(!world.isBlockLoaded(front)) return;
            IBlockState state = world.getBlockState(front);
            boolean source = FluidicWater.isWater(state) && state.getValue(BlockLiquid.LEVEL) == 0;
            if(!source && (state.getMaterial().isReplaceable() || state.getMaterial() == Material.WATER)) {
                // Flowing like water poured from a bucket, so it starts spreading
                world.setBlockState(front, Blocks.FLOWING_WATER.getDefaultState());
            }
            return;
        }
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
