package fluidic.handlers;

import fermiumbooter.annotations.MixinConfig;
import net.minecraftforge.common.config.Config;
import net.minecraftforge.common.config.ConfigManager;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import fluidic.Fluidic;
import fluidic.sim.Reservoirs;
import fluidic.sim.Terrain;

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

		@Config.Comment({
				"Entities swim in, splash into, are slowed by and drown in water that is still flowing, not only settled",
				"water, boats float on it, mobs path around it and fish bite in it, and it hydrates farmland, hardens",
				"concrete powder and waters sugar cane. Turning it off only leaves these out, the rest of Fluidic's",
				"water handling stays. Needs FermiumBooter."})
		@Config.Name("Enable Flowing Water Entity Physics (Vanilla)")
		@MixinConfig.MixinToggle(earlyMixin = "mixins.fluidic.entity.json", defaultValue = true)
		public boolean enableFlowingWaterPhysics = true;

		@Config.Comment("Runs the water simulation. Takes effect when a world loads.")
		@Config.Name("Enable Simulation")
		@Config.RequiresWorldRestart
		public boolean enableSimulation = true;

		@Config.Comment({
				"Dimension ids that keep vanilla water: it flows and spreads endlessly from sources as in vanilla, nothing",
				"is simulated, and the rest of Fluidic's water handling, like partly filled buckets and lily pads",
				"following the water, is left out there too. Fountains place a water source while powered and drains",
				"delete water touching them."})
		@Config.Name("Vanilla Water Dimensions")
		@Config.RequiresWorldRestart
		public int[] vanillaWaterDimensions = {};

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

		@Config.Comment({
				"Chunks from a player water updates are sent to them at full rate. Further out they are sent less often, up to",
				"half a second apart at the edge of view, which saves the network and the client redrawing them."})
		@Config.Name("Full Rate Update Distance")
		@Config.RangeInt(min = 0, max = 32)
		public int fullRateDistance = 4;

		@Config.Comment("Rain adds water to the simulation.")
		@Config.Name("Enable Rain Accumulation")
		public boolean enableRain = true;

		@Config.Comment({
				"Biomes where rain adds water, on top of the biome raining at all. Some modded deserts report rain without",
				"showing any, water would collect there. Entries are biome ids, like minecraft:plains, biome dictionary tags,",
				"like FOREST or SANDY, or * for every biome. An entry starting with ! leaves its biomes out, like !SNOWY."})
		@Config.Name("Rain Biomes")
		public String[] rainBiomes = {"*", "!SANDY", "!SNOWY"};

		@Config.Comment("Chance per player loaded chunk per tick that a raindrop lands in it while it rains.")
		@Config.Name("Rain Chance")
		@Config.RangeDouble(min = 0.0D, max = 1.0D)
		public double rainChance = 0.015D;

		@Config.Comment("Multiplies the rain chance during thunderstorms.")
		@Config.Name("Thunderstorm Rain Multiplier")
		@Config.RangeDouble(min = 0.0D, max = 16.0D)
		public double thunderMultiplier = 2.5D;

		@Config.Comment("Eighths of a block of water each raindrop adds, the smallest amount water blocks can hold.")
		@Config.Name("Rain Amount")
		@Config.RangeInt(min = 1, max = 8)
		public int rainAmount = 1;

		@Config.Comment("Chance per simulation step that leaves holding water absorb up to a sixteenth of a block of it instead of letting it drip through.")
		@Config.Name("Leaf Absorption Chance")
		@Config.RangeDouble(min = 0.0D, max = 1.0D)
		public double leafAbsorption = 0.5D;

		@Config.Comment({
				"How water treats blocks, as modid:block=class or modid:block:meta=class. Blocks not listed are classified",
				"from their material: plants and fixtures like torches are washable, other blocks you can walk through are",
				"permeable, leaves are porous, and solid blocks are rough, smooth or solid by material.",
				"Classes:",
				"  open      - like air, water settles there",
				"  washable  - water flows through, the block breaks under more than half a block of water (flowers)",
				"  permeable - water flows through at any depth without breaking the block (ladders, signs)",
				"  waterloggable - water flows in and out sideways and stays in the block, which is sealed top and bottom, so a",
				"              roof of them does not leak (stairs, slabs, trapdoors, chests, like waterlogged blocks of later versions)",
				"  porous    - water drips down through, and fills it where it cannot drain, like leaves under a pool (leaves)",
				"  solid     - blocks water",
				"  rough     - blocks water, water runs slowly over it (grass, dirt)",
				"  smooth    - blocks water, water runs quickly over it (stone, glass)",
				"  reactive  - blocks water, the block is updated when water touches it (concrete powder, lava)",
				"  sink      - water flowing in disappears (water of other mods)"})
		@Config.Name("Block Classifications")
		public String[] blockClassifications = {
				"minecraft:ladder=permeable",
				"minecraft:standing_sign=permeable",
				"minecraft:wall_sign=permeable",
				"minecraft:reeds=permeable",
				"minecraft:standing_banner=permeable",
				"minecraft:wall_banner=permeable",
				"minecraft:iron_bars=permeable",
				"minecraft:glass_pane=permeable",
				"minecraft:stained_glass_pane=permeable",
				"minecraft:fence=permeable",
				"minecraft:spruce_fence=permeable",
				"minecraft:birch_fence=permeable",
				"minecraft:jungle_fence=permeable",
				"minecraft:dark_oak_fence=permeable",
				"minecraft:acacia_fence=permeable",
				"minecraft:nether_brick_fence=permeable",
				"minecraft:cobblestone_wall=permeable",
				"minecraft:rail=permeable",
				"minecraft:golden_rail=permeable",
				"minecraft:detector_rail=permeable",
				"minecraft:activator_rail=permeable",
				"minecraft:oak_stairs=waterloggable",
				"minecraft:spruce_stairs=waterloggable",
				"minecraft:birch_stairs=waterloggable",
				"minecraft:jungle_stairs=waterloggable",
				"minecraft:acacia_stairs=waterloggable",
				"minecraft:dark_oak_stairs=waterloggable",
				"minecraft:stone_stairs=waterloggable",
				"minecraft:brick_stairs=waterloggable",
				"minecraft:stone_brick_stairs=waterloggable",
				"minecraft:nether_brick_stairs=waterloggable",
				"minecraft:sandstone_stairs=waterloggable",
				"minecraft:red_sandstone_stairs=waterloggable",
				"minecraft:quartz_stairs=waterloggable",
				"minecraft:purpur_stairs=waterloggable",
				"minecraft:stone_slab=waterloggable",
				"minecraft:stone_slab2=waterloggable",
				"minecraft:wooden_slab=waterloggable",
				"minecraft:purpur_slab=waterloggable",
				"minecraft:trapdoor=waterloggable",
				"minecraft:iron_trapdoor=waterloggable",
				"minecraft:chest=waterloggable",
				"minecraft:trapped_chest=waterloggable",
				"minecraft:ender_chest=waterloggable",
				"minecraft:web=washable",
				"minecraft:leaves=porous",
				"minecraft:leaves2=porous",
				"minecraft:concrete_powder=reactive",
				"minecraft:grass=rough",
				"minecraft:dirt=rough",
				"minecraft:grass_path=rough",
				"minecraft:farmland=rough",
				"minecraft:mycelium=rough",
				"minecraft:sand=rough",
				"minecraft:gravel=rough",
				"minecraft:clay=rough",
				"minecraft:soul_sand=rough",
				"minecraft:snow=rough",
				"minecraft:stone=smooth",
				"minecraft:stonebrick=smooth",
				"minecraft:quartz_block=smooth",
				"minecraft:prismarine=smooth",
				"minecraft:obsidian=smooth",
				"minecraft:concrete=smooth",
				"minecraft:glass=smooth",
				"minecraft:stained_glass=smooth",
				"minecraft:ice=smooth",
				"minecraft:packed_ice=smooth",
				"minecraft:iron_block=smooth",
				"minecraft:gold_block=smooth",
				"minecraft:diamond_block=smooth",
				"minecraft:emerald_block=smooth"
		};

		@Config.Comment({
				"Biomes whose large bodies of water are endless, like oceans and rivers. A body of water below sea level",
				"is endless when its surface at sea level spans at least 128 blocks and reaches into one of these biomes,",
				"small ponds stay ordinary water. Entries are biome ids, like minecraft:swampland, or biome dictionary",
				"tags, like OCEAN, RIVER, BEACH, SWAMP or WET."})
		@Config.Name("Reservoir Biomes")
		public String[] reservoirBiomes = {"OCEAN", "RIVER", "BEACH", "SWAMP"};

		@Config.Comment({
				"Finds endless water by the body of water it belongs to: a sea or river counts past the edge of its biome,",
				"while ponds and flooded caves below sea level inside those biomes do not. Turned off, every full water",
				"block below sea level inside a reservoir biome is endless and none outside them are, which is cheaper",
				"but less realistic."})
		@Config.Name("Search Connected Water Bodies")
		public boolean searchWaterBodies = true;

		@Config.Comment("Chance per random tick that the surface of still water loses a sixteenth of a block to evaporation. Nothing evaporates while it rains or thunders, or below sea level.")
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

		@Config.Comment("Blocks of water a sponge soaks up before it turns into a wet sponge. Sponges soak up water touching them, partly filled blocks and flowing water included, and pools drain into them.")
		@Config.Name("Sponge Capacity")
		@Config.RangeDouble(min = 0.125D, max = 1024.0D)
		public double spongeCapacity = 16.0D;

		@Config.Comment("Blocks of water a powered fountain pours out per second.")
		@Config.Name("Fountain Output")
		@Config.RangeDouble(min = 0.05D, max = 64.0D)
		public double fountainOutput = 1.0D;
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

		@Config.Comment({
				"Draws simulated water as part of the chunks it is in, as water blocks, instead of separately. Shader packs",
				"only shade what the chunks draw, so this is needed for them, but water moving makes its chunks redraw,",
				"which costs more in busy weather.",
				"  AUTO   - while an OptiFine shader pack is in use",
				"  ALWAYS - always",
				"  NEVER  - never"})
		@Config.Name("Draw Simulated Water In Chunks")
		public ChunkWaterMode chunkWater = ChunkWaterMode.AUTO;
	}

	public enum ChunkWaterMode {
		AUTO,
		ALWAYS,
		NEVER
	}

	@Mod.EventBusSubscriber
	private static class EventHandler{
		@SubscribeEvent
		public static void onConfigChanged(ConfigChangedEvent.OnConfigChangedEvent event) {
			if(event.getModID().equals(Fluidic.MODID)) {
				ConfigManager.sync(Fluidic.MODID, Config.Type.INSTANCE);
				// Classifications are cached, running simulations pick up the change with their terrain refreshes
				Terrain.reload();
				Reservoirs.reload();
				ServerEventHandler.reload();
			}
		}
	}
}
