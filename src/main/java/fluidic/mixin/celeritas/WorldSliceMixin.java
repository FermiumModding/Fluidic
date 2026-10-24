package fluidic.mixin.celeritas;

import fluidic.client.ChunkLight;
import fluidic.client.ChunkWater;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumSkyBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The copy of the world Celeritas draws a section from, in place of vanilla's ChunkCache: while the chunks draw
 * simulated water, empty blocks holding it read as water blocks, like ChunkCacheMixin does for vanilla.
 */
@Pseudo
@Mixin(targets = "org.taumc.celeritas.impl.world.WorldSlice", remap = false)
public abstract class WorldSliceMixin {

    @Inject(method = "getBlockState(III)Lnet/minecraft/block/state/IBlockState;", at = @At("RETURN"), cancellable = true, require = 0)
    private void fluidic_chunkWater(int x, int y, int z, CallbackInfoReturnable<IBlockState> cir) {
        if(!ChunkWater.isActive()) return;
        IBlockState state = cir.getReturnValue();
        IBlockState seen = ChunkWater.substitute(state, x, y, z);
        if(seen != state) cir.setReturnValue(seen);
    }

    // Sky light inside simulated water, dimmed for drawing like water blocks dim it, see ChunkLight
    @Inject(method = "getLightFromNeighborsFor(Lnet/minecraft/world/EnumSkyBlock;Lnet/minecraft/util/math/BlockPos;)I", at = @At("RETURN"), cancellable = true, require = 0)
    private void fluidic_dimUnderWater(EnumSkyBlock type, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        if(type != EnumSkyBlock.SKY) return;
        int sky = cir.getReturnValueI();
        int dimmed = ChunkLight.sky(sky, pos.getX(), pos.getY(), pos.getZ());
        if(dimmed != sky) cir.setReturnValue(dimmed);
    }
}
