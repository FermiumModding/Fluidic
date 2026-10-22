package fluidic.handlers;

import fluidic.Fluidic;
import fluidic.sim.SimManager;
import fluidic.sim.Terrain;
import fluidic.sim.Water;
import fluidic.sim.WaterSimulation;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.init.SoundEvents;
import net.minecraft.item.ItemStack;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.event.entity.player.FillBucketEvent;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.event.world.ChunkWatchEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fluids.BlockFluidBase;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.Event;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.Iterator;
import java.util.Random;

@Mod.EventBusSubscriber(modid = Fluidic.MODID)
public class ServerEventHandler {

    @SubscribeEvent
    public static void onWorldLoad(WorldEvent.Load event) {
        if(event.getWorld() instanceof WorldServer && ForgeConfigHandler.server.enableSimulation) {
            SimManager.load((WorldServer)event.getWorld());
        }
    }

    @SubscribeEvent
    public static void onWorldUnload(WorldEvent.Unload event) {
        if(event.getWorld() instanceof WorldServer) SimManager.unload((WorldServer)event.getWorld());
    }

    @SubscribeEvent
    public static void onWorldSave(WorldEvent.Save event) {
        WaterSimulation simulation = SimManager.get(event.getWorld());
        if(simulation != null) simulation.save();
    }

    @SubscribeEvent
    public static void onWorldTick(TickEvent.WorldTickEvent event) {
        if(event.phase != TickEvent.Phase.END || !(event.world instanceof WorldServer)) return;
        WaterSimulation simulation = SimManager.get(event.world);
        if(simulation == null) return;
        rain((WorldServer)event.world, simulation);
        simulation.tick();
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        WaterSimulation simulation = SimManager.get(event.getWorld());
        if(simulation != null) simulation.chunkLoaded(event.getChunk().x, event.getChunk().z);
    }

    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        WaterSimulation simulation = SimManager.get(event.getWorld());
        if(simulation != null) simulation.chunkUnloaded(event.getChunk().x, event.getChunk().z);
    }

    @SubscribeEvent
    public static void onChunkWatch(ChunkWatchEvent.Watch event) {
        WaterSimulation simulation = SimManager.get(event.getPlayer().world);
        if(simulation != null) simulation.sendChunk(event.getPlayer().getUniqueID(), event.getChunk().x, event.getChunk().z);
    }

    /**
     * Empty buckets scoop up a full block of settled water into a Fluidic bucket, so the water stays finite.
     * Vanilla would take a full bucket out of any level, partly filled blocks cannot be scooped.
     */
    @SubscribeEvent
    public static void onFillBucket(FillBucketEvent event) {
        RayTraceResult target = event.getTarget();
        if(target == null || target.typeOfHit != RayTraceResult.Type.BLOCK || event.getEmptyBucket().getItem() != Items.BUCKET) return;
        World world = event.getWorld();
        BlockPos pos = target.getBlockPos();
        IBlockState state = world.getBlockState(pos);
        if(state.getBlock() != ModRegistry.SETTLED_WATER) return;
        if(state.getValue(BlockFluidBase.LEVEL) + 1 < Water.LEVELS) {
            event.setCanceled(true);
            return;
        }
        // Neighbours are updated so the surrounding water flows into the hole
        world.setBlockState(pos, Blocks.AIR.getDefaultState(), 11);
        WaterSimulation simulation = SimManager.get(world);
        if(simulation != null) simulation.setTerrain(pos, Terrain.classify(Blocks.AIR.getDefaultState()));
        world.playSound(event.getEntityPlayer(), pos, SoundEvents.ITEM_BUCKET_FILL, SoundCategory.BLOCKS, 1.0F, 1.0F);
        event.setFilledBucket(new ItemStack(ModRegistry.FLUIDIC_BUCKET));
        event.setResult(Event.Result.ALLOW);
    }

    /** Drops rain into random columns of the chunks around players, the same way vanilla places snow */
    private static void rain(WorldServer world, WaterSimulation simulation) {
        ForgeConfigHandler.ServerConfig config = ForgeConfigHandler.server;
        if(!config.enableRain || !world.isRaining()) return;
        Random rand = world.rand;
        int amount = config.rainAmount * Water.UNIT;
        double chance = config.rainChance * (world.isThundering() ? config.thunderMultiplier : 1.0D);
        for(Iterator<Chunk> chunks = world.getPersistentChunkIterable(world.getPlayerChunkMap().getChunkIterator()); chunks.hasNext(); ) {
            Chunk chunk = chunks.next();
            // Chances above 1 drop several times per tick
            int drops = (int)chance + (rand.nextDouble() < chance % 1.0D ? 1 : 0);
            for(int n = 0; n < drops; n++) drop(world, simulation, chunk, rand, amount);
        }
    }

    private static void drop(WorldServer world, WaterSimulation simulation, Chunk chunk, Random rand, int amount) {
        BlockPos pos = world.getPrecipitationHeight(new BlockPos(chunk.x * 16 + rand.nextInt(16), 0, chunk.z * 16 + rand.nextInt(16)));
        if(!world.isRainingAt(pos)) return;
        // Rain drips through leaves and plants down to the ground
        while(pos.getY() > 0) {
            IBlockState below = world.getBlockState(pos.down());
            if(below.getMaterial() != Material.LEAVES && !Terrain.isOpen(below)) break;
            pos = pos.down();
        }
        if(pos.getY() <= 0) return;
        BlockPos below = pos.down();
        IBlockState belowState = world.getBlockState(below);
        if(Terrain.isSink(belowState)) return;
        if(belowState.getBlock() == ModRegistry.SETTLED_WATER) {
            // Rain on a settled pool raises it in place. Waking the block into the simulation for every drop
            // would change it twice, redrawing the chunk each time. Uneven pools level out on random ticks.
            int level = belowState.getValue(BlockFluidBase.LEVEL) + 1;
            int levels = amount / Water.UNIT;
            int raised = Math.min(Water.LEVELS, level + levels);
            if(raised > level) {
                IBlockState state = belowState.withProperty(BlockFluidBase.LEVEL, raised - 1);
                world.setBlockState(below, state, 2);
                simulation.setTerrain(below, Terrain.classify(state));
            }
            amount -= (raised - level) * Water.UNIT;
            if(amount <= 0) return;
        }
        simulation.addWater(pos, amount);
    }
}
