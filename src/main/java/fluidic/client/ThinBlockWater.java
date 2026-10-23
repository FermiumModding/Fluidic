package fluidic.client;

import fluidic.block.FluidicWater;
import fluidic.sim.Water;
import net.minecraft.block.Block;
import net.minecraft.block.BlockFence;
import net.minecraft.block.BlockPane;
import net.minecraft.block.BlockWall;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.biome.BiomeColorHelper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Water beside thin blocks like glass panes. They block water like a wall, but only fill part of their block, so the
 * water beside one is drawn reaching into its block up to the glass instead of stopping at the edge of the block and
 * leaving a gap. Seen from above, the block is split along the edges of its parts into a grid of rectangles. Open
 * rectangles touching water, beside the block or diagonally at a corner like inside a corner of panes, are wet, and
 * so are the open rectangles they connect to. Water on each side of the glass is drawn on its own side only.
 *
 * Water blocks are drawn into the block with the chunk, simulated water by WaterRenderer, which redraws as it moves.
 * Where both reach the same open part, the chunk draws it.
 */
public final class ThinBlockWater {

    /** Faces against the block stand off it this far, they would flicker against its faces */
    private static final double INSET = 0.002D;
    private static final float SIDE_SHADE = 0.8F;
    /** Half the thickness of posts and arms, in blocks */
    private static final double PANE = 1.0D / 16.0D, WALL_POST = 4.0D / 16.0D, WALL_ARM = 3.0D / 16.0D, FENCE_POST = 2.0D / 16.0D;
    /** The eight blocks around a block, x and z offsets: west, east, north, south, then the corners */
    private static final int[][] AROUND = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}, {-1, -1}, {1, -1}, {-1, 1}, {1, 1}};
    private static final int DRY = 0, SIMULATED = 1, BLOCK = 2;

    /** Takes a vertex of water, shade darkens its color like vanilla shades the sides of water */
    public interface Vertex {
        void put(double x, double y, double z, float u, float v, float shade);
    }

    private ThinBlockWater() {}

    /**
     * Whether simulated water at a position is the first simulated water around a thin block, the one whose section
     * draws the block's water, so it is drawn once.
     */
    public static boolean drawsFor(BlockPos thin, BlockPos water) {
        for(int[] offset : AROUND) {
            BlockPos at = thin.add(offset[0], 0, offset[1]);
            if(FluidicWater.simHeight(at) > 0) return at.equals(water);
        }
        return false;
    }

    /** Draws the water of water blocks into a thin block while its chunk is drawn, returns whether anything was drawn */
    public static boolean drawBesideWater(IBlockAccess world, BlockPos pos, IBlockState state, BufferBuilder buffer) {
        int light = world.getCombinedLight(pos, 0);
        int sky = light >> 16 & 65535, block = light & 65535;
        int color = BiomeColorHelper.getWaterColorAtPos(world, pos);
        int red = color >> 16 & 255, green = color >> 8 & 255, blue = color & 255;
        TextureAtlasSprite sprite = Minecraft.getMinecraft().getTextureMapBlocks().getAtlasSprite("minecraft:blocks/water_still");
        // Shader packs tell water by the block its vertices are tagged with
        boolean tagged = ChunkWater.isActive() && ChunkWater.tagAs(FluidicWater.state(Water.LEVELS), pos, world, buffer);
        try {
            return draw(world, pos, state, false, (x, y, z, u, v, shade) -> buffer.pos(x, y, z)
                    .color((int)(red * shade), (int)(green * shade), (int)(blue * shade), 255).tex(u, v).lightmap(sky, block).endVertex(),
                    sprite, pos.getX(), pos.getY(), pos.getZ());
        }
        finally {
            if(tagged) ChunkWater.untag(buffer);
        }
    }

    /**
     * Draws the water reaching into a thin block at pos, its vertices at (x, y, z) plus their place in the block. The
     * chunk draws the water of water blocks, simulated false, WaterRenderer the water fed by simulated water alone.
     * Returns whether anything was drawn.
     */
    public static boolean draw(IBlockAccess world, BlockPos pos, IBlockState state, boolean simulated, Vertex out, TextureAtlasSprite sprite, double x, double y, double z) {
        int[] around = new int[AROUND.length];
        boolean any = false;
        for(int k = 0; k < AROUND.length; k++) {
            around[k] = water(world, pos.add(AROUND[k][0], 0, AROUND[k][1]));
            any |= around[k] != DRY;
        }
        if(!any) return false;

        // The grid of rectangles the edges of the block's parts split it into
        List<double[]> parts = parts(world, pos, state);
        double[] xs = edges(parts, 0), zs = edges(parts, 1);
        int nx = xs.length - 1, nz = zs.length - 1;
        boolean[][] open = new boolean[nx][nz];
        for(int i = 0; i < nx; i++) {
            for(int j = 0; j < nz; j++) {
                double cx = (xs[i] + xs[i + 1]) / 2, cz = (zs[j] + zs[j + 1]) / 2;
                open[i][j] = true;
                for(double[] part : parts) {
                    if(cx > part[0] && cx < part[2] && cz > part[1] && cz < part[3]) open[i][j] = false;
                }
            }
        }

        // Wet rectangles, joined into the separate pools of water they belong to
        int[][] pool = new int[nx][nz];
        for(int[] row : pool) Arrays.fill(row, -1);
        List<boolean[]> feeds = new ArrayList<>();
        for(int i = 0; i < nx; i++) {
            for(int j = 0; j < nz; j++) {
                if(!open[i][j] || pool[i][j] >= 0 || fedBy(i, j, nx, nz, around) == 0) continue;
                boolean[] fed = new boolean[AROUND.length];
                fill(pool, open, i, j, feeds.size(), nx, nz, around, fed);
                feeds.add(fed);
            }
        }
        boolean drawn = false;
        for(int p = 0; p < feeds.size(); p++) {
            boolean[] fed = feeds.get(p);
            boolean blocks = false, sim = false;
            for(int k = 0; k < AROUND.length; k++) {
                if(!fed[k]) continue;
                blocks |= around[k] == BLOCK;
                sim |= around[k] == SIMULATED;
            }
            if(simulated ? blocks || !sim : !blocks) continue;
            float[] heights = heights(world, pos, fed);
            // Water going on up into the block above, more water reaching into it, has no surface here
            boolean covered = heights[0] >= 1.0F && heights[1] >= 1.0F && heights[2] >= 1.0F && heights[3] >= 1.0F
                    && FluidicWater.isThin(world, world.getBlockState(pos.up()));
            for(int i = 0; i < nx; i++) {
                for(int j = 0; j < nz; j++) {
                    if(pool[i][j] != p) continue;
                    rectangle(world, pos, out, sprite, x, y, z, xs, zs, i, j, pool, heights, around, covered);
                    drawn = true;
                }
            }
        }
        return drawn;
    }

    /** Parts of a block seen from above, as {minX, minZ, maxX, maxZ} within the block */
    private static List<double[]> parts(IBlockAccess world, BlockPos pos, IBlockState state) {
        List<double[]> parts = new ArrayList<>(5);
        IBlockState actual = state.getActualState(world, pos);
        Block block = state.getBlock();
        AxisAlignedBB box = state.getBoundingBox(world, pos);
        boolean north = connects(actual, "north"), south = connects(actual, "south"), west = connects(actual, "west"), east = connects(actual, "east");
        if(!north && !south && !west && !east && !(block instanceof BlockPane || block instanceof BlockWall || block instanceof BlockFence)) {
            parts.add(new double[] {box.minX, box.minZ, box.maxX, box.maxZ});
            return parts;
        }
        // Blocks joining their neighbours, like panes, walls and fences: a post with arms toward them. Their bounding box
        // spans the open quarter inside a corner too, their arms do not.
        double post, arm;
        if(block instanceof BlockPane) {
            post = arm = PANE;
        }
        else if(block instanceof BlockWall) {
            post = actual.getValue(BlockWall.UP) ? WALL_POST : WALL_ARM;
            arm = WALL_ARM;
        }
        else if(block instanceof BlockFence) {
            post = FENCE_POST;
            arm = PANE;
        }
        else {
            // Other mods' blocks: as thick as the bounding box across a side it does not reach
            double thickness = 1.0D;
            if(box.minX > 0.0D || box.maxX < 1.0D) thickness = Math.min(thickness, box.maxX - box.minX);
            if(box.minZ > 0.0D || box.maxZ < 1.0D) thickness = Math.min(thickness, box.maxZ - box.minZ);
            post = arm = thickness < 1.0D ? thickness / 2.0D : PANE;
        }
        parts.add(new double[] {0.5D - post, 0.5D - post, 0.5D + post, 0.5D + post});
        if(north) parts.add(new double[] {0.5D - arm, 0.0D, 0.5D + arm, 0.5D});
        if(south) parts.add(new double[] {0.5D - arm, 0.5D, 0.5D + arm, 1.0D});
        if(west) parts.add(new double[] {0.0D, 0.5D - arm, 0.5D, 0.5D + arm});
        if(east) parts.add(new double[] {0.5D, 0.5D - arm, 1.0D, 0.5D + arm});
        return parts;
    }

    /** Whether a block joins the block on one side, by a property named after the side like panes and walls have */
    private static boolean connects(IBlockState state, String side) {
        for(IProperty<?> property : state.getPropertyKeys()) {
            if(property.getName().equals(side) && property.getValueClass() == Boolean.class) return (Boolean)state.getValue(property);
        }
        return false;
    }

    /** Whether a block holds water: a water block, simulated water or none */
    private static int water(IBlockAccess world, BlockPos pos) {
        if(FluidicWater.isWater(world.getBlockState(pos))) return BLOCK;
        return FluidicWater.simHeight(pos) > 0 ? SIMULATED : DRY;
    }

    /** Sorted edges of the parts along x (axis 0) or z (axis 1), with the edges of the block */
    private static double[] edges(List<double[]> parts, int axis) {
        double[] edges = new double[parts.size() * 2 + 2];
        int n = 0;
        edges[n++] = 0.0D;
        edges[n++] = 1.0D;
        for(double[] part : parts) {
            if(part[axis] > 0.0D && part[axis] < 1.0D) edges[n++] = part[axis];
            if(part[axis + 2] > 0.0D && part[axis + 2] < 1.0D) edges[n++] = part[axis + 2];
        }
        Arrays.sort(edges, 0, n);
        int unique = 0;
        for(int k = 0; k < n; k++) if(unique == 0 || edges[k] - edges[unique - 1] > 1.0E-6D) edges[unique++] = edges[k];
        return Arrays.copyOf(edges, unique);
    }

    /** The blocks around holding water a rectangle touches at the edge of the block, as bits by AROUND index */
    private static int fedBy(int i, int j, int nx, int nz, int[] around) {
        boolean west = i == 0, east = i == nx - 1, north = j == 0, south = j == nz - 1;
        boolean[] touches = {west, east, north, south, west && north, east && north, west && south, east && south};
        int bits = 0;
        for(int k = 0; k < AROUND.length; k++) if(touches[k] && around[k] != DRY) bits |= 1 << k;
        return bits;
    }

    private static void fill(int[][] pool, boolean[][] open, int i, int j, int id, int nx, int nz, int[] around, boolean[] fed) {
        if(i < 0 || j < 0 || i >= nx || j >= nz || !open[i][j] || pool[i][j] >= 0) return;
        pool[i][j] = id;
        int bits = fedBy(i, j, nx, nz, around);
        for(int k = 0; k < AROUND.length; k++) if((bits & 1 << k) != 0) fed[k] = true;
        fill(pool, open, i - 1, j, id, nx, nz, around, fed);
        fill(pool, open, i + 1, j, id, nx, nz, around, fed);
        fill(pool, open, i, j - 1, id, nx, nz, around, fed);
        fill(pool, open, i, j + 1, id, nx, nz, around, fed);
    }

    /**
     * Surface heights of a pool at the block's corners, northwest, northeast, southwest and southeast, the same as the
     * water feeding it draws its own corners there. Corners no feeding water touches take the others' average.
     */
    private static float[] heights(IBlockAccess world, BlockPos pos, boolean[] fed) {
        float[] heights = new float[4];
        float sum = 0.0F;
        int known = 0;
        for(int c = 0; c < 4; c++) {
            int cx = c & 1, cz = c >> 1;
            BlockPos corner = pos.add(cx, 0, cz);
            float height = Float.NaN;
            for(int k = 0; k < AROUND.length; k++) {
                int dx = AROUND[k][0], dz = AROUND[k][1];
                // Only blocks touching this corner, on its side in x, in z or both
                if(!fed[k] || dx != 0 && dx != cx * 2 - 1 || dz != 0 && dz != cz * 2 - 1) continue;
                float at = FluidicWater.edgeHeight(world, pos.add(dx, 0, dz), corner);
                if(Float.isNaN(height) || at > height) height = at;
            }
            heights[c] = height;
            if(!Float.isNaN(height)) {
                sum += height;
                known++;
            }
        }
        for(int c = 0; c < 4; c++) if(Float.isNaN(heights[c])) heights[c] = known > 0 ? sum / known : Water.RENDER_HEIGHT;
        return heights;
    }

    private static float height(float[] heights, double fx, double fz) {
        double north = heights[0] + (heights[1] - heights[0]) * fx;
        double south = heights[2] + (heights[3] - heights[2]) * fx;
        return (float)(north + (south - north) * fz);
    }

    /** A wet rectangle: its surface, and faces where it meets the block or dry ground beside the block */
    private static void rectangle(IBlockAccess world, BlockPos pos, Vertex out, TextureAtlasSprite sprite, double x, double y, double z,
                                  double[] xs, double[] zs, int i, int j, int[][] pool, float[] heights, int[] around, boolean covered) {
        double x0 = xs[i], x1 = xs[i + 1], z0 = zs[j], z1 = zs[j + 1];
        int nx = xs.length - 1, nz = zs.length - 1;
        double[][] top = {{x0, z0}, {x0, z1}, {x1, z1}, {x1, z0}};
        for(int pass = 0; pass < 2 && !covered; pass++) {
            for(int k = 0; k < 4; k++) {
                double[] c = top[pass == 0 ? k : 3 - k];
                out.put(x + c[0], y + height(heights, c[0], c[1]), z + c[1], sprite.getInterpolatedU(c[0] * 16.0D), sprite.getInterpolatedV(c[1] * 16.0D), 1.0F);
            }
        }
        int id = pool[i][j];
        // A face where the rectangle beside it is not this pool, set off the block, or at the edge of the block where
        // the water does not go on
        if(i > 0 ? pool[i - 1][j] != id : !continues(world, pos.west(), around[0])) {
            double at = i > 0 ? x0 + INSET : x0;
            side(out, sprite, x, y, z, heights, at, z0, at, z1);
        }
        if(i < nx - 1 ? pool[i + 1][j] != id : !continues(world, pos.east(), around[1])) {
            double at = i < nx - 1 ? x1 - INSET : x1;
            side(out, sprite, x, y, z, heights, at, z0, at, z1);
        }
        if(j > 0 ? pool[i][j - 1] != id : !continues(world, pos.north(), around[2])) {
            double at = j > 0 ? z0 + INSET : z0;
            side(out, sprite, x, y, z, heights, x0, at, x1, at);
        }
        if(j < nz - 1 ? pool[i][j + 1] != id : !continues(world, pos.south(), around[3])) {
            double at = j < nz - 1 ? z1 - INSET : z1;
            side(out, sprite, x, y, z, heights, x0, at, x1, at);
        }
    }

    /** Whether water at the edge of the block goes on into the block beside it, water or another thin block */
    private static boolean continues(IBlockAccess world, BlockPos pos, int water) {
        return water != DRY || FluidicWater.isThin(world, world.getBlockState(pos));
    }

    /** A face from the bottom of the block up to the surface between two points, drawn from both sides */
    private static void side(Vertex out, TextureAtlasSprite sprite, double x, double y, double z, float[] heights, double ax, double az, double bx, double bz) {
        float ha = height(heights, ax, az), hb = height(heights, bx, bz);
        double length = Math.abs(bx - ax) + Math.abs(bz - az);
        double[][] corners = {{ax, az, 0.0D, 0.0D}, {ax, az, ha, 0.0D}, {bx, bz, hb, length}, {bx, bz, 0.0D, length}};
        for(int pass = 0; pass < 2; pass++) {
            for(int k = 0; k < 4; k++) {
                double[] c = corners[pass == 0 ? k : 3 - k];
                out.put(x + c[0], y + c[2], z + c[1], sprite.getInterpolatedU(c[3] * 8.0D), sprite.getInterpolatedV((1.0D - c[2]) * 8.0D), SIDE_SHADE);
            }
        }
    }
}
