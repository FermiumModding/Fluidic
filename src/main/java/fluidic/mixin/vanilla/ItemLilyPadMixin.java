package fluidic.mixin.vanilla;

import fluidic.block.FluidicWater;
import fluidic.sim.Water;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraft.item.ItemLilyPad;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Lily pads can be placed on water more than half full. Vanilla only places them on full water. */
@Mixin(ItemLilyPad.class)
public abstract class ItemLilyPadMixin {

    @Redirect(method = "onItemRightClick", at = @At(value = "INVOKE", target = "Lnet/minecraft/block/state/IBlockState;getValue(Lnet/minecraft/block/properties/IProperty;)Ljava/lang/Comparable;"))
    private <T extends Comparable<T>> T fluidic_partlyFilledWater(IBlockState state, IProperty<T> property) {
        T value = state.getValue(property);
        if(FluidicWater.isWater(state) && FluidicWater.level(state) > Water.LEVELS / 2) {
            @SuppressWarnings("unchecked") T full = (T)Integer.valueOf(0);
            return full;
        }
        return value;
    }
}
