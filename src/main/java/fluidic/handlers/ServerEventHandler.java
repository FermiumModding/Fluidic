package fluidic.handlers;

import fluidic.Fluidic;
import fluidic.block.FluidicWater;
import fluidic.network.MessageVanillaDimensions;
import fluidic.network.PacketHandler;
import fluidic.sim.BiomeList;
import fluidic.sim.SimManager;
import fluidic.sim.Terrain;
import fluidic.sim.Water;
import fluidic.sim.WaterSimulation;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.event.world.ChunkWatchEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.Event;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.Iterator;
import java.util.Random;
import java.util.Set;

@Mod.EventBusSubscriber(modid = Fluidic.MODID)
public class ServerEventHandler {

    /** The configured rain biomes, parsed on first use */
    private static volatile Set<Biome> rainBiomes;

    /** The config changed: rain biomes are parsed again */
    static void reload() {
        rainBiomes = null;
    }

    @SubscribeEvent
    public static void onWorldLoad(WorldEvent.Load event) {
        // Dimensions keeping vanilla water simulate nothing
        if(event.getWorld() instanceof WorldServer && ForgeConfigHandler.server.enableSimulation && FluidicWater.isFinite(event.getWorld())) {
            SimManager.load((WorldServer)event.getWorld());
        }
    }

    /** The player's client learns where water stays vanilla, it draws and handles water there the vanilla way */
    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if(event.player instanceof EntityPlayerMP) {
            PacketHandler.INSTANCE.sendTo(new MessageVanillaDimensions(ForgeConfigHandler.server.vanillaWaterDimensions.clone()), (EntityPlayerMP)event.player);
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
        // No chunk is being populated between ticks, this only matters if populating one ever threw
        FluidicWater.generating = 0;
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
        Set<Biome> biomes = rainBiomes;
        if(biomes == null) rainBiomes = biomes = BiomeList.parse("Rain Biomes", ForgeConfigHandler.server.rainBiomes);
        if(!biomes.contains(world.getBiome(pos))) return;
        // Rain drips through leaves and plants down to the ground
        while(pos.getY() > 0) {
            IBlockState below = world.getBlockState(pos.down());
            if(!Terrain.letsRainThrough(below)) break;
            pos = pos.down();
        }
        if(pos.getY() <= 0) return;
        BlockPos below = pos.down();
        IBlockState belowState = world.getBlockState(below);
        // Oceans and rivers swallow rain
        if(Terrain.isSink(belowState) || FluidicWater.isReservoir(world, below, belowState)) return;
        if(FluidicWater.isWater(belowState)) {
            // Rain on a pool raises it in place. Waking the block into the simulation for every drop would change
            // it twice, redrawing the chunk each time. Each level goes to the lowest of the block and its neighbours,
            // raising one block by several levels would leave a step that wakes the pool on its next random tick.
            BlockPos[] targets = new BlockPos[5];
            int[] levels = new int[5];
            int[] raised = new int[5];
            int count = 0;
            for(int k = 0; k < 5; k++) {
                BlockPos target = k == 0 ? below : below.offset(EnumFacing.HORIZONTALS[k - 1]);
                // Reading an unloaded block would load its chunk
                if(!world.isBlockLoaded(target)) continue;
                IBlockState state = k == 0 ? belowState : world.getBlockState(target);
                if(!canRaise(world, simulation, target, state, k == 0)) continue;
                targets[count] = target;
                levels[count] = raised[count] = FluidicWater.level(state);
                count++;
            }
            while(amount >= Water.UNIT && count > 0) {
                int lowest = 0;
                for(int k = 1; k < count; k++) if(raised[k] < raised[lowest]) lowest = k;
                if(raised[lowest] >= Water.LEVELS) break;
                raised[lowest]++;
                amount -= Water.UNIT;
            }
            for(int k = 0; k < count; k++) {
                if(raised[k] == levels[k]) continue;
                IBlockState state = FluidicWater.state(raised[k]);
                FluidicWater.placeQuietly(world, targets[k], state, 3);
                simulation.setTerrain(targets[k], Terrain.classify(state));
            }
            if(amount <= 0) return;
        }
        simulation.addRain(pos, amount);
    }

    /** Surface water that rain can raise in place, not an ocean. The block the rain hit may have a plant above it. */
    private static boolean canRaise(World world, WaterSimulation simulation, BlockPos pos, IBlockState state, boolean hit) {
        if(!FluidicWater.isWater(state) || FluidicWater.isReservoir(world, pos, state) || simulation.isRemoving(pos)) return false;
        IBlockState above = world.getBlockState(pos.up());
        return hit && !above.getMaterial().isLiquid() || above.getMaterial() == Material.AIR;
    }
}
