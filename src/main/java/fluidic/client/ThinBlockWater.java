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
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.biome.BiomeColorHelper;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Water beside thin blocks like glass panes. They block water like a wall, but only fill part of their block, so the
 * water beside one is drawn reaching into its block up to the glass instead of stopping at the edge of the block and
 * leaving a gap. Seen from above, the block is split along the edges of its parts into a grid of rectangles. Open
 * rectangles touching water, beside the block or diagonally at a corner like inside a corner of panes, are wet, and
 * so are the open rectangles they connect to, but not ones at the edge of the block with open dry ground beyond. Water
 * on each side of the glass is drawn on its own side only.
 *
 * Water blocks are drawn into the block with the chunk, simulated water by WaterRenderer, which redraws as it moves.
 * Where both reach the same open part, the chunk draws it.
 */
public final class ThinBlockWater {

    /** Faces against the block stand off it this far, they would flicker against its faces */
    private static final double INSET = 0.002D;
    /** Half the width of the split across the free end of a thin block joining one block, see parts */
    private static final double SPLIT = 1.0E-4D;
    /** How far a surface reaches over the edge of the block toward water beside it, see rectangle */
    private static final double OVERLAP = 0.005D;
    /** How far under the water beside the part of a surface reaching over the edge of the block lies, see rectangle */
    private static final double UNDER = 5.0E-4D;
    private static final float SIDE_SHADE = 0.8F;
    /** Half the thickness of posts and arms, in blocks */
    private static final double PANE = 1.0D / 16.0D, WALL_POST = 4.0D / 16.0D, WALL_ARM = 3.0D / 16.0D, FENCE_POST = 2.0D / 16.0D;
    /** The eight blocks around a block, x and z offsets: west, east, north, south, then the corners */
    private static final int[][] AROUND = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}, {-1, -1}, {1, -1}, {-1, 1}, {1, 1}};
    private static final int DRY = 0, SIMULATED = 1, BLOCK = 2;
    /** Corner heights of water filling its block, read only */
    private static final float[] FULL = {1.0F, 1.0F, 1.0F, 1.0F};

    /** Takes a vertex of water, shade darkens its color like vanilla shades the sides of water */
    public interface Vertex {
        void put(double x, double y, double z, float u, float v, float shade);
    }

    private ThinBlockWater() {}

    private static float edgeHeight(IBlockAccess world, BlockPos water, BlockPos corner) {
        return FluidicWater.edgeHeight(world, water, corner);
    }

    /**
     * Whether simulated water at a position is the first simulated water around a thin block, the one whose section
     * draws the block's water, so it is drawn once.
     */
    public static boolean drawsFor(IBlockAccess world, BlockPos thin, BlockPos water) {
        for(int[] offset : AROUND) {
            BlockPos at = thin.add(offset[0], 0, offset[1]);
            if(FluidicWater.simDrawnAt(world, at)) return at.equals(water);
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
        Layout layout = layout(world, pos, state);
        if(layout == null) return false;
        int[] around = layout.around;
        double[] xs = layout.xs, zs = layout.zs;
        int nx = layout.nx, nz = layout.nz;
        int[][] pool = layout.pool;
        List<boolean[]> feeds = layout.feeds;
        boolean drawn = false;
        Layout above = above(world, pos);
        boolean flooded = waterAbove(world, pos);
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
            // Under vanilla's rules flowing water shows the flowing texture, turned the way its surface runs down, like the
            // water beside it. Its sides show the flowing texture too, like vanilla's water sides.
            TextureAtlasSprite top = sprite, sides = sprite;
            double angle = Double.NaN;
            if(FluidicWater.vanillaRules) {
                TextureAtlasSprite flowing = Minecraft.getMinecraft().getTextureMapBlocks().getAtlasSprite("minecraft:blocks/water_flow");
                sides = flowing;
                angle = slopeAngle(heights);
                if(!Double.isNaN(angle)) top = flowing;
            }
            // Rectangles under water in the thin block above, like a stream falling down through panes onto water running off
            // to the side in the panes below. The water there goes on up into it and fills the block, like water with water
            // above it anywhere, with no surface of its own. The block above can be shaped otherwise, like a corner of panes
            // over a row going on past it, and hold water over part of the pool only: the rest keeps its own surface, a
            // wall rising from it to the full part.
            boolean[][] wet = new boolean[nx][nz];
            for(int i = 0; i < nx; i++) {
                for(int j = 0; j < nz; j++) wet[i][j] = pool[i][j] == p && (flooded || above != null && wetOver(layout, i, j, above));
            }
            for(int i = 0; i < nx; i++) {
                for(int j = 0; j < nz; j++) {
                    if(pool[i][j] != p) continue;
                    if(wet[i][j]) {
                        rectangle(world, pos, out, sprite, sides, Double.NaN, x, y, z, xs, zs, i, j, pool, layout.solid, FULL, around, true);
                        if(i > 0 && pool[i - 1][j] == p && !wet[i - 1][j]) wall(out, sides, x, y, z, heights, xs[i], zs[j], xs[i], zs[j + 1]);
                        if(i < nx - 1 && pool[i + 1][j] == p && !wet[i + 1][j]) wall(out, sides, x, y, z, heights, xs[i + 1], zs[j], xs[i + 1], zs[j + 1]);
                        if(j > 0 && pool[i][j - 1] == p && !wet[i][j - 1]) wall(out, sides, x, y, z, heights, xs[i], zs[j], xs[i + 1], zs[j]);
                        if(j < nz - 1 && pool[i][j + 1] == p && !wet[i][j + 1]) wall(out, sides, x, y, z, heights, xs[i], zs[j + 1], xs[i + 1], zs[j + 1]);
                    }
                    else rectangle(world, pos, out, top, sides, angle, x, y, z, xs, zs, i, j, pool, layout.solid, heights, around, false);
                    drawn = true;
                }
            }
        }
        return drawn;
    }

    /** How water reaches into a thin block: the water around it, the grid of rectangles and the pools they form */
    private static final class Layout {
        int[] around;
        double[] xs, zs;
        int nx, nz;
        /** Pool of each rectangle, -1 for dry */
        int[][] pool;
        /** Rectangles a part of the block fills, like the glass of a pane */
        boolean[][] solid;
        /** The water around feeding each pool, by AROUND index */
        List<boolean[]> feeds;
    }

    /** The layout of the water reaching into a thin block, null when no water is around it */
    @Nullable
    private static Layout layout(IBlockAccess world, BlockPos pos, IBlockState state) {
        int[] around = new int[AROUND.length];
        boolean any = false;
        for(int k = 0; k < AROUND.length; k++) {
            around[k] = water(world, pos.add(AROUND[k][0], 0, AROUND[k][1]));
            any |= around[k] != DRY;
        }
        if(!any) return null;

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

        // Water spreads only into rectangles inside the block or against blocks holding it back. One no water touches at
        // an edge of the block with open dry ground beyond, like around the post of a pane ending at a hole that water
        // runs through, would show water spilling out where none flows.
        boolean[] spills = new boolean[4];
        for(int k = 0; k < 4; k++) {
            spills[k] = around[k] == DRY && !world.getBlockState(pos.add(AROUND[k][0], 0, AROUND[k][1])).getMaterial().blocksMovement();
        }
        boolean[][] closed = new boolean[nx][nz];
        for(int i = 0; i < nx; i++) {
            for(int j = 0; j < nz; j++) {
                int bits = fedBy(i, j, nx, nz, around);
                boolean atSpill = i == 0 && spills[0] || i == nx - 1 && spills[1] || j == 0 && spills[2] || j == nz - 1 && spills[3];
                // Under vanilla's rules water touching a corner alone, beside open dry ground, is not there: like water falling
                // past the corner of a pane with nothing beside it, it would hang in the air
                closed[i][j] = !open[i][j] || atSpill && (bits == 0 || FluidicWater.vanillaRules && (bits & 0xF) == 0);
            }
        }

        // Wet rectangles, joined into the separate pools of water they belong to
        int[][] pool = new int[nx][nz];
        for(int[] row : pool) Arrays.fill(row, -1);
        List<boolean[]> feeds = new ArrayList<>();
        for(int i = 0; i < nx; i++) {
            for(int j = 0; j < nz; j++) {
                if(closed[i][j] || pool[i][j] >= 0 || fedBy(i, j, nx, nz, around) == 0) continue;
                boolean[] fed = new boolean[AROUND.length];
                fill(pool, closed, i, j, feeds.size(), nx, nz, around, fed);
                feeds.add(fed);
            }
        }
        Layout layout = new Layout();
        layout.around = around;
        layout.xs = xs;
        layout.zs = zs;
        layout.nx = nx;
        layout.nz = nz;
        layout.pool = pool;
        layout.feeds = feeds;
        boolean[][] solid = new boolean[nx][nz];
        for(int i = 0; i < nx; i++) {
            for(int j = 0; j < nz; j++) solid[i][j] = !open[i][j];
        }
        layout.solid = solid;
        return layout;
    }

    /** Index of the span of sorted edges a coordinate inside the block lies in */
    private static int span(double[] edges, double at) {
        for(int n = 0; n < edges.length - 2; n++) if(at < edges[n + 1]) return n;
        return edges.length - 2;
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
        // Under vanilla's rules one joining a single block, like the end of a row of panes, holds the water on each side of
        // its glass apart across the whole block, level with the water on that side, see FluidicWater.isThin. Around the
        // free end of its post they would have to meet at one height.
        if(FluidicWater.vanillaRules && (north ? 1 : 0) + (south ? 1 : 0) + (west ? 1 : 0) + (east ? 1 : 0) == 1) {
            // No glass is drawn across the free end, a hair's width keeps the two sides apart without a strip of no water
            // showing between them
            if(north) parts.add(new double[] {0.5D - SPLIT, 0.5D, 0.5D + SPLIT, 1.0D});
            if(south) parts.add(new double[] {0.5D - SPLIT, 0.0D, 0.5D + SPLIT, 0.5D});
            if(west) parts.add(new double[] {0.5D, 0.5D - SPLIT, 1.0D, 0.5D + SPLIT});
            if(east) parts.add(new double[] {0.0D, 0.5D - SPLIT, 0.5D, 0.5D + SPLIT});
        }
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
        // Not water in another thin block, it is drawn split by that block's glass, see FluidicWater.isThin
        return FluidicWater.simDrawnAt(world, pos) ? SIMULATED : DRY;
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

    private static void fill(int[][] pool, boolean[][] closed, int i, int j, int id, int nx, int nz, int[] around, boolean[] fed) {
        if(i < 0 || j < 0 || i >= nx || j >= nz || closed[i][j] || pool[i][j] >= 0) return;
        pool[i][j] = id;
        int bits = fedBy(i, j, nx, nz, around);
        for(int k = 0; k < AROUND.length; k++) if((bits & 1 << k) != 0) fed[k] = true;
        fill(pool, closed, i - 1, j, id, nx, nz, around, fed);
        fill(pool, closed, i + 1, j, id, nx, nz, around, fed);
        fill(pool, closed, i, j - 1, id, nx, nz, around, fed);
        fill(pool, closed, i, j + 1, id, nx, nz, around, fed);
    }

    /**
     * Surface heights of a pool at the block's corners, northwest, northeast, southwest and southeast, the same as the
     * water feeding it draws its own corners there. Corners no feeding water touches take the others' average.
     */
    private static float[] heights(IBlockAccess world, BlockPos pos, boolean[] fed) {
        float[] heights = new float[4];
        float sum = 0.0F;
        int known = 0;
        // Water reaching into the thin block above too, the water here goes on up into it and fills the block, falling
        // water beside raises the corners like everywhere else. Left out, it would end below the water above.
        BlockPos up = pos.up();
        boolean wetAbove = FluidicWater.vanillaRules && pos.getY() < 255 && FluidicWater.isThin(world, up, world.getBlockState(up)) && sideWater(world, up);
        for(int c = 0; c < 4; c++) {
            int cx = c & 1, cz = c >> 1;
            BlockPos corner = pos.add(cx, 0, cz);
            float height = Float.NaN, columns = Float.NaN;
            for(int k = 0; k < AROUND.length; k++) {
                int dx = AROUND[k][0], dz = AROUND[k][1];
                // Only blocks touching this corner, on its side in x, in z or both
                if(!fed[k] || dx != 0 && dx != cx * 2 - 1 || dz != 0 && dz != cz * 2 - 1) continue;
                BlockPos water = pos.add(dx, 0, dz);
                float at = edgeHeight(world, water, corner);
                // Under vanilla's rules water falling past beside flowing water leaves the corner to that water, as water
                // blocks leave columns out of their corners, see FluidicWater.surfaceCorner. Taken, the water inside the
                // block would stand up against the falling stream above the water beside it.
                if(FluidicWater.vanillaRules && !wetAbove && FluidicWater.renderHeight(world, water) >= 1.0F) {
                    columns = at;
                    continue;
                }
                if(Float.isNaN(height) || at > height) height = at;
            }
            if(Float.isNaN(height)) height = columns;
            heights[c] = height;
            if(!Float.isNaN(height)) {
                sum += height;
                known++;
            }
        }
        // The surface can slope. Corners across the glass from the water take the corner on the water's side of the same
        // edge, so the surface runs level across the block toward the glass. The average would tilt each block on its own,
        // and blocks side by side in a row of panes would meet at different heights with a slit between them.
        float[] mirrored = heights.clone();
        for(int c = 0; c < 4; c++) {
            if(!Float.isNaN(heights[c])) continue;
            // Same x across z, then same z across x: corners 0 northwest, 1 northeast, 2 southwest, 3 southeast
            float acrossZ = heights[c ^ 2], acrossX = heights[c ^ 1];
            if(!Float.isNaN(acrossZ)) mirrored[c] = acrossZ;
            else if(!Float.isNaN(acrossX)) mirrored[c] = acrossX;
        }
        System.arraycopy(mirrored, 0, heights, 0, 4);
        for(int c = 0; c < 4; c++) if(Float.isNaN(heights[c])) heights[c] = known > 0 ? sum / known : Water.RENDER_HEIGHT;
        return heights;
    }

    /** The layout of the water reaching into a thin block above, which water here goes on up into, null when none */
    @Nullable
    private static Layout above(IBlockAccess world, BlockPos pos) {
        if(pos.getY() >= 255) return null;
        BlockPos up = pos.up();
        IBlockState state = world.getBlockState(up);
        return FluidicWater.isThin(world, up, state) ? layout(world, up, state) : null;
    }

    /** Whether water in the thin block above is over a rectangle */
    private static boolean wetOver(Layout layout, int i, int j, Layout above) {
        int ai = span(above.xs, (layout.xs[i] + layout.xs[i + 1]) / 2.0D), aj = span(above.zs, (layout.zs[j] + layout.zs[j + 1]) / 2.0D);
        return above.pool[ai][aj] >= 0;
    }

    /** Surface heights of a wet rectangle: full under water in the thin block above, see draw, or its pool's */
    private static float[] rectangleHeights(IBlockAccess world, BlockPos pos, Layout layout, int i, int j) {
        if(waterAbove(world, pos)) return FULL;
        Layout above = above(world, pos);
        if(above != null && wetOver(layout, i, j, above)) return FULL;
        return heights(world, pos, layout.feeds.get(layout.pool[i][j]));
    }

    /**
     * Whether water of its own block is above a thin block, a water block or simulated water drawn there, as
     * FluidicWater.renderHeight asks. Like a pane under the sea, the water in it goes on up into that water across the
     * whole block and has no surface: drawn, it would show on top of the glass under the water above.
     */
    private static boolean waterAbove(IBlockAccess world, BlockPos pos) {
        if(pos.getY() >= 255) return false;
        BlockPos up = pos.up();
        return world.getBlockState(up).getMaterial().isLiquid()
                || FluidicWater.simHeight(up) >= FluidicWater.MIN_DRAWN && FluidicWater.simDrawnAt(world, up)
                || FluidicWater.drawnSimulated(world, up);
    }

    /**
     * A face where part of a pool under water above, full, meets the rest of it at its own surface, from that surface up
     * to the top of the block between points (ax, az) and (bx, bz), drawn from both sides
     */
    private static void wall(Vertex out, TextureAtlasSprite sprite, double x, double y, double z, float[] heights, double ax, double az, double bx, double bz) {
        float ha = height(heights, ax, az), hb = height(heights, bx, bz);
        if(ha >= 1.0F - 1.0E-3F && hb >= 1.0F - 1.0E-3F) return;
        double length = Math.abs(bx - ax) + Math.abs(bz - az);
        double[][] corners = {{ax, az, ha, 0.0D}, {ax, az, 1.0D, 0.0D}, {bx, bz, 1.0D, length}, {bx, bz, hb, length}};
        for(int pass = 0; pass < 2; pass++) {
            for(int k = 0; k < 4; k++) {
                double[] c = corners[pass == 0 ? k : 3 - k];
                out.put(x + c[0], y + c[2], z + c[1], sprite.getInterpolatedU(c[3] * 8.0D), sprite.getInterpolatedV((1.0D - c[2]) * 8.0D), SIDE_SHADE);
            }
        }
    }

    private static float height(float[] heights, double fx, double fz) {
        double north = heights[0] + (heights[1] - heights[0]) * fx;
        double south = heights[2] + (heights[3] - heights[2]) * fx;
        return (float)(north + (south - north) * fz);
    }

    /**
     * Direction a pool's surface runs down, from its corner heights, as BlockLiquid.getSlopeAngle gives it for the flow of
     * a water block. NaN for a level surface.
     */
    private static double slopeAngle(float[] heights) {
        // Corners 0 northwest, 1 northeast, 2 southwest, 3 southeast. The flow goes downhill, against the rise.
        double fx = (heights[0] + heights[2]) - (heights[1] + heights[3]);
        double fz = (heights[0] + heights[1]) - (heights[2] + heights[3]);
        if(Math.abs(fx) < 1.0E-3D && Math.abs(fz) < 1.0E-3D) return Double.NaN;
        return Math.atan2(fz, fx) - Math.PI / 2.0D;
    }

    /**
     * Texture coordinates at a point of the block seen from above, u then v. The still texture square on, or the flowing
     * texture turned by angle as BlockFluidRenderer turns it over a whole block, the same mapping at any point of it.
     */
    private static float[] topUV(TextureAtlasSprite sprite, double angle, double px, double pz) {
        if(Double.isNaN(angle)) return new float[] {sprite.getInterpolatedU(px * 16.0D), sprite.getInterpolatedV(pz * 16.0D)};
        double s = Math.sin(angle) * 0.25D, c = Math.cos(angle) * 0.25D;
        double u = -c - s + 2.0D * c * px + 2.0D * s * pz;
        double v = -c + s - 2.0D * s * px + 2.0D * c * pz;
        return new float[] {sprite.getInterpolatedU(8.0D + u * 16.0D), sprite.getInterpolatedV(8.0D + v * 16.0D)};
    }

    /** A wet rectangle: its surface, and faces where it meets the block or dry ground beside the block */
    private static void rectangle(IBlockAccess world, BlockPos pos, Vertex out, TextureAtlasSprite sprite, TextureAtlasSprite sides, double angle,
                                  double x, double y, double z, double[] xs, double[] zs, int i, int j, int[][] pool, boolean[][] solid, float[] heights, int[] around, boolean covered) {
        double x0 = xs[i], x1 = xs[i + 1], z0 = zs[j], z1 = zs[j + 1];
        int nx = xs.length - 1, nz = zs.length - 1;
        // A sloped surface meeting the water beside the block at an edge splits it with more corners than that water's
        // surface has, and single pixels show through along it. Reaching a hair over the edge covers them, like
        // WaterRenderer does between meshes.
        double tx0 = x0, tx1 = x1, tz0 = z0, tz1 = z1;
        if(i == 0 && around[0] != DRY) tx0 -= OVERLAP;
        if(i == nx - 1 && around[1] != DRY) tx1 += OVERLAP;
        if(j == 0 && around[2] != DRY) tz0 -= OVERLAP;
        if(j == nz - 1 && around[3] != DRY) tz1 += OVERLAP;
        double[][] top = {{tx0, tz0}, {tx0, tz1}, {tx1, tz1}, {tx1, tz0}};
        for(int pass = 0; pass < 2 && !covered; pass++) {
            for(int k = 0; k < 4; k++) {
                double[] c = top[pass == 0 ? k : 3 - k];
                // Textured as at the edge it reaches over: past it the coordinates would leave the sprite and show a sliver
                // of the texture beside it on the atlas
                float[] uv = topUV(sprite, angle, Math.max(0.0D, Math.min(1.0D, c[0])), Math.max(0.0D, Math.min(1.0D, c[1])));
                // Just under its corners like every water surface, see FluidicWater.SURFACE_DROP, and over the edge a hair
                // under the water beside, which stays on top there instead of fighting it
                boolean over = c[0] < 0.0D || c[0] > 1.0D || c[1] < 0.0D || c[1] > 1.0D;
                double drop = FluidicWater.SURFACE_DROP + (over ? UNDER : 0.0D);
                out.put(x + c[0], y + height(heights, c[0], c[1]) - drop, z + c[1], uv[0], uv[1], 1.0F);
            }
        }
        sprite = sides;
        // Against the glass the plain overlay texture, seen only from outside like vanilla's water sides against a block:
        // from inside the water it would show as sheets of water standing in it, along the glass and where two rows of
        // panes meet
        TextureAtlasSprite overlay = Minecraft.getMinecraft().getTextureMapBlocks().getAtlasSprite("minecraft:blocks/water_overlay");
        int id = pool[i][j];
        // A face where the rectangle beside it is not this pool, set off the block, or at the edge of the block where
        // the water does not go on. The second pass of side faces toward -x and +z, the first toward +x and -z.
        if(i > 0 ? pool[i - 1][j] != id : !continues(world, pos, pos.west(), around[0], (z0 + z1) / 2.0D)) {
            double at = i > 0 ? x0 + INSET : x0;
            boolean glass = i > 0 && solid[i - 1][j];
            side(out, glass ? overlay : sprite, x, y, z, heights, at, z0, at, z1, glass ? 1 : -1);
        }
        else if(i == 0) step(world, pos, pos.west(), around[0], out, sprite, x, y, z, heights, x0, z0, x0, z1);
        if(i < nx - 1 ? pool[i + 1][j] != id : !continues(world, pos, pos.east(), around[1], (z0 + z1) / 2.0D)) {
            double at = i < nx - 1 ? x1 - INSET : x1;
            boolean glass = i < nx - 1 && solid[i + 1][j];
            side(out, glass ? overlay : sprite, x, y, z, heights, at, z0, at, z1, glass ? 0 : -1);
        }
        else if(i == nx - 1) step(world, pos, pos.east(), around[1], out, sprite, x, y, z, heights, x1, z0, x1, z1);
        if(j > 0 ? pool[i][j - 1] != id : !continues(world, pos, pos.north(), around[2], (x0 + x1) / 2.0D)) {
            double at = j > 0 ? z0 + INSET : z0;
            boolean glass = j > 0 && solid[i][j - 1];
            side(out, glass ? overlay : sprite, x, y, z, heights, x0, at, x1, at, glass ? 0 : -1);
        }
        else if(j == 0) step(world, pos, pos.north(), around[2], out, sprite, x, y, z, heights, x0, z0, x1, z0);
        if(j < nz - 1 ? pool[i][j + 1] != id : !continues(world, pos, pos.south(), around[3], (x0 + x1) / 2.0D)) {
            double at = j < nz - 1 ? z1 - INSET : z1;
            boolean glass = j < nz - 1 && solid[i][j + 1];
            side(out, glass ? overlay : sprite, x, y, z, heights, x0, at, x1, at, glass ? 1 : -1);
        }
        else if(j == nz - 1) step(world, pos, pos.south(), around[3], out, sprite, x, y, z, heights, x0, z1, x1, z1);
    }

    /**
     * The water beside a thin block can be lower than the water reaching into it, like water running down a slope or, under
     * vanilla's rules, flowing water running off beside a falling stream that fills the block next to it. Neither draws a face between the two, the water
     * beside only draws faces down from itself and none toward the thin block, so the step down to it is drawn here, from
     * that water's surface along the edge of the block up to this water's, between points (ax, az) and (bx, bz) on it.
     */
    private static void step(IBlockAccess world, BlockPos pos, BlockPos beside, int water, Vertex out, TextureAtlasSprite sprite,
                             double x, double y, double z, float[] heights, double ax, double az, double bx, double bz) {
        boolean alongZ = ax == bx;
        float ha = height(heights, ax, az), hb = height(heights, bx, bz);
        float lowA, lowB;
        if(water == DRY) {
            // Another thin block with water reaching into it, like the next pane of a row. Neither draws a face between the
            // two, so where the water there is lower, the higher side draws the step down to it, from its pool at this edge.
            IBlockState state = world.getBlockState(beside);
            if(!FluidicWater.isThin(world, beside, state)) return;
            Layout layout = layout(world, beside, state);
            if(layout == null) return;
            int dx = beside.getX() - pos.getX(), dz = beside.getZ() - pos.getZ();
            double mid = alongZ ? (az + bz) / 2.0D : (ax + bx) / 2.0D;
            int i = alongZ ? dx > 0 ? 0 : layout.nx - 1 : span(layout.xs, mid);
            int j = alongZ ? span(layout.zs, mid) : dz > 0 ? 0 : layout.nz - 1;
            int id = layout.pool[i][j];
            if(id < 0) return;
            float[] there = rectangleHeights(world, beside, layout, i, j);
            // The same edge seen from the block beside
            double edge = alongZ ? dx > 0 ? 0.0D : 1.0D : dz > 0 ? 0.0D : 1.0D;
            lowA = alongZ ? height(there, edge, az) : height(there, ax, edge);
            lowB = alongZ ? height(there, edge, bz) : height(there, bx, edge);
            // Only the higher side draws it
            if(ha <= lowA + 1.0E-3F && hb <= lowB + 1.0E-3F) return;
        }
        else {
            // The surface beside at the two corners of the block on this edge, the two ends of the edge
            BlockPos from = alongZ ? pos.add((int)Math.round(ax), 0, 0) : pos.add(0, 0, (int)Math.round(az));
            BlockPos to = alongZ ? from.add(0, 0, 1) : from.add(1, 0, 0);
            float lowFrom = edgeHeight(world, beside, from), lowTo = edgeHeight(world, beside, to);
            double ta = alongZ ? az : ax, tb = alongZ ? bz : bx;
            lowA = (float)(lowFrom + (lowTo - lowFrom) * ta);
            lowB = (float)(lowFrom + (lowTo - lowFrom) * tb);
            // Whichever is higher: water beside draws no face toward a thin block either way
            if(Math.abs(ha - lowA) <= 1.0E-3F && Math.abs(hb - lowB) <= 1.0E-3F) return;
        }
        double length = Math.abs(bx - ax) + Math.abs(bz - az);
        double[][] corners = {{ax, az, Math.min(lowA, ha), 0.0D}, {ax, az, Math.max(lowA, ha), 0.0D}, {bx, bz, Math.max(lowB, hb), length}, {bx, bz, Math.min(lowB, hb), length}};
        for(int pass = 0; pass < 2; pass++) {
            for(int k = 0; k < 4; k++) {
                double[] c = corners[pass == 0 ? k : 3 - k];
                out.put(x + c[0], y + c[2], z + c[1], sprite.getInterpolatedU(c[3] * 8.0D), sprite.getInterpolatedV((1.0D - c[2]) * 8.0D), SIDE_SHADE);
            }
        }
    }

    /** Whether water at the edge of the block goes on into the block beside it, water or another thin block */
    private static boolean continues(IBlockAccess world, BlockPos pos, BlockPos beside, int water, double along) {
        if(water != DRY) return true;
        IBlockState state = world.getBlockState(beside);
        if(!FluidicWater.isThin(world, beside, state)) return false;
        // Under vanilla's rules a thin block beside can be dry, like the end of a row of panes beside water running over
        // an edge, see FluidicWater.isThin
        if(!FluidicWater.vanillaRules || sideWater(world, beside)) return true;
        // Or hold water only from beside its corner, like the corner where two rows of panes meet with water inside it. It
        // goes on into it where that water reaches this edge, a face there would stand in the water.
        Layout layout = layout(world, beside, state);
        if(layout == null) return false;
        int dx = beside.getX() - pos.getX(), dz = beside.getZ() - pos.getZ();
        int i = dx != 0 ? dx > 0 ? 0 : layout.nx - 1 : span(layout.xs, along);
        int j = dz != 0 ? dz > 0 ? 0 : layout.nz - 1 : span(layout.zs, along);
        return layout.pool[i][j] >= 0;
    }

    /** Whether water is beside a side of a thin block, so water reaches into it, not only beside one of its corners */
    private static boolean sideWater(IBlockAccess world, BlockPos pos) {
        for(EnumFacing side : EnumFacing.HORIZONTALS) {
            if(water(world, pos.offset(side)) != DRY) return true;
        }
        return false;
    }

    /** A face from the bottom of the block up to the surface between two points, drawn from both sides */
    private static void side(Vertex out, TextureAtlasSprite sprite, double x, double y, double z, float[] heights, double ax, double az, double bx, double bz, int only) {
        float ha = height(heights, ax, az), hb = height(heights, bx, bz);
        double length = Math.abs(bx - ax) + Math.abs(bz - az);
        double[][] corners = {{ax, az, 0.0D, 0.0D}, {ax, az, ha, 0.0D}, {bx, bz, hb, length}, {bx, bz, 0.0D, length}};
        for(int pass = 0; pass < 2; pass++) {
            if(only >= 0 && pass != only) continue;
            for(int k = 0; k < 4; k++) {
                double[] c = corners[pass == 0 ? k : 3 - k];
                out.put(x + c[0], y + c[2], z + c[1], sprite.getInterpolatedU(c[3] * 8.0D), sprite.getInterpolatedV((1.0D - c[2]) * 8.0D), SIDE_SHADE);
            }
        }
    }
}
