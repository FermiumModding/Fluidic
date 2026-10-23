package fluidic.mixin.vanilla;

import fluidic.block.FluidicWater;
import fluidic.sim.SimulatedWater;
import fluidic.sim.Water;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.projectile.EntityFishHook;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * A fishing hook floats and fish bite in water still being simulated, which is no block, as in a water block of the
 * same depth. Vanilla only looks at the block the hook is in.
 */
@Mixin(EntityFishHook.class)
public abstract class EntityFishHookMixin {

    @Redirect(method = "onUpdate", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/World;getBlockState(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/block/state/IBlockState;"))
    private IBlockState fluidic_simulatedWater(World world, BlockPos pos) {
        IBlockState state = world.getBlockState(pos);
        if(state.getMaterial() == Material.WATER || !FluidicWater.isFinite(world)) return state;
        int height = SimulatedWater.height(world, pos.getX(), pos.getY(), pos.getZ());
        return height > 0 ? FluidicWater.state(Math.max(1, Math.round(height * Water.LEVELS / 255.0F))) : state;
    }
}
