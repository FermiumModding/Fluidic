package fluidic.mixin.vanilla;

import fluidic.sim.SimulatedWater;
import net.minecraft.block.BlockFarmland;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Farmland is hydrated by water blocks within four blocks. Settled Fluidic water is a water block already,
 * water that is still simulated is not a block, so it is checked in the same area here.
 */
@Mixin(BlockFarmland.class)
public abstract class BlockFarmlandMixin {

    @Inject(method = "hasWater", at = @At("RETURN"), cancellable = true)
    private void fluidic_simulatedWaterNearby(World world, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if(!cir.getReturnValueZ() && SimulatedWater.anyWater(world, pos.add(-4, 0, -4), pos.add(4, 1, 4))) cir.setReturnValue(true);
    }
}
