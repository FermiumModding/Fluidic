package fluidic.mixin.vanilla;

import fluidic.block.FluidicWater;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.IChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tells Fluidic water while a chunk is being populated, by vanilla and by mods. Lakes, springs and the like placed
 * then are left as they are generated instead of each block being checked as it is placed, see
 * {@link FluidicWater#generating}.
 */
@Mixin(Chunk.class)
public abstract class ChunkMixin {

    @Inject(method = "populate(Lnet/minecraft/world/gen/IChunkGenerator;)V", at = @At("HEAD"))
    private void fluidic_beginPopulating(IChunkGenerator generator, CallbackInfo ci) {
        FluidicWater.generating++;
    }

    @Inject(method = "populate(Lnet/minecraft/world/gen/IChunkGenerator;)V", at = @At("RETURN"))
    private void fluidic_endPopulating(IChunkGenerator generator, CallbackInfo ci) {
        FluidicWater.generating--;
    }
}
