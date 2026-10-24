package fluidic.mixin.vanilla;

import fluidic.client.ChunkLight;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.profiler.Profiler;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.storage.ISaveHandler;
import net.minecraft.world.storage.WorldInfo;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Light looked up for drawing in the client world, dimmed in simulated water like water blocks dim it, see ChunkLight:
 * how bright entities in it are drawn, the underwater overlay and the fog colour, both by the light where the camera
 * is. The light engine reads the light itself through other methods, it is not affected.
 */
@Mixin(WorldClient.class)
public abstract class WorldClientMixin extends World {

    protected WorldClientMixin(ISaveHandler saveHandler, WorldInfo info, WorldProvider provider, Profiler profiler, boolean client) {
        super(saveHandler, info, provider, profiler, client);
    }

    @Override
    public int getCombinedLight(BlockPos pos, int lightValue) {
        int light = super.getCombinedLight(pos, lightValue);
        int sky = light >> 20 & 15;
        int dimmed = ChunkLight.dimmed(sky, pos.getX(), pos.getY(), pos.getZ());
        return dimmed == sky ? light : (light & ~(15 << 20)) | dimmed << 20;
    }

    @Override
    public float getLightBrightness(BlockPos pos) {
        if(pos.getY() < 0 || pos.getY() > 255) return super.getLightBrightness(pos);
        int sky = getLightFromNeighborsFor(EnumSkyBlock.SKY, pos);
        int dimmed = ChunkLight.dimmed(sky, pos.getX(), pos.getY(), pos.getZ());
        if(dimmed == sky) return super.getLightBrightness(pos);
        int level = Math.max(dimmed - getSkylightSubtracted(), getLightFromNeighborsFor(EnumSkyBlock.BLOCK, pos));
        return provider.getLightBrightnessTable()[Math.max(0, level)];
    }
}
