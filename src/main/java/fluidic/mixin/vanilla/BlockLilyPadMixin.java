package fluidic.mixin.vanilla;

import fluidic.handlers.ModRegistry;
import net.minecraft.block.BlockLilyPad;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lily pads only rest on vanilla water source blocks. They rest on settled Fluidic water deeper than half a block
 * too, vanilla would read its level backwards and only accept the shallowest.
 */
@Mixin(BlockLilyPad.class)
public abstract class BlockLilyPadMixin {

    @Inject(method = "canSustainBush", at = @At("RETURN"), cancellable = true)
    private void fluidic_sustainedByFluidicWater(IBlockState state, CallbackInfoReturnable<Boolean> cir) {
        if(!cir.getReturnValueZ() && ModRegistry.SETTLED_WATER.canFloatLilyPad(state)) cir.setReturnValue(true);
    }

    @Inject(method = "canBlockStay", at = @At("RETURN"), cancellable = true)
    private void fluidic_staysOnFluidicWater(World world, BlockPos pos, IBlockState state, CallbackInfoReturnable<Boolean> cir) {
        if(!cir.getReturnValueZ() && pos.getY() > 0 && ModRegistry.SETTLED_WATER.canFloatLilyPad(world.getBlockState(pos.down()))) {
            cir.setReturnValue(true);
        }
    }
}
