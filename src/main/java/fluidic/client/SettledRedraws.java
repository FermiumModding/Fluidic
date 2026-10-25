package fluidic.client;

import fluidic.Fluidic;
import fluidic.sim.Water;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;

/**
 * Settled water blends into simulated water next to it, so it is redrawn when simulated water appears or
 * vanishes beside it. Redrawing rebuilds whole chunk sections, which is expensive, so changes are merged into
 * one area per section and a limited number of sections is redrawn each tick.
 */
@Mod.EventBusSubscriber(modid = Fluidic.MODID, value = Side.CLIENT)
public final class SettledRedraws {

    private static final int SECTIONS_PER_TICK = 8;

    /** Section key to the area of changed blocks in it: min x, y, z, max x, y, z */
    private static final Long2ObjectLinkedOpenHashMap<int[]> PENDING = new Long2ObjectLinkedOpenHashMap<>();

    private SettledRedraws() {}

    static void add(int x, int y, int z) {
        // Until they are redrawn, simulated water resting on them is drawn against them as they were
        for(int dx = -1; dx <= 1; dx += 2) {
            for(int dy = -1; dy <= 1; dy += 2) {
                for(int dz = -1; dz <= 1; dz += 2) {
                    int sy = (y + dy) >> 4;
                    if(sy >= 0 && sy <= 15) ChunkRedraws.simulatedChanged(Water.sectionKey((x + dx) >> 4, sy, (z + dz) >> 4));
                }
            }
        }
        long key = Water.sectionKey(x >> 4, y >> 4, z >> 4);
        int[] area = PENDING.get(key);
        if(area == null) {
            PENDING.put(key, new int[] {x, y, z, x, y, z});
            return;
        }
        area[0] = Math.min(area[0], x);
        area[1] = Math.min(area[1], y);
        area[2] = Math.min(area[2], z);
        area[3] = Math.max(area[3], x);
        area[4] = Math.max(area[4], y);
        area[5] = Math.max(area[5], z);
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if(event.phase != TickEvent.Phase.END || PENDING.isEmpty()) return;
        WorldClient world = Minecraft.getMinecraft().world;
        if(world == null) {
            PENDING.clear();
            return;
        }
        ChunkRedraws.setWaterRedraw(true);
        try {
            for(int n = 0; n < SECTIONS_PER_TICK && !PENDING.isEmpty(); n++) {
                int[] area = PENDING.removeFirst();
                // The settled blocks beside the changed ones are what needs redrawing
                world.markBlockRangeForRenderUpdate(area[0] - 1, area[1] - 1, area[2] - 1, area[3] + 1, area[4] + 1, area[5] + 1);
            }
        }
        finally {
            ChunkRedraws.setWaterRedraw(false);
        }
    }

    @SubscribeEvent
    public static void onWorldUnload(WorldEvent.Unload event) {
        if(event.getWorld().isRemote) PENDING.clear();
    }
}
