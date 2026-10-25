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
import net.minecraft.util.math.MathHelper;
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

import javax.annotation.Nullable;

/**
 * Renders water that is still being simulated. Each section with water is compiled into a display list
 * when the server sends new heights, so drawing a frame is one call per section.
 */
@Mod.EventBusSubscriber(modid = Fluidic.MODID, value = Side.CLIENT)
public final class WaterRenderer {

    private static final Long2IntOpenHashMap DISPLAY_LISTS = new Long2IntOpenHashMap();
    private static final LongLinkedOpenHashSet DIRTY = new LongLinkedOpenHashSet();
    private static final LongLinkedOpenHashSet URGENT = new LongLinkedOpenHashSet();
    /** Sections around chunk sections that just came on screen, rebuilt in full this frame, see chunkRedrawn */
    private static final LongLinkedOpenHashSet SYNC = new LongLinkedOpenHashSet();
    /** Sections drawn as puddles further than they are drawn in full, see buildPuddle, rebuilt in full once near */
    private static final LongOpenHashSet SIMPLE = new LongOpenHashSet();
    /** When water easing was last rebuilt, see onRenderWorldLast */
    private static long lastEaseFrame;
    /** The view of the frame being drawn, see seen */
    private static Frustum viewFrustum;
    private static double viewX, viewZ;
    private static int viewDistance;
    /**
     * Chunk sections vanilla found in view this frame, see RenderGlobalMixin: its cave culling leaves out those behind
     * walls and the ground. Null when it did not say this frame, OptiFine or Celeritas may draw the chunks instead.
     */
    private static final LongOpenHashSet SECTIONS_IN_VIEW = new LongOpenHashSet();
    private static boolean sectionsInViewFresh;
    private static LongOpenHashSet sectionsInView;
    /**
     * How far outside the view sections are rebuilt anyway, in blocks. Turning, the sections coming into view were mostly
     * built already, the rest are a frame or two behind.
     */
    private static final int VIEW_MARGIN = 8;
    private static final BufferBuilder BUFFER = new BufferBuilder(1 << 16);
    private static final WorldVertexBufferUploader UPLOADER = new WorldVertexBufferUploader();
    private static final BlockPos.MutableBlockPos POS = new BlockPos.MutableBlockPos();
    /** Keeps faces between water and solid blocks from z-fighting */
    private static final float INSET = 0.001F;
    /**
     * Vanilla draws the surface of water blocks this far below its corners. Simulated water drawn at the corners would
     * stand a step above the water blocks beside it, open at grazing angles.
     */
    private static final float SURFACE_DROP = FluidicWater.SURFACE_DROP;
    /** How far a surface reaches over an edge shared with water of another mesh, see buildCell */
    private static final float OVERLAP = 0.005F;
    /** Time per frame spent rebuilding meshes at most */
    private static final long REBUILD_NANOS = 2_000_000L;
    /** Time per frame spent on sections beside changed blocks at most */
    private static final long URGENT_NANOS = 4_000_000L;
    /** Most sections rebuilt together in one group, see rebuildGroup */
    private static final int MAX_GROUP = 64;
    /** Heights of a section whose water is gone */
    private static final byte[] NO_WATER = new byte[4096];
    /** Surface height of a full block of water as vanilla renders it */
    private static final float FULL_HEIGHT = Water.RENDER_HEIGHT;

    private static TextureAtlasSprite still, flow, overlay;
    private static int red, green, blue;
    /** The cell being built is a thin layer resting on a full block of water, see FluidicWater.isFilm */
    private static boolean film;
    /** Surface corners of the last cell built, h00, h10, h11, h01 */
    private static final float[] cellCorners = new float[4];
    /** Corners of the last cell built as the water around shares them, s00, s10, s11, s01 */
    private static final float[] cellShared = new float[4];
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
        WaterMotion.clear();
        URGENT.clear();
        DIRTY.clear();
        if(!ChunkWater.isActive()) for(long key : ClientWaterCache.keys()) DIRTY.add(key);
    }

    /**
     * A chunk section drawn with new simulated heights is on screen, see ChunkRedraws.drawn. Simulated water around it
     * meets its water blocks as they are drawn now, rebuilt in this same frame.
     */
    static void chunkRedrawn(long key, int borders) {
        if(ChunkWater.isActive() || !ForgeConfigHandler.client.renderWater) return;
        Minecraft mc = Minecraft.getMinecraft();
        if(!mc.isCallingFromMinecraftThread()) {
            mc.addScheduledTask(() -> chunkRedrawn(key, borders));
            return;
        }
        int sx = Water.keyX(key), sy = Water.keyY(key), sz = Water.keyZ(key);
        // The section and the ones beside it its switched water blocks are near, as bits by offset, see markDirty
        for(int bit = 0; bit < 27; bit++) {
            if((borders & (1 << bit)) == 0) continue;
            int ny = sy + bit / 3 % 3 - 1;
            if(ny < 0 || ny > 15) continue;
            long section = Water.sectionKey(sx + bit / 9 - 1, ny, sz + bit % 3 - 1);
            if(ClientWaterCache.hasSection(section) || DISPLAY_LISTS.containsKey(section) || ChunkRedraws.anyTaken(section)) SYNC.add(section);
        }
    }

    /** The client dropped a section, nothing waits for it to be drawn again */
    static void unloadSection(long key) {
        FILM_WAITS.remove(key);
        SIMPLE.remove(key);
        WaterMotion.unload(key);
        // Water blocks it drew for the chunks are gone with it
        int list = DISPLAY_LISTS.remove(key);
        if(list != 0) GLAllocation.deleteDisplayLists(list);
        SYNC.remove(key);
    }

    static void clear() {
        for(int list : DISPLAY_LISTS.values()) GLAllocation.deleteDisplayLists(list);
        DISPLAY_LISTS.clear();
        SIMPLE.clear();
        SYNC.clear();
        WaterMotion.clear();
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
        // Water easing is rebuilt at most 50 times a second, see WaterMotion.MIN_EASE_FRAME_NANOS. Between those frames the
        // eased heights stay where its meshes were built, everything else built meets them there.
        long frameTime = System.nanoTime();
        boolean easeFrame = !WaterMotion.anyMoving() || frameTime - lastEaseFrame >= WaterMotion.MIN_EASE_FRAME_NANOS;
        if(easeFrame) {
            lastEaseFrame = frameTime;
            WaterMotion.frame();
        }
        float partialTicks = event.getPartialTicks();
        double camX = view.lastTickPosX + (view.posX - view.lastTickPosX) * partialTicks;
        double camY = view.lastTickPosY + (view.posY - view.lastTickPosY) * partialTicks;
        double camZ = view.lastTickPosZ + (view.posZ - view.lastTickPosZ) * partialTicks;
        int distance = Math.min(ForgeConfigHandler.client.renderDistance, mc.gameSettings.renderDistanceChunks) * 16 + 8;
        double maxDistanceSq = (double)distance * distance;
        // Sections out of view are skipped, the view is the one the chunks were just drawn with
        Frustum frustum = new Frustum();
        frustum.setPosition(camX, camY, camZ);
        viewFrustum = frustum;
        viewX = camX;
        viewZ = camZ;
        viewDistance = distance;
        sectionsInView = sectionsInViewFresh ? SECTIONS_IN_VIEW : null;
        sectionsInViewFresh = false;
        // Sections out of view wait to be rebuilt until they come into view, they are not drawn until then
        deferUnseen(SYNC);
        deferUnseen(URGENT);
        // Rebuilds are capped by count and by time, so busy weather lags the water behind instead of the frame rate
        int budget = ForgeConfigHandler.client.meshRebuildsPerFrame;
        // Puddles drawn simply that came near enough to be drawn in full
        if(!SIMPLE.isEmpty()) {
            for(LongIterator it = SIMPLE.iterator(); it.hasNext(); ) {
                long key = it.nextLong();
                if(beyond(key, ForgeConfigHandler.client.puddleDetailDistance)) continue;
                it.remove();
                DIRTY.add(key);
            }
        }
        // Chunks drawn with water blocks that came or went this frame, their simulated water switches with them
        ChunkRedraws.caughtUp(WaterRenderer::markUrgent, WaterRenderer::filmsCaughtUp);
        // Chunk sections that came on screen with water blocks left to this renderer or taken back, all of them, the
        // chunks already show the change
        while(!SYNC.isEmpty()) {
            long key = SYNC.removeFirstLong();
            DIRTY.remove(key);
            URGENT.remove(key);
            rebuildGroup(world, key, MAX_GROUP, Long.MAX_VALUE);
        }
        // Water easing to new heights, built from the heights eased to this frame, with the sections around it whose
        // corners it shares, all of them in this same frame, see WaterMotion
        LongOpenHashSet moving = easeFrame ? WaterMotion.moving() : new LongOpenHashSet();
        // Sections beside them waiting for a rebuild with heights they already read, the shared edges would not meet
        LongArrayList waiting = new LongArrayList();
        for(long key : moving) {
            int sx = Water.keyX(key), sy = Water.keyY(key), sz = Water.keyZ(key);
            for(int dx = -1; dx <= 1; dx++) {
                for(int dy = -1; dy <= 1; dy++) {
                    for(int dz = -1; dz <= 1; dz++) {
                        long neighbor = Water.sectionKey(sx + dx, sy + dy, sz + dz);
                        if(sy + dy >= 0 && sy + dy <= 15 && (DIRTY.contains(neighbor) || URGENT.contains(neighbor))) waiting.add(neighbor);
                    }
                }
            }
        }
        moving.addAll(waiting);
        for(long key : moving) {
            if(!ClientWaterCache.hasSection(key) && !DISPLAY_LISTS.containsKey(key) && !ChunkRedraws.anyTaken(key)) continue;
            URGENT.remove(key);
            // Built where it eased to once it comes into view
            if(!seen(key)) {
                DIRTY.add(key);
                continue;
            }
            DIRTY.remove(key);
            rebuild(world, key);
        }
        // Sections beside changed blocks go first and past the count, vanilla may draw the change in this same frame
        // The rest waits for the next frame water easing is rebuilt in, the eased water around reads their new heights then
        long urgentDeadline = System.nanoTime() + URGENT_NANOS;
        while(!URGENT.isEmpty() && easeFrame && System.nanoTime() < urgentDeadline) {
            long key = URGENT.removeFirstLong();
            DIRTY.remove(key);
            rebuildGroup(world, key, MAX_GROUP, urgentDeadline);
        }
        long deadline = System.nanoTime() + REBUILD_NANOS;
        if(easeFrame && !DIRTY.isEmpty()) {
            // Those in view, in the order they were marked
            LongArrayList seen = new LongArrayList();
            for(LongIterator it = DIRTY.iterator(); it.hasNext(); ) {
                long key = it.nextLong();
                if(seen(key)) seen.add(key);
            }
            for(int n = 0; n < seen.size() && budget > 0 && System.nanoTime() < deadline; n++) {
                long key = seen.getLong(n);
                // Rebuilt already with a group before it
                if(DIRTY.remove(key)) budget -= rebuildGroup(world, key, Math.min(budget, MAX_GROUP), deadline);
            }
        }
        if(DISPLAY_LISTS.isEmpty()) return;

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
            if(sectionsInView != null && !sectionsInView.contains(key)) continue;
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
                        // Out of view it is not drawn against this one, it waits, see deferUnseen
                        if(!seen(neighbor)) continue;
                        if(DIRTY.remove(neighbor) | URGENT.remove(neighbor) | SYNC.remove(neighbor)) group.add(neighbor);
                    }
                }
            }
        }
        // Too many at once, the rest waits for the next frame
        for(int n = built; n < group.size(); n++) DIRTY.add(group.getLong(n));
        return built;
    }

    private static void rebuild(WorldClient world, long key) {
        // As eased this frame, see WaterMotion
        byte[] heights = WaterMotion.section(key);
        // Water blocks the chunk on screen left to this renderer, see ChunkRedraws.Snapshot
        boolean[] takenCells = ChunkRedraws.takenCells(key);
        boolean taken = takenCells != null;
        if(heights == null && taken) heights = NO_WATER;
        // Puddles further than they are drawn in full, drawn simply, see buildPuddle
        boolean simple = heights != null && !taken && ClientWaterCache.puddles(heights) && beyond(key, ForgeConfigHandler.client.puddleDetailDistance);
        boolean any = false;
        if(heights != null) {
            int ox = Water.keyX(key) * 16, oy = Water.keyY(key) * 16, oz = Water.keyZ(key) * 16;
            // Corners and colours worked out for this section only
            generation++;
            building = key;
            SectionBlocks blocks = BLOCKS.begin(world, ox, oy, oz);
            BUFFER.begin(GL11.GL_QUADS, DefaultVertexFormats.BLOCK);
            RuntimeException failed = null;
            // Every height looked up while building is the eased one, here and in the sections around
            WaterMotion.easing = true;
            try {
                for(int i = 0; i < 4096; i++) {
                    int lx = i & 15, ly = i >> 8, lz = (i >> 4) & 15;
                    if(heights[i] == 0 && !(taken && takenCells[i] && FluidicWater.isWater(shown(blocks, ox + lx, oy + ly, oz + lz)))) continue;
                    any |= simple ? buildPuddle(blocks, ox, oy, oz, lx, ly, lz) : buildCell(blocks, ox, oy, oz, lx, ly, lz);
                }
            }
            catch(RuntimeException ex) {
                failed = ex;
            }
            finally {
                WaterMotion.easing = false;
                // Left building, every later rebuild would fail
                BUFFER.finishDrawing();
            }
            if(failed != null) {
                Fluidic.LOGGER.error("Failed to build simulated water mesh of a section", failed);
                any = false;
            }
        }

        if(simple && any) SIMPLE.add(key);
        else SIMPLE.remove(key);
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

    /**
     * Whether a section is in view this frame or close to it, see VIEW_MARGIN. Sections out of view are not drawn, they
     * wait to be rebuilt until they come into view, and a storm behind the camera costs nothing.
     */
    private static boolean seen(long key) {
        if(viewFrustum == null) return true;
        double dx = Water.keyX(key) * 16 + 8 - viewX, dz = Water.keyZ(key) * 16 + 8 - viewZ;
        double reach = viewDistance + VIEW_MARGIN;
        if(dx * dx + dz * dz > reach * reach) return false;
        int bx = Water.keyX(key) * 16, by = Water.keyY(key) * 16, bz = Water.keyZ(key) * 16;
        if(!viewFrustum.isBoxInFrustum(bx - VIEW_MARGIN, by - VIEW_MARGIN, bz - VIEW_MARGIN, bx + 16 + VIEW_MARGIN, by + 16 + VIEW_MARGIN, bz + 16 + VIEW_MARGIN)) return false;
        // Hidden behind walls or the ground. Along the edges of the view it is built ahead of turning anyway.
        if(sectionsInView == null || !viewFrustum.isBoxInFrustum(bx, by, bz, bx + 16, by + 16, bz + 16)) return true;
        return sectionsInView.contains(key);
    }

    /**
     * Vanilla works out the chunk sections in view, returns the set to fill with them, null when nothing here needs them,
     * see RenderGlobalMixin
     */
    @Nullable
    public static LongOpenHashSet beginSectionsInView() {
        if(!ForgeConfigHandler.client.renderWater || ChunkWater.isActive()) return null;
        if(DISPLAY_LISTS.isEmpty() && DIRTY.isEmpty() && URGENT.isEmpty() && SYNC.isEmpty() && !WaterMotion.anyMoving()) return null;
        SECTIONS_IN_VIEW.clear();
        sectionsInViewFresh = true;
        return SECTIONS_IN_VIEW;
    }

    /** Moves the sections of a set out of view to DIRTY, rebuilt once in view */
    private static void deferUnseen(LongLinkedOpenHashSet sections) {
        for(LongIterator it = sections.iterator(); it.hasNext(); ) {
            long key = it.nextLong();
            if(seen(key)) continue;
            it.remove();
            DIRTY.add(key);
        }
    }

    /** Whether a section is further from the camera than a distance in chunks */
    private static boolean beyond(long key, int distance) {
        Entity view = Minecraft.getMinecraft().getRenderViewEntity();
        if(view == null) return false;
        int cx = MathHelper.floor(view.posX) >> 4, cz = MathHelper.floor(view.posZ) >> 4;
        return Math.max(Math.abs(Water.keyX(key) - cx), Math.abs(Water.keyZ(key) - cz)) > distance;
    }

    /**
     * A cell of a section of puddles further than they are drawn in full, see ClientWaterCache.puddles. Rain keeps puddles
     * changing all over the ground, and buildCell works out corners shared with the water around, looking up hundreds of
     * blocks and heights for every cell. Here the cell is flat at its own height, with faces down to lower or dry water
     * beside it, so it closes up against the puddles around without sharing corners. Where it meets water drawn in full a
     * small step may show, hard to see that far away.
     */
    private static boolean buildPuddle(SectionBlocks world, int ox, int oy, int oz, int lx, int ly, int lz) {
        int x = ox + lx, y = oy + ly, z = oz + lz;
        // Drawn by the chunk as a water block
        if(FluidicWater.isWater(shown(world, x, y, z))) return false;
        float top = puddleHeight(world, x, y, z);
        if(top <= 0.0F) return false;
        int light = world.light(x, y, z), above = world.light(x, y + 1, z);
        int sky = Math.max(light >> 20 & 15, above >> 20 & 15) << 4, block = light & 65535;
        int column16 = lx * 16 + lz;
        if(colorGeneration[column16] != generation) {
            colorGeneration[column16] = generation;
            colors[column16] = BiomeColorHelper.getWaterColorAtPos(world, POS.setPos(x, y, z));
        }
        int color = colors[column16];
        red = color >> 16 & 255;
        green = color >> 8 & 255;
        blue = color & 255;
        float u0 = still.getMinU(), u1 = still.getMaxU(), v0 = still.getMinV(), v1 = still.getMaxV();
        vertex(lx, ly + top, lz, u0, v0, sky, block);
        vertex(lx, ly + top, lz + 1, u0, v1, sky, block);
        vertex(lx + 1, ly + top, lz + 1, u1, v1, sky, block);
        vertex(lx + 1, ly + top, lz, u1, v0, sky, block);
        puddleSide(world, x, y, z - 1, lx, ly, lz, lx + 1, lz, top, EnumFacing.NORTH);
        puddleSide(world, x, y, z + 1, lx + 1, ly, lz + 1, lx, lz + 1, top, EnumFacing.SOUTH);
        puddleSide(world, x - 1, y, z, lx, ly, lz + 1, lx, lz, top, EnumFacing.WEST);
        puddleSide(world, x + 1, y, z, lx + 1, ly, lz, lx + 1, lz + 1, top, EnumFacing.EAST);
        return true;
    }

    /** Surface of a puddle cell drawn simply, see buildPuddle, 0 for no water drawn there */
    private static float puddleHeight(SectionBlocks world, int x, int y, int z) {
        if(isLiquid(world, x, y, z)) return liquidHeight(world, x, y, z) - SURFACE_DROP;
        // By eighths, smaller changes do not rebuild it, see ClientWaterCache.show
        int height = ClientWaterCache.eighths(ClientWaterCache.height(x, y, z));
        return height == 0 ? 0.0F : height / 8.0F * FULL_HEIGHT - SURFACE_DROP;
    }

    /** The face of a puddle drawn simply down to the water beside it, or the ground beside it, where that is lower */
    private static void puddleSide(SectionBlocks world, int nx, int y, int nz, float x0, int ly, float z0, float x1, float z1, float top, EnumFacing toward) {
        if(isSolid(world, nx, y, nz) || isOpaque(world, nx, y, nz)) return;
        float bottom = Math.max(0.0F, puddleHeight(world, nx, y, nz));
        if(bottom >= top) return;
        int light = world.light(nx, y, nz);
        int sky = (light >> 20 & 15) << 4, block = light & 65535;
        float shade = toward.getAxis() == EnumFacing.Axis.Z ? 0.8F : 0.6F;
        float u0 = flow.getMinU(), u1 = flow.getInterpolatedU(8.0D);
        float vTop = flow.getInterpolatedV((1.0F - top) * 8.0F), vBottom = flow.getInterpolatedV((1.0F - bottom) * 8.0F);
        vertex(x0, ly + bottom, z0, u0, vBottom, sky, block, shade);
        vertex(x0, ly + top, z0, u0, vTop, sky, block, shade);
        vertex(x1, ly + top, z1, u1, vTop, sky, block, shade);
        vertex(x1, ly + bottom, z1, u1, vBottom, sky, block, shade);
        vertex(x0, ly + bottom, z0, u0, vBottom, sky, block, shade);
        vertex(x1, ly + bottom, z1, u1, vBottom, sky, block, shade);
        vertex(x1, ly + top, z1, u1, vTop, sky, block, shade);
        vertex(x0, ly + top, z0, u0, vTop, sky, block, shade);
    }

    /** Adds the visible faces of one water cell in section local coordinates, returns whether any were added */
    private static boolean buildCell(SectionBlocks world, int ox, int oy, int oz, int lx, int ly, int lz) {
        int x = ox + lx, y = oy + ly, z = oz + lz;
        // A water block the chunk on screen still shows, or already shows, draws this water. Drawing both doubles it,
        // darker, for a moment: waking water is sent before its block goes, settled water stays until the chunk has it.
        IBlockState drawn = shown(world, x, y, z);
        if(FluidicWater.isWater(drawn) && !ChunkRedraws.taken(x, y, z)) {
            return false;
        }
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
        surfaceCorners(world, x, y, z, lx, ly, lz, surface);
        float s00 = cellShared[0], s10 = cellShared[1], s11 = cellShared[2], s01 = cellShared[3];
        float h00 = cellCorners[0], h10 = cellCorners[1], h11 = cellCorners[2], h01 = cellCorners[3];
        // Water resting on another mod's liquid reaches down to its surface, which sits below the top of the block.
        // Fluidic water under simulated water fills its block to the top itself.
        float floor = ClientWaterCache.height(x, y - 1, z) == 0 ? Math.min(0.0F, liquidLevel(world, x, y - 1, z) - 1.0F) : 0.0F;

        if(surface < 1.0F) {
            float u0 = still.getMinU(), u1 = still.getMaxU(), v0 = still.getMinV(), v1 = still.getMaxV();
            // Water drawn by another mesh beside it, a water block of the chunk or the next section, meets it at the same
            // edge, but drawn apart the two do not rasterize watertight and single pixels show through along it. Reaching
            // a hair over the edge covers them.
            float x0 = lx - overlap(world, x - 1, y, z, lx == 0), x1 = lx + 1 + overlap(world, x + 1, y, z, lx == 15);
            float z0 = lz - overlap(world, x, y, z - 1, lz == 0), z1 = lz + 1 + overlap(world, x, y, z + 1, lz == 15);
            vertex(x0, ly + h00, z0, u0, v0, sky, block);
            vertex(x0, ly + h01, z1, u0, v1, sky, block);
            vertex(x1, ly + h11, z1, u1, v1, sky, block);
            vertex(x1, ly + h10, z0, u1, v0, sky, block);
            // Seen from below too, from inside the water, where vanilla's water draws it from below, see shownFromBelow
            if(shownFromBelow(world, x, y + 1, z)) {
                vertex(x0, ly + h00, z0, u0, v0, sky, block);
                vertex(x1, ly + h10, z0, u1, v0, sky, block);
                vertex(x1, ly + h11, z1, u1, v1, sky, block);
                vertex(x0, ly + h01, z1, u0, v1, sky, block);
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

        if(!isThin(world, x, y, z - 1)) added |= side(world, x, y, z - 1, lx, ly, lz + inset(world, x, y, z - 1), lx + 1, lz + inset(world, x, y, z - 1), h00, h10, s00, s10, floor, column, EnumFacing.NORTH);
        if(!isThin(world, x, y, z + 1)) added |= side(world, x, y, z + 1, lx + 1, ly, lz + 1 - inset(world, x, y, z + 1), lx, lz + 1 - inset(world, x, y, z + 1), h11, h01, s11, s01, floor, column, EnumFacing.SOUTH);
        if(!isThin(world, x - 1, y, z)) added |= side(world, x - 1, y, z, lx + inset(world, x - 1, y, z), ly, lz + 1, lx + inset(world, x - 1, y, z), lz, h01, h00, s01, s00, floor, column, EnumFacing.WEST);
        if(!isThin(world, x + 1, y, z)) added |= side(world, x + 1, y, z, lx + 1 - inset(world, x + 1, y, z), ly, lz, lx + 1 - inset(world, x + 1, y, z), lz + 1, h10, h11, s10, s11, floor, column, EnumFacing.EAST);
        added |= thinBlocks(world, x, y, z, lx, ly, lz);
        return added;
    }

    /**
     * How far a side face sits inside its block's edge toward a block beside it. Against a block it would flicker with
     * the block's face. Against water it stands on the edge: a step down to the water beside it, set in, would leave a
     * slit along the edge of that water's surface, showing as a dark line.
     */
    private static float inset(SectionBlocks world, int x, int y, int z) {
        return isSolid(world, x, y, z) || isOpaque(world, x, y, z) ? INSET : 0.0F;
    }

    /** How far a surface reaches over its edge toward a block, see buildCell: water drawn by another mesh there */
    private static float overlap(SectionBlocks world, int x, int y, int z, boolean otherSection) {
        return isLiquid(world, x, y, z) || otherSection && ClientWaterCache.height(x, y, z) > 0 ? OVERLAP : 0.0F;
    }

    /**
     * The corners of a cell as the water around shares them into cellShared, s00, s10, s11, s01, and its surface corners
     * into cellCorners, full height for water continuing up
     */
    private static void surfaceCorners(SectionBlocks world, int x, int y, int z, int lx, int ly, int lz, float surface) {
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
        // Just under the corners like vanilla's water surfaces, see SURFACE_DROP. Every face meeting at a corner takes it
        // from here, the sides of a step down too, or the step would stand apart from the surface it meets.
        cellShared[0] = s00 - SURFACE_DROP;
        cellShared[1] = s10 - SURFACE_DROP;
        cellShared[2] = s11 - SURFACE_DROP;
        cellShared[3] = s01 - SURFACE_DROP;
        boolean column = surface >= 1.0F;
        for(int k = 0; k < 4; k++) cellCorners[k] = column ? 1.0F - SURFACE_DROP : cellShared[k];
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
                added |= ThinBlockWater.draw(world, pos, state, true, (vx, vy, vz, u, v, shade) -> vertex(vx, vy, vz, u, v, sky, block, shade),
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
        boolean ledge = false;
        if(neighbor >= 0.0F) {
            // The neighbour's surface slopes along the shared edge, the face follows it so no wedge stays open
            bottom0 = Math.max(-SURFACE_DROP, edge0);
            bottom1 = Math.max(-SURFACE_DROP, edge1);
        }
        else {
            float bottom = floor;
            bottom0 = bottom1 = bottom;
            // Not the edge of a thin layer on a pool, the pool beside it rises to meet it, see FluidicWater.isFilm
            if(!isSolid(world, nx, y, nz) && !film) {
                float below = surface(world, nx, y - 1, nz);
                if(below >= 0.0F && below < 1.0F) {
                    // Down to that water's surface at the corners it shares, which slopes along the edge. Level with its
                    // middle, the face would stand above it where its corners are lower and leave a slit under it.
                    BlockPos under = new BlockPos(nx, y - 1, nz);
                    int ox = Water.keyX(building) * 16, oz = Water.keyZ(building) * 16;
                    bottom0 = Math.min(bottom, corner(world, ox + Math.round(x0), y - 1, oz + Math.round(z0), below, under) - SURFACE_DROP - 1.0F);
                    bottom1 = Math.min(bottom, corner(world, ox + Math.round(x1), y - 1, oz + Math.round(z1), below, under) - SURFACE_DROP - 1.0F);
                    ledge = true;
                }
            }
            // The edge of a thin layer onto the pool beside it, which is drawn just under the top of its block
            else if(film && !isSolid(world, nx, y, nz) && surface(world, nx, y - 1, nz) >= 0.0F) {
                bottom0 = bottom1 = Math.min(bottom, -SURFACE_DROP);
            }
        }
        if(top0 <= bottom0 && top1 <= bottom1) return false;
        // Reaching down over a ledge it runs along the face of the block under this cell, in the same plane. Set out from
        // it like faces against blocks are set in, or the two would flicker.
        int fx = toward.getXOffset(), fz = toward.getZOffset();
        if(ledge && inset(world, nx - fx, y - 1, nz - fz) > 0.0F) {
            x0 += fx * INSET;
            x1 += fx * INSET;
            z0 += fz * INSET;
            z1 += fz * INSET;
        }
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
        vertex(x, y, z, u, v, sky, block, 1.0F);
    }

    /** A vertex shaded by the direction of its face, like vanilla shades block faces */
    private static void vertex(double x, double y, double z, float u, float v, int sky, int block, float shade) {
        BUFFER.pos(x, y, z).color((int)(red * shade), (int)(green * shade), (int)(blue * shade), 255).tex(u, v).lightmap(sky, block).endVertex();
    }

    /**
     * Rendered water level of a cell, simulated or a water block: -1 for no water, 1 when water continues above.
     * Uses the same scale as vanilla's fluid renderer so simulated water and water blocks meet.
     */
    private static float surface(SectionBlocks world, int x, int y, int z) {
        int height = ClientWaterCache.height(x, y, z);
        if(height == 0) return liquidHeight(world, x, y, z);
        // A trace is not drawn, faces beside it show like beside air, see FluidicWater.drawnSimulated
        if(isTrace(world, x, y, z)) return liquidHeight(world, x, y, z);
        // A water block drawn here, its simulated water not taken in yet, see ChunkRedraws.Snapshot
        if(FluidicWater.isWater(shown(world, x, y, z)) && ChunkRedraws.taken(x, y, z)) return liquidHeight(world, x, y, z);
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
        // Like FluidicWater.renderHeight: water continuing up into a block or simulated water makes it a column. A block the
        // chunks draw is raised as its chunk was drawn, see corner, unless a corner it is part of looks at it that way already
        boolean raised;
        if(ChunkRedraws.viewingDrawn() || !chunkDrawn(world, x, y, z)) raised = raised(world, x, y + 1, z);
        else {
            ChunkRedraws.Snapshot before = ChunkRedraws.view(x, y, z);
            try {
                raised = raised(world, x, y + 1, z);
            }
            finally {
                ChunkRedraws.endView(before);
            }
        }
        if(raised) return 1.0F;
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
        // A corner touching a water block the chunks draw is shaped by the simulated water around it as the block's chunk
        // was drawn, the chunk draws the block's side of it with those. Water blocks left to this renderer are drawn with the
        // heights now, see ChunkRedraws.Snapshot.
        for(int dx = -1; dx <= 0; dx++) {
            for(int dz = -1; dz <= 0; dz++) {
                if(!chunkDrawn(world, x + dx, y, z + dz)) continue;
                ChunkRedraws.Snapshot before = ChunkRedraws.view(x + dx, y, z + dz);
                try {
                    return cornerOf(world, x, y, z, fallback, cell);
                }
                finally {
                    ChunkRedraws.endView(before);
                }
            }
        }
        return cornerOf(world, x, y, z, fallback, cell);
    }

    private static float cornerOf(SectionBlocks world, int x, int y, int z, float fallback, BlockPos cell) {
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
        // Water under it drawn here reaches up to it in this same mesh, only water blocks the chunks draw wait for them
        if(!chunkDrawn(world, x, y - 1, z)) return true;
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

    /**
     * Whether water at a block makes the water below reach up to it: any water drawn there. A trace drawn as part of the
     * surface beside it, see FluidicWater.isTrace, would otherwise be drawn as a second surface just above the one below.
     */
    private static boolean raised(SectionBlocks world, int x, int y, int z) {
        int height = ClientWaterCache.height(x, y, z);
        return height >= FluidicWater.MIN_DRAWN || isLiquid(world, x, y, z) || height > 0 && !FluidicWater.isTrace(world, new BlockPos(x, y, z), height);
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

    /** Whether a liquid block is drawn by the chunks, Fluidic water near simulated water is drawn here, see ChunkRedraws.Snapshot */
    private static boolean chunkDrawn(SectionBlocks world, int x, int y, int z) {
        if(!isLiquid(world, x, y, z)) return false;
        return !FluidicWater.isWater(shown(world, x, y, z)) || !ChunkRedraws.taken(x, y, z);
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
