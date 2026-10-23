package fluidic.block;

import fluidic.handlers.ForgeConfigHandler;
import fluidic.sim.SimManager;
import fluidic.sim.SimulatedWater;
import fluidic.sim.Terrain;
import fluidic.sim.Water;
import fluidic.sim.WaterSimulation;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.block.Block;
import net.minecraft.block.BlockStaticLiquid;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Replaces vanilla still water, registered as minecraft:water. It is still vanilla water to every other system,
 * but it never flows on its own: when something changes next to it, the simulation decides whether it has to
 * start flowing, and then turns it into simulated water. Finished flows settle back into these blocks.
 */
public class BlockFluidicWater extends BlockStaticLiquid {

    /** Most surface blocks evened out at once, a lake is levelled a piece at a time */
    private static final int MAX_LEVEL_CELLS = 1024;

    public BlockFluidicWater() {
        super(Material.WATER);
        setHardness(100.0F);
        setLightOpacity(3);
        setTranslationKey("water");
        disableStats();
        // Random ticks drive evaporation and catch pools that settled apart at different levels
        setTickRandomly(true);
        setRegistryName("minecraft", "water");
    }

    // ---------------------------------------------------------------- simulation

    @Override
    public void onBlockAdded(@Nonnull World world, @Nonnull BlockPos pos, @Nonnull IBlockState state) {
        // Water placed by anything but the simulation, like a bucket or melting ice, may have to flow
        if(!FluidicWater.placing) neighborChanged(world, pos, state);
    }

    @Override
    public void neighborChanged(@Nonnull IBlockState state, @Nonnull World world, @Nonnull BlockPos pos, @Nonnull Block block, @Nonnull BlockPos fromPos) {
        neighborChanged(world, pos, state);
    }

    static void neighborChanged(World world, BlockPos pos, IBlockState state) {
        WaterSimulation simulation = SimManager.get(world);
        if(simulation != null) simulation.neighborChanged(pos, FluidicWater.level(state), FluidicWater.isReservoir(world, pos, state));
    }

    @Override
    public void updateTick(@Nonnull World world, @Nonnull BlockPos pos, @Nonnull IBlockState state, @Nonnull Random rand) {
    }

    /** The surface of small bodies of still water evaporates, faster in direct sun, and soaks into soil */
    @Override
    public void randomTick(@Nonnull World world, @Nonnull BlockPos pos, @Nonnull IBlockState state, @Nonnull Random rand) {
        if(world.isRemote) return;
        int level = FluidicWater.level(state);
        boolean waterAbove = world.getBlockState(pos.up()).getMaterial().isLiquid();
        // A full block under more water, the inside of a lake or the sea, neither moves nor dries up. Most random
        // ticks of the sea land there, they skip looking up whether it is the sea.
        if(level == Water.LEVELS && waterAbove) return;
        if(FluidicWater.isReservoir(world, pos, state)) {
            // An ocean or river that lost water below sea level fills back up, open sky above it is part of it
            if(pos.getY() + 1 < world.getSeaLevel() && world.isAirBlock(pos.up())) neighborChanged(world, pos, state);
            return;
        }
        WaterSimulation simulation = SimManager.get(world);
        // Its water already went into the simulation
        if(simulation != null && simulation.isRemoving(pos)) return;
        // Only blocks that can be out of balance with their neighbours are rechecked, the inside of a lake is skipped
        if(level < Water.LEVELS || !waterAbove && hasDryNeighbor(world, pos)) neighborChanged(world, pos, state);

        if(levelSurface(world, simulation, pos, rand)) return;

        // Lakes keep their water, puddles, shallow pools and partly filled surfaces dry up
        if(!canEvaporate(world, simulation, pos, state)) return;
        ForgeConfigHandler.ServerConfig config = ForgeConfigHandler.server;
        boolean sun = world.isDaytime() && !world.isRaining() && FluidicWater.isUnderSky(world, pos.up());
        // The chances are per sixteenth of a block, a level is an eighth. Nothing evaporates in rain or below sea level,
        // soil still soaks it up.
        double chance = world.isRaining() || pos.getY() < world.getSeaLevel() ? 0.0D : config.evaporationChance * (sun ? config.sunEvaporationMultiplier : 1.0D);
        if(isSoil(world.getBlockState(pos.down()))) chance += config.groundAbsorptionChance;
        if(rand.nextDouble() >= chance * 0.5D) return;

        // The level comes off the highest of this block and its neighbours. Blocks drying on their own would pit the
        // surface with holes, and the steps between them wake the pool over and over.
        BlockPos target = pos;
        int highest = level;
        for(EnumFacing facing : EnumFacing.HORIZONTALS) {
            BlockPos offset = pos.offset(facing);
            // Reading an unloaded block would load its chunk
            if(!world.isBlockLoaded(offset)) continue;
            IBlockState neighbor = world.getBlockState(offset);
            if(!FluidicWater.isWater(neighbor) || FluidicWater.level(neighbor) <= highest) continue;
            if(!canEvaporate(world, simulation, offset, neighbor) || FluidicWater.isReservoir(world, offset, neighbor)) continue;
            target = offset;
            highest = FluidicWater.level(neighbor);
        }
        IBlockState dried = highest == 1 ? Blocks.AIR.getDefaultState() : FluidicWater.state(highest - 1);
        FluidicWater.placeQuietly(world, target, dried, 2);
        if(simulation != null) simulation.setTerrain(target, Terrain.classify(dried));
    }

    /**
     * Evens out the surface this block belongs to. The simulation only wakes still water for steps of two levels,
     * so rain and water running into a pool leave blocks one level above or below the rest. Every connected surface
     * cell at this height gets the average. Empty cells on top of full water count as level 0, so a thin layer of
     * rain on a full pool spreads over all of it or dries up as a whole instead of breaking into bumps and holes.
     * Returns whether anything changed.
     */
    private static boolean levelSurface(World world, WaterSimulation simulation, BlockPos pos, Random rand) {
        int level = surfaceLevel(world, simulation, pos);
        if(level < 0) return false;
        boolean uneven = false;
        for(EnumFacing facing : EnumFacing.HORIZONTALS) {
            int neighbor = surfaceLevel(world, simulation, pos.offset(facing));
            if(neighbor >= 0 && neighbor != level) uneven = true;
        }
        if(!uneven) return false;

        List<BlockPos> cells = new ArrayList<>();
        IntArrayList levels = new IntArrayList();
        Set<BlockPos> visited = new HashSet<>();
        cells.add(pos);
        levels.add(level);
        visited.add(pos);
        int total = 0;
        boolean complete = true;
        for(int n = 0; n < cells.size(); n++) {
            BlockPos cell = cells.get(n);
            total += levels.getInt(n);
            for(EnumFacing facing : EnumFacing.HORIZONTALS) {
                BlockPos offset = cell.offset(facing);
                if(!world.isBlockLoaded(offset) || !visited.add(offset)) continue;
                int neighbor = surfaceLevel(world, simulation, offset);
                if(neighbor < 0) continue;
                if(cells.size() >= MAX_LEVEL_CELLS) {
                    complete = false;
                    continue;
                }
                cells.add(offset);
                levels.add(neighbor);
            }
        }
        // A pool cannot hold a fraction of a level evenly. Rounding up by chance keeps the water on average, rounding
        // to the nearest level would swallow the rain that raised a few blocks of a large pool. Part of a large lake
        // only rounds down, so it never grows a layer that ends partway across.
        int even = total / cells.size();
        if(complete && rand.nextInt(cells.size()) < total % cells.size()) even++;
        boolean changed = false;
        for(int n = 0; n < cells.size(); n++) {
            if(levels.getInt(n) == even) continue;
            BlockPos cell = cells.get(n);
            IBlockState leveled = even == 0 ? Blocks.AIR.getDefaultState() : FluidicWater.state(even);
            FluidicWater.placeQuietly(world, cell, leveled, 2);
            if(simulation != null) simulation.setTerrain(cell, Terrain.classify(leveled));
            changed = true;
        }
        return changed;
    }

    /**
     * Level of a cell as part of a still surface: a water block with nothing above it, or 0 for an empty cell on top
     * of a full one. -1 for anything else, oceans and water about to become simulated water included.
     */
    private static int surfaceLevel(World world, WaterSimulation simulation, BlockPos pos) {
        if(!world.isBlockLoaded(pos) || simulation != null && simulation.isRemoving(pos)) return -1;
        IBlockState state = world.getBlockState(pos);
        if(world.getBlockState(pos.up()).getMaterial().isLiquid() || SimulatedWater.height(world, pos.getX(), pos.getY() + 1, pos.getZ()) > 0) return -1;
        if(FluidicWater.isWater(state)) return FluidicWater.isReservoir(world, pos, state) ? -1 : FluidicWater.level(state);
        if(state.getMaterial() != Material.AIR || SimulatedWater.height(world, pos.getX(), pos.getY(), pos.getZ()) > 0) return -1;
        BlockPos down = pos.down();
        IBlockState below = world.getBlockState(down);
        if(!FluidicWater.isWater(below) || FluidicWater.level(below) < Water.LEVELS || FluidicWater.isReservoir(world, down, below)) return -1;
        return simulation != null && simulation.isRemoving(down) ? -1 : 0;
    }

    /** Surface water that dries up: nothing above it, and not a full block over more water, which is a lake */
    private static boolean canEvaporate(World world, WaterSimulation simulation, BlockPos pos, IBlockState state) {
        if(simulation != null && simulation.isRemoving(pos)) return false;
        if(world.getBlockState(pos.up()).getMaterial().isLiquid()) return false;
        return FluidicWater.level(state) < Water.LEVELS || !world.getBlockState(pos.down()).getMaterial().isLiquid();
    }

    private static boolean hasDryNeighbor(World world, BlockPos pos) {
        for(EnumFacing facing : EnumFacing.HORIZONTALS) {
            BlockPos offset = pos.offset(facing);
            if(!world.isBlockLoaded(offset)) continue;
            IBlockState neighbor = world.getBlockState(offset);
            if(!neighbor.getMaterial().isLiquid() && Terrain.isOpen(neighbor)) return true;
        }
        return false;
    }

    private static boolean isSoil(IBlockState state) {
        Material material = state.getMaterial();
        return material == Material.GROUND || material == Material.GRASS || material == Material.SAND || material == Material.CLAY;
    }

    // ---------------------------------------------------------------- entities

    /** Fluidic water is still, vanilla would push entities along level differences and draw it flowing */
    @Override
    @Nonnull
    protected Vec3d getFlow(@Nonnull IBlockAccess world, @Nonnull BlockPos pos, @Nonnull IBlockState state) {
        return Vec3d.ZERO;
    }

    @Override
    public Boolean isEntityInsideMaterial(IBlockAccess world, BlockPos pos, IBlockState state, Entity entity, double yToTest, Material material, boolean testingHead) {
        // Eyes go under the surface at any depth, swimming needs more than half a block
        if(material == Material.WATER && !testingHead && FluidicWater.isPuddle(world, pos, state)) return false;
        return null;
    }

    @Override
    public Boolean isAABBInsideMaterial(World world, BlockPos pos, AxisAlignedBB boundingBox, Material material) {
        return material == Material.WATER && FluidicWater.isPuddle(world, pos, world.getBlockState(pos)) ? false : null;
    }

    @Override
    public Boolean isAABBInsideLiquid(World world, BlockPos pos, AxisAlignedBB boundingBox) {
        return FluidicWater.isPuddle(world, pos, world.getBlockState(pos)) ? false : null;
    }

    /**
     * Rays that look for liquids, like an empty bucket's, hit water of any level. Vanilla only lets them hit full
     * water, partly filled vanilla water is flowing water, but Fluidic water is still at every level.
     */
    @Override
    public boolean canCollideCheck(@Nonnull IBlockState state, boolean hitIfLiquid) {
        return hitIfLiquid;
    }

    /** Half a block or less blocks little light, so grass under a puddle keeps growing */
    @Override
    @SuppressWarnings("deprecation")
    public int getLightOpacity(@Nonnull IBlockState state) {
        return FluidicWater.level(state) > Water.LEVELS / 2 ? lightOpacity : 1;
    }

    // ---------------------------------------------------------------- rendering

    @Override
    public boolean shouldSideBeRendered(@Nonnull IBlockState state, @Nonnull IBlockAccess world, @Nonnull BlockPos pos, @Nonnull EnumFacing side) {
        BlockPos offset = pos.offset(side);
        boolean simulated = FluidicWater.simHeight(offset) > 0;
        // Simulated water is drawn by the client renderer, its top and bottom meet this water without a face
        if(simulated && side.getAxis() == EnumFacing.Axis.Y) return false;
        // Water surfaces meet at shared corners, so no face is drawn between them, except where this water continues
        // up and stands above the water beside it. Rain settling on a full pool makes the block under it full height,
        // and the step down to the pool around it would show a gap.
        if(side.getAxis() != EnumFacing.Axis.Y && (simulated || world.getBlockState(offset).getMaterial() == Material.WATER)) {
            return FluidicWater.renderHeight(world, pos) >= 1.0F && FluidicWater.renderHeight(world, offset) < 1.0F;
        }
        return super.shouldSideBeRendered(state, world, pos, side);
    }

    /** Vanilla plays a flowing sound for partly filled water, Fluidic water is still at any level */
    @Override
    @SideOnly(Side.CLIENT)
    public void randomDisplayTick(@Nonnull IBlockState state, @Nonnull World world, @Nonnull BlockPos pos, @Nonnull Random rand) {
        if(rand.nextInt(10) == 0) {
            world.spawnParticle(EnumParticleTypes.SUSPENDED, pos.getX() + rand.nextFloat(), pos.getY() + rand.nextFloat(), pos.getZ() + rand.nextFloat(), 0.0D, 0.0D, 0.0D);
        }
    }
}
