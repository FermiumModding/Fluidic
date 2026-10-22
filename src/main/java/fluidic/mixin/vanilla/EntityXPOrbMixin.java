package fluidic.mixin.vanilla;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import fluidic.sim.SimulatedWater;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityXPOrb;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Experience orbs have their own copy of Entity.handleWaterMovement, see {@link EntityMixin}.
 */
@Mixin(EntityXPOrb.class)
public abstract class EntityXPOrbMixin {

    @ModifyExpressionValue(
            method = "handleWaterMovement",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/World;handleMaterialAcceleration(Lnet/minecraft/util/math/AxisAlignedBB;Lnet/minecraft/block/material/Material;Lnet/minecraft/entity/Entity;)Z")
    )
    private boolean fluidic_inSimulatedWater(boolean inWater) {
        Entity entity = (Entity)(Object)this;
        return SimulatedWater.handleEntity(entity, entity.getEntityBoundingBox()) || inWater;
    }
}
