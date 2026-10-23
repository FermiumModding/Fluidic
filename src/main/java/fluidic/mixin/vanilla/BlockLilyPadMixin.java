package fluidic.mixin.vanilla;

import fluidic.block.LilyPads;
import net.minecraft.block.BlockBush;
import net.minecraft.block.BlockLilyPad;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lily pads float on water more than half full, simulated water too, and check now and then whether the water under
 * them went down, see {@link LilyPads}.
 */
@Mixin(BlockLilyPad.class)
public abstract class BlockLilyPadMixin extends BlockBush {

    @Inject(method = "<init>", at = @At("RETURN"))
    private void fluidic_followWater(CallbackInfo ci) {
        setTickRandomly(true);
    }

    @Inject(method = "canBlockStay", at = @At("RETURN"), cancellable = true)
    private void fluidic_floatOnFluidicWater(World world, BlockPos pos, IBlockState state, CallbackInfoReturnable<Boolean> cir) {
        if(!cir.getReturnValueZ() && pos.getY() > 0 && pos.getY() < 256 && LilyPads.floats(world, pos.down())) cir.setReturnValue(true);
    }
}
