package fluidic.mixin.vanilla;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import fluidic.handlers.ModRegistry;
import net.minecraft.block.state.IBlockState;
import net.minecraft.item.ItemLilyPad;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Placing a lily pad checks the water it is used on for vanilla source level 0. Settled Fluidic water deeper than
 * half a block passes as a source, see {@link BlockLilyPadMixin}.
 */
@Mixin(ItemLilyPad.class)
public abstract class ItemLilyPadMixin {

    @ModifyExpressionValue(method = "onItemRightClick", at = @At(value = "INVOKE", target = "Ljava/lang/Integer;intValue()I"))
    private int fluidic_fluidicWaterAsSource(int level, @Local IBlockState state) {
        if(state.getBlock() != ModRegistry.SETTLED_WATER) return level;
        return ModRegistry.SETTLED_WATER.canFloatLilyPad(state) ? 0 : 1;
    }
}
