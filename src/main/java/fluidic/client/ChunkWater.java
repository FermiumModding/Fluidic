package fluidic.client;

import fluidic.Fluidic;
import fluidic.block.FluidicWater;
import fluidic.handlers.ForgeConfigHandler;
import fluidic.sim.Terrain;
import fluidic.sim.Water;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraftforge.fml.common.Loader;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Draws simulated water as part of the chunks it is in instead of with {@link WaterRenderer}. Shader packs only
 * shade what the chunks draw, water drawn separately shows in plain vanilla colours among shaded water. While a
 * chunk is redrawn, empty blocks holding simulated water read as water blocks of the same level, so the chunk draws
 * them like any other water. Water moving then makes its chunks redraw, which costs more than the separate renderer,
 * so this is only used with shaders unless the config says otherwise.
 */
public final class ChunkWater {

    private static volatile boolean active;
    /** The chunk redraw did not look its blocks up through ChunkCache, some renderer replaced it. Drawn separately then. */
    private static volatile boolean bypassed;
    /** Per redraw thread: whether a redraw is running, how many blocks it looked up, and whether the block about to
     * be drawn only had its turn for the water inside it */
    private static final ThreadLocal<int[]> REDRAW = ThreadLocal.withInitial(() -> new int[3]);
    /** The block given a turn in the translucent layer for the water inside it */
    private static final ThreadLocal<IBlockState> WATER_TURN = new ThreadLocal<>();
    /** Redraws that may pass without a block lookup before the hook counts as bypassed */
    private static final int MAX_EMPTY_REDRAWS = 200;
    private static final AtomicInteger EMPTY_REDRAWS = new AtomicInteger();
    private static volatile boolean seenLookups;

    private ChunkWater() {}

    public static boolean isActive() {
        return active;
    }

    /** Checks once a tick whether chunks should draw simulated water, and redraws them all when that changes */
    static void update() {
        boolean wanted;
        switch(ForgeConfigHandler.client.chunkWater) {
            case ALWAYS: wanted = true; break;
            case NEVER: wanted = false; break;
            // Celeritas draws chunks with its own shader, water drawn separately comes out lighter than its water blocks
            default: wanted = isShaderPackLoaded() || Celeritas.LOADED;
        }
        wanted &= ForgeConfigHandler.client.renderWater && !bypassed;
        if(wanted == active) return;
        active = wanted;
        FluidicWater.simulatedInChunks = wanted;
        WaterRenderer.redrawAll();
        Minecraft mc = Minecraft.getMinecraft();
        if(mc.renderGlobal != null && mc.world != null) mc.renderGlobal.loadRenderers();
    }

    public static void beginRedraw() {
        if(!active) return;
        int[] redraw = REDRAW.get();
        redraw[0] = 1;
        redraw[1] = 0;
    }

    public static void endRedraw() {
        int[] redraw = REDRAW.get();
        if(redraw[0] == 0) return;
        redraw[0] = 0;
        redraw[2] = 0;
        // Empty sections are redrawn without looking at their blocks, so only many redraws that never did tell
        if(redraw[1] > 0) seenLookups = true;
        else if(!seenLookups && !bypassed && EMPTY_REDRAWS.incrementAndGet() > MAX_EMPTY_REDRAWS) {
            bypassed = true;
            Fluidic.LOGGER.warn("Chunks are not drawn through ChunkCache, simulated water is drawn separately instead");
        }
    }

    /** The block a chunk redraw sees: simulated water in an empty block reads as a water block of the same level */
    public static IBlockState substitute(IBlockState state, BlockPos pos) {
        return substitute(state, pos.getX(), pos.getY(), pos.getZ());
    }

    public static IBlockState substitute(IBlockState state, int x, int y, int z) {
        int[] redraw = REDRAW.get();
        if(redraw[0] == 0) return state;
        redraw[1]++;
        if(state.getMaterial() != Material.AIR) return state;
        int height = ClientWaterCache.heightAnyThread(x, y, z);
        if(height == 0) return state;
        return FluidicWater.state(Math.max(1, Math.round(height * Water.LEVELS / 255.0F)));
    }

    /**
     * Whether a block that can hold water gets a turn in the translucent layer during this redraw, where
     * {@link #drawWaterInside} draws the water in it. Simulated water in a block like sugar cane cannot stand in as
     * a water block, the block has to stay, so it is drawn on top of it.
     */
    public static boolean wantsWaterLayer(IBlockState state) {
        int[] redraw = REDRAW.get();
        if(redraw[0] == 0 || state.getMaterial() == Material.AIR || state.getMaterial() == Material.WATER || !Terrain.holdsWater(state)) return false;
        redraw[2] = 1;
        WATER_TURN.set(state);
        return true;
    }

    /** Whether the block about to be drawn in this layer only had its turn for the water inside it */
    public static boolean drawWaterInside(IBlockState state, BlockRenderLayer layer) {
        int[] redraw = REDRAW.get();
        if(redraw[2] == 0) return false;
        redraw[2] = 0;
        return layer == BlockRenderLayer.TRANSLUCENT && WATER_TURN.get() == state;
    }

    /** The water block the simulated water inside a block is drawn as, null when there is none */
    public static IBlockState waterInside(BlockPos pos) {
        int height = ClientWaterCache.heightAnyThread(pos.getX(), pos.getY(), pos.getZ());
        return height == 0 ? null : FluidicWater.state(Math.max(1, Math.round(height * Water.LEVELS / 255.0F)));
    }

    /** Marks the chunks around changed simulated water for redrawing */
    static void redraw(int x0, int y0, int z0, int x1, int y1, int z1) {
        Minecraft mc = Minecraft.getMinecraft();
        if(mc.world != null) mc.world.markBlockRangeForRenderUpdate(x0 - 1, y0 - 1, z0 - 1, x1 + 1, y1 + 1, z1 + 1);
    }

    /**
     * Tags the vertices drawn next as the given block for OptiFine shaders, which tell water by it. Returns whether
     * it did, then {@link #untag} has to be called once they are drawn.
     */
    public static boolean tagAs(IBlockState state, BlockPos pos, IBlockAccess world, BufferBuilder buffer) {
        if(OptiFine.PUSH_ENTITY == null || OptiFine.POP_ENTITY == null || !isOptiFineShaderPackLoaded()) return false;
        try {
            OptiFine.PUSH_ENTITY.invoke(null, state, pos, world, buffer);
            return true;
        }
        catch(ReflectiveOperationException ex) {
            return false;
        }
    }

    public static void untag(BufferBuilder buffer) {
        try {
            OptiFine.POP_ENTITY.invoke(null, buffer);
        }
        catch(ReflectiveOperationException ex) {
            Fluidic.LOGGER.error("Could not untag water vertices for shaders", ex);
        }
    }

    private static boolean isShaderPackLoaded() {
        return isOptiFineShaderPackLoaded() || Celeritas.isShaderPackInUse();
    }

    private static boolean isOptiFineShaderPackLoaded() {
        if(OptiFine.SHADER_PACK_LOADED == null) return false;
        try {
            return OptiFine.SHADER_PACK_LOADED.getBoolean(null);
        }
        catch(IllegalAccessException ex) {
            return false;
        }
    }

    /**
     * Shader packs of Celeritas and its forks like Voxtavium, through their shader API. They draw chunks themselves,
     * fluidic.mixin.celeritas hooks into that instead of ChunkCache.
     */
    private static final class Celeritas {
        static final boolean LOADED = Loader.isModLoaded("celeritas");
        static final Object API = api();
        static final Method IN_USE = API == null ? null : method(API);

        static boolean isShaderPackInUse() {
            if(IN_USE == null) return false;
            try {
                return (Boolean)IN_USE.invoke(API);
            }
            catch(ReflectiveOperationException | RuntimeException ex) {
                return false;
            }
        }

        private static Object api() {
            try {
                return Class.forName("org.taumc.celeritas.api.v0.CeleritasShadersApi").getMethod("getInstance").invoke(null);
            }
            catch(ReflectiveOperationException | Error | RuntimeException ex) {
                return null;
            }
        }

        private static Method method(Object api) {
            try {
                Method method = Class.forName("org.taumc.celeritas.api.v0.CeleritasShadersApi").getMethod("isShaderPackInUse");
                method.setAccessible(true);
                return method;
            }
            catch(ReflectiveOperationException | LinkageError | RuntimeException ex) {
                return null;
            }
        }
    }

    /** OptiFine's shader hooks, looked up once by whichever thread asks first, class initialisation is thread safe */
    private static final class OptiFine {
        static final Method PUSH_ENTITY = method("pushEntity", IBlockState.class, BlockPos.class, IBlockAccess.class, BufferBuilder.class);
        static final Method POP_ENTITY = method("popEntity", BufferBuilder.class);
        static final Field SHADER_PACK_LOADED = shaderPackLoaded();

        private static Method method(String name, Class<?>... parameters) {
            try {
                return Class.forName("net.optifine.shaders.SVertexBuilder").getMethod(name, parameters);
            }
            catch(ReflectiveOperationException | LinkageError ex) {
                return null;
            }
        }

        private static Field shaderPackLoaded() {
            for(String name : new String[] {"net.optifine.shaders.Shaders", "shadersmod.client.Shaders"}) {
                try {
                    return Class.forName(name).getField("shaderPackLoaded");
                }
                catch(ReflectiveOperationException | LinkageError ignored) {}
            }
            return null;
        }
    }
}
