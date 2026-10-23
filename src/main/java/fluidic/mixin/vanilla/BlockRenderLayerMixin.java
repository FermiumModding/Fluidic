package fluidic.mixin.vanilla;

import fluidic.block.FluidicWater;
import fluidic.client.ChunkWater;
import fluidic.sim.Terrain;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.BlockRenderLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * While chunks draw simulated water, blocks that can hold water, like sugar cane, also get a turn in the
 * translucent layer during chunk redraws, to draw the water inside them. See {@link ChunkWater#wantsWaterLayer}.
 */
@Mixin(Block.class)
public abstract class BlockRenderLayerMixin {

    @Inject(method = "canRenderInLayer", at = @At("RETURN"), cancellable = true, remap = false)
    private void fluidic_waterInsideLayer(IBlockState state, BlockRenderLayer layer, CallbackInfoReturnable<Boolean> cir) {
        if(cir.getReturnValueZ() || layer != BlockRenderLayer.TRANSLUCENT) return;
        // Thin blocks like glass panes draw the water beside them reaching into them, see ThinBlockWater
        if(!FluidicWater.clientVanilla && Terrain.isThin(state)) cir.setReturnValue(true);
        else if(ChunkWater.isActive() && ChunkWater.wantsWaterLayer(state)) cir.setReturnValue(true);
    }
}
