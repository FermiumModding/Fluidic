package fluidic.sim;

import fluidic.handlers.ForgeConfigHandler;
import fluidic.block.FluidicWater;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * Entity physics for water that is still being simulated. It is not a block, so vanilla's water checks
 * never see it; the entity mixins ask here as well. Like settled water, puddles of half a block or less on
 * the ground do not count.
 */
public final class SimulatedWater {

    /** Water heights on the client, set by the client proxy */
    public interface HeightSource {
        int height(int x, int y, int z);
    }

    public static HeightSource client;

    /** Heights are 0-255, more than this is over half a block */
    private static final int HALF = 127;
    /** Surface slopes in blocks per block below this push nothing, the noise of a pool evening out */
    private static final double MIN_SLOPE = 1.0D / 32.0D;
    /** Surface slope at which the current pushes as hard as vanilla flowing water */
    private static final double FULL_SLOPE = 1.0D / 8.0D;
    /** Surface of a full block of water as the client renders it */
    private static final float RENDER_HEIGHT = Water.RENDER_HEIGHT;

    private SimulatedWater() {}

    /** Height 0-255 of simulated water at a block */
    public static int height(World world, int x, int y, int z) {
        if(world.isRemote) return client == null ? 0 : client.height(x, y, z);
        WaterSimulation simulation = SimManager.get(world);
        return simulation == null ? 0 : simulation.height(x, y, z);
    }

    /**
     * Whether simulated water in a block counts for entities. A puddle of half a block or less on the ground
     * does not, the same depth on top of more water is the surface of a pool and does.
     */
    private static boolean counts(World world, int x, int y, int z, int height) {
        if(height > HALF) return true;
        if(height == 0) return false;
        return height(world, x, y - 1, z) > 0 || (y > 0 && world.getBlockState(new BlockPos(x, y - 1, z)).getMaterial().isLiquid());
    }

    /** Surface of the water in a block, relative to its bottom, 1 when water continues above */
    private static float surface(World world, int x, int y, int z, int height) {
        return height(world, x, y + 1, z) > 0 ? 1.0F : height / 255.0F;
    }

    /**
     * Whether a bounding box touches Fluidic water of any depth, settled or simulated. Shallow water is too
     * little to swim in but still gets entities wet, which puts out fire like vanilla's shallow water does.
     */
    public static boolean isTouchingWater(World world, AxisAlignedBB box) {
        int minX = MathHelper.floor(box.minX), maxX = MathHelper.ceil(box.maxX);
        int minY = MathHelper.floor(box.minY), maxY = MathHelper.ceil(box.maxY);
        int minZ = MathHelper.floor(box.minZ), maxZ = MathHelper.ceil(box.maxZ);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for(int x = minX; x < maxX; x++) {
            for(int y = minY; y < maxY; y++) {
                for(int z = minZ; z < maxZ; z++) {
                    double surface;
                    int height = height(world, x, y, z);
                    IBlockState state = world.getBlockState(pos.setPos(x, y, z));
                    if(state.getMaterial() == Material.WATER) surface = FluidicWater.isWater(state) ? FluidicWater.surface(world, pos, state) : 1.0D;
                    else if(height > 0) surface = surface(world, x, y, z, height);
                    else continue;
                    if(box.minY < y + surface) return true;
                }
            }
        }
        return false;
    }

    /** Whether there is any simulated water in a box of blocks, corners inclusive */
    public static boolean anyWater(World world, BlockPos from, BlockPos to) {
        for(int x = from.getX(); x <= to.getX(); x++) {
            for(int y = from.getY(); y <= to.getY(); y++) {
                for(int z = from.getZ(); z <= to.getZ(); z++) {
                    if(height(world, x, y, z) > 0) return true;
                }
            }
        }
        return false;
    }

    /** Whether any simulated water is in the blocks a bounding box reaches into, like World.containsAnyLiquid */
    public static boolean containsWater(World world, AxisAlignedBB box) {
        int minX = MathHelper.floor(box.minX), maxX = MathHelper.ceil(box.maxX);
        int minY = MathHelper.floor(box.minY), maxY = MathHelper.ceil(box.maxY);
        int minZ = MathHelper.floor(box.minZ), maxZ = MathHelper.ceil(box.maxZ);
        for(int x = minX; x < maxX; x++) {
            for(int y = minY; y < maxY; y++) {
                for(int z = minZ; z < maxZ; z++) {
                    if(height(world, x, y, z) > 0) return true;
                }
            }
        }
        return false;
    }

    /** Whether a bounding box reaches into simulated water that is over half a block deep */
    public static boolean isInWater(World world, AxisAlignedBB box) {
        int minX = MathHelper.floor(box.minX), maxX = MathHelper.ceil(box.maxX);
        int minY = MathHelper.floor(box.minY), maxY = MathHelper.ceil(box.maxY);
        int minZ = MathHelper.floor(box.minZ), maxZ = MathHelper.ceil(box.maxZ);
        for(int x = minX; x < maxX; x++) {
            for(int y = minY; y < maxY; y++) {
                for(int z = minZ; z < maxZ; z++) {
                    int height = height(world, x, y, z);
                    if(counts(world, x, y, z, height) && box.minY < y + surface(world, x, y, z, height)) return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether an entity's bounding box is in simulated water, pushing it along the current the way
     * World.handleMaterialAcceleration does for flowing water blocks. The current pushes in water of any depth, like
     * vanilla flowing water carries items along a thin stream, being in water to swim takes more than half a block.
     */
    public static boolean handleEntity(Entity entity, AxisAlignedBB box) {
        boolean inWater = isInWater(entity.world, box);
        if(entity.isPushedByWater()) push(entity, current(entity.world, box));
        return inWater;
    }

    private static void push(Entity entity, Vec3d current) {
        // Push as hard as vanilla flowing water on a clear slope, fading out on the small differences of a pool
        // that is still evening out
        double slope = Math.sqrt(current.x * current.x + current.z * current.z);
        double strength = MathHelper.clamp((slope - MIN_SLOPE) / (FULL_SLOPE - MIN_SLOPE), 0.0D, 1.0D);
        // Or as hard as vanilla flowing water wherever it flows at all
        if(strength > 0.0D && ForgeConfigHandler.server.vanillaPushStrength) strength = 1.0D;
        if(strength > 0.0D) {
            entity.motionX += current.x / slope * strength * 0.014D;
            entity.motionZ += current.z / slope * strength * 0.014D;
        }
        entity.motionY += current.y * 0.014D;
    }

    /**
     * How simulated water in a bounding box flows: the average slope of the water surface between wet cells in
     * blocks per block horizontally, and the share of cells falling freely as a negative y. Only slopes between
     * wet cells count, water at the edge of a pool does not flow onto dry ground. Wet cells of any depth count, a
     * thin stream carries things too.
     */
    public static Vec3d current(World world, AxisAlignedBB box) {
        int minX = MathHelper.floor(box.minX), maxX = MathHelper.ceil(box.maxX);
        int minY = MathHelper.floor(box.minY), maxY = MathHelper.ceil(box.maxY);
        int minZ = MathHelper.floor(box.minZ), maxZ = MathHelper.ceil(box.maxZ);
        double vx = 0.0D, vz = 0.0D;
        int cells = 0, falling = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for(int x = minX; x < maxX; x++) {
            for(int y = minY; y < maxY; y++) {
                for(int z = minZ; z < maxZ; z++) {
                    if(height(world, x, y, z) == 0) continue;
                    cells++;
                    // Compare the surfaces of the columns so water below a sloped surface is pushed too
                    double surface = columnSurface(world, x, y, z);
                    for(EnumFacing facing : EnumFacing.HORIZONTALS) {
                        int nx = x + facing.getXOffset(), nz = z + facing.getZOffset();
                        if(height(world, nx, y, nz) == 0) continue;
                        double difference = surface - columnSurface(world, nx, y, nz);
                        vx += facing.getXOffset() * difference * 0.5D;
                        vz += facing.getZOffset() * difference * 0.5D;
                    }
                    // Water pouring over an edge drags down, like vanilla's falling water
                    IBlockState below = world.getBlockState(pos.setPos(x, y - 1, z));
                    if(height(world, x, y - 1, z) == 0 && !below.getMaterial().blocksMovement() && !below.getMaterial().isLiquid()) falling++;
                }
            }
        }
        if(cells == 0) return Vec3d.ZERO;
        return new Vec3d(vx / cells, -(double)falling / cells, vz / cells);
    }

    /** Height of the water surface above the bottom of block y, following the water up its column */
    private static double columnSurface(World world, int x, int y, int z) {
        int top = y;
        while(top < y + 16 && height(world, x, top, z) >= 255 && height(world, x, top + 1, z) > 0) top++;
        return top - y + height(world, x, top, z) / 255.0D;
    }

    /** Highest surface of simulated water columns that count for entities in block rows minY (inclusive) to maxY (exclusive), NaN if none */
    public static double surfaceLevel(World world, AxisAlignedBB box, int minY, int maxY) {
        int minX = MathHelper.floor(box.minX), maxX = MathHelper.ceil(box.maxX);
        int minZ = MathHelper.floor(box.minZ), maxZ = MathHelper.ceil(box.maxZ);
        double level = Double.NaN;
        for(int x = minX; x < maxX; x++) {
            for(int y = minY; y < maxY; y++) {
                for(int z = minZ; z < maxZ; z++) {
                    int height = height(world, x, y, z);
                    if(counts(world, x, y, z, height)) {
                        double surface = y + columnSurface(world, x, y, z);
                        if(Double.isNaN(level) || surface > level) level = surface;
                    }
                }
            }
        }
        return level;
    }

    /** Same as EntityBoat.getWaterLevelAbove, for simulated water: the first row that is not completely full */
    public static float levelAbove(World world, AxisAlignedBB box, int fromY, int toY) {
        int minX = MathHelper.floor(box.minX), maxX = MathHelper.ceil(box.maxX);
        int minZ = MathHelper.floor(box.minZ), maxZ = MathHelper.ceil(box.maxZ);
        for(int y = fromY; y < toY; y++) {
            float level = 0.0F;
            for(int x = minX; x < maxX; x++) {
                for(int z = minZ; z < maxZ; z++) {
                    int height = height(world, x, y, z);
                    if(counts(world, x, y, z, height)) level = Math.max(level, surface(world, x, y, z, height));
                }
            }
            if(level < 1.0F) return y + level;
        }
        return toY + 1;
    }

    /**
     * Whether a point, like an entity's eyes, is under the rendered surface of simulated water. Unlike
     * swimming this has no minimum depth, eyes can sink into a partly filled block.
     */
    public static boolean isSubmerged(World world, double x, double y, double z) {
        int bx = MathHelper.floor(x), by = MathHelper.floor(y), bz = MathHelper.floor(z);
        int height = height(world, bx, by, bz);
        if(height == 0) return false;
        float surface = height(world, bx, by + 1, bz) > 0 ? 1.0F : height / 255.0F * RENDER_HEIGHT;
        return y < by + surface;
    }
}
