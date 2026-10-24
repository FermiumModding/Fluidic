package fluidic.mixin.celeritas;

import fluidic.client.CeleritasMaterials;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Celeritas tags the quads of a block's turn with that block's shader material. Water Fluidic drew in the turn of a
 * pane, grass and the like is tagged as water instead, so shader packs draw it as their water. See CeleritasMaterials.
 */
@Pseudo
@Mixin(targets = "org.taumc.celeritas.impl.render.terrain.compile.VintageChunkBuildContext", remap = false)
public abstract class ChunkBuildContextMixin {

    @Inject(method = "recordRenderedQuads", at = @At("HEAD"), require = 0)
    private void fluidic_tagWater(BlockRenderLayer layer, int startVertex, int endVertex, IBlockState state, BlockPos pos, CallbackInfo ci) {
        CeleritasMaterials.tagWater(this, startVertex, endVertex);
    }

    @Inject(method = "recordRenderedQuads", at = @At("RETURN"), require = 0)
    private void fluidic_untagWater(BlockRenderLayer layer, int startVertex, int endVertex, IBlockState state, BlockPos pos, CallbackInfo ci) {
        CeleritasMaterials.untagWater(this);
    }
}
