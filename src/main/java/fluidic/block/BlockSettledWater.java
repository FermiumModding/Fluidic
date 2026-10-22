package fluidic.block;

import fluidic.Fluidic;
import fluidic.handlers.ForgeConfigHandler;
import fluidic.sim.SimManager;
import fluidic.sim.SimulatedWater;
import fluidic.sim.Terrain;
import fluidic.sim.WaterSimulation;
import net.minecraft.block.Block;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraftforge.common.EnumPlantType;
import net.minecraftforge.common.IPlantable;
import net.minecraftforge.common.property.IExtendedBlockState;
import net.minecraftforge.fluids.BlockFluidFinite;
import net.minecraftforge.fluids.Fluid;

import javax.annotation.Nonnull;
import java.util.Random;

/**
 * Water that the simulation has settled into a real block. It never flows on its own: when a neighbour
 * changes the simulation decides whether it has to become simulated water again.
 * LEVEL + 1 is the number of sixteenths of a block it holds.
 */
public class BlockSettledWater extends BlockFluidFinite {

    public BlockSettledWater(Fluid fluid) {
        super(fluid, Material.WATER);
        setQuantaPerBlock(16);
        setTickRandomly(true);
        setLightOpacity(3);
        setHardness(100.0F);
        setRegistryName(Fluidic.MODID, "settled_water");
        setTranslationKey(Fluidic.MODID + ".settled_water");
    }

    @Override
    public void onBlockAdded(@Nonnull World world, @Nonnull BlockPos pos, @Nonnull IBlockState state) {
    }

    @Override
    public void neighborChanged(@Nonnull IBlockState state, @Nonnull World world, @Nonnull BlockPos pos, @Nonnull Block neighborBlock, @Nonnull BlockPos neighbourPos) {
        WaterSimulation simulation = SimManager.get(world);
        if(simulation != null) simulation.neighborChanged(pos, state.getValue(LEVEL) + 1);
    }

    /**
     * Forge culls sides against any water, but vanilla water does not share corner heights with this block,
     * so the side has to be drawn there or a gap shows where the levels differ.
     */
    @Override
    public boolean shouldSideBeRendered(@Nonnull IBlockState state, @Nonnull IBlockAccess world, @Nonnull BlockPos pos, @Nonnull EnumFacing side) {
        BlockPos offset = pos.offset(side);
        // Simulated water is drawn by the client renderer. Between the two the higher water draws the face. Water
        // falling onto settled water reaches down to its surface, so the surface stays
        if(simHeight(offset) > 0) {
            if(side.getAxis() == EnumFacing.Axis.Y) return side == EnumFacing.UP;
            return renderHeight(world, pos) > renderHeight(world, offset);
        }
        IBlockState neighbor = world.getBlockState(offset);
        if(side.getAxis() != EnumFacing.Axis.Y && neighbor.getBlock() != this && neighbor.getMaterial().isLiquid()) return true;
        return super.shouldSideBeRendered(state, world, pos, side);
    }

    /**
     * Height 0-255 of simulated water at a block as the client knows it, for rendering. 0 on servers, and when
     * simulated water is not rendered, so settled water does not blend into water that is not drawn.
     */
    private static int simHeight(BlockPos pos) {
        SimulatedWater.HeightSource client = SimulatedWater.client;
        if(client == null || !ForgeConfigHandler.client.renderWater) return 0;
        return client.height(pos.getX(), pos.getY(), pos.getZ());
    }

    /**
     * Forge raises a corner to full height whenever fluid sits on top of any neighbour, which is meant for
     * waterfalls. That ramps water up to any water above or beside it. Here only water that itself continues up
     * is full height, and the surface around it stays flat, see {@link #cornerHeight}.
     */
    @Override
    @Nonnull
    public IBlockState getExtendedState(@Nonnull IBlockState oldState, @Nonnull IBlockAccess world, @Nonnull BlockPos pos) {
        IExtendedBlockState state = (IExtendedBlockState)super.getExtendedState(oldState, world, pos);
        float[][] height = new float[3][3];
        for(int i = 0; i < 3; i++) {
            for(int j = 0; j < 3; j++) {
                height[i][j] = renderHeight(world, pos.add(i - 1, 0, j - 1));
            }
        }
        float[][] corner = new float[2][2];
        for(int i = 0; i < 2; i++) {
            for(int j = 0; j < 2; j++) {
                corner[i][j] = height[1][1] >= 1.0F ? 1.0F : cornerHeight(height[i][j], height[i][j + 1], height[i + 1][j], height[i + 1][j + 1]);
            }
        }
        return state.withProperty(LEVEL_CORNERS[0], corner[0][0])
                .withProperty(LEVEL_CORNERS[1], corner[0][1])
                .withProperty(LEVEL_CORNERS[2], corner[1][1])
                .withProperty(LEVEL_CORNERS[3], corner[1][0]);
    }

    /**
     * Averages the cells around a corner. Cells with water continuing up, like a falling stream, are left out
     * so they do not drag the surface around them up into a ramp; they are full height on their own.
     */
    private float cornerHeight(float... heights) {
        float[] rest = new float[heights.length];
        int count = 0;
        boolean column = false;
        for(float height : heights) {
            if(height >= 1.0F) column = true;
            else rest[count++] = height;
        }
        if(count == 0) return column ? 1.0F : 0.0F;
        float average = getFluidHeightAverage(java.util.Arrays.copyOf(rest, count));
        return Float.isNaN(average) ? 0.0F : average;
    }

    /** Surface height of a block for corner averaging: -1 for solid, 0 for air and plants */
    private float renderHeight(IBlockAccess world, BlockPos pos) {
        IBlockState state = world.getBlockState(pos);
        boolean waterAbove = world.getBlockState(pos.up()).getMaterial().isLiquid();
        if(!state.getMaterial().isLiquid()) {
            // Simulated water next to settled water joins its surface instead of counting as a dry block
            int sim = simHeight(pos);
            if(sim > 0) return waterAbove || simHeight(pos.up()) > 0 ? 1.0F : sim / 255.0F * quantaFraction;
            return state.getMaterial().isSolid() ? -1.0F : 0.0F;
        }
        if(waterAbove) return 1.0F;
        if(state.getBlock() == this) return (state.getValue(LEVEL) + 1) / quantaPerBlockFloat * quantaFraction;
        if(state.getBlock() instanceof BlockLiquid) return Math.min(1.0F - BlockLiquid.getLiquidHeightPercent(state.getValue(BlockLiquid.LEVEL)), quantaFraction);
        return quantaFraction;
    }

    /**
     * Settled water is still by definition. Forge would derive a current from level differences between
     * neighbours, which renders the flowing texture and pushes entities.
     */
    @Override
    public Vec3d getFlowVector(IBlockAccess world, BlockPos pos) {
        return Vec3d.ZERO;
    }

    /** Lily pads and other water plants grow on settled water deeper than half a block */
    public boolean canFloatLilyPad(IBlockState state) {
        return state.getBlock() == this && state.getValue(LEVEL) + 1 > quantaPerBlock / 2;
    }

    /** Forge checks water plants against vanilla's source level 0, which Fluidic levels do not follow */
    @Override
    public boolean canSustainPlant(@Nonnull IBlockState state, @Nonnull IBlockAccess world, BlockPos pos, @Nonnull EnumFacing direction, IPlantable plantable) {
        if(plantable.getPlantType(world, pos.offset(direction)) == EnumPlantType.Water) return canFloatLilyPad(state);
        return super.canSustainPlant(state, world, pos, direction, plantable);
    }

    /**
     * Blocks light like vanilla water, which turns grass beneath it to dirt in the dark and stops grass
     * spreading under it. Half a block or less blocks little light, so grass under a puddle keeps growing.
     */
    @Override
    @SuppressWarnings("deprecation")
    public int getLightOpacity(@Nonnull IBlockState state) {
        return state.getValue(LEVEL) + 1 > quantaPerBlock / 2 ? lightOpacity : 1;
    }

    /**
     * A puddle of half a block or less on the ground is too shallow to swim in or push entities. The same
     * level on top of deeper water is just the surface of a pool and counts as water.
     */
    private boolean isPuddle(IBlockAccess world, BlockPos pos, IBlockState state) {
        if(state.getValue(LEVEL) + 1 > quantaPerBlock / 2) return false;
        if(world.getBlockState(pos.down()).getMaterial().isLiquid()) return false;
        return !(world instanceof World) || SimulatedWater.height((World)world, pos.getX(), pos.getY() - 1, pos.getZ()) == 0;
    }

    @Override
    public Boolean isEntityInsideMaterial(IBlockAccess world, BlockPos pos, IBlockState state, Entity entity, double yToTest, Material material, boolean testingHead) {
        if(material != Material.WATER) return null;
        // Eyes are underwater below the surface at any depth, matching where Forge starts the underwater fog
        if(testingHead) return yToTest < pos.getY() + getFilledPercentage(world, pos);
        if(isPuddle(world, pos, state)) return false;
        return null;
    }

    @Override
    public Boolean isAABBInsideMaterial(World world, BlockPos pos, AxisAlignedBB boundingBox, Material material) {
        return material == Material.WATER && isPuddle(world, pos, world.getBlockState(pos)) ? false : null;
    }

    @Override
    public Boolean isAABBInsideLiquid(World world, BlockPos pos, AxisAlignedBB boundingBox) {
        return isPuddle(world, pos, world.getBlockState(pos)) ? false : null;
    }

    @Override
    public void updateTick(@Nonnull World world, @Nonnull BlockPos pos, @Nonnull IBlockState state, @Nonnull Random rand) {
    }

    /** The surface of still water evaporates, faster in direct sun, and water on soil soaks into the ground */
    @Override
    public void randomTick(@Nonnull World world, @Nonnull BlockPos pos, @Nonnull IBlockState state, @Nonnull Random rand) {
        if(world.isRemote) return;
        ForgeConfigHandler.ServerConfig config = ForgeConfigHandler.server;
        int level = state.getValue(LEVEL) + 1;
        WaterSimulation simulation = SimManager.get(world);
        // Settled water only rechecks its neighbours when they change, this catches pools that settled apart
        // at different levels, so they flow together and level out
        if(simulation != null) simulation.neighborChanged(pos, level);
        double chance = 0.0D;
        if(!world.getBlockState(pos.up()).getMaterial().isLiquid()) {
            boolean sun = world.isDaytime() && !world.isRaining() && world.canSeeSky(pos.up());
            chance += config.evaporationChance * (sun ? config.sunEvaporationMultiplier : 1.0D);
        }
        if(isSoil(world.getBlockState(pos.down()))) chance += config.groundAbsorptionChance;
        if(rand.nextDouble() >= chance) return;
        IBlockState dried = level == 1 ? Blocks.AIR.getDefaultState() : state.withProperty(LEVEL, level - 2);
        world.setBlockState(pos, dried, 2);
        if(simulation != null) simulation.setTerrain(pos, Terrain.classify(dried));
    }

    private static boolean isSoil(IBlockState state) {
        Material material = state.getMaterial();
        return material == Material.GROUND || material == Material.GRASS || material == Material.SAND || material == Material.CLAY;
    }
}
