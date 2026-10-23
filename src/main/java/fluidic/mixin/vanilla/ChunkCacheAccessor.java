package fluidic.mixin.vanilla;

import net.minecraft.world.ChunkCache;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The world a chunk cache was made from, path finding looks blocks up through one, see {@link WalkNodeProcessorMixin} */
@Mixin(ChunkCache.class)
public interface ChunkCacheAccessor {

    @Accessor("world")
    World fluidic_getWorld();
}
