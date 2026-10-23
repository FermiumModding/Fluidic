package fluidic.mixin.vanilla;

import net.minecraft.client.renderer.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Sets up the world's fog again for simulated water, vanilla turns it off before the last render event, see {@link fluidic.client.WaterRenderer} */
@Mixin(EntityRenderer.class)
public interface EntityRendererAccessor {

    @Invoker("setupFog")
    void fluidic_setupFog(int startCoords, float partialTicks);
}
