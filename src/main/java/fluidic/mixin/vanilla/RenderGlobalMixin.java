package fluidic.mixin.vanilla;

import fluidic.client.ChunkRedraws;
import fluidic.client.WaterRenderer;
import fluidic.sim.Water;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Set;

/**
 * Vanilla redraws chunk sections near the camera on the client thread, in the frame they change, so blocks the player
 * places show at once. Rain, settling and evaporation change water blocks around the player all the time, each change a
 * whole section redrawn while the frame waits. Sections waiting only for water are redrawn by the chunk worker threads
 * like distant ones, simulated water keeps meeting them as they are on screen, see ChunkRedraws.
 * Also tells WaterRenderer which sections vanilla found in view, past walls and the ground none are.
 */
@Mixin(RenderGlobal.class)
public abstract class RenderGlobalMixin {

    @Shadow
    private Set<RenderChunk> chunksToUpdate;

    @Shadow
    private List<?> renderInfos;

    @Redirect(method = "setupTerrain", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/chunk/ChunkRenderDispatcher;updateChunkNow(Lnet/minecraft/client/renderer/chunk/RenderChunk;)Z"), require = 0)
    private boolean fluidic_redrawWaterLater(ChunkRenderDispatcher dispatcher, RenderChunk chunk) {
        if(!((ChunkRedraws.WaterRedraw)chunk).fluidic_waterOnly()) return dispatcher.updateChunkNow(chunk);
        chunksToUpdate.add(chunk);
        return false;
    }

    @Inject(method = "setupTerrain", at = @At("RETURN"), require = 0)
    private void fluidic_sectionsInView(Entity viewEntity, double partialTicks, ICamera camera, int frameCount, boolean playerSpectator, CallbackInfo ci) {
        LongOpenHashSet sections = WaterRenderer.beginSectionsInView();
        if(sections == null) return;
        for(Object info : renderInfos) {
            BlockPos pos = ((RenderInfoAccessor)info).fluidic_getRenderChunk().getPosition();
            sections.add(Water.sectionKey(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4));
        }
    }
}
