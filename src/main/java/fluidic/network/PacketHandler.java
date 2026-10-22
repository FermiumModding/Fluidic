package fluidic.network;

import fluidic.Fluidic;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;

public class PacketHandler {

    public static final SimpleNetworkWrapper INSTANCE = NetworkRegistry.INSTANCE.newSimpleChannel(Fluidic.MODID);

    public static void init() {
        INSTANCE.registerMessage(MessageWaterSection.Handler.class, MessageWaterSection.class, 0, Side.CLIENT);
    }
}
