package fluidic.mixin.vanilla;

import fluidic.block.FluidicWater;
import fluidic.sim.SimulatedWater;
import net.minecraft.pathfinding.PathNodeType;
import net.minecraft.pathfinding.WalkNodeProcessor;
import net.minecraft.world.ChunkCache;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Water still being simulated is no block, path finding would walk mobs into it like air. Where it is deep enough to
 * swim in, mobs go around it or swim through it like water blocks. Shallower water is walked through, like puddles,
 * see BlockFluidicWater.getAiPathNodeType.
 */
@Mixin(WalkNodeProcessor.class)
public abstract class WalkNodeProcessorMixin {

    @Inject(method = "getPathNodeTypeRaw", at = @At("RETURN"), cancellable = true)
    private void fluidic_simulatedWater(IBlockAccess access, int x, int y, int z, CallbackInfoReturnable<PathNodeType> cir) {
        if(cir.getReturnValue() != PathNodeType.OPEN) return;
        World world = access instanceof World ? (World)access : access instanceof ChunkCache ? ((ChunkCacheAccessor)access).fluidic_getWorld() : null;
        if(world == null || !FluidicWater.isFinite(world)) return;
        // Over half a block, where entities count it as water
        if(SimulatedWater.height(world, x, y, z) > 127) cir.setReturnValue(PathNodeType.WATER);
    }
}
