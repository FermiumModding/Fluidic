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
public abstract class RenderChunkMixin implements ChunkRedraws.WaterRedraw {

    @Shadow
    public abstract BlockPos getPosition();

    @Shadow
    private boolean needsUpdate;

    /** Waiting for a redraw only because water changed, see RenderGlobalMixin */
    @Unique
    private boolean fluidic_waterOnly;

    @Unique
    private long fluidic_redrawStarted;
    /** What the redraw is drawn with, see ChunkRedraws.beginRedraw */
    @Unique
    private ChunkRedraws.Snapshot fluidic_redrawSnapshot;

    @Inject(method = "rebuildChunk", at = @At("HEAD"), require = 0)
    private void fluidic_beginChunkWater(float x, float y, float z, ChunkCompileTaskGenerator generator, CallbackInfo ci) {
        ChunkWater.beginRedraw();
        ChunkLight.beginRedraw();
        fluidic_redrawSnapshot = ChunkRedraws.beginRedraw(getPosition());
    }

    @Inject(method = "rebuildChunk", at = @At("RETURN"), require = 0)
    private void fluidic_endChunkWater(float x, float y, float z, ChunkCompileTaskGenerator generator, CallbackInfo ci) {
        ChunkWater.endRedraw();
        ChunkLight.endRedraw();
        ChunkRedraws.endRedraw();
    }

    @Inject(method = "setNeedsUpdate", at = @At("HEAD"))
    private void fluidic_markWaterRedraw(boolean immediate, CallbackInfo ci) {
        // Anything else changing in the section while it waits makes it a redraw like any other
        fluidic_waterOnly = ChunkRedraws.isWaterRedraw() && (!needsUpdate || fluidic_waterOnly);
    }

    @Override
    public boolean fluidic_waterOnly() {
        return fluidic_waterOnly;
    }

    @Inject(method = "makeCompileTaskChunk", at = @At("HEAD"))
    private void fluidic_startRedraw(CallbackInfoReturnable<ChunkCompileTaskGenerator> cir) {
        fluidic_redrawStarted = ChunkRedraws.next();
    }

    @Inject(method = "setCompiledChunk", at = @At("HEAD"))
    private void fluidic_redrawn(CompiledChunk compiled, CallbackInfo ci) {
        ChunkRedraws.drawn(getPosition(), fluidic_redrawStarted, fluidic_redrawSnapshot);
    }
}
