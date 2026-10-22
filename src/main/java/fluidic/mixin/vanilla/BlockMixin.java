package fluidic.mixin.vanilla;

import fluidic.sim.SimulatedWater;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraftforge.common.EnumPlantType;
import net.minecraftforge.common.IPlantable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Beach plants like sugar cane need water beside the grass, dirt or sand they grow on. Settled Fluidic water is a
 * water block already, water that is still simulated is not a block, so it is checked here.
 */
@Mixin(Block.class)
public abstract class BlockMixin {

    @Inject(method = "canSustainPlant", at = @At("RETURN"), cancellable = true, remap = false)
    private void fluidic_simulatedWaterForBeachPlants(IBlockState state, IBlockAccess access, BlockPos pos, EnumFacing direction, IPlantable plantable, CallbackInfoReturnable<Boolean> cir) {
        if(cir.getReturnValueZ() || !(access instanceof World)) return;
        Block block = (Block)(Object)this;
        if(block != Blocks.GRASS && block != Blocks.DIRT && block != Blocks.SAND) return;
        if(plantable.getPlantType(access, pos.offset(direction)) != EnumPlantType.Beach) return;
        World world = (World)access;
        for(EnumFacing facing : EnumFacing.HORIZONTALS) {
            BlockPos side = pos.offset(facing);
            if(SimulatedWater.height(world, side.getX(), side.getY(), side.getZ()) > 0) {
                cir.setReturnValue(true);
                return;
            }
        }
    }
}
