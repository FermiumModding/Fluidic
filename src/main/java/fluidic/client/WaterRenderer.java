package fluidic.client;

import fluidic.Fluidic;
import fluidic.handlers.ForgeConfigHandler;
import fluidic.handlers.ModRegistry;
import fluidic.sim.Water;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GLAllocation;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.WorldVertexBufferUploader;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.world.biome.BiomeColorHelper;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fluids.BlockFluidBase;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import org.lwjgl.opengl.GL11;

/**
 * Renders water that is still being simulated. Each section with water is compiled into a display list
 * when the server sends new heights, so drawing a frame is one call per section.
 */
@Mod.EventBusSubscriber(modid = Fluidic.MODID, value = Side.CLIENT)
public final class WaterRenderer {

    private static final Long2IntOpenHashMap DISPLAY_LISTS = new Long2IntOpenHashMap();
    private static final LongLinkedOpenHashSet DIRTY = new LongLinkedOpenHashSet();
    private static final BufferBuilder BUFFER = new BufferBuilder(1 << 16);
    private static final WorldVertexBufferUploader UPLOADER = new WorldVertexBufferUploader();
    private static final BlockPos.MutableBlockPos POS = new BlockPos.MutableBlockPos();
    /** Keeps faces between water and solid blocks from z-fighting */
    private static final float INSET = 0.001F;
    /** Time per frame spent rebuilding meshes at most */
    private static final long REBUILD_NANOS = 2_000_000L;
    /** Surface height of a full block of water in Forge's fluid model, which renders settled water */
    private static final float FULL_HEIGHT = Water.LEVELS / (Water.LEVELS + 1.0F);

    private static TextureAtlasSprite still;
    private static int red, green, blue;

    private WaterRenderer() {}

    /** Marks a section for rebuilding, plus the neighbours whose shared border (bit per direction) changed */
    static void markDirty(long key, int borders) {
        DIRTY.add(key);
        // Faces on the border depend on the neighbouring sections
        int sx = Water.keyX(key), sy = Water.keyY(key), sz = Water.keyZ(key);
        for(int dir = 0; dir < 6; dir++) {
            if((borders & (1 << dir)) == 0) continue;
            int ny = sy + Water.DY[dir];
            if(ny < 0 || ny > 15) continue;
            long neighbor = Water.sectionKey(sx + Water.DX[dir], ny, sz + Water.DZ[dir]);
            if(ClientWaterCache.hasSection(neighbor) || DISPLAY_LISTS.containsKey(neighbor)) DIRTY.add(neighbor);
        }
    }

    static void clear() {
        for(int list : DISPLAY_LISTS.values()) GLAllocation.deleteDisplayLists(list);
        DISPLAY_LISTS.clear();
        DIRTY.clear();
    }

    @SubscribeEvent
    public static void onRenderWorldLast(RenderWorldLastEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        WorldClient world = mc.world;
        Entity view = mc.getRenderViewEntity();
        if(world == null || view == null || !ForgeConfigHandler.client.renderWater) return;

        still = mc.getTextureMapBlocks().getAtlasSprite("minecraft:blocks/water_still");
        // Rebuilds are capped by count and by time, so busy weather lags the water behind instead of the frame rate
        int budget = ForgeConfigHandler.client.meshRebuildsPerFrame;
        long deadline = System.nanoTime() + REBUILD_NANOS;
        for(LongIterator it = DIRTY.iterator(); it.hasNext() && budget > 0 && System.nanoTime() < deadline; budget--) {
            long key = it.nextLong();
            it.remove();
            rebuild(world, key);
        }
        if(DISPLAY_LISTS.isEmpty()) return;

        float partialTicks = event.getPartialTicks();
        double camX = view.lastTickPosX + (view.posX - view.lastTickPosX) * partialTicks;
        double camY = view.lastTickPosY + (view.posY - view.lastTickPosY) * partialTicks;
        double camZ = view.lastTickPosZ + (view.posZ - view.lastTickPosZ) * partialTicks;
        int distance = Math.min(ForgeConfigHandler.client.renderDistance, mc.gameSettings.renderDistanceChunks) * 16 + 8;
        double maxDistanceSq = (double)distance * distance;

        GlStateManager.pushMatrix();
        mc.entityRenderer.enableLightmap();
        RenderHelper.disableStandardItemLighting();
        mc.getTextureManager().bindTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA, GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
        GlStateManager.enableDepth();
        GlStateManager.depthMask(false);
        GlStateManager.disableCull();
        GlStateManager.shadeModel(GL11.GL_SMOOTH);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);

        for(ObjectIterator<Long2IntMap.Entry> it = DISPLAY_LISTS.long2IntEntrySet().fastIterator(); it.hasNext(); ) {
            Long2IntMap.Entry entry = it.next();
            long key = entry.getLongKey();
            double ox = Water.keyX(key) * 16 - camX;
            double oy = Water.keyY(key) * 16 - camY;
            double oz = Water.keyZ(key) * 16 - camZ;
            double dx = ox + 8, dz = oz + 8;
            if(dx * dx + dz * dz > maxDistanceSq) continue;
            GlStateManager.pushMatrix();
            GlStateManager.translate(ox, oy, oz);
            GlStateManager.callList(entry.getIntValue());
            GlStateManager.popMatrix();
        }

        GlStateManager.shadeModel(GL11.GL_FLAT);
        GlStateManager.enableCull();
        GlStateManager.depthMask(true);
        GlStateManager.disableBlend();
        mc.entityRenderer.disableLightmap();
        GlStateManager.popMatrix();
    }

    /**
     * Toggling simulated water rendering changes how settled water draws next to it, so every chunk is redrawn,
     * and meshes are dropped or rebuilt.
     */
    @SubscribeEvent
    public static void onConfigChanged(ConfigChangedEvent.PostConfigChangedEvent event) {
        if(!event.getModID().equals(Fluidic.MODID)) return;
        if(ForgeConfigHandler.client.renderWater) {
            for(long key : ClientWaterCache.keys()) DIRTY.add(key);
        }
        else {
            clear();
        }
        Minecraft mc = Minecraft.getMinecraft();
        if(mc.world != null) mc.renderGlobal.loadRenderers();
    }

    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        if(event.getWorld().isRemote) ClientWaterCache.unloadChunk(event.getChunk().x, event.getChunk().z);
    }

    @SubscribeEvent
    public static void onWorldUnload(WorldEvent.Unload event) {
        if(event.getWorld().isRemote) ClientWaterCache.clear();
    }

    // ---------------------------------------------------------------- mesh building

    private static void rebuild(WorldClient world, long key) {
        byte[] heights = ClientWaterCache.section(key);
        boolean any = false;
        if(heights != null) {
            int ox = Water.keyX(key) * 16, oy = Water.keyY(key) * 16, oz = Water.keyZ(key) * 16;
            BUFFER.begin(GL11.GL_QUADS, DefaultVertexFormats.BLOCK);
            for(int i = 0; i < 4096; i++) {
                if(heights[i] == 0) continue;
                any |= buildCell(world, ox, oy, oz, i & 15, i >> 8, (i >> 4) & 15);
            }
            BUFFER.finishDrawing();
        }

        int list = DISPLAY_LISTS.get(key);
        if(!any) {
            BUFFER.reset();
            if(list != 0) {
                GLAllocation.deleteDisplayLists(list);
                DISPLAY_LISTS.remove(key);
            }
            return;
        }
        if(list == 0) {
            list = GLAllocation.generateDisplayLists(1);
            DISPLAY_LISTS.put(key, list);
        }
        GlStateManager.glNewList(list, GL11.GL_COMPILE);
        UPLOADER.draw(BUFFER);
        GlStateManager.glEndList();
    }

    /** Adds the visible faces of one water cell in section local coordinates, returns whether any were added */
    private static boolean buildCell(WorldClient world, int ox, int oy, int oz, int lx, int ly, int lz) {
        int x = ox + lx, y = oy + ly, z = oz + lz;
        float surface = surface(world, x, y, z);
        int light = world.getCombinedLight(POS.setPos(x, y, z), 0);
        // Same biome tint vanilla water gets, the 1.12 water texture is already blue
        int color = BiomeColorHelper.getWaterColorAtPos(world, POS);
        red = color >> 16 & 255;
        green = color >> 8 & 255;
        blue = color & 255;
        int sky = light >> 16 & 65535, block = light & 65535;
        boolean added = false;

        // Corner heights, shared with neighbouring cells so the surface is continuous. Water continuing up, like a
        // falling stream, is full height on its own and draws its sides down to the water around it
        boolean column = surface >= 1.0F;
        float h00 = column ? 1.0F : corner(world, x, y, z, surface);
        float h10 = column ? 1.0F : corner(world, x + 1, y, z, surface);
        float h11 = column ? 1.0F : corner(world, x + 1, y, z + 1, surface);
        float h01 = column ? 1.0F : corner(world, x, y, z + 1, surface);
        // Water resting on a water block reaches down to that block's surface, which sits below the top of the block
        float floor = ClientWaterCache.height(x, y - 1, z) == 0 ? Math.min(0.0F, liquidLevel(world, x, y - 1, z) - 1.0F) : 0.0F;

        if(surface < 1.0F) {
            float u0 = still.getMinU(), u1 = still.getMaxU(), v0 = still.getMinV(), v1 = still.getMaxV();
            vertex(lx, ly + h00, lz, u0, v0, sky, block);
            vertex(lx, ly + h01, lz + 1, u0, v1, sky, block);
            vertex(lx + 1, ly + h11, lz + 1, u1, v1, sky, block);
            vertex(lx + 1, ly + h10, lz, u1, v0, sky, block);
            added = true;
        }

        if(ClientWaterCache.height(x, y - 1, z) == 0 && !isOpaque(world, x, y - 1, z) && !isLiquid(world, x, y - 1, z)) {
            float u0 = still.getMinU(), u1 = still.getMaxU(), v0 = still.getMinV(), v1 = still.getMaxV();
            float by = ly + INSET;
            vertex(lx, by, lz, u0, v0, sky, block);
            vertex(lx + 1, by, lz, u1, v0, sky, block);
            vertex(lx + 1, by, lz + 1, u1, v1, sky, block);
            vertex(lx, by, lz + 1, u0, v1, sky, block);
            added = true;
        }

        added |= side(world, x, y, z - 1, lx, ly, lz + INSET, lx + 1, lz + INSET, h00, h10, floor, sky, block);
        added |= side(world, x, y, z + 1, lx + 1, ly, lz + 1 - INSET, lx, lz + 1 - INSET, h11, h01, floor, sky, block);
        added |= side(world, x - 1, y, z, lx + INSET, ly, lz + 1, lx + INSET, lz, h01, h00, floor, sky, block);
        added |= side(world, x + 1, y, z, lx + 1 - INSET, ly, lz, lx + 1 - INSET, lz + 1, h10, h11, floor, sky, block);
        return added;
    }

    /**
     * Side face towards neighbour (nx, y, nz), from the neighbour's water level, simulated or a water block, up
     * to this cell's corners. Settled water blends its surface into simulated water, so the two meet there.
     */
    private static boolean side(WorldClient world, int nx, int y, int nz, float x0, int ly, float z0, float x1, float z1, float top0, float top1, float floor, int sky, int block) {
        if(isOpaque(world, nx, y, nz)) return false;
        float neighbor = surface(world, nx, y, nz);
        float bottom = neighbor >= 0.0F ? neighbor : floor;
        if(top0 <= bottom && top1 <= bottom) return false;
        float u0 = still.getMinU(), u1 = still.getInterpolatedU(8.0D);
        vertex(x0, ly + bottom, z0, u0, still.getInterpolatedV((1.0F - bottom) * 8.0F), sky, block);
        vertex(x0, ly + Math.max(top0, bottom), z0, u0, still.getInterpolatedV((1.0F - top0) * 8.0F), sky, block);
        vertex(x1, ly + Math.max(top1, bottom), z1, u1, still.getInterpolatedV((1.0F - top1) * 8.0F), sky, block);
        vertex(x1, ly + bottom, z1, u1, still.getInterpolatedV((1.0F - bottom) * 8.0F), sky, block);
        return true;
    }

    private static void vertex(float x, float y, float z, float u, float v, int sky, int block) {
        BUFFER.pos(x, y, z).color(red, green, blue, 255).tex(u, v).lightmap(sky, block).endVertex();
    }

    /**
     * Rendered water level of a cell, simulated or a water block: -1 for no water, 1 when water continues above.
     * Uses the same scale as Forge's fluid model so simulated and settled water meet.
     */
    private static float surface(WorldClient world, int x, int y, int z) {
        int height = ClientWaterCache.height(x, y, z);
        if(height == 0) return liquidHeight(world, x, y, z);
        if(ClientWaterCache.height(x, y + 1, z) > 0 || isLiquid(world, x, y + 1, z)) return 1.0F;
        return height / 255.0F * FULL_HEIGHT;
    }

    /** Rendered level of a water block, -1 if there is none */
    private static float liquidHeight(WorldClient world, int x, int y, int z) {
        if(y < 0 || y > 255) return -1.0F;
        IBlockState state = world.getBlockState(POS.setPos(x, y, z));
        if(!state.getMaterial().isLiquid()) return -1.0F;
        // Like settled water itself: only a water block above makes it a column, falling simulated water reaches down to it
        if(isLiquid(world, x, y + 1, z)) return 1.0F;
        if(state.getBlock() == ModRegistry.SETTLED_WATER) return (state.getValue(BlockFluidBase.LEVEL) + 1) / (Water.LEVELS + 1.0F);
        if(state.getBlock() instanceof BlockLiquid) return 1.0F - BlockLiquid.getLiquidHeightPercent(state.getValue(BlockLiquid.LEVEL));
        return FULL_HEIGHT;
    }

    /** Surface of a water block ignoring any water above it, 1 for anything else so nothing reaches into it */
    private static float liquidLevel(WorldClient world, int x, int y, int z) {
        if(y < 0 || y > 255) return 1.0F;
        IBlockState state = world.getBlockState(POS.setPos(x, y, z));
        if(!state.getMaterial().isLiquid()) return 1.0F;
        if(state.getBlock() == ModRegistry.SETTLED_WATER) return (state.getValue(BlockFluidBase.LEVEL) + 1) / (Water.LEVELS + 1.0F);
        if(state.getBlock() instanceof BlockLiquid) return 1.0F - BlockLiquid.getLiquidHeightPercent(state.getValue(BlockLiquid.LEVEL));
        return FULL_HEIGHT;
    }

    /**
     * Level of the corner at the low x/z edge of (x, z), averaged over the four cells around it exactly like
     * settled water does (BlockSettledWater.getExtendedState and Forge's getFluidHeightAverage), so both kinds
     * of water share their corners: solid blocks are left out, open blocks count as dry and near full cells
     * weigh more.
     */
    private static float corner(WorldClient world, int x, int y, int z, float fallback) {
        float sum = 0.0F;
        int count = 0, columns = 0;
        for(int dx = -1; dx <= 0; dx++) {
            for(int dz = -1; dz <= 0; dz++) {
                float s = surface(world, x + dx, y, z + dz);
                // Water continuing up, like a falling stream, does not drag the surface around it up
                if(s >= 1.0F) {
                    columns++;
                    continue;
                }
                if(s < 0.0F) {
                    if(isSolid(world, x + dx, y, z + dz)) continue;
                    s = 0.0F;
                }
                if(s >= FULL_HEIGHT) {
                    sum += s * 10.0F;
                    count += 10;
                }
                sum += s;
                count++;
            }
        }
        if(count == 0) return columns > 0 ? 1.0F : fallback;
        return sum / count;
    }

    private static boolean isSolid(WorldClient world, int x, int y, int z) {
        return y < 0 || y > 255 || world.getBlockState(POS.setPos(x, y, z)).getMaterial().isSolid();
    }

    private static boolean isOpaque(WorldClient world, int x, int y, int z) {
        return y >= 0 && y <= 255 && world.getBlockState(POS.setPos(x, y, z)).isOpaqueCube();
    }

    private static boolean isLiquid(WorldClient world, int x, int y, int z) {
        return y >= 0 && y <= 255 && world.getBlockState(POS.setPos(x, y, z)).getMaterial().isLiquid();
    }
}
