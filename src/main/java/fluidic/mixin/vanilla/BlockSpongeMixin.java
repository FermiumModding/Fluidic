package fluidic.mixin.vanilla;

import fluidic.block.FluidicWater;
import fluidic.sim.SimManager;
import net.minecraft.block.BlockSponge;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sponges soak up water through the simulation, which counts partly filled and flowing water against the sponge
 * capacity in the config. Vanilla would delete up to 65 water blocks at once.
 */
@Mixin(BlockSponge.class)
public abstract class BlockSpongeMixin {

    @Inject(method = "tryAbsorb", at = @At("HEAD"), cancellable = true)
    private void fluidic_simulatedAbsorb(World world, BlockPos pos, IBlockState state, CallbackInfo ci) {
        // Under vanilla's rules sponges soak up water blocks the vanilla way
        if(SimManager.get(world) != null && !FluidicWater.vanillaRules) ci.cancel();
    }
}
