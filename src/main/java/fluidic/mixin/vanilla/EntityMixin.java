package fluidic.mixin.vanilla;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import fluidic.block.FluidicWater;
import fluidic.sim.SimulatedWater;
import net.minecraft.block.material.Material;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityBoat;
import net.minecraft.init.SoundEvents;
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

    /**
     * Swimming against a wall lifts entities over it, like out of the water onto a ledge, only where no water is above
     * them. Simulated water above keeps them down too, they would otherwise hop up the wall like auto jump.
     */
    @WrapOperation(
            method = "isLiquidPresentInAABB",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/World;containsAnyLiquid(Lnet/minecraft/util/math/AxisAlignedBB;)Z")
    )
    private boolean fluidic_simulatedWaterPresent(World world, AxisAlignedBB box, Operation<Boolean> original) {
        return original.call(world, box) || SimulatedWater.containsWater(world, box);
    }

    /**
     * Puts out entities touching Fluidic water of any depth. Vanilla's wet check only runs when an entity moves,
     * the water check runs every tick.
     */
    @Inject(method = "handleWaterMovement", at = @At("RETURN"))
    private void fluidic_extinguishInFluidicWater(CallbackInfoReturnable<Boolean> cir) {
        Entity entity = (Entity)(Object)this;
        if(world.isRemote || !entity.isBurning() || !FluidicWater.isFinite(world) || !SimulatedWater.isTouchingWater(world, getEntityBoundingBox())) return;
        entity.playSound(SoundEvents.ENTITY_GENERIC_EXTINGUISH_FIRE, 0.7F, 1.6F + (world.rand.nextFloat() - world.rand.nextFloat()) * 0.4F);
        entity.extinguish();
    }

    /**
     * Fluidic water of any depth gets entities wet. Vanilla then puts out their fire with a hiss, keeps them from
     * catching fire, and hurts endermen, like it does in shallow vanilla water.
     */
    @Inject(method = "isWet", at = @At("RETURN"), cancellable = true)
    private void fluidic_wetInFluidicWater(CallbackInfoReturnable<Boolean> cir) {
        if(!cir.getReturnValueZ() && FluidicWater.isFinite(world) && SimulatedWater.isTouchingWater(world, getEntityBoundingBox())) cir.setReturnValue(true);
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
