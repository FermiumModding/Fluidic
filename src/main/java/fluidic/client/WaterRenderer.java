package fluidic.client;

import fluidic.Fluidic;
import fluidic.handlers.ForgeConfigHandler;
import fluidic.block.FluidicWater;
import fluidic.mixin.vanilla.EntityRendererAccessor;
import fluidic.sim.Terrain;
import fluidic.sim.Water;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.state.BlockFaceShape;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GLAllocation;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.WorldVertexBufferUploader;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.util.EnumFacing;
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
    private static final LongLinkedOpenHashSet URGENT = new LongLinkedOpenHashSet();
    private static final BufferBuilder BUFFER = new BufferBuilder(1 << 16);
    private static final WorldVertexBufferUploader UPLOADER = new WorldVertexBufferUploader();
    private static final BlockPos.MutableBlockPos POS = new BlockPos.MutableBlockPos();
    /** Keeps faces between water and solid blocks from z-fighting */
    private static final float INSET = 0.001F;
    /** Time per frame spent rebuilding meshes at most */
    private static final long REBUILD_NANOS = 2_000_000L;
    /** Time per frame spent on sections beside changed blocks at most */
    private static final long URGENT_NANOS = 4_000_000L;
    /** Most sections rebuilt together in one group, see rebuildGroup */
    private static final int MAX_GROUP = 64;
    /** Surface height of a full block of water as vanilla renders it */
    private static final float FULL_HEIGHT = Water.RENDER_HEIGHT;

    private static TextureAtlasSprite still, flow, overlay;
    private static int red, green, blue;
    /** The cell being built is a thin layer resting on a full block of water, see FluidicWater.isFilm */
    private static boolean film;
    /** Per section rebuild: corner levels by local corner, 0-16 along x and z, and water colours by column */
    private static final float[] corners = new float[17 * 17 * 16];
    private static final int[] cornerGeneration = new int[17 * 17 * 16];
    private static final int[] colors = new int[16 * 16];
    private static final int[] colorGeneration = new int[16 * 16];
    /** Counts section rebuilds, entries from an earlier one are stale */
    private static int generation;
    /** Whether the last corner worked out was at a door */
    private static boolean cornerDoors;
    /** The section being rebuilt */
    private static long building;
    /** The blocks around the section being rebuilt */
    private static final SectionBlocks BLOCKS = new SectionBlocks();
    /** Sections of water blocks not yet drawn again under thin layers, see filmShown, to the meshes drawn without them */
    private static final Long2ObjectOpenHashMap<LongOpenHashSet> FILM_WAITS = new Long2ObjectOpenHashMap<>();

    private WaterRenderer() {}

    /**
     * Marks a section for rebuilding, plus the sections around it whose shared edge changed: a bit per offset,
     * (dx + 1) * 9 + (dy + 1) * 3 + (dz + 1), see {@link #around}
     */
    static void markDirty(long key, int borders) {
        DIRTY.add(key);
        int sx = Water.keyX(key), sy = Water.keyY(key), sz = Water.keyZ(key);
        for(int bit = 0; bit < 27; bit++) {
            if((borders & (1 << bit)) == 0 || bit == 13) continue;
            int ny = sy + bit / 3 % 3 - 1;
            if(ny < 0 || ny > 15) continue;
            long neighbor = Water.sectionKey(sx + bit / 9 - 1, ny, sz + bit % 3 - 1);
            if(ClientWaterCache.hasSection(neighbor) || DISPLAY_LISTS.containsKey(neighbor)) DIRTY.add(neighbor);
        }
    }

    /** The sections a cell at local coordinates is drawn against, as bits for {@link #markDirty} */
    static int around(int x, int y, int z) {
        int bits = 0;
        for(int dx = x == 0 ? -1 : 0; dx <= (x == 15 ? 1 : 0); dx++) {
            for(int dy = y == 0 ? -1 : 0; dy <= (y == 15 ? 1 : 0); dy++) {
                for(int dz = z == 0 ? -1 : 0; dz <= (z == 15 ? 1 : 0); dz++) bits |= 1 << ((dx + 1) * 9 + (dy + 1) * 3 + dz + 1);
            }
        }
        return bits;
    }

    /** Marks a section for rebuilding this frame, simulated water beside a block that changed draws against it */
    static void markUrgent(long key) {
        URGENT.add(key);
    }

    /** Drops every mesh and rebuilds them from the cache, when drawing switches between here and the chunks */
    static void redrawAll() {
        for(int list : DISPLAY_LISTS.values()) GLAllocation.deleteDisplayLists(list);
        DISPLAY_LISTS.clear();
        URGENT.clear();
        DIRTY.clear();
        if(!ChunkWater.isActive()) for(long key : ClientWaterCache.keys()) DIRTY.add(key);
    }

    /** The client dropped a section, nothing waits for it to be drawn again */
    static void unloadSection(long key) {
        FILM_WAITS.remove(key);
    }

    static void clear() {
        for(int list : DISPLAY_LISTS.values()) GLAllocation.deleteDisplayLists(list);
        DISPLAY_LISTS.clear();
        FILM_WAITS.clear();
        DIRTY.clear();
        URGENT.clear();
    }

    @SubscribeEvent
    public static void onRenderWorldLast(RenderWorldLastEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        WorldClient world = mc.world;
        Entity view = mc.getRenderViewEntity();
        // Drawn by the chunks themselves while ChunkWater is active
        if(world == null || view == null || !ForgeConfigHandler.client.renderWater || ChunkWater.isActive()) return;

        still = mc.getTextureMapBlocks().getAtlasSprite("minecraft:blocks/water_still");
        // Sides run down like vanilla's water, which draws them with the flowing texture
        flow = mc.getTextureMapBlocks().getAtlasSprite("minecraft:blocks/water_flow");
        overlay = mc.getTextureMapBlocks().getAtlasSprite("minecraft:blocks/water_overlay");
        // Rebuilds are capped by count and by time, so busy weather lags the water behind instead of the frame rate
        int budget = ForgeConfigHandler.client.meshRebuildsPerFrame;
        // Chunks drawn with water blocks that came or went this frame, their simulated water switches with them
        ChunkRedraws.caughtUp(WaterRenderer::markUrgent, WaterRenderer::filmsCaughtUp);
        // Sections beside changed blocks go first and past the count, vanilla may draw the change in this same frame
        long urgentDeadline = System.nanoTime() + URGENT_NANOS;
        while(!URGENT.isEmpty() && System.nanoTime() < urgentDeadline) {
            long key = URGENT.removeFirstLong();
            DIRTY.remove(key);
            rebuildGroup(world, key, MAX_GROUP, urgentDeadline);
        }
        long deadline = System.nanoTime() + REBUILD_NANOS;
        while(!DIRTY.isEmpty() && budget > 0 && System.nanoTime() < deadline) budget -= rebuildGroup(world, DIRTY.removeFirstLong(), Math.min(budget, MAX_GROUP), deadline);
        if(DISPLAY_LISTS.isEmpty()) return;

        float partialTicks = event.getPartialTicks();
        double camX = view.lastTickPosX + (view.posX - view.lastTickPosX) * partialTicks;
        double camY = view.lastTickPosY + (view.posY - view.lastTickPosY) * partialTicks;
        double camZ = view.lastTickPosZ + (view.posZ - view.lastTickPosZ) * partialTicks;
        int distance = Math.min(ForgeConfigHandler.client.renderDistance, mc.gameSettings.renderDistanceChunks) * 16 + 8;
        double maxDistanceSq = (double)distance * distance;
        // Sections out of view are skipped, the view is the one the chunks were just drawn with
        Frustum frustum = new Frustum();
        frustum.setPosition(camX, camY, camZ);

        GlStateManager.pushMatrix();
        // Vanilla turns fog off before this event, without it distant water would not fade out like the chunks around it.
        // OptiFine patches the same method, so its fog settings apply too
        ((EntityRendererAccessor)mc.entityRenderer).fluidic_setupFog(0, partialTicks);
        mc.entityRenderer.enableLightmap();
        RenderHelper.disableStandardItemLighting();
        mc.getTextureManager().bindTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);
        // Mipmapped like the chunks draw water, at a distance or a slant the water texture sampled at full size lets more or
        // less of the blocks behind it through than the chunks' water does
        mc.getTextureManager().getTexture(TextureMap.LOCATION_BLOCKS_TEXTURE).setBlurMipmap(false, mc.gameSettings.mipmapLevels > 0);
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA, GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
        GlStateManager.enableDepth();
        GlStateManager.depthMask(false);
        // Faces are one sided like vanilla's water, the ones seen from both sides are drawn twice
        GlStateManager.enableCull();
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
            int bx = Water.keyX(key) * 16, by = Water.keyY(key) * 16, bz = Water.keyZ(key) * 16;
            if(!frustum.isBoxInFrustum(bx, by, bz, bx + 16, by + 16, bz + 16)) continue;
            GlStateManager.pushMatrix();
            GlStateManager.translate(ox, oy, oz);
            GlStateManager.callList(entry.getIntValue());
            GlStateManager.popMatrix();
        }

        GlStateManager.shadeModel(GL11.GL_FLAT);
        GlStateManager.enableCull();
        GlStateManager.depthMask(true);
        GlStateManager.disableBlend();
        mc.getTextureManager().getTexture(TextureMap.LOCATION_BLOCKS_TEXTURE).restoreLastBlurMipmap();
        mc.entityRenderer.disableLightmap();
        GlStateManager.disableFog();
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

    /**
     * Rebuilds a section and the sections around it waiting for a rebuild too, in the same frame. Sections share the
     * corners along their borders, rebuilt in different frames the two sides would not meet for a frame and open gaps,
     * as a wake spreading through water crosses them. Within the frame's budget, in busy weather nearly every section
     * waits and the group would take them all. Returns how many were rebuilt.
     */
    private static int rebuildGroup(WorldClient world, long first, int limit, long deadline) {
        LongArrayList group = new LongArrayList();
        group.add(first);
        int built = 0;
        for(; built < group.size() && built < limit && (built == 0 || System.nanoTime() < deadline); built++) {
            long key = group.getLong(built);
            rebuild(world, key);
            int sx = Water.keyX(key), sy = Water.keyY(key), sz = Water.keyZ(key);
            for(int dx = -1; dx <= 1; dx++) {
                for(int dy = -1; dy <= 1; dy++) {
                    for(int dz = -1; dz <= 1; dz++) {
                        if(sy + dy < 0 || sy + dy > 15) continue;
                        long neighbor = Water.sectionKey(sx + dx, sy + dy, sz + dz);
                        if(DIRTY.remove(neighbor) | URGENT.remove(neighbor)) group.add(neighbor);
                    }
                }
            }
        }
        // Too many at once, the rest waits for the next frame
        for(int n = built; n < group.size(); n++) DIRTY.add(group.getLong(n));
        return built;
    }

    private static void rebuild(WorldClient world, long key) {
        byte[] heights = ClientWaterCache.section(key);
        boolean any = false;
        if(heights != null) {
            int ox = Water.keyX(key) * 16, oy = Water.keyY(key) * 16, oz = Water.keyZ(key) * 16;
            // Corners and colours worked out for this section only
            generation++;
            building = key;
            SectionBlocks blocks = BLOCKS.begin(world, ox, oy, oz);
            BUFFER.begin(GL11.GL_QUADS, DefaultVertexFormats.BLOCK);
            RuntimeException failed = null;
            try {
                for(int i = 0; i < 4096; i++) {
                    if(heights[i] == 0) continue;
                    any |= buildCell(blocks, ox, oy, oz, i & 15, i >> 8, (i >> 4) & 15);
                }
            }
            catch(RuntimeException ex) {
                failed = ex;
            }
            finally {
                // Left building, every later rebuild would fail
                BUFFER.finishDrawing();
            }
            if(failed != null) {
                Fluidic.LOGGER.error("Failed to build simulated water mesh of a section", failed);
                any = false;
            }
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
    private static boolean buildCell(SectionBlocks world, int ox, int oy, int oz, int lx, int ly, int lz) {
        int x = ox + lx, y = oy + ly, z = oz + lz;
        // A water block the chunk on screen still shows, or already shows, draws this water. Drawing both doubles it,
        // darker, for a moment: waking water is sent before its block goes, settled water stays until the chunk has it.
        if(FluidicWater.isWater(shown(world, x, y, z))) return false;
        // A trace of water resting on water is not drawn, see FluidicWater.MIN_DRAWN
        if(FluidicWater.isTrace(world, new BlockPos(x, y, z), ClientWaterCache.height(x, y, z))) return false;
        float surface = surface(world, x, y, z);
        int light = world.light(x, y, z);
        // Same biome tint vanilla water gets, the 1.12 water texture is already blue. The same for the whole column.
        int column16 = lx * 16 + lz;
        if(colorGeneration[column16] != generation) {
            colorGeneration[column16] = generation;
            colors[column16] = BiomeColorHelper.getWaterColorAtPos(world, POS.setPos(x, y, z));
        }
        int color = colors[column16];
        red = color >> 16 & 255;
        green = color >> 8 & 255;
        blue = color & 255;
        // Sky light dimmed by the simulated water, like water blocks dim it, the surface lit from above like theirs
        int above = world.light(x, y + 1, z) >> 20 & 15;
        int skyLevel = Math.max(ChunkLight.dimmed(light >> 20 & 15, x, y, z), ChunkLight.dimmed(above, x, y + 1, z));
        int sky = skyLevel << 4, block = light & 65535;
        boolean added = false;

        // Corner heights, shared with neighbouring cells so the surface is continuous. Water continuing up, like a
        // falling stream, is full height on its own and draws its sides down to the water around it
        boolean column = surface >= 1.0F;
        film = filmShown(world, x, y, z);
        // The corners as the water around shares them, a column's sides reach down to these
        BlockPos cell = new BlockPos(x, y, z);
        float s00 = sharedCorner(world, x, y, z, lx, ly, lz, surface, cell);
        float s10 = sharedCorner(world, x + 1, y, z, lx + 1, ly, lz, surface, cell);
        float s11 = sharedCorner(world, x + 1, y, z + 1, lx + 1, ly, lz + 1, surface, cell);
        float s01 = sharedCorner(world, x, y, z + 1, lx, ly, lz + 1, surface, cell);
        // Water inside a door is level with the water on its open side. Against the door only its own water counts,
        // which stays simulated a little below the water around it, and the surface would slope down to the door.
        EnumFacing door = Terrain.doorSide(world, cell, world.getBlockState(cell));
        if(door == EnumFacing.WEST) {
            s00 = s10;
            s01 = s11;
        }
        else if(door == EnumFacing.EAST) {
            s10 = s00;
            s11 = s01;
        }
        else if(door == EnumFacing.NORTH) {
            s00 = s01;
            s10 = s11;
        }
        else if(door == EnumFacing.SOUTH) {
            s01 = s00;
            s11 = s10;
        }
        float h00 = column ? 1.0F : s00;
        float h10 = column ? 1.0F : s10;
        float h11 = column ? 1.0F : s11;
        float h01 = column ? 1.0F : s01;
        // Water resting on another mod's liquid reaches down to its surface, which sits below the top of the block.
        // Fluidic water under simulated water fills its block to the top itself.
        float floor = ClientWaterCache.height(x, y - 1, z) == 0 ? Math.min(0.0F, liquidLevel(world, x, y - 1, z) - 1.0F) : 0.0F;

        if(surface < 1.0F) {
            float u0 = still.getMinU(), u1 = still.getMaxU(), v0 = still.getMinV(), v1 = still.getMaxV();
            vertex(lx, ly + h00, lz, u0, v0, sky, block);
            vertex(lx, ly + h01, lz + 1, u0, v1, sky, block);
            vertex(lx + 1, ly + h11, lz + 1, u1, v1, sky, block);
            vertex(lx + 1, ly + h10, lz, u1, v0, sky, block);
            // Seen from below too, from inside the water, where vanilla's water draws it from below, see shownFromBelow
            if(shownFromBelow(world, x, y + 1, z)) {
                vertex(lx, ly + h00, lz, u0, v0, sky, block);
                vertex(lx + 1, ly + h10, lz, u1, v0, sky, block);
                vertex(lx + 1, ly + h11, lz + 1, u1, v1, sky, block);
                vertex(lx, ly + h01, lz + 1, u0, v1, sky, block);
            }
            added = true;
        }

        if(ClientWaterCache.height(x, y - 1, z) == 0 && !isOpaque(world, x, y - 1, z) && !isLiquid(world, x, y - 1, z)) {
            float u0 = still.getMinU(), u1 = still.getMaxU(), v0 = still.getMinV(), v1 = still.getMaxV();
            float by = ly + INSET;
            // Lit by the block below, like vanilla's water bottoms
            int below = world.light(x, y - 1, z), belowSky = (below >> 20 & 15) << 4, belowBlock = below & 65535;
            vertex(lx, by, lz, u0, v0, belowSky, belowBlock, 0.5F);
            vertex(lx + 1, by, lz, u1, v0, belowSky, belowBlock, 0.5F);
            vertex(lx + 1, by, lz + 1, u1, v1, belowSky, belowBlock, 0.5F);
            vertex(lx, by, lz + 1, u0, v1, belowSky, belowBlock, 0.5F);
            added = true;
        }

        if(!isThin(world, x, y, z - 1)) added |= side(world, x, y, z - 1, lx, ly, lz + INSET, lx + 1, lz + INSET, h00, h10, s00, s10, floor, column, EnumFacing.NORTH);
        if(!isThin(world, x, y, z + 1)) added |= side(world, x, y, z + 1, lx + 1, ly, lz + 1 - INSET, lx, lz + 1 - INSET, h11, h01, s11, s01, floor, column, EnumFacing.SOUTH);
        if(!isThin(world, x - 1, y, z)) added |= side(world, x - 1, y, z, lx + INSET, ly, lz + 1, lx + INSET, lz, h01, h00, s01, s00, floor, column, EnumFacing.WEST);
        if(!isThin(world, x + 1, y, z)) added |= side(world, x + 1, y, z, lx + 1 - INSET, ly, lz, lx + 1 - INSET, lz + 1, h10, h11, s10, s11, floor, column, EnumFacing.EAST);
        added |= thinBlocks(world, x, y, z, lx, ly, lz);
        return added;
    }

    /** Whether a block beside is a thin block like a glass pane, the water reaching into it is drawn by {@link #thinBlocks} */
    private static boolean isThin(SectionBlocks world, int x, int y, int z) {
        return FluidicWater.isThin(world, new BlockPos(x, y, z), world.state(x, y, z));
    }

    /**
     * Draws the simulated water reaching into thin blocks around a cell, like glass panes, the ones this cell draws for,
     * see ThinBlockWater.drawsFor. The water inside a corner of panes only touches its cell diagonally.
     */
    private static boolean thinBlocks(SectionBlocks world, int x, int y, int z, int lx, int ly, int lz) {
        boolean added = false;
        BlockPos water = new BlockPos(x, y, z);
        for(int dx = -1; dx <= 1; dx++) {
            for(int dz = -1; dz <= 1; dz++) {
                if(dx == 0 && dz == 0) continue;
                BlockPos pos = water.add(dx, 0, dz);
                IBlockState state = world.getBlockState(pos);
                if(!FluidicWater.isThin(world, pos, state) || !ThinBlockWater.drawsFor(pos, water)) continue;
                // Lit by the thin block's own light, like ThinBlockWater.drawBesideWater lights settled water in it
                int light = world.light(x + dx, y, z + dz);
                int sky = (light >> 20 & 15) << 4, block = light & 65535;
                added |= ThinBlockWater.draw(world, pos, state, true, (vx, vy, vz, u, v, shade) -> BUFFER.pos(vx, vy, vz)
                        .color((int)(red * shade), (int)(green * shade), (int)(blue * shade), 255).tex(u, v).lightmap(sky, block).endVertex(),
                        still, lx + dx, ly, lz + dz);
            }
        }
        return added;
    }

    /**
     * Side face towards neighbour (nx, y, nz), up to this cell's corners. Next to other water, simulated or a water
     * block, the surfaces meet at shared corners and no face is drawn, it would only show as a line through the
     * water. Only water continuing up stands above the water beside it and draws a face down to its level.
     * At a ledge, where the neighbour is empty with water below it, the face reaches down to that water like water
     * running over the edge, instead of leaving the bank between the two showing.
     */
    private static boolean side(SectionBlocks world, int nx, int y, int nz, float x0, int ly, float z0, float x1, float z1, float top0, float top1,
                                float edge0, float edge1, float floor, boolean column, EnumFacing toward) {
        if(isOpaque(world, nx, y, nz)) return false;
        float neighbor = surface(world, nx, y, nz);
        if(neighbor >= 0.0F && (!column || neighbor >= 1.0F)) return false;
        float bottom0, bottom1;
        if(neighbor >= 0.0F) {
            // The neighbour's surface slopes along the shared edge, the face follows it so no wedge stays open
            bottom0 = Math.max(0.0F, edge0);
            bottom1 = Math.max(0.0F, edge1);
        }
        else {
            float bottom = floor;
            // Not the edge of a thin layer on a pool, the pool beside it rises to meet it, see FluidicWater.isFilm
            if(!isSolid(world, nx, y, nz) && !film) {
                float below = surface(world, nx, y - 1, nz);
                if(below >= 0.0F && below < 1.0F) bottom = Math.min(bottom, below - 1.0F);
            }
            bottom0 = bottom1 = bottom;
        }
        if(top0 <= bottom0 && top1 <= bottom1) return false;
        // Against a solid face like glass the plain overlay texture, like vanilla's water sides
        POS.setPos(nx, y, nz);
        TextureAtlasSprite sprite = world.getBlockState(POS).getBlockFaceShape(world, POS, toward.getOpposite()) == BlockFaceShape.SOLID ? overlay : flow;
        float u0 = sprite.getMinU(), u1 = sprite.getInterpolatedU(8.0D);
        // Lit by the block it faces, like vanilla's water sides, not by the depth of the water behind it
        int light = world.light(nx, y, nz);
        int sky = (light >> 20 & 15) << 4, block = light & 65535;
        // Shaded by direction like vanilla's water sides
        float shade = toward.getAxis() == EnumFacing.Axis.Z ? 0.8F : 0.6F;
        vertex(x0, ly + bottom0, z0, u0, sprite.getInterpolatedV(Math.min(16.0F, (1.0F - bottom0) * 8.0F)), sky, block, shade);
        vertex(x0, ly + Math.max(top0, bottom0), z0, u0, sprite.getInterpolatedV(Math.min(16.0F, (1.0F - top0) * 8.0F)), sky, block, shade);
        vertex(x1, ly + Math.max(top1, bottom1), z1, u1, sprite.getInterpolatedV(Math.min(16.0F, (1.0F - top1) * 8.0F)), sky, block, shade);
        vertex(x1, ly + bottom1, z1, u1, sprite.getInterpolatedV(Math.min(16.0F, (1.0F - bottom1) * 8.0F)), sky, block, shade);
        // Flowing sides are seen from inside the water too. Against a solid face only from outside like vanilla's, from
        // inside it would show as a second layer of water behind glass.
        if(sprite != overlay) {
            vertex(x0, ly + bottom0, z0, u0, sprite.getInterpolatedV(Math.min(16.0F, (1.0F - bottom0) * 8.0F)), sky, block, shade);
            vertex(x1, ly + bottom1, z1, u1, sprite.getInterpolatedV(Math.min(16.0F, (1.0F - bottom1) * 8.0F)), sky, block, shade);
            vertex(x1, ly + Math.max(top1, bottom1), z1, u1, sprite.getInterpolatedV(Math.min(16.0F, (1.0F - top1) * 8.0F)), sky, block, shade);
            vertex(x0, ly + Math.max(top0, bottom0), z0, u0, sprite.getInterpolatedV(Math.min(16.0F, (1.0F - top0) * 8.0F)), sky, block, shade);
        }
        return true;
    }

    /**
     * Whether a water surface below a position is drawn from below too, like BlockLiquid.shouldRenderSides: when open to
     * a block around above it that is neither water nor a full block.
     */
    private static boolean shownFromBelow(SectionBlocks world, int x, int y, int z) {
        if(y > 255) return true;
        for(int dx = -1; dx <= 1; dx++) {
            for(int dz = -1; dz <= 1; dz++) {
                POS.setPos(x + dx, y, z + dz);
                IBlockState state = world.getBlockState(POS);
                if(!state.getMaterial().isLiquid() && !state.isFullBlock()) return true;
            }
        }
        return false;
    }

    private static void vertex(float x, float y, float z, float u, float v, int sky, int block) {
        BUFFER.pos(x, y, z).color(red, green, blue, 255).tex(u, v).lightmap(sky, block).endVertex();
    }

    /** A vertex shaded by the direction of its face, like vanilla shades block faces */
    private static void vertex(float x, float y, float z, float u, float v, int sky, int block, float shade) {
        BUFFER.pos(x, y, z).color((int)(red * shade), (int)(green * shade), (int)(blue * shade), 255).tex(u, v).lightmap(sky, block).endVertex();
    }

    /**
     * Rendered water level of a cell, simulated or a water block: -1 for no water, 1 when water continues above.
     * Uses the same scale as vanilla's fluid renderer so simulated water and water blocks meet.
     */
    private static float surface(SectionBlocks world, int x, int y, int z) {
        int height = ClientWaterCache.height(x, y, z);
        if(height == 0) return liquidHeight(world, x, y, z);
        if(raised(world, x, y + 1, z)) return 1.0F;
        // Full water reaches up to an opaque block above it instead of leaving a gap of air under it
        if(height >= 255 && isOpaque(world, x, y + 1, z)) return 1.0F;
        return height / 255.0F * FULL_HEIGHT;
    }

    /** Rendered level of a water block, -1 if there is none */
    private static float liquidHeight(SectionBlocks world, int x, int y, int z) {
        if(y < 0 || y > 255) return -1.0F;
        IBlockState state = shown(world, x, y, z);
        if(!state.getMaterial().isLiquid()) return -1.0F;
        // Like FluidicWater.renderHeight: water continuing up into a block or simulated water makes it a column
        if(raised(world, x, y + 1, z)) return 1.0F;
        if(FluidicWater.isWater(state) && FluidicWater.level(state) == Water.LEVELS && isOpaque(world, x, y + 1, z)) return 1.0F;
        if(state.getBlock() instanceof BlockLiquid) return 1.0F - BlockLiquid.getLiquidHeightPercent(state.getValue(BlockLiquid.LEVEL));
        return FULL_HEIGHT;
    }

    /** Surface of a water block ignoring any water above it, 1 for anything else so nothing reaches into it */
    private static float liquidLevel(SectionBlocks world, int x, int y, int z) {
        if(y < 0 || y > 255) return 1.0F;
        IBlockState state = shown(world, x, y, z);
        if(!state.getMaterial().isLiquid()) return 1.0F;
        if(FluidicWater.isWater(state)) return 1.0F;
        if(state.getBlock() instanceof BlockLiquid) return 1.0F - BlockLiquid.getLiquidHeightPercent(state.getValue(BlockLiquid.LEVEL));
        return FULL_HEIGHT;
    }

    /**
     * A corner as {@link #corner} works it out, once per section rebuild: the four cells around it share it. Corners at a
     * door depend on the cell asking, they are not kept.
     */
    private static float sharedCorner(SectionBlocks world, int x, int y, int z, int cx, int ly, int cz, float fallback, BlockPos cell) {
        int index = (cx * 17 + cz) * 16 + ly;
        if(cornerGeneration[index] != generation) {
            cornerDoors = false;
            // NaN where the cell's own surface is used
            float s = corner(world, x, y, z, Float.NaN, cell);
            if(cornerDoors) return Float.isNaN(s) ? fallback : s;
            cornerGeneration[index] = generation;
            corners[index] = s;
        }
        float s = corners[index];
        return Float.isNaN(s) ? fallback : s;
    }

    /**
     * Level of the corner at the low x/z edge of (x, z), averaged over the four cells around it exactly like
     * water blocks do (FluidicWater.cornerHeight, used by vanilla's fluid renderer), so both kinds
     * of water share their corners: solid blocks are left out, open blocks count as dry and near full cells
     * weigh more.
     */
    private static float corner(SectionBlocks world, int x, int y, int z, float fallback, BlockPos cell) {
        // Corners touching water blocks follow the blocks alone, like FluidicWater.cornerHeight, the blocks are only
        // redrawn when simulated water appears or vanishes beside them, not every time it moves
        boolean blocks = false, doors = false;
        for(int dx = -1; dx <= 0; dx++) {
            for(int dz = -1; dz <= 0; dz++) {
                IBlockState state = world.state(x + dx, y, z + dz);
                blocks |= state.getMaterial().isLiquid();
                doors |= Terrain.isDoor(state);
            }
        }
        if(doors) {
            cornerDoors = true;
            // Only the water blocks on this side of the door count
            blocks = false;
            for(int dx = -1; dx <= 0; dx++) {
                for(int dz = -1; dz <= 0; dz++) blocks |= isLiquid(world, x + dx, y, z + dz) && !FluidicWater.apartByDoor(world, cell, x + dx, z + dz);
            }
        }
        float sum = 0.0F;
        int count = 0, columns = 0, sheets = 0;
        boolean edge = false;
        // The edge of a thin layer on a pool slopes down onto the pool, see FluidicWater.surfaceCorner. By the cells around
        // the corner, so the cells sharing it agree.
        boolean film = false;
        for(int dx = -1; dx <= 0 && !film; dx++) {
            for(int dz = -1; dz <= 0 && !film; dz++) film = filmShown(world, x + dx, y, z + dz);
        }
        for(int dx = -1; dx <= 0; dx++) {
            for(int dz = -1; dz <= 0; dz++) {
                float s;
                // Water across the closed side of a door has its own surface
                if(doors && FluidicWater.apartByDoor(world, cell, x + dx, z + dz)) continue;
                if(isLiquid(world, x + dx, y, z + dz)) s = liquidHeight(world, x + dx, y, z + dz);
                // A trace resting on water counts as an empty cell, see FluidicWater.surfaceCorner
                else if(isTrace(world, x + dx, y, z + dz)) s = -1.0F;
                else if(blocks && ClientWaterCache.height(x + dx, y, z + dz) > 0) {
                    // Left out, but raised under a sheet of water it still raises the corner, see below
                    sheets += sheetWeight(world, x + dx, y + 1, z + dz);
                    continue;
                }
                else s = surface(world, x + dx, y, z + dz);
                // Water continuing up, like a falling stream, does not drag the surface around it up
                if(s >= 1.0F) {
                    columns++;
                    sheets += sheetWeight(world, x + dx, y + 1, z + dz);
                    continue;
                }
                if(s < 0.0F) {
                    if(isSolid(world, x + dx, y, z + dz)) continue;
                    s = 0.0F;
                    if(film && raised(world, x + dx, y - 1, z + dz)) edge = true;
                }
                if(s >= FULL_HEIGHT) {
                    sum += s * 10.0F;
                    count += 10;
                }
                sum += s;
                count++;
            }
        }
        // Unless two meet here, a sheet of water spreading over a pool, see FluidicWater.surfaceCorner
        if(edge) return 0.0F;
        if(sheets >= 2) return 1.0F;
        if(count == 0) return columns > 0 ? 1.0F : fallback;
        return sum / count;
    }

    /**
     * A thin layer resting on full water, see FluidicWater.isFilm, once the water blocks under it are drawn reaching up to
     * it. Until their chunk is redrawn they show their usual surface, the layer is drawn as plain water down to it, or
     * its edge would hang above them with a gap below.
     */
    private static boolean filmShown(SectionBlocks world, int x, int y, int z) {
        if(!FluidicWater.isFilm(world, new BlockPos(x, y, z))) return false;
        long below = Water.sectionKey(x >> 4, (y - 1) >> 4, z >> 4);
        if(ChunkRedraws.shows(below)) return true;
        // Drawn again once that section is, see filmsCaughtUp
        FILM_WAITS.computeIfAbsent(below, k -> new LongOpenHashSet()).add(building);
        return false;
    }

    /** A section of water blocks was drawn again, the meshes with thin layers waiting for it are rebuilt to match */
    private static void filmsCaughtUp(long key) {
        LongOpenHashSet waiting = FILM_WAITS.remove(key);
        if(waiting != null) for(long section : waiting) markUrgent(section);
    }

    /** A trace of simulated water resting on water with nothing raising it, not drawn, see FluidicWater.MIN_DRAWN */
    private static boolean isTrace(SectionBlocks world, int x, int y, int z) {
        int height = ClientWaterCache.height(x, y, z);
        return height > 0 && height < FluidicWater.MIN_DRAWN && FluidicWater.isTrace(world, new BlockPos(x, y, z), height) && !raised(world, x, y + 1, z);
    }

    /** Whether water at a block makes the water below reach up to it */
    private static boolean raised(SectionBlocks world, int x, int y, int z) {
        return ClientWaterCache.height(x, y, z) >= FluidicWater.MIN_DRAWN || isLiquid(world, x, y, z);
    }

    /** See FluidicWater.sheetWeight */
    private static int sheetWeight(SectionBlocks world, int x, int y, int z) {
        return y > 255 || !raised(world, x, y, z) ? 0 : FluidicWater.sheetWeight(world, new BlockPos(x, y, z));
    }

    private static boolean isSolid(SectionBlocks world, int x, int y, int z) {
        return y < 0 || y > 255 || world.state(x, y, z).getMaterial().isSolid();
    }

    private static boolean isOpaque(SectionBlocks world, int x, int y, int z) {
        return y >= 0 && y <= 255 && world.state(x, y, z).isOpaqueCube();
    }

    private static boolean isLiquid(SectionBlocks world, int x, int y, int z) {
        return y >= 0 && y <= 255 && shown(world, x, y, z).getMaterial().isLiquid();
    }

    /**
     * The block at a position as the chunk on screen shows it. Water blocks that came, went or changed level show as they
     * were until their chunk is redrawn, simulated water meets them as they are drawn, without gaps between the two.
     */
    private static IBlockState shown(SectionBlocks world, int x, int y, int z) {
        return ChunkRedraws.shownState(x, y, z, world.state(x, y, z));
    }
}
