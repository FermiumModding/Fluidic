package fluidic.block;

import fluidic.sim.SimManager;
import fluidic.sim.SimulatedWater;
import fluidic.sim.Terrain;
import fluidic.sim.Water;
import fluidic.sim.WaterSimulation;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.FluidTankProperties;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.IFluidTankProperties;

import javax.annotation.Nullable;

/**
 * Forge fluid handlers for Fluidic water, which pumps, tanks and fluid containers of other mods get through
 * FluidUtil.getFluidHandler. Forge's handler for vanilla water only takes and places whole source blocks. Fluidic water
 * holds eighths of a block, so these take and add water an eighth (125 mB) at a time, and water that is still being
 * simulated, which is no block, can be taken too.
 */
public final class WaterFluidHandlers {

    /** Millibuckets in an eighth of a block */
    private static final int EIGHTH = Fluid.BUCKET_VOLUME / Water.LEVELS;

    private WaterFluidHandlers() {}

    /** The handler for the water at a position, null to leave it to Forge: no water there, or a block with its own */
    @Nullable
    public static IFluidHandler get(World world, BlockPos pos) {
        if(!FluidicWater.isFinite(world) || !world.isBlockLoaded(pos)) return null;
        IBlockState state = world.getBlockState(pos);
        if(FluidicWater.isWater(state)) return new BlockHandler(world, pos);
        if(state.getBlock().hasTileEntity(state) || !Terrain.isOpen(state) && !Terrain.holdsWater(state)) return null;
        return SimulatedWater.height(world, pos.getX(), pos.getY(), pos.getZ()) > 0 ? new SimulatedHandler(world, pos) : null;
    }

    private static boolean isWater(@Nullable FluidStack resource) {
        return resource != null && resource.getFluid() == FluidRegistry.WATER;
    }

    private static IFluidTankProperties[] tank(int amount) {
        FluidStack contents = amount > 0 ? new FluidStack(FluidRegistry.WATER, amount) : null;
        return new IFluidTankProperties[] {new FluidTankProperties(contents, Fluid.BUCKET_VOLUME, true, true)};
    }

    /**
     * A water block. Neighbours are told when its level changes, so a pool evens out around water taken from it and
     * the sea fills a hole back up, as after a bucket.
     */
    private static final class BlockHandler implements IFluidHandler {

        private final World world;
        private final BlockPos pos;

        BlockHandler(World world, BlockPos pos) {
            this.world = world;
            this.pos = pos;
        }

        @Override
        public IFluidTankProperties[] getTankProperties() {
            IBlockState state = world.getBlockState(pos);
            return tank(FluidicWater.isWater(state) ? FluidicWater.level(state) * EIGHTH : 0);
        }

        @Override
        public int fill(FluidStack resource, boolean doFill) {
            IBlockState state = world.getBlockState(pos);
            if(!isWater(resource) || !FluidicWater.isWater(state)) return 0;
            int level = FluidicWater.level(state);
            int levels = Math.min(Water.LEVELS - level, resource.amount / EIGHTH);
            if(levels <= 0) return 0;
            if(doFill) world.setBlockState(pos, FluidicWater.state(level + levels), 3);
            return levels * EIGHTH;
        }

        @Nullable
        @Override
        public FluidStack drain(FluidStack resource, boolean doDrain) {
            return isWater(resource) ? drain(resource.amount, doDrain) : null;
        }

        @Nullable
        @Override
        public FluidStack drain(int maxDrain, boolean doDrain) {
            IBlockState state = world.getBlockState(pos);
            if(!FluidicWater.isWater(state)) return null;
            int level = FluidicWater.level(state);
            int levels = Math.min(level, maxDrain / EIGHTH);
            if(levels <= 0) return null;
            if(doDrain) world.setBlockState(pos, level == levels ? Blocks.AIR.getDefaultState() : FluidicWater.state(level - levels), 3);
            return new FluidStack(FluidRegistry.WATER, levels * EIGHTH);
        }
    }

    /**
     * Water still being simulated in an open block. The server takes and adds it through the simulation, the client
     * only tells what it would take, from the heights it was sent.
     */
    private static final class SimulatedHandler implements IFluidHandler {

        private final World world;
        private final BlockPos pos;

        SimulatedHandler(World world, BlockPos pos) {
            this.world = world;
            this.pos = pos;
        }

        /** Millibuckets of water there, without water another container was promised already */
        private int available() {
            WaterSimulation simulation = SimManager.get(world);
            int height = simulation != null ? simulation.available(pos.getX(), pos.getY(), pos.getZ())
                    : SimulatedWater.height(world, pos.getX(), pos.getY(), pos.getZ());
            return height * Fluid.BUCKET_VOLUME / 255;
        }

        @Override
        public IFluidTankProperties[] getTankProperties() {
            return tank(available());
        }

        @Override
        public int fill(FluidStack resource, boolean doFill) {
            if(!isWater(resource)) return 0;
            WaterSimulation simulation = SimManager.get(world);
            if(simulation == null) return 0;
            int amount = Math.min(resource.amount, Fluid.BUCKET_VOLUME);
            if(doFill) simulation.addWater(pos, amount * Water.FULL / Fluid.BUCKET_VOLUME);
            return amount;
        }

        @Nullable
        @Override
        public FluidStack drain(FluidStack resource, boolean doDrain) {
            return isWater(resource) ? drain(resource.amount, doDrain) : null;
        }

        @Nullable
        @Override
        public FluidStack drain(int maxDrain, boolean doDrain) {
            int amount = Math.min(maxDrain, available());
            if(amount <= 0) return null;
            WaterSimulation simulation = SimManager.get(world);
            if(doDrain && simulation != null) simulation.takeWater(pos, amount * Water.FULL / Fluid.BUCKET_VOLUME, 0);
            return new FluidStack(FluidRegistry.WATER, amount);
        }
    }
}
