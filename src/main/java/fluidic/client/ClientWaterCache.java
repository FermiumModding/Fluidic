package fluidic.client;

import fluidic.Fluidic;
import fluidic.handlers.ForgeConfigHandler;
import fluidic.block.FluidicWater;
import fluidic.sim.SectionHeights;
import fluidic.sim.Water;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.entity.Entity;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import net.minecraftforge.fml.relauncher.Side;

import java.util.Iterator;

/**
 * Heights of simulated water received from the server, one byte per block (255 = full). Written on the client
 * thread; {@link #heightAnyThread} may also be read from chunk render threads, which draw settled water.
 */
@Mod.EventBusSubscriber(modid = Fluidic.MODID, value = Side.CLIENT)
public final class ClientWaterCache {

    private static final int ALL_BORDERS = (1 << 27) - 1;

    /** Most ticks settled water keeps its simulated drawing while waiting for the chunk to redraw with the new blocks */
    private static final int SETTLE_OVERLAP_TICKS = 60;

    private static final SectionHeights SECTIONS = new SectionHeights();
    /** The heights sections switch to once the water that settled in them stops being drawn */
    private static final Long2ObjectOpenHashMap<Deferred> DEFERRED = new Long2ObjectOpenHashMap<>();
    /** Updates of sections further than the water is drawn in full, held back, see holdTicks, and the tick they are shown */
    private static final Long2ObjectOpenHashMap<byte[]> HELD = new Long2ObjectOpenHashMap<>();
    private static final Long2IntOpenHashMap HELD_UNTIL = new Long2IntOpenHashMap();
    /** Stands for an update of a section that has no water left, a held back update cannot be null */
    private static final byte[] GONE = new byte[0];
    /** Most ticks an update is held back */
    private static final int MAX_HOLD_TICKS = 10;
    /** Most ticks an update of puddles is held back, see puddles */
    private static final int MAX_PUDDLE_HOLD_TICKS = 40;
    /** Deepest water of a puddle, half a block */
    static final int PUDDLE_DEPTH = 128;
    private static int dimension = Integer.MIN_VALUE;
    private static int ticks;

    private ClientWaterCache() {}

    public static void update(int packetDimension, long key, byte[] heights) {
        Minecraft mc = Minecraft.getMinecraft();
        if(mc.world == null || mc.world.provider.getDimension() != packetDimension) return;
        if(dimension != packetDimension) {
            clear();
            dimension = packetDimension;
        }
        // Further away than the water is drawn in full, a newer update replaces one held back but keeps its time, unless it
        // is due sooner, like a pond forming among puddles
        int hold = holdTicks(key, heights);
        if(hold > 0) {
            if(HELD.put(key, heights == null ? GONE : heights) == null) HELD_UNTIL.put(key, ticks + hold);
            else HELD_UNTIL.put(key, Math.min(HELD_UNTIL.get(key), ticks + hold));
            return;
        }
        HELD.remove(key);
        HELD_UNTIL.remove(key);
        apply(key, heights);
    }

    /**
     * Ticks an update of a section is held back. Small changes of water a few chunks away are hard to see, and every
     * update rebuilds its mesh and the meshes beside it. Held back as a whole, it is shown as if it arrived later, the
     * water around meets it as it is drawn. Puddles are held back longer and nearer, see puddles.
     */
    private static int holdTicks(long key, byte[] heights) {
        Entity view = Minecraft.getMinecraft().getRenderViewEntity();
        if(view == null) return 0;
        int distance = Math.max(Math.abs((MathHelper.floor(view.posX) >> 4) - Water.keyX(key)), Math.abs((MathHelper.floor(view.posZ) >> 4) - Water.keyZ(key)));
        int puddle = ForgeConfigHandler.client.puddleDetailDistance;
        if(distance > puddle && heights != GONE && puddles(heights) && puddles(SECTIONS.get(key))) {
            return Math.min(MAX_PUDDLE_HOLD_TICKS, (distance - puddle) * 10);
        }
        int full = ForgeConfigHandler.client.fullDetailDistance;
        return distance <= full ? 0 : Math.min(MAX_HOLD_TICKS, (distance - full) * 2);
    }

    /**
     * Whether the water of a section is puddles alone: none of it deeper than half a block or resting on water in it.
     * Rain keeps puddles changing in small steps all over the ground, each rebuilding its section and the ones beside it.
     * Ponds hold far fewer cells for the water they show, and their changes are larger.
     */
    static boolean puddles(byte[] heights) {
        if(heights == null) return true;
        for(int i = 0; i < 4096; i++) {
            int height = heights[i] & 255;
            if(height == 0) continue;
            if(height > PUDDLE_DEPTH || i >= 256 && heights[i - 256] != 0) return false;
        }
        return true;
    }

    /** Shows the updates held back whose time came, or that came near enough */
    private static void releaseHeld() {
        if(HELD.isEmpty()) return;
        for(Iterator<Long2ObjectMap.Entry<byte[]>> it = HELD.long2ObjectEntrySet().fastIterator(); it.hasNext(); ) {
            Long2ObjectMap.Entry<byte[]> entry = it.next();
            long key = entry.getLongKey();
            byte[] heights = entry.getValue();
            if(ticks < HELD_UNTIL.get(key) && holdTicks(key, heights) > 0) continue;
            it.remove();
            HELD_UNTIL.remove(key);
            apply(key, heights == GONE ? null : heights);
        }
    }

    private static void apply(long key, byte[] heights) {
        Minecraft mc = Minecraft.getMinecraft();
        WaterMotion.arrived(key);
        // Water that settled into blocks keeps being drawn until the chunk is redrawn with the new blocks. That runs on
        // worker threads and can take many ticks in busy weather, until then the cell would show a hole.
        byte[] shown = heights;
        byte[] current = SECTIONS.get(key);
        if(current != null) {
            int ox = Water.keyX(key) * 16, oy = Water.keyY(key) * 16, oz = Water.keyZ(key) * 16;
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for(int i = 0; i < 4096; i++) {
                if(current[i] == 0 || heights != null && heights[i] != 0) continue;
                if(!FluidicWater.isWater(mc.world.getBlockState(pos.setPos(ox + (i & 15), oy + (i >> 8), oz + ((i >> 4) & 15))))) continue;
                if(shown == heights) shown = heights == null ? new byte[4096] : heights.clone();
                shown[i] = current[i];
            }
        }
        Deferred pending = DEFERRED.get(key);
        if(shown != heights) DEFERRED.put(key, new Deferred(heights, pending != null ? pending.since : ticks));
        else if(pending != null) DEFERRED.remove(key);
        show(key, steadyTraces(current, shown));
    }

    /**
     * Drawn water thinning out stays drawn until it is half as thin as drawn water has to be, see FluidicWater.MIN_DRAWN.
     * Water running down a ledge holds about that much, a little more or less with every update. Whether it is drawn
     * decides whether the water under it is a column up to it or the ledge's face runs down the block instead, crossing
     * back and forth by a point or two the stream would flicker between the two.
     */
    private static byte[] steadyTraces(byte[] current, byte[] shown) {
        if(current == null || shown == null) return shown;
        byte[] result = shown;
        for(int i = 0; i < 4096; i++) {
            int was = current[i] & 255, now = shown[i] & 255;
            if(was < FluidicWater.MIN_DRAWN || now < FluidicWater.MIN_DRAWN / 2 || now >= FluidicWater.MIN_DRAWN) continue;
            if(result == shown) result = shown.clone();
            result[i] = (byte)FluidicWater.MIN_DRAWN;
        }
        return result;
    }

    /** The next server sends its own dimensions keeping vanilla water */
    @SubscribeEvent
    public static void onDisconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        FluidicWater.clientVanillaDimensions = new int[0];
    }

    @SubscribeEvent
    public static void onWorldLoad(WorldEvent.Load event) {
        if(event.getWorld().isRemote) event.getWorld().addEventListener(new ClientWorldListener());
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if(event.phase != TickEvent.Phase.END) return;
        ticks++;
        WorldClient current = Minecraft.getMinecraft().world;
        FluidicWater.clientVanilla = current != null && !FluidicWater.isFinite(current);
        ChunkWater.update();
        if(current == null) {
            HELD.clear();
            HELD_UNTIL.clear();
        }
        else releaseHeld();
        if(DEFERRED.isEmpty()) return;
        if(Minecraft.getMinecraft().world == null) {
            DEFERRED.clear();
            return;
        }
        for(Iterator<Long2ObjectMap.Entry<Deferred>> it = DEFERRED.long2ObjectEntrySet().fastIterator(); it.hasNext(); ) {
            Long2ObjectMap.Entry<Deferred> entry = it.next();
            Deferred deferred = entry.getValue();
            // Until the blocks are drawn, at least a tick later. Without the render mixins nothing says so, then it times out.
            if(ticks == deferred.since || !ChunkRedraws.upToDate(entry.getLongKey()) && ticks - deferred.since < SETTLE_OVERLAP_TICKS) continue;
            // The fast iterator reuses its entry, read it before removing
            long key = entry.getLongKey();
            it.remove();
            show(key, deferred.heights);
        }
    }

    private static void show(long key, byte[] heights) {
        Minecraft mc = Minecraft.getMinecraft();
        byte[] old = heights == null ? SECTIONS.remove(key) : SECTIONS.put(key, heights);
        // Drawn easing there from where it is shown
        WaterMotion.show(key, heights);
        if(old == null && heights == null) return;
        // Puddles further than they are drawn in full are drawn simply by eighths, see WaterRenderer.buildPuddle. Until an
        // eighth changes nothing is rebuilt, and nothing beside them: no gaps are kept closed that far away.
        if(old != null && heights != null && !ChunkWater.isActive() && farPuddles(key) && puddles(old) && puddles(heights)) {
            if(!sameEighths(old, heights)) WaterRenderer.markDirty(key, 0);
            return;
        }

        // Work out what changed, so only what depends on it is redrawn
        int ox = Water.keyX(key) * 16, oy = Water.keyY(key) * 16, oz = Water.keyZ(key) * 16;
        int borders = 0;
        boolean meshChanged = old == null || heights == null;
        int x0 = 16, y0 = 16, z0 = 16, x1 = -1, y1 = -1, z1 = -1;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for(int i = 0; i < 4096; i++) {
            int a = old == null ? 0 : old[i] & 255, b = heights == null ? 0 : heights[i] & 255;
            if(a == b) continue;
            int x = i & 15, y = i >> 8, z = (i >> 4) & 15;
            x0 = Math.min(x0, x); y0 = Math.min(y0, y); z0 = Math.min(z0, z);
            x1 = Math.max(x1, x); y1 = Math.max(y1, y); z1 = Math.max(z1, z);
            // Rain falling on a pool keeps changing traces all over it that no mesh shows
            if(unseenTrace(mc, pos, ox + x, oy + y, oz + z, a, b)) continue;
            meshChanged = true;
            // Cells on the edge are drawn against the sections beside them, diagonal ones too since corners are shared
            if(x == 0 || x == 15 || y == 0 || y == 15 || z == 0 || z == 15) borders |= WaterRenderer.around(x, y, z);
            // Settled water culls its faces against simulated water and its corners take that water's shape: water
            // above raises them, a thin layer's edge drops them, see FluidicWater.surfaceCorner. Redrawn when the shape
            // changes, diagonally too since a corner is shared by four blocks.
            if(ForgeConfigHandler.client.renderWater && FluidicWater.shapeOf(a) != FluidicWater.shapeOf(b) && nearSettledWater(mc, pos, ox + x, oy + y, oz + z)) {
                SettledRedraws.add(ox + x, oy + y, oz + z);
            }
            // The simulated water below is lit through it, see ChunkLight.drawnSky, the chunks draw the blocks around it
            // with that light. Nothing else is dimmed, the ground under a puddle is not.
            if(!ChunkWater.isActive() && ChunkLight.dimOf(a) != ChunkLight.dimOf(b)) {
                for(int dy = 1; dy <= ChunkLight.reach(); dy++) {
                    int below = y - dy;
                    boolean wet = height(ox + x, oy + below, oz + z) > 0 || below >= 0 && old != null && old[i - dy * 256] != 0;
                    if(!wet) break;
                    SettledRedraws.add(ox + x, oy + below, oz + z);
                }
            }
        }
        if(meshChanged) WaterRenderer.markDirty(key, borders);
        // Drawn by the chunks, the changed blocks and those beside them are redrawn
        if(ChunkWater.isActive() && x1 >= 0) ChunkWater.redraw(ox + x0, oy + y0 - ChunkLight.reach(), oz + z0, ox + x1, oy + y1, oz + z1);
    }

    /** Whether a section is further from the camera than puddles are drawn in full */
    private static boolean farPuddles(long key) {
        Entity view = Minecraft.getMinecraft().getRenderViewEntity();
        if(view == null) return false;
        int distance = Math.max(Math.abs((MathHelper.floor(view.posX) >> 4) - Water.keyX(key)), Math.abs((MathHelper.floor(view.posZ) >> 4) - Water.keyZ(key)));
        return distance > ForgeConfigHandler.client.puddleDetailDistance;
    }

    /** Whether two sections of heights are the same in eighths of a block, as puddles are drawn simply */
    private static boolean sameEighths(byte[] a, byte[] b) {
        for(int i = 0; i < 4096; i++) {
            if(a[i] != b[i] && eighths(a[i] & 255) != eighths(b[i] & 255)) return false;
        }
        return true;
    }

    /** A height 0-255 in eighths of a block, at least one for any water */
    static int eighths(int height) {
        return height == 0 ? 0 : Math.max(1, Math.round(height * 8 / 255.0F));
    }

    /**
     * A trace of water on water that stays one, see FluidicWater.MIN_DRAWN. It is not drawn, and only the corners of drawn
     * simulated water beside it at its level average it in, with none there no mesh changes. Simulated water under it rises
     * with it until it is half as much as drawn water, see FluidicWater.tracedSurface.
     */
    private static boolean unseenTrace(Minecraft mc, BlockPos.MutableBlockPos pos, int x, int y, int z, int a, int b) {
        if(a == 0 || b == 0 || a >= FluidicWater.MIN_DRAWN || b >= FluidicWater.MIN_DRAWN) return false;
        if(Math.min(a, b) < FluidicWater.MIN_DRAWN / 2 && height(x, y - 1, z) > 0) return false;
        if(!FluidicWater.isTrace(mc.world, pos.setPos(x, y, z), b)) return false;
        for(int dx = -1; dx <= 1; dx++) {
            for(int dz = -1; dz <= 1; dz++) {
                if((dx != 0 || dz != 0) && height(x + dx, y, z + dz) >= FluidicWater.MIN_DRAWN) return false;
            }
        }
        return true;
    }

    /** Whether a water block is in the blocks around a position, the ones SettledRedraws redraws for it */
    private static boolean nearSettledWater(Minecraft mc, BlockPos.MutableBlockPos pos, int x, int y, int z) {
        for(int dy = -1; dy <= 1; dy++) {
            for(int dx = -1; dx <= 1; dx++) {
                for(int dz = -1; dz <= 1; dz++) {
                    if((dx != 0 || dy != 0 || dz != 0) && FluidicWater.isWater(mc.world.getBlockState(pos.setPos(x + dx, y + dy, z + dz)))) return true;
                }
            }
        }
        return false;
    }

    public static void unloadChunk(int cx, int cz) {
        ChunkRedraws.unloadChunk(cx, cz);
        for(int sy = 0; sy < 16; sy++) {
            long key = Water.sectionKey(cx, sy, cz);
            DEFERRED.remove(key);
            HELD.remove(key);
            HELD_UNTIL.remove(key);
            WaterRenderer.unloadSection(key);
            if(SECTIONS.remove(key) != null) WaterRenderer.markDirty(key, ALL_BORDERS);
        }
    }

    /** A block changed, simulated water beside it draws its faces and corners against it */
    public static void blockChanged(BlockPos pos) {
        ChunkRedraws.blockChanged(pos);
        // Water woken from its block is sent before the block goes, held back the block would leave a hole until it is shown
        long section = Water.sectionKey(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
        byte[] held = HELD.remove(section);
        if(held != null) {
            HELD_UNTIL.remove(section);
            apply(section, held == GONE ? null : held);
        }
        LongOpenHashSet dirty = null;
        for(int dx = -1; dx <= 1; dx++) {
            for(int dy = -1; dy <= 1; dy++) {
                for(int dz = -1; dz <= 1; dz++) {
                    int x = pos.getX() + dx, y = pos.getY() + dy, z = pos.getZ() + dz;
                    if(height(x, y, z) == 0) continue;
                    if(dirty == null) dirty = new LongOpenHashSet();
                    dirty.add(Water.sectionKey(x >> 4, y >> 4, z >> 4));
                }
            }
        }
        // Rebuilt right away, the block may already be gone from the chunk drawn next to it
        if(dirty != null) for(long key : dirty) WaterRenderer.markUrgent(key);
    }

    public static void clear() {
        SECTIONS.clear();
        DEFERRED.clear();
        HELD.clear();
        HELD_UNTIL.clear();
        ChunkRedraws.clear();
        dimension = Integer.MIN_VALUE;
        WaterRenderer.clear();
    }

    static byte[] section(long key) {
        return SECTIONS.get(key);
    }

    static Iterable<Long> keys() {
        return SECTIONS.keySet();
    }

    static boolean hasSection(long key) {
        return SECTIONS.containsKey(key);
    }

    /** Height 0-255 of the water at a block position as sent, not eased, see WaterMotion */
    static int sentHeight(int x, int y, int z) {
        return SECTIONS.heightOnWriter(x, y, z);
    }

    /** Height 0-255 of the water at a block position, client thread only */
    public static int height(int x, int y, int z) {
        // Against water blocks as their chunk was drawn, see ChunkRedraws.view
        ChunkRedraws.Snapshot drawn = ChunkRedraws.clientView();
        if(drawn != null) {
            int height = drawn.height(x, y, z);
            if(height >= 0) return height;
        }
        // A mesh being built from the eased heights, see WaterMotion
        if(WaterMotion.easing) return WaterMotion.height(x, y, z);
        return SECTIONS.heightOnWriter(x, y, z);
    }

    private static final class Deferred {
        final byte[] heights;
        final int since;

        Deferred(byte[] heights, int since) {
            this.heights = heights;
            this.since = since;
        }
    }

    /** Height 0-255 of the water at a block position, safe from any thread */
    public static int heightAnyThread(int x, int y, int z) {
        if(WaterMotion.onClientThread()) return height(x, y, z);
        // A chunk redraw reads the heights it began with throughout, see ChunkRedraws.beginRedraw
        int drawn = ChunkRedraws.redrawHeight(x, y, z);
        return drawn >= 0 ? drawn : SECTIONS.height(x, y, z);
    }

}
