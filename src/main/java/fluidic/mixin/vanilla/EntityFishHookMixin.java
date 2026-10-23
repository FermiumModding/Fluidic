package fluidic.mixin.vanilla;

import fluidic.block.FluidicWater;
import fluidic.sim.SimulatedWater;
import fluidic.sim.Water;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.EntityFishHook;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * A fishing hook floats and fish bite in water still being simulated, which is no block, as in a water block of the
 * same depth. Vanilla only looks at the block the hook is in. The block it looked up is changed as it is stored, not
 * the lookup itself, which Fluidlogged API replaces with its own.
 */
@Mixin(EntityFishHook.class)
public abstract class EntityFishHookMixin {

    @ModifyVariable(method = "onUpdate", at = @At("STORE"), ordinal = 0)
    private IBlockState fluidic_simulatedWater(IBlockState state) {
        if(state.getMaterial() != Material.AIR) return state;
        Entity hook = (Entity)(Object)this;
        World world = hook.world;
        if(!FluidicWater.isFinite(world)) return state;
        BlockPos pos = new BlockPos(hook);
        int height = SimulatedWater.height(world, pos.getX(), pos.getY(), pos.getZ());
        return height > 0 ? FluidicWater.state(Math.max(1, Math.round(height * Water.LEVELS / 255.0F))) : state;
    }
}
