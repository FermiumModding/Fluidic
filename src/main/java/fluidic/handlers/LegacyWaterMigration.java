package fluidic.handlers;

import fluidic.Fluidic;
import fluidic.block.BlockLegacySettledWater;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Converts the settled water blocks of earlier Fluidic versions into Fluidic water. Chunks are scanned as they
 * load, and the blocks found are converted on the next tick of their world, outside of chunk loading.
 */
@Mod.EventBusSubscriber(modid = Fluidic.MODID)
public final class LegacyWaterMigration {

    private static final Map<World, LongArrayList> PENDING = new WeakHashMap<>();

    private LegacyWaterMigration() {}

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        World world = event.getWorld();
        if(world.isRemote) return;
        Chunk chunk = event.getChunk();
        LongArrayList found = null;
        for(ExtendedBlockStorage storage : chunk.getBlockStorageArray()) {
            if(storage == Chunk.NULL_BLOCK_STORAGE || storage.isEmpty()) continue;
            for(int y = 0; y < 16; y++) {
                for(int z = 0; z < 16; z++) {
                    for(int x = 0; x < 16; x++) {
                        if(storage.get(x, y, z).getBlock() != ModRegistry.LEGACY_SETTLED_WATER) continue;
                        if(found == null) found = PENDING.computeIfAbsent(world, w -> new LongArrayList());
                        found.add(new BlockPos(chunk.x * 16 + x, storage.getYLocation() + y, chunk.z * 16 + z).toLong());
                    }
                }
            }
        }
    }

    @SubscribeEvent
    public static void onWorldTick(TickEvent.WorldTickEvent event) {
        if(event.phase != TickEvent.Phase.START || event.world.isRemote) return;
        LongArrayList pending = PENDING.remove(event.world);
        if(pending == null) return;
        for(int n = 0; n < pending.size(); n++) {
            BlockPos pos = BlockPos.fromLong(pending.getLong(n));
            if(!event.world.isBlockLoaded(pos)) continue;
            IBlockState state = event.world.getBlockState(pos);
            BlockLegacySettledWater.convert(event.world, pos, state);
        }
    }

    @SubscribeEvent
    public static void onWorldUnload(WorldEvent.Unload event) {
        PENDING.remove(event.getWorld());
    }
}
