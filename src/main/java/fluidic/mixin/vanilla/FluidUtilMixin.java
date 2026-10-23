package fluidic.mixin.vanilla;

import fluidic.block.WaterFluidHandlers;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fluids.FluidUtil;
import net.minecraftforge.fluids.capability.IFluidHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import javax.annotation.Nullable;

/**
 * Pumps, tanks and fluid containers of other mods take Fluidic water an eighth of a block at a time, partly filled
 * water and water still being simulated included, see {@link WaterFluidHandlers}.
 */
@Mixin(value = FluidUtil.class, remap = false)
public abstract class FluidUtilMixin {

    @Inject(method = "getFluidHandler(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/util/EnumFacing;)Lnet/minecraftforge/fluids/capability/IFluidHandler;",
            at = @At("HEAD"), cancellable = true)
    private static void fluidic_waterHandler(World world, BlockPos pos, @Nullable EnumFacing side, CallbackInfoReturnable<IFluidHandler> cir) {
        IFluidHandler handler = WaterFluidHandlers.get(world, pos);
        if(handler != null) cir.setReturnValue(handler);
    }
}
