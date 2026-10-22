package fluidic.handlers;

import fermiumbooter.annotations.MixinConfig;
import net.minecraftforge.common.config.Config;
import net.minecraftforge.common.config.ConfigManager;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import fluidic.Fluidic;

@Config(modid = Fluidic.MODID)
public class ForgeConfigHandler {

	@Config.Comment("Server-Side Options")
	@Config.Name("Server Options")
	public static ServerConfig server = new ServerConfig();

	@Config.Comment("Client-Side Options")
	@Config.Name("Client Options")
	public static ClientConfig client = new ClientConfig();

	@MixinConfig(name = Fluidic.MODID) //Needed on config classes that contain MixinToggles for those mixins to be added
	public static class ServerConfig {

		@Config.Comment("Entities swim in, splash into, are slowed by and drown in water that is still flowing, not only settled water, and it hydrates farmland. Needs FermiumBooter.")
		@Config.Name("Enable Flowing Water Entity Physics (Vanilla)")
		@MixinConfig.MixinToggle(earlyMixin = "mixins.fluidic.vanilla.json", defaultValue = true)
		public boolean enableFlowingWaterPhysics = true;

		@Config.Comment("Runs the water simulation. Takes effect when a world loads.")
		@Config.Name("Enable Simulation")
		@Config.RequiresWorldRestart
		public boolean enableSimulation = true;

		@Config.Comment("Threads used to step large simulations in parallel, 0 uses half the available processors.")
		@Config.Name("Worker Threads")
		@Config.RangeInt(min = 0, max = 64)
		@Config.RequiresMcRestart
		public int workerThreads = 0;

		@Config.Comment("Simulation steps water has to stay still before it settles into real blocks.")
		@Config.Name("Settle Steps")
		@Config.RangeInt(min = 1, max = 1200)
		public int settleSteps = 40;

		@Config.Comment("Simulation steps between water updates sent to clients.")
		@Config.Name("Network Interval")
		@Config.RangeInt(min = 1, max = 20)
		public int networkInterval = 2;

		@Config.Comment("Rain adds water to the simulation.")
		@Config.Name("Enable Rain Accumulation")
		public boolean enableRain = true;

		@Config.Comment("Chance per player loaded chunk per tick that a raindrop lands in it while it rains.")
		@Config.Name("Rain Chance")
		@Config.RangeDouble(min = 0.0D, max = 1.0D)
		public double rainChance = 0.03D;

		@Config.Comment("Multiplies the rain chance during thunderstorms.")
		@Config.Name("Thunderstorm Rain Multiplier")
		@Config.RangeDouble(min = 0.0D, max = 16.0D)
		public double thunderMultiplier = 2.5D;

		@Config.Comment("Sixteenths of a block of water each raindrop adds.")
		@Config.Name("Rain Amount")
		@Config.RangeInt(min = 1, max = 16)
		public int rainAmount = 1;

		@Config.Comment("Chance per simulation step that leaves holding water absorb up to a sixteenth of a block of it instead of letting it drip through.")
		@Config.Name("Leaf Absorption Chance")
		@Config.RangeDouble(min = 0.0D, max = 1.0D)
		public double leafAbsorption = 0.5D;

		@Config.Comment("Chance per random tick that the surface of still water loses a sixteenth of a block to evaporation.")
		@Config.Name("Evaporation Chance")
		@Config.RangeDouble(min = 0.0D, max = 1.0D)
		public double evaporationChance = 0.1D;

		@Config.Comment("Multiplies the evaporation chance for water in direct sun: daytime, not raining and open to the sky.")
		@Config.Name("Sun Evaporation Multiplier")
		@Config.RangeDouble(min = 0.0D, max = 100.0D)
		public double sunEvaporationMultiplier = 10.0D;

		@Config.Comment("Chance per random tick that still water resting on soil (dirt, grass, sand, clay) soaks a sixteenth of a block into the ground.")
		@Config.Name("Ground Absorption Chance")
		@Config.RangeDouble(min = 0.0D, max = 1.0D)
		public double groundAbsorptionChance = 0.1D;
	}

	public static class ClientConfig {

		@Config.Comment("Renders water that is still being simulated.")
		@Config.Name("Render Simulated Water")
		public boolean renderWater = true;

		@Config.Comment("Distance in chunks simulated water is rendered at, capped by the render distance.")
		@Config.Name("Render Distance")
		@Config.RangeInt(min = 1, max = 32)
		public int renderDistance = 12;

		@Config.Comment("Water section meshes rebuilt per frame, lower is smoother but lags behind fast flows.")
		@Config.Name("Mesh Rebuilds Per Frame")
		@Config.RangeInt(min = 1, max = 256)
		public int meshRebuildsPerFrame = 24;
	}

	@Mod.EventBusSubscriber
	private static class EventHandler{
		@SubscribeEvent
		public static void onConfigChanged(ConfigChangedEvent.OnConfigChangedEvent event) {
			if(event.getModID().equals(Fluidic.MODID)) {
				ConfigManager.sync(Fluidic.MODID, Config.Type.INSTANCE);
			}
		}
	}
}
