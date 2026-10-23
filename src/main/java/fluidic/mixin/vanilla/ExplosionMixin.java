package fluidic.mixin.vanilla;

import fluidic.block.FluidicWater;
import fluidic.sim.SimulatedWater;
import fluidic.sim.Water;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Explosion;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Water still being simulated is no block, explosion rays would pass through it like air. They see a water block of
 * the same depth there instead, which takes their strength like water blocks do, so TNT going off in simulated water
 * does no harm to the blocks around it. Only the rays look, the blocks the explosion destroys stay the real ones.
 */
@Mixin(Explosion.class)
public abstract class ExplosionMixin {

    @Redirect(method = "doExplosionA", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/World;getBlockState(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/block/state/IBlockState;"))
    private IBlockState fluidic_simulatedWater(World world, BlockPos pos) {
        IBlockState state = world.getBlockState(pos);
        if(state.getMaterial() != Material.AIR || !FluidicWater.isFinite(world)) return state;
        int height = SimulatedWater.height(world, pos.getX(), pos.getY(), pos.getZ());
        return height > 0 ? FluidicWater.state(Math.max(1, Math.round(height * Water.LEVELS / 255.0F))) : state;
    }
}
