package fluidic.client;

import fluidic.Fluidic;
import net.minecraft.block.state.IBlockState;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;

/**
 * Tags water Fluidic drew in the turn of another block as water for Celeritas' shader packs, see ChunkWater.drewWater.
 * Celeritas tags parts of a turn as another block for Better Foliage, with a list of vertex ranges and their blocks on
 * its build context. The water's range goes in that list for the turn, and out again after it. Reached by reflection,
 * Fluidic does not compile against Celeritas. When its classes look different, the water keeps the block's material.
 */
public final class CeleritasMaterials {

    private static final ThreadLocal<Object> ADDED = new ThreadLocal<>();
    private static final ThreadLocal<int[]> RANGE = ThreadLocal.withInitial(() -> new int[2]);
    private static Field capture, ranges, start;
    private static Constructor<?> range;
    private static boolean failed;

    private CeleritasMaterials() {}

    public static void tagWater(Object context, int startVertex, int endVertex) {
        int[] drawn = RANGE.get();
        IBlockState water = ChunkWater.takeDrawnWater(drawn);
        if(water == null || failed || drawn[0] < startVertex || drawn[1] > endVertex || drawn[0] % 4 != 0 || drawn[1] % 4 != 0) return;
        try {
            List<Object> list = ranges(context);
            Object added = range.newInstance(drawn[0], drawn[1], water);
            // The list goes by vertex order
            int at = list.size();
            while(at > 0 && (Integer)start.get(list.get(at - 1)) > drawn[0]) at--;
            list.add(at, added);
            ADDED.set(added);
        }
        catch(ReflectiveOperationException | RuntimeException ex) {
            failed = true;
            Fluidic.LOGGER.warn("Could not tag water for Celeritas shader packs, water in other blocks keeps their material", ex);
        }
    }

    public static void untagWater(Object context) {
        Object added = ADDED.get();
        if(added == null) return;
        ADDED.set(null);
        try {
            ranges(context).remove(added);
        }
        catch(ReflectiveOperationException | RuntimeException ex) {
            failed = true;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> ranges(Object context) throws ReflectiveOperationException {
        if(capture == null) {
            capture = context.getClass().getField("foliageCapture");
            Object value = capture.get(context);
            ranges = value.getClass().getField("ranges");
            Class<?> type = Class.forName("org.taumc.celeritas.impl.compat.betterfoliage.BetterFoliageCompat$MaterialRange", false, context.getClass().getClassLoader());
            range = type.getDeclaredConstructor(int.class, int.class, IBlockState.class);
            range.setAccessible(true);
            start = type.getDeclaredField("startVertex");
            start.setAccessible(true);
        }
        return (List<Object>)ranges.get(capture.get(context));
    }
}
