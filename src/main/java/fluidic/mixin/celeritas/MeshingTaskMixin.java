package fluidic.mixin.celeritas;

import fluidic.client.CeleritasRedraws;
import fluidic.client.ChunkLight;
import fluidic.client.ChunkRedraws;
import fluidic.client.ChunkWater;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Celeritas' section build, in place of vanilla's RenderChunk.rebuildChunk: simulated water is drawn by the chunks
 * while it runs, see ChunkWater, and the build is followed until it is on screen, see CeleritasRedraws.
 */
@Pseudo
@Mixin(targets = "org.taumc.celeritas.impl.render.terrain.compile.task.ChunkBuilderMeshingTask", remap = false)
public abstract class MeshingTaskMixin {

    @Unique
    private long fluidic_redrawStarted;

    // Queued on the client thread with the blocks as they are now
    @Inject(method = "<init>", at = @At("RETURN"), require = 0)
    private void fluidic_queued(CallbackInfo ci) {
        fluidic_redrawStarted = ChunkRedraws.next();
    }

    @Inject(method = "execute", at = @At("HEAD"), require = 0)
    private void fluidic_beginChunkWater(CallbackInfoReturnable<Object> cir) {
        ChunkWater.beginRedraw();
        ChunkLight.beginRedraw();
    }

    @Inject(method = "execute", at = @At("RETURN"), require = 0)
    private void fluidic_endChunkWater(CallbackInfoReturnable<Object> cir) {
        ChunkWater.endRedraw();
        ChunkLight.endRedraw();
        if(cir.getReturnValue() != null) CeleritasRedraws.built(cir.getReturnValue(), fluidic_redrawStarted);
    }
}
