package fluidic.mixin.vanilla;

import fluidic.sim.SimulatedWater;
import net.minecraft.block.BlockColored;
import net.minecraft.block.BlockConcretePowder;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Concrete powder hardens touching water on any side but below. Settled Fluidic water is a water block already,
 * water that is still simulated is not a block, so it is checked here. The simulation also updates powder when
 * its water reaches it, see WaterSimulation.
 */
@Mixin(BlockConcretePowder.class)
public abstract class BlockConcretePowderMixin {

    @Inject(method = "tryTouchWater", at = @At("RETURN"), cancellable = true)
    private void fluidic_hardenInSimulatedWater(World world, BlockPos pos, IBlockState state, CallbackInfoReturnable<Boolean> cir) {
        if(cir.getReturnValueZ()) return;
        for(EnumFacing facing : EnumFacing.values()) {
            if(facing == EnumFacing.DOWN) continue;
            BlockPos side = pos.offset(facing);
            if(SimulatedWater.height(world, side.getX(), side.getY(), side.getZ()) > 0) {
                world.setBlockState(pos, Blocks.CONCRETE.getDefaultState().withProperty(BlockColored.COLOR, state.getValue(BlockConcretePowder.COLOR)), 3);
                cir.setReturnValue(true);
                return;
            }
        }
    }
}
