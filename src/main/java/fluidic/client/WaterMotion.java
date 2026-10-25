package fluidic.client;

import fluidic.handlers.ForgeConfigHandler;
import fluidic.sim.Water;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GLAllocation;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.renderer.vertex.VertexFormatElement;
import org.lwjgl.opengl.GL11;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * Smooths simulated water between the heights the server sends. Each update moves the water a step at once, at most 20
 * times a second and not evenly spaced, which looks like a low frame rate. When a section's mesh is rebuilt, each vertex
 * also gets its height in the water as shown at that moment, and the frames after move it on to its new height over
 * about the time between updates of the section. Only the heights are written each frame, the mesh is not rebuilt.
 * Client thread only, apart from {@link #previous}.
 */
final class WaterMotion {

    /** Shortest and longest time the water takes to reach the heights sent, in nanoseconds */
    private static final long MIN_NANOS = 50_000_000L;
    private static final long MAX_NANOS = 500_000_000L;

    private static final Long2ObjectOpenHashMap<Motion> SECTIONS = new Long2ObjectOpenHashMap<>();
    /** Set while a cell is built again as the water was shown, height lookups on the client thread then read it */
    static boolean previous;
    /** Time of the frame being drawn */
    private static long now = System.nanoTime();
    /** Heights of the vertices of a cell built again as the water was shown */
    private static final FloatArrayList CAPTURED = new FloatArrayList();
    private static boolean capturing;
    /** Shown heights by vertex of the mesh being built, NaN where a vertex does not move */
    private static final FloatArrayList FROM = new FloatArrayList();
    private static boolean moving;
    private static long lastKey = Long.MIN_VALUE;
    private static Motion last;

    private WaterMotion() {}

    private static final class Motion {
        /** Heights moved from and to, from is null when at rest */
        byte[] from, to;
        long start, duration;
        /** When the last update arrived, and the time between updates, smoothed */
        long arrived;
        long interval = MIN_NANOS;
        /** While moving: the mesh, its vertices written at their heights this frame, and their heights both ways */
        ByteBuffer vertices;
        int count, stride, yOffset;
        float[] fromY, toY;
    }

    /** A frame starts, the water moving is shown as it is at this time */
    static void frame() {
        now = System.nanoTime();
    }

    /** New heights of a section arrived, the time since the last ones is how long the water takes to reach them */
    static void arrived(long key, boolean empty) {
        long time = System.nanoTime();
        Motion motion = SECTIONS.get(key);
        if(motion == null) {
            if(empty) return;
            motion = new Motion();
            SECTIONS.put(key, motion);
            forget();
        }
        else {
            long gap = Math.max(MIN_NANOS, Math.min(MAX_NANOS, time - motion.arrived));
            // Packets arriving in bunches would cut it short, smoothed it stays near the rate they are sent at
            motion.interval = (motion.interval * 3 + gap) / 4;
        }
        motion.arrived = time;
    }

    static void unload(long key) {
        SECTIONS.remove(key);
        forget();
    }

    static void clear() {
        SECTIONS.clear();
        forget();
    }

    private static void forget() {
        lastKey = Long.MIN_VALUE;
        last = null;
    }

    private static Motion motion(long key) {
        if(key != lastKey) {
            lastKey = key;
            last = SECTIONS.get(key);
        }
        return last;
    }

    private static float progress(Motion motion) {
        return motion.from == null || motion.duration <= 0 ? 1.0F : Math.min(1.0F, (float)(now - motion.start) / motion.duration);
    }

    /** Height 0-255 of a cell as its section's mesh shows it this frame */
    private static int shown(Motion motion, int i) {
        if(motion == null || motion.to == null) return 0;
        int to = motion.to[i] & 255;
        float t = progress(motion);
        if(t >= 1.0F) return to;
        int from = motion.from[i] & 255;
        return Math.round(from + (to - from) * t);
    }

    /** Height 0-255 of the water at a block position as shown this frame */
    static int height(int x, int y, int z) {
        if(y < 0 || y > 255) return 0;
        return shown(motion(Water.sectionKey(x >> 4, y >> 4, z >> 4)), Water.cellIndex(x & 15, y & 15, z & 15));
    }

    /** Whether the water as shown differs from the water sent around a cell, as far as its faces look */
    static boolean changedAround(int x, int y, int z) {
        for(int dy = -1; dy <= 1; dy++) {
            for(int dx = -1; dx <= 1; dx++) {
                for(int dz = -1; dz <= 1; dz++) {
                    if(height(x + dx, y + dy, z + dz) != ClientWaterCache.height(x + dx, y + dy, z + dz)) return true;
                }
            }
        }
        return false;
    }

    /** Whether lookups now are on the client thread, chunk render threads always read the heights sent */
    static boolean onClientThread() {
        return Minecraft.getMinecraft().isCallingFromMinecraftThread();
    }

    // ---------------------------------------------------------------- building

    /**
     * A section's mesh is rebuilt, returns whether its vertices move on from where the water is shown. A section
     * drawn for the first time shows its heights at once.
     */
    static boolean begin(long key) {
        FROM.clear();
        Motion motion = SECTIONS.get(key);
        moving = ForgeConfigHandler.client.smoothWater && motion != null && motion.to != null;
        return moving;
    }

    /** Vertices of a cell were added that do not move */
    static void still(int vertices) {
        for(int n = 0; n < vertices; n++) FROM.add(Float.NaN);
    }

    /** Vertices of a cell that had no water shown, they rise from the floor of its block */
    static void rise(BufferBuilder buffer, int first, int vertices, int ly) {
        VertexFormat format = buffer.getVertexFormat();
        int stride = format.getSize(), offset = format.getOffset(0) + 4;
        ByteBuffer bytes = buffer.getByteBuffer();
        for(int n = 0; n < vertices; n++) {
            float y = bytes.getFloat((first + n) * stride + offset);
            FROM.add(y > ly ? ly : Float.NaN);
        }
    }

    /** Starts building a cell again as the water is shown, its vertices go to {@link #captured} instead of the mesh */
    static void capture() {
        CAPTURED.clear();
        capturing = true;
        previous = true;
    }

    static boolean capturing() {
        return capturing;
    }

    static void captured(float y) {
        CAPTURED.add(y);
    }

    /**
     * Ends building a cell as the water is shown. Built with the same faces both ways, each vertex moves from one height
     * to the other. A cell whose faces changed shows its new ones at once.
     */
    static void endCapture(BufferBuilder buffer, int first, int vertices) {
        capturing = false;
        previous = false;
        if(CAPTURED.size() != vertices) {
            still(vertices);
            return;
        }
        VertexFormat format = buffer.getVertexFormat();
        int stride = format.getSize(), offset = format.getOffset(0) + 4;
        ByteBuffer bytes = buffer.getByteBuffer();
        for(int n = 0; n < vertices; n++) {
            float y = bytes.getFloat((first + n) * stride + offset), from = CAPTURED.getFloat(n);
            FROM.add(from == y ? Float.NaN : from);
        }
    }

    /**
     * A section's mesh was built from these heights, null when it has no water, moving on from where the water is
     * shown over the given time. The buffer is finished.
     */
    static void built(long key, byte[] heights, BufferBuilder buffer, long duration) {
        capturing = previous = false;
        if(heights == null) {
            unload(key);
            return;
        }
        Motion motion = SECTIONS.get(key);
        if(motion == null) {
            motion = new Motion();
            motion.arrived = now;
            SECTIONS.put(key, motion);
            forget();
        }
        byte[] from = null;
        if(moving) {
            from = new byte[4096];
            for(int i = 0; i < 4096; i++) from[i] = (byte)shown(motion, i);
        }
        motion.from = from;
        motion.to = heights;
        motion.start = now;
        motion.duration = duration;
        motion.vertices = null;
        motion.fromY = motion.toY = null;
        if(!moving) return;

        int count = Math.min(buffer.getVertexCount(), FROM.size());
        VertexFormat format = buffer.getVertexFormat();
        int stride = format.getSize(), offset = format.getOffset(0) + 4;
        ByteBuffer source = buffer.getByteBuffer();
        float[] fromY = null, toY = null;
        for(int n = 0; n < count; n++) {
            float f = FROM.getFloat(n);
            if(Float.isNaN(f)) continue;
            if(fromY == null) {
                fromY = new float[count];
                toY = new float[count];
                for(int m = 0; m < count; m++) fromY[m] = toY[m] = source.getFloat(m * stride + offset);
            }
            fromY[n] = f;
        }
        if(fromY == null) return;
        ByteBuffer copy = GLAllocation.createDirectByteBuffer(count * stride);
        ByteBuffer view = source.duplicate();
        view.position(0);
        view.limit(count * stride);
        copy.put(view);
        copy.flip();
        motion.vertices = copy;
        motion.count = count;
        motion.stride = stride;
        motion.yOffset = offset;
        motion.fromY = fromY;
        motion.toY = toY;
    }

    /** How long a section's water takes to reach new heights, in nanoseconds */
    static long duration(long key) {
        Motion motion = SECTIONS.get(key);
        return motion == null ? MIN_NANOS : motion.interval;
    }

    // ---------------------------------------------------------------- drawing

    /** Draws a section's mesh while it is moving, false when it is at rest and its display list draws it */
    static boolean draw(long key, VertexFormat format) {
        Motion motion = motion(key);
        if(motion == null || motion.vertices == null) return false;
        float t = progress(motion);
        if(t >= 1.0F) {
            motion.vertices = null;
            motion.fromY = motion.toY = null;
            return false;
        }
        // Linear, water flowing on steadily keeps its speed from one update to the next
        ByteBuffer vertices = motion.vertices;
        float[] fromY = motion.fromY, toY = motion.toY;
        for(int n = 0; n < motion.count; n++) {
            float from = fromY[n], to = toY[n];
            if(from != to) vertices.putFloat(n * motion.stride + motion.yOffset, from + (to - from) * t);
        }
        List<VertexFormatElement> elements = format.getElements();
        for(int j = 0; j < elements.size(); j++) {
            vertices.position(format.getOffset(j));
            elements.get(j).getUsage().preDraw(format, j, motion.stride, vertices);
        }
        GlStateManager.glDrawArrays(GL11.GL_QUADS, 0, motion.count);
        for(int j = 0; j < elements.size(); j++) elements.get(j).getUsage().postDraw(format, j, motion.stride, vertices);
        vertices.position(0);
        return true;
    }
}
