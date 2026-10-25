package fluidic.mixin.vanilla;

import net.minecraft.client.renderer.chunk.RenderChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A chunk section vanilla found in view this frame, see RenderGlobalMixin */
@Mixin(targets = "net.minecraft.client.renderer.RenderGlobal$ContainerLocalRenderInformation")
public interface RenderInfoAccessor {

    @Accessor("renderChunk")
    RenderChunk fluidic_getRenderChunk();
}
