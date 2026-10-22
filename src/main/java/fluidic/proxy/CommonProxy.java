package fluidic.proxy;

public class CommonProxy {

    public void preInit() {
    }

    /** Called on the network thread with a section's water heights, null when it has no water */
    public void handleWaterSection(int dimension, long key, byte[] heights) {
    }
}
