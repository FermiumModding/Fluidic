package fluidic.mixin.celeritas;

import fluidic.client.CeleritasRedraws;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;

/** Celeritas uploading finished section builds, they are drawn from this frame on, see CeleritasRedraws */
@Pseudo
@Mixin(targets = "org.embeddedt.embeddium.impl.render.chunk.RenderSectionManager", remap = false)
public abstract class RenderSectionManagerMixin {

    @Inject(method = "processChunkBuildResults(Ljava/util/ArrayList;)V", at = @At("RETURN"), require = 0)
    private void fluidic_uploaded(ArrayList<?> results, CallbackInfo ci) {
        CeleritasRedraws.uploaded(results);
    }
}
