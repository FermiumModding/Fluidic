package fluidic.mixin.vanilla;

import fluidic.client.ChunkWater;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.ChunkCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Chunk redraws look their blocks up here, simulated water reads as water blocks to them while
 * {@link ChunkWater} is active. Client only, so the server's path finding is untouched.
 */
@Mixin(ChunkCache.class)
public abstract class ChunkCacheMixin {

    @Inject(method = "getBlockState", at = @At("RETURN"), cancellable = true, require = 0)
    private void fluidic_chunkWater(BlockPos pos, CallbackInfoReturnable<IBlockState> cir) {
        if(!ChunkWater.isActive()) return;
        IBlockState state = cir.getReturnValue();
        IBlockState seen = ChunkWater.substitute(state, pos);
        if(seen != state) cir.setReturnValue(seen);
    }
}
