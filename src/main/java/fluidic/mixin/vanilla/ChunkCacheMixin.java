package fluidic.mixin.vanilla;

import fluidic.client.ChunkLight;
import fluidic.client.ChunkWater;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.ChunkCache;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Chunk redraws look their blocks up here, simulated water reads as water blocks to them while
 * {@link ChunkWater} is active. Client only, so the server's path finding is untouched.
 */
@Mixin(ChunkCache.class)
public abstract class ChunkCacheMixin {

    @Shadow
    @Final
    protected World world;

    /**
     * Sky light inside simulated water, dimmed for drawing like water blocks dim it, see ChunkLight. Where every block
     * model and OptiFine's chunk cache take their light from. On the client a chunk cache only draws chunks, on the
     * server it finds paths, with the light of the world as it is.
     */
    @Inject(method = "getCombinedLight", at = @At("RETURN"), cancellable = true)
    private void fluidic_dimUnderWater(BlockPos pos, int lightValue, CallbackInfoReturnable<Integer> cir) {
        if(world == null || !world.isRemote) return;
        int light = cir.getReturnValueI();
        int sky = light >> 20 & 15;
        int dimmed = ChunkLight.drawnSky(sky, pos.getX(), pos.getY(), pos.getZ());
        if(dimmed != sky) cir.setReturnValue(light & ~(15 << 20) | dimmed << 20);
    }

    @Inject(method = "getBlockState", at = @At("RETURN"), cancellable = true, require = 0)
    private void fluidic_chunkWater(BlockPos pos, CallbackInfoReturnable<IBlockState> cir) {
        if(!ChunkWater.isActive()) return;
        IBlockState state = cir.getReturnValue();
        IBlockState seen = ChunkWater.substitute(state, pos);
        if(seen != state) cir.setReturnValue(seen);
    }
}
