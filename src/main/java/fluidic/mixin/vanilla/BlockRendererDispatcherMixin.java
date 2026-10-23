package fluidic.mixin.vanilla;

import fluidic.client.ChunkWater;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.BlockFluidRenderer;
import net.minecraft.client.renderer.BlockRendererDispatcher;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraftforge.client.MinecraftForgeClient;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Draws the simulated water inside a block like sugar cane, as water, in the turn the block got in the translucent
 * layer for it. The block itself is drawn in its own layer as usual. See {@link ChunkWater#wantsWaterLayer}.
 */
@Mixin(BlockRendererDispatcher.class)
public abstract class BlockRendererDispatcherMixin {

    @Shadow
    @Final
    private BlockFluidRenderer fluidRenderer;

    @Inject(method = "renderBlock", at = @At("HEAD"), cancellable = true)
    private void fluidic_waterInside(IBlockState state, BlockPos pos, IBlockAccess world, BufferBuilder buffer, CallbackInfoReturnable<Boolean> cir) {
        if(!ChunkWater.isActive() || !ChunkWater.drawWaterInside(state, MinecraftForgeClient.getRenderLayer())) return;
        IBlockState water = ChunkWater.waterInside(pos);
        if(water == null) {
            cir.setReturnValue(false);
            return;
        }
        // Shader packs tell water by the block its vertices are tagged with, OptiFine tagged them with the block here
        boolean tagged = ChunkWater.tagAs(water, pos, world, buffer);
        try {
            cir.setReturnValue(fluidRenderer.renderFluid(world, water, pos, buffer));
        }
        finally {
            if(tagged) ChunkWater.untag(buffer);
        }
    }
}
