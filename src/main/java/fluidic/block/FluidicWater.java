package fluidic.block;

import fluidic.handlers.ForgeConfigHandler;
import fluidic.sim.Reservoirs;
import fluidic.sim.SimulatedWater;
import fluidic.sim.Water;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;

/**
 * Vanilla water blocks replaced by Fluidic. Levels follow vanilla, so other code reading water keeps working:
 * LEVEL 0 is a full block, 1 to 7 are partly filled blocks from 7/8 down to 1/8, and 8 to 15 (vanilla's falling
 * water) count as full. The simulation works in eighths, 1 to 8.
 */
public final class FluidicWater {

    /** Set while the simulation places water itself, so the placement is not reported back to it */
    public static boolean placing;

    /**
     * Chunks being populated, nested when populating one generates another. Server thread only. Water placed by world
     * generation is left as generated: checking every block of a lake as it is placed costs more than generating it,
     * and the half finished lake would start flowing. Water near players gets checked by its random ticks anyway.
     */
    public static int generating;

    /** Set by the client while chunks draw simulated water as water blocks, see fluidic.client.ChunkWater */
    public static volatile boolean simulatedInChunks;

    /** Corner heights are worked out per corner, this is the block whose corners are being drawn */
    private static final ThreadLocal<BlockPos> RENDERING = new ThreadLocal<>();
    /** How far inside the block edge vanilla draws the side faces of liquids */
    private static final double SIDE_INSET = 0.0010000000474974513D;

    private FluidicWater() {}

    /** Sets a block with {@link #placing} set, it is put back however setting the block ends */
    public static void placeQuietly(World world, BlockPos pos, IBlockState state, int flags) {
        boolean was = placing;
        placing = true;
        try {
            world.setBlockState(pos, state, flags);
        }
        finally {
            placing = was;
        }
    }

    /** Dimensions keeping vanilla water on the server the client is connected to, sent when it joins */
    public static volatile int[] clientVanillaDimensions = new int[0];
    /** Whether the client's current world keeps vanilla water, for block lookups that come without the world */
    public static volatile boolean clientVanilla;

    /** Whether water in a world is Fluidic's finite water, false in the dimensions the config gives vanilla water */
    public static boolean isFinite(World world) {
        int dimension = world.provider.getDimension();
        int[] vanilla = world.isRemote ? clientVanillaDimensions : ForgeConfigHandler.server.vanillaWaterDimensions;
        for(int id : vanilla) if(id == dimension) return false;
        return true;
    }

    /** Same for a block access, outside of a world it is a chunk cache the client draws its chunks from */
    public static boolean isFinite(IBlockAccess world) {
        return world instanceof World ? isFinite((World)world) : !clientVanilla;
    }

    public static boolean isWater(IBlockState state) {
        return state.getBlock() == Blocks.WATER || state.getBlock() == Blocks.FLOWING_WATER;
    }

    /** Eighths of a block of water in a water block, 1 to 8 */
    public static int level(IBlockState state) {
        int meta = state.getValue(BlockLiquid.LEVEL);
        return meta >= 8 ? Water.LEVELS : Water.LEVELS - meta;
    }

    /** Still water holding the given eighths of a block, 1 to 8 */
    public static IBlockState state(int level) {
        return Blocks.WATER.getDefaultState().withProperty(BlockLiquid.LEVEL, Water.LEVELS - Math.max(1, Math.min(Water.LEVELS, level)));
    }

    /**
     * Full water below sea level in the body of water of an ocean or river is an endless reservoir: it never drains,
     * refills what is taken from it and swallows water flowing in, so breaching the sea floor cannot empty the ocean.
     * The body counts as a whole, past the edge of its biome too, see {@link Reservoirs}.
     */
    public static boolean isReservoir(World world, BlockPos pos, IBlockState state) {
        return !world.isRemote && isWater(state) && level(state) == Water.LEVELS && pos.getY() < world.getSeaLevel() && Reservoirs.isReservoir(world, pos);
    }

    /**
     * Whether a block lies open to the sky, by its sky light: leaves overhead take one level off, a cave roof far
     * more. Vanilla's height map is not used, it counts any block high above, like a tree beside a river, as a roof.
     */
    public static boolean isUnderSky(World world, BlockPos pos) {
        return world.getLightFor(EnumSkyBlock.SKY, pos) >= 14;
    }

    /**
     * A puddle of half a block or less on the ground is too shallow to swim in or push entities. The same
     * level on top of deeper water is just the surface of a pool and counts as water.
     */
    public static boolean isPuddle(IBlockAccess world, BlockPos pos, IBlockState state) {
        if(level(state) > Water.LEVELS / 2) return false;
        if(world.getBlockState(pos.down()).getMaterial().isLiquid()) return false;
        return !(world instanceof World) || SimulatedWater.height((World)world, pos.getX(), pos.getY() - 1, pos.getZ()) == 0;
    }

    /** Surface of the water in a water block above its bottom, 1 when water continues above */
    public static float surface(IBlockAccess world, BlockPos pos, IBlockState state) {
        if(world.getBlockState(pos.up()).getMaterial().isLiquid()) return 1.0F;
        return 1.0F - BlockLiquid.getLiquidHeightPercent(state.getValue(BlockLiquid.LEVEL));
    }

    // ---------------------------------------------------------------- rendering

    /** Height 0-255 of simulated water as the client draws it, 0 on servers and when it is not drawn */
    public static int simHeight(BlockPos pos) {
        SimulatedWater.HeightSource client = SimulatedWater.client;
        if(client == null || !ForgeConfigHandler.client.renderWater) return 0;
        return client.height(pos.getX(), pos.getY(), pos.getZ());
    }

    /** Called by the fluid renderer mixin before drawing a water block */
    public static void beginRendering(BlockPos pos) {
        RENDERING.set(pos);
    }

    /**
     * Surface height of a block for corner averaging: -1 for solid, 0 for dry open blocks, 1 for water that
     * continues up. Simulated water next to water blocks joins their surface instead of counting as dry.
     */
    public static float renderHeight(IBlockAccess world, BlockPos pos) {
        IBlockState state = world.getBlockState(pos);
        // Water continues up into a water block or into simulated water, like rain landing on a pool. Either way
        // the water below fills its block, without a gap under the water above it.
        boolean waterAbove = world.getBlockState(pos.up()).getMaterial().isLiquid() || simHeight(pos.up()) > 0;
        if(!state.getMaterial().isLiquid()) {
            int sim = simHeight(pos);
            if(sim > 0) return waterAbove || sim >= 255 && underCeiling(world, pos) ? 1.0F : sim / 255.0F * Water.RENDER_HEIGHT;
            return state.getMaterial().isSolid() ? -1.0F : 0.0F;
        }
        if(waterAbove) return 1.0F;
        // Simulated water drawn by the chunks stands in as a water block, but is drawn at its own height between levels,
        // so it rises and falls smoothly. Its chunk redraws whenever it changes.
        if(simulatedInChunks) {
            int sim = simHeight(pos);
            if(sim > 0) return sim >= 255 && underCeiling(world, pos) ? 1.0F : sim / 255.0F * Water.RENDER_HEIGHT;
        }
        if(isWater(state) && level(state) == Water.LEVELS && underCeiling(world, pos)) return 1.0F;
        if(state.getBlock() instanceof BlockLiquid) return 1.0F - BlockLiquid.getLiquidHeightPercent(state.getValue(BlockLiquid.LEVEL));
        return Water.RENDER_HEIGHT;
    }

    /**
     * Whether full water in a block reaches up to the block above it: an opaque block, its bottom face is the water's
     * ceiling. Drawn at the usual surface height, a full block under it would show a gap of air below it, like an air
     * bubble under a block in the middle of the sea.
     */
    public static boolean underCeiling(IBlockAccess world, BlockPos pos) {
        return pos.getY() < 255 && world.getBlockState(pos.up()).isOpaqueCube();
    }

    /**
     * Height of the water surface at a corner, replacing vanilla's BlockFluidRenderer.getFluidHeight. Vanilla raises
     * a corner to full height whenever water sits on top of any block around it, which ramps the surface up to
     * waterfalls and to water one block higher. Here water continuing up is full height on its own, and the
     * corners around it are averaged without it, weighting near full cells like vanilla does.
     */
    public static float cornerHeight(IBlockAccess world, BlockPos corner) {
        BlockPos rendering = RENDERING.get();
        if(rendering != null && renderHeight(world, rendering) >= 1.0F) return 1.0F;
        return surfaceCorner(world, corner);
    }

    /** Height of the still surface at a corner, as water that does not continue up draws it */
    private static float surfaceCorner(IBlockAccess world, BlockPos corner) {
        // Simulated water moves all the time but water blocks are only redrawn when it appears or vanishes beside
        // them. Corners touching a water block leave it out, so the shared corner never goes stale and opens a gap.
        boolean blocks = false;
        for(int j = 0; j < 4; j++) {
            blocks |= world.getBlockState(corner.add(-(j & 1), 0, -(j >> 1 & 1))).getMaterial().isLiquid();
        }
        float sum = 0.0F;
        int count = 0;
        boolean column = false;
        for(int j = 0; j < 4; j++) {
            BlockPos cell = corner.add(-(j & 1), 0, -(j >> 1 & 1));
            if(blocks && !world.getBlockState(cell).getMaterial().isLiquid() && simHeight(cell) > 0) continue;
            float height = renderHeight(world, cell);
            if(height >= 1.0F) {
                column = true;
                continue;
            }
            if(height < 0.0F) continue;
            if(height >= Water.RENDER_HEIGHT) {
                sum += height * 10.0F;
                count += 10;
            }
            sum += height;
            count++;
        }
        if(count == 0) return column ? 1.0F : 0.0F;
        return sum / count;
    }

    /**
     * Bottom of a side face vertex of a water block. Vanilla draws sides down to the bottom of the block, but the
     * only side drawn against more water is the step of water continuing up above the surface beside it, so it
     * ends at that surface. Side vertices sit 0.001 inside the block edge, which tells them from the bottom face.
     */
    public static double sideBottom(IBlockAccess world, BlockPos pos, double x, double y, double z) {
        double fx = x - pos.getX(), fz = z - pos.getZ();
        EnumFacing side;
        if(Math.abs(fx - SIDE_INSET) < SIDE_INSET / 2) side = EnumFacing.WEST;
        else if(Math.abs(fx - 1.0D + SIDE_INSET) < SIDE_INSET / 2) side = EnumFacing.EAST;
        else if(Math.abs(fz - SIDE_INSET) < SIDE_INSET / 2) side = EnumFacing.NORTH;
        else if(Math.abs(fz - 1.0D + SIDE_INSET) < SIDE_INSET / 2) side = EnumFacing.SOUTH;
        else return y;
        BlockPos offset = pos.offset(side);
        if(world.getBlockState(offset).getMaterial() != Material.WATER && simHeight(offset) == 0) return y;
        float beside = renderHeight(world, offset);
        if(beside <= 0.0F || beside >= 1.0F) return y;
        return y + surfaceCorner(world, new BlockPos(Math.round(x), pos.getY(), Math.round(z)));
    }
}
