package fluidic.proxy;

import fluidic.client.ClientWaterCache;
import fluidic.sim.SimulatedWater;
import net.minecraft.client.Minecraft;

public class ClientProxy extends CommonProxy {

    @Override
    public void preInit() {
        SimulatedWater.client = ClientWaterCache::heightAnyThread;
    }

    @Override
    public void handleWaterSection(int dimension, long key, byte[] heights) {
        Minecraft.getMinecraft().addScheduledTask(() -> ClientWaterCache.update(dimension, key, heights));
    }
}
