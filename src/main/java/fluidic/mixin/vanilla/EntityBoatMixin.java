package fluidic.mixin.vanilla;

import fluidic.handlers.ModRegistry;
import fluidic.sim.SimulatedWater;
import fluidic.sim.Water;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityBoat;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraftforge.fluids.BlockFluidBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets boats float on water that is still being simulated, and float up out of Fluidic water they are
 * under. Boats find water with their own checks rather than Entity.handleWaterMovement.
 */
@Mixin(EntityBoat.class)
public abstract class EntityBoatMixin {

    @Shadow
    private double waterLevel;

    @Shadow
    private double lastYd;

    /**
     * Vanilla reads the water level from the block at the boat's bottom only. Fluidic water follows its column up
     * to the real surface, so boats ride on top of a thin top layer instead of inside it. Simulated water is
     * added too.
     */
    @Inject(method = "checkInWater", at = @At("RETURN"), cancellable = true)
    private void fluidic_checkInFluidicWater(CallbackInfoReturnable<Boolean> cir) {
        Entity boat = (Entity)(Object)this;
        AxisAlignedBB box = boat.getEntityBoundingBox();
        int minY = MathHelper.floor(box.minY), maxY = MathHelper.ceil(box.minY + 0.001D);
        double level = SimulatedWater.surfaceLevel(boat.world, box, minY, maxY);
        int minX = MathHelper.floor(box.minX), maxX = MathHelper.ceil(box.maxX);
        int minZ = MathHelper.floor(box.minZ), maxZ = MathHelper.ceil(box.maxZ);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for(int x = minX; x < maxX; x++) {
            for(int y = minY; y < maxY; y++) {
                for(int z = minZ; z < maxZ; z++) {
                    if(boat.world.getBlockState(pos.setPos(x, y, z)).getBlock() != ModRegistry.SETTLED_WATER) continue;
                    while(pos.getY() < 255 && boat.world.getBlockState(pos.up()).getBlock() == ModRegistry.SETTLED_WATER) pos.setY(pos.getY() + 1);
                    IBlockState top = boat.world.getBlockState(pos);
                    double surface = pos.getY() + (top.getValue(BlockFluidBase.LEVEL) + 1) / (Water.LEVELS + 1.0D);
                    if(Double.isNaN(level) || surface > level) level = surface;
                }
            }
        }
        if(Double.isNaN(level)) return;
        waterLevel = Math.max(waterLevel, level);
        if(box.minY < level) cir.setReturnValue(true);
    }

    /**
     * Boats under Fluidic water float up. Vanilla 1.12 boats stay down under water, and treat Fluidic water as
     * flowing water since it is not a source block, which holds them down too. Reporting the boat as in water
     * makes vanilla set the water level to the top of the boat and float it up at a steady rate until it
     * surfaces. Boats under vanilla water keep vanilla's behaviour.
     */
    @Inject(method = "getUnderwaterStatus", at = @At("RETURN"), cancellable = true)
    private void fluidic_underwaterStatus(CallbackInfoReturnable<EntityBoat.Status> cir) {
        Entity boat = (Entity)(Object)this;
        AxisAlignedBB box = boat.getEntityBoundingBox();
        double top = box.maxY + 0.001D;
        int minX = MathHelper.floor(box.minX), maxX = MathHelper.ceil(box.maxX);
        int minY = MathHelper.floor(box.maxY), maxY = MathHelper.ceil(top);
        int minZ = MathHelper.floor(box.minZ), maxZ = MathHelper.ceil(box.maxZ);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        boolean fluidic = false;
        for(int x = minX; x < maxX; x++) {
            for(int y = minY; y < maxY; y++) {
                for(int z = minZ; z < maxZ; z++) {
                    IBlockState state = boat.world.getBlockState(pos.setPos(x, y, z));
                    if(state.getBlock() == ModRegistry.SETTLED_WATER) {
                        fluidic |= top < y + ModRegistry.SETTLED_WATER.getFilledPercentage(boat.world, pos);
                    }
                    else if(state.getMaterial() == Material.WATER) {
                        return;
                    }
                    else {
                        fluidic |= SimulatedWater.isSubmerged(boat.world, x + 0.5D, top, z + 0.5D);
                    }
                }
            }
        }
        if(fluidic) cir.setReturnValue(EntityBoat.Status.IN_WATER);
    }

    @Inject(method = "getWaterLevelAbove", at = @At("RETURN"), cancellable = true)
    private void fluidic_simulatedWaterLevelAbove(CallbackInfoReturnable<Float> cir) {
        Entity boat = (Entity)(Object)this;
        AxisAlignedBB box = boat.getEntityBoundingBox();
        float level = SimulatedWater.levelAbove(boat.world, box, MathHelper.floor(box.maxY), MathHelper.ceil(box.maxY - lastYd));
        if(level > cir.getReturnValueF()) cir.setReturnValue(level);
    }
}
