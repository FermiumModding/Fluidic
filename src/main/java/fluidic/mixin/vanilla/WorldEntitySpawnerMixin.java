package fluidic.mixin.vanilla;

import fluidic.block.FluidicWater;
import fluidic.sim.SimulatedWater;
import fluidic.sim.Water;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.EntityLiving;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldEntitySpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Squid, guardians and other water mobs spawn in full water, settled or still being simulated, which is no block and
 * vanilla takes for air. Partly filled water does not count, vanilla would spawn them in any water.
 */
@Mixin(WorldEntitySpawner.class)
public abstract class WorldEntitySpawnerMixin {

    // Added by Forge, it keeps its name outside of the development environment
    @Inject(method = "canCreatureTypeSpawnBody", at = @At("HEAD"), cancellable = true, remap = false)
    private static void fluidic_fullWater(EntityLiving.SpawnPlacementType type, World world, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if(type != EntityLiving.SpawnPlacementType.IN_WATER || !FluidicWater.isFinite(world)) return;
        cir.setReturnValue(isFullWater(world, pos) && isFullWater(world, pos.down()) && !world.getBlockState(pos.up()).isNormalCube());
    }

    /** A full block of water, a water block or simulated water. Water of other mods counts as vanilla's does. */
    private static boolean isFullWater(World world, BlockPos pos) {
        IBlockState state = world.getBlockState(pos);
        if(FluidicWater.isWater(state)) return FluidicWater.level(state) == Water.LEVELS;
        if(state.getMaterial() == Material.WATER) return true;
        int height = SimulatedWater.height(world, pos.getX(), pos.getY(), pos.getZ());
        return Math.round(height * Water.LEVELS / 255.0F) == Water.LEVELS;
    }
}
