package fluidic.mixin.vanilla;

import fluidic.sim.SimulatedWater;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.ActiveRenderInfo;
import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The camera in water that is still being simulated sees water, as it would in a water block: vanilla then draws the
 * underwater fog in its colour, darkened by the light around the camera, and density, narrows the view and fogs the
 * sky, and mods asking where the camera is see it too. The blue overlay goes by Entity.isInsideOfMaterial instead.
 */
@Mixin(ActiveRenderInfo.class)
public abstract class ActiveRenderInfoMixin {

    @Inject(method = "getBlockStateAtEntityViewpoint", at = @At("RETURN"), cancellable = true)
    private static void fluidic_simulatedWaterAtViewpoint(World world, Entity entity, float partialTicks, CallbackInfoReturnable<IBlockState> cir) {
        if(cir.getReturnValue().getMaterial().isLiquid()) return;
        Vec3d eyes = ActiveRenderInfo.projectViewFromEntity(entity, partialTicks);
        if(SimulatedWater.isSubmerged(world, eyes.x, eyes.y, eyes.z)) cir.setReturnValue(Blocks.WATER.getDefaultState());
    }
}
