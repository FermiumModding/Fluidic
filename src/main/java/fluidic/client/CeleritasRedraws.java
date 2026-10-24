package fluidic.client;

import fluidic.Fluidic;
import net.minecraft.util.math.BlockPos;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Celeritas and its forks like Voxtavium draw chunks themselves, without vanilla's RenderChunk. Their section builds
 * are followed here instead, see the mixins in fluidic.mixin.celeritas: a build takes a sequence number when it is
 * queued, with the blocks as they are then, and reports it as drawn once its mesh is uploaded, like
 * {@link ChunkRedraws} does for vanilla. Their classes are reached by reflection, Fluidic does not compile against them.
 */
public final class CeleritasRedraws {

    /** Build output to the sequence number its build was queued with, until the output is uploaded */
    private static final Map<Object, Long> STARTED = Collections.synchronizedMap(new WeakHashMap<>());
    private static Method output, originX, originY, originZ;
    private static Field render;
    private static boolean failed;

    private CeleritasRedraws() {}

    /** A section build finished with the given output, queued with the given sequence number */
    public static void built(Object output, long started) {
        STARTED.put(output, started);
    }

    /** Build results were uploaded and are drawn from now on, as Celeritas' ChunkJobResult.Success of a build output */
    public static void uploaded(Iterable<?> results) {
        if(failed) return;
        try {
            for(Object result : results) {
                if(output == null) {
                    output = result.getClass().getMethod("output");
                    output.setAccessible(true);
                }
                Object built = output.invoke(result);
                Long started = built == null ? null : STARTED.remove(built);
                if(started == null) continue;
                if(render == null) render = built.getClass().getField("render");
                Object section = render.get(built);
                if(originX == null) {
                    originX = section.getClass().getMethod("getOriginX");
                    originY = section.getClass().getMethod("getOriginY");
                    originZ = section.getClass().getMethod("getOriginZ");
                    originX.setAccessible(true);
                    originY.setAccessible(true);
                    originZ.setAccessible(true);
                }
                ChunkRedraws.drawn(new BlockPos((Integer)originX.invoke(section), (Integer)originY.invoke(section), (Integer)originZ.invoke(section)), started);
            }
        }
        catch(ReflectiveOperationException | RuntimeException ex) {
            // Settled water then falls back to waiting a moment for the chunk, see ClientWaterCache
            failed = true;
            Fluidic.LOGGER.warn("Could not follow Celeritas chunk builds, water may flicker as it settles or wakes", ex);
        }
    }
}
