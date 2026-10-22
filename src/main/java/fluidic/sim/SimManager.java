package fluidic.sim;

import fluidic.handlers.ForgeConfigHandler;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;

/**
 * Owns the simulation of every loaded dimension and the worker pool they share for parallel steps.
 */
public final class SimManager {

    private static final Int2ObjectOpenHashMap<WaterSimulation> SIMULATIONS = new Int2ObjectOpenHashMap<>();
    private static volatile ForkJoinPool pool;

    private SimManager() {}

    static ForkJoinPool pool() {
        ForkJoinPool current = pool;
        if(current == null) {
            synchronized(SimManager.class) {
                current = pool;
                if(current == null) {
                    int threads = ForgeConfigHandler.server.workerThreads;
                    if(threads <= 0) threads = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
                    pool = current = new ForkJoinPool(threads, p -> {
                        ForkJoinWorkerThread thread = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(p);
                        thread.setName("Fluidic Worker " + thread.getPoolIndex());
                        thread.setDaemon(true);
                        return thread;
                    }, null, false);
                }
            }
        }
        return current;
    }

    /** The simulation of a server world, null on clients or when the simulation is disabled */
    public static WaterSimulation get(World world) {
        if(world.isRemote) return null;
        return SIMULATIONS.get(world.provider.getDimension());
    }

    public static WaterSimulation load(WorldServer world) {
        WaterSimulation simulation = new WaterSimulation(world);
        WaterSimulation old = SIMULATIONS.put(world.provider.getDimension(), simulation);
        if(old != null) old.shutdown();
        world.addEventListener(new WaterWorldListener(simulation));
        return simulation;
    }

    public static void unload(WorldServer world) {
        WaterSimulation simulation = SIMULATIONS.remove(world.provider.getDimension());
        if(simulation != null) simulation.shutdown();
    }
}
