package fluidic.mixin.vanilla;

import fluidic.client.ChunkRedraws;
import fluidic.client.ChunkLight;
import fluidic.client.ChunkWater;
import net.minecraft.client.renderer.chunk.ChunkCompileTaskGenerator;
import net.minecraft.client.renderer.chunk.CompiledChunk;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tells {@link ChunkRedraws} when a section redraw starts and when it is on screen. Starting a redraw cancels the
 * one before, so the newest start is the one that finishes. Also marks redraws for {@link ChunkWater}.
 */
@Mixin(RenderChunk.class)
public abstract class RenderChunkMixin {

    @Shadow
    public abstract BlockPos getPosition();

    @Unique
    private long fluidic_redrawStarted;

    @Inject(method = "rebuildChunk", at = @At("HEAD"), require = 0)
    private void fluidic_beginChunkWater(float x, float y, float z, ChunkCompileTaskGenerator generator, CallbackInfo ci) {
        ChunkWater.beginRedraw();
        ChunkLight.beginRedraw();
    }

    @Inject(method = "rebuildChunk", at = @At("RETURN"), require = 0)
    private void fluidic_endChunkWater(float x, float y, float z, ChunkCompileTaskGenerator generator, CallbackInfo ci) {
        ChunkWater.endRedraw();
        ChunkLight.endRedraw();
    }

    @Inject(method = "makeCompileTaskChunk", at = @At("HEAD"))
    private void fluidic_startRedraw(CallbackInfoReturnable<ChunkCompileTaskGenerator> cir) {
        fluidic_redrawStarted = ChunkRedraws.next();
    }

    @Inject(method = "setCompiledChunk", at = @At("HEAD"))
    private void fluidic_redrawn(CompiledChunk compiled, CallbackInfo ci) {
        ChunkRedraws.drawn(getPosition(), fluidic_redrawStarted);
    }
}
