package fluidic.mixin.vanilla;

import fluidic.block.FluidicWater;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.BlockFluidRenderer;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Water corner heights from {@link FluidicWater#cornerHeight}: still surfaces stay flat next to waterfalls and
 * blend into water that is still being simulated, instead of vanilla's ramps. Side faces against lower water end
 * at its surface, see {@link FluidicWater#sideBottom}.
 */
@Mixin(BlockFluidRenderer.class)
public abstract class BlockFluidRendererMixin {

    @Inject(method = "renderFluid", at = @At("HEAD"))
    private void fluidic_beginWater(IBlockAccess world, IBlockState state, BlockPos pos, BufferBuilder buffer, CallbackInfoReturnable<Boolean> cir) {
        FluidicWater.beginRendering(pos);
    }

    @Inject(method = "getFluidHeight", at = @At("HEAD"), cancellable = true)
    private void fluidic_waterCornerHeight(IBlockAccess world, BlockPos corner, Material material, CallbackInfoReturnable<Float> cir) {
        if(material == Material.WATER && FluidicWater.isFinite(world)) cir.setReturnValue(FluidicWater.cornerHeight(world, corner));
    }

    @Redirect(method = "renderFluid", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/BufferBuilder;pos(DDD)Lnet/minecraft/client/renderer/BufferBuilder;"))
    private BufferBuilder fluidic_waterSideBottom(BufferBuilder buffer, double x, double y, double z, IBlockAccess world, IBlockState state, BlockPos pos, BufferBuilder target) {
        if(y == pos.getY() && state.getMaterial() == Material.WATER && FluidicWater.isFinite(world)) y = FluidicWater.sideBottom(world, pos, x, y, z);
        return buffer.pos(x, y, z);
    }
}
