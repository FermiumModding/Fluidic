package fluidic;

import java.util.Map;
import net.minecraftforge.fml.common.Loader;

import fermiumbooter.FermiumRegistryAPI;
import net.minecraftforge.fml.relauncher.CoreModManager;
import org.apache.commons.lang3.StringUtils;
import org.spongepowered.asm.launch.MixinBootstrap;
import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;
import org.spongepowered.asm.mixin.MixinEnvironment;

// This is a coremod (=loaded really early during startup, before minecraft itself)
// you only need this if you want to do more complex things than what you do with @MixinToggle in configs
@IFMLLoadingPlugin.MCVersion("1.12.2")
public class FluidicPlugin implements IFMLLoadingPlugin {

	public FluidicPlugin() {
		// The water handling Fluidic needs to work at all: rendering, buckets, lily pads, sponges, fluid handlers and
		// world generation. Always on. The entity physics of simulated water are a separate config, switched by
		// @MixinConfig.MixinToggle in ForgeConfigHandler. False for Vanilla/Coremod mixins.
		FermiumRegistryAPI.enqueueMixin(false, "mixins.fluidic.vanilla.json");
		// Celeritas and its forks like Voxtavium draw chunks themselves, simulated water hooks into that too
		FermiumRegistryAPI.enqueueMixin(true, "mixins.fluidic.celeritas.json", () -> Loader.isModLoaded("celeritas"));
	}

	@Override
	public String[] getASMTransformerClass()
	{
		return new String[0];
	}
	
	@Override
	public String getModContainerClass()
	{
		return null;
	}
	
	@Override
	public String getSetupClass()
	{
		return null;
	}
	
	@Override
	public void injectData(Map<String, Object> data) {
		if (Boolean.FALSE.equals(data.get("runtimeDeobfuscationEnabled"))) {
			MixinEnvironment.getDefaultEnvironment().setObfuscationContext("searge");
			CoreModManager.getReparseableCoremods().removeIf(s -> StringUtils.containsIgnoreCase(s, "fermiumbooter"));
		}
	}
	
	@Override
	public String getAccessTransformerClass()
	{
		return null;
	}
}