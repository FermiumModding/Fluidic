package fluidic.mixin.vanilla;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import fluidic.sim.SimulatedWater;
import net.minecraft.block.material.Material;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityBoat;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets entities swim in, splash into, be carried by, be slowed by and drown in water that is still being simulated.
 */
@Mixin(Entity.class)
public abstract class EntityMixin {

    @Shadow
    public World world;

    @Shadow
    public double posX;

    @Shadow
    public double posY;

    @Shadow
    public double posZ;

    @Shadow
    public abstract AxisAlignedBB getEntityBoundingBox();

    @Shadow
    public abstract float getEyeHeight();

    @Shadow
    public abstract Entity getRidingEntity();

    /** Vanilla then handles the splash, fall damage reset, extinguishing and swimming */
    @ModifyExpressionValue(
            method = "handleWaterMovement",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/World;handleMaterialAcceleration(Lnet/minecraft/util/math/AxisAlignedBB;Lnet/minecraft/block/material/Material;Lnet/minecraft/entity/Entity;)Z")
    )
    private boolean fluidic_inSimulatedWater(boolean inWater) {
        AxisAlignedBB box = getEntityBoundingBox().grow(0.0D, -0.4000000059604645D, 0.0D).shrink(0.001D);
        return SimulatedWater.handleEntity((Entity)(Object)this, box) || inWater;
    }

    /** Drowning, air bubbles, the underwater overlay and the underwater checks of mobs */
    @Inject(method = "isInsideOfMaterial", at = @At("RETURN"), cancellable = true)
    private void fluidic_headInSimulatedWater(Material material, CallbackInfoReturnable<Boolean> cir) {
        if(material == Material.WATER && !cir.getReturnValueZ() && !(getRidingEntity() instanceof EntityBoat)
                && SimulatedWater.isSubmerged(world, posX, posY + getEyeHeight(), posZ)) {
            cir.setReturnValue(true);
        }
    }
}
