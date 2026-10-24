package fluidic.client;

import fluidic.Fluidic;
import fluidic.handlers.ForgeConfigHandler;
import fluidic.block.FluidicWater;
import fluidic.sim.SectionHeights;
import fluidic.sim.Water;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.util.math.BlockPos;
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
        show(key, shown);
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
        if(old == null && heights == null) return;

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
            // The blocks below are lit through it, see ChunkLight. Drawn by the chunks, its redraw reaches them.
            if(!ChunkWater.isActive() && ChunkLight.dimOf(a) != ChunkLight.dimOf(b)) {
                for(int dy = 0; dy <= ChunkLight.reach(); dy++) SettledRedraws.add(ox + x, oy + y - dy, oz + z);
            }
        }
        if(meshChanged) WaterRenderer.markDirty(key, borders);
        // Drawn by the chunks, the changed blocks and those beside them are redrawn
        if(ChunkWater.isActive() && x1 >= 0) ChunkWater.redraw(ox + x0, oy + y0 - ChunkLight.reach(), oz + z0, ox + x1, oy + y1, oz + z1);
    }

    /**
     * A trace of water on water that stays one, see FluidicWater.MIN_DRAWN. It is not drawn, and only the corners of drawn
     * simulated water beside it at its level average it in, with none there no mesh changes.
     */
    private static boolean unseenTrace(Minecraft mc, BlockPos.MutableBlockPos pos, int x, int y, int z, int a, int b) {
        if(a == 0 || b == 0 || a >= FluidicWater.MIN_DRAWN || b >= FluidicWater.MIN_DRAWN) return false;
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
            WaterRenderer.unloadSection(key);
            if(SECTIONS.remove(key) != null) WaterRenderer.markDirty(key, ALL_BORDERS);
        }
    }

    /** A block changed, simulated water beside it draws its faces and corners against it */
    public static void blockChanged(BlockPos pos) {
        ChunkRedraws.blockChanged(pos);
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

    /** Height 0-255 of the water at a block position, client thread only */
    public static int height(int x, int y, int z) {
        return SECTIONS.height(x, y, z);
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
        return SECTIONS.height(x, y, z);
    }

}
