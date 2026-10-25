package fluidic;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.Instance;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import fluidic.command.CommandFluidic;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import fluidic.handlers.ModRegistry;
import fluidic.network.PacketHandler;
import fluidic.proxy.CommonProxy;

@Mod(modid = Fluidic.MODID, version = Fluidic.VERSION, name = Fluidic.NAME)
public class Fluidic {
    public static final String MODID = "fluidic";
    public static final String VERSION = "0.2.0";
    public static final String NAME = "Fluidic";
    public static final Logger LOGGER = LogManager.getLogger(NAME);
    public static boolean completedLoading = false;

    @SidedProxy(clientSide = "fluidic.proxy.ClientProxy", serverSide = "fluidic.proxy.CommonProxy")
    public static CommonProxy PROXY;

	@Instance(MODID)
	public static Fluidic instance;

	@Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        PacketHandler.init();
        Fluidic.PROXY.preInit();
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        completedLoading = true;
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new CommandFluidic());
    }
}
