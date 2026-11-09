/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.retromod.legacyapi.forge.LegacySharedSlots;
import com.retromod.shim.common.embedded.LegacyLog;
import com.retromod.shim.common.embedded.MultiBufferSource;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;

/** Records what an old renderer draws, per render type, and the items it submits, for the frame to draw once it ends. */
final class LegacyItemGeometry implements Consumer<Consumer<SubmitNodeCollector>> {

    // Items are drawn on the render thread one after another, so one recorder and its pose stack serve every draw.
    private static final LegacyItemGeometry SHARED = new LegacyItemGeometry();
    // The vertices hand themselves back once the frame has drawn them; past this many, spares are left to the collector.
    private static final int MAX_POOLED = 64;
    // A pooled buffer that grew past this many vertices for one large model goes back to its starting size.
    private static final int MAX_KEPT_VERTICES = 4096;
    private static final ArrayDeque<Vertices> POOL = new ArrayDeque<>();
    private static final VertexConsumer DISCARD = new Discard();
    // The host keeps the pose it is handed, and the vertices already carry the renderer's own.
    private static final PoseStack IDENTITY = new PoseStack();
    // Minecraft 26.3 added a UV3 element, which its glint render types read; 26.2 has no such member.
    private static final MethodHandle SET_UV3 = setUv3();
    // Draws in progress in every jar's copy, as {buffer source, sink for deferred submits, camera}, so an old call that
    // hands back a draw's buffer source finds the draw, whichever copy made it.
    // Concurrent, so another thread reading the shared slot can never break a draw in progress.
    @SuppressWarnings("unchecked")
    private static final ConcurrentLinkedDeque<Object[]> OPEN = LegacySharedSlots.get("legacy-draws", 1, ConcurrentLinkedDeque.class,
            ConcurrentLinkedDeque::new);
    // How deep draws nest across copies; an item renderer that draws its own item would otherwise never stop.
    private static final AtomicInteger DEPTH = LegacySharedSlots.get("legacy-draw-depth", 1, AtomicInteger.class, AtomicInteger::new);
    private static final int MAX_DEPTH = 8;

    private final List<RenderType> types = new ArrayList<>(4);
    private final List<Writer> writers = new ArrayList<>(4);
    private final PoseStack pose = new PoseStack();
    // Items and entities the renderer drew, submitted only once it returns, so one that throws leaves nothing behind.
    private final List<Consumer<SubmitNodeCollector>> deferred = new ArrayList<>(4);
    private final Object[] link = new Object[3];
    private Buffers buffers;
    private int light;
    private int overlay;
    private boolean busy;

    private LegacyItemGeometry() {}

    /** The recorder for one draw, with its pose set to the host's: the shared one, or a new one inside another draw. */
    static LegacyItemGeometry begin(PoseStack host, int light, int overlay) {
        return begin(host, light, overlay, null);
    }

    /** The same, for a draw that knows the frame's camera, which an old call drawing an entity needs. */
    static LegacyItemGeometry begin(PoseStack host, int light, int overlay, CameraRenderState camera) {
        LegacyItemGeometry geometry;
        synchronized (LegacyItemGeometry.class) {
            geometry = SHARED.busy ? new LegacyItemGeometry() : SHARED;
            geometry.busy = true;
        }
        // Nothing from an earlier draw is carried into this one.
        geometry.drop();
        geometry.buffers = new Buffers(geometry);
        geometry.light = light;
        geometry.overlay = overlay;
        // A renderer that threw last time may have left poses pushed.
        while (!geometry.pose.isEmpty()) geometry.pose.popPose();
        geometry.pose.last().set(host.last());
        // Open last, so a draw is only ever listed once it is ready to take writes.
        geometry.link[0] = geometry.buffers;
        geometry.link[1] = geometry;
        geometry.link[2] = camera;
        OPEN.push(geometry.link);
        DEPTH.incrementAndGet();
        return geometry;
    }

    /** The sink for submits made with {@code buffers} while its draw is open, or null when no open draw handed them out. */
    @SuppressWarnings("unchecked")
    static Consumer<Consumer<SubmitNodeCollector>> sinkFor(Object buffers) {
        Object[] link = linkFor(buffers);
        return link == null ? null : (Consumer<Consumer<SubmitNodeCollector>>) link[1];
    }

    /** The frame's camera for the open draw that handed out {@code buffers}, or null when that draw has none. */
    static CameraRenderState cameraFor(Object buffers) {
        Object[] link = linkFor(buffers);
        return link == null ? null : (CameraRenderState) link[2];
    }

    private static Object[] linkFor(Object buffers) {
        if (buffers == null) return null;
        for (Object[] link : OPEN) {
            if (link[0] == buffers) return link;
        }
        return null;
    }

    /** Keeps a submit for when the renderer returns: run with the frame's collector then, or dropped if it threw. */
    @Override
    public void accept(Consumer<SubmitNodeCollector> submit) {
        if (buffers != null && buffers.owner == this) deferred.add(submit);
    }

    /** The pose the renderer draws with, a copy of the host's, so a renderer that throws cannot unbalance the host's stack. */
    PoseStack pose() {
        return pose;
    }

    /** This draw's buffer source, which the old renderer is handed and which stops recording when the draw ends. */
    MultiBufferSource buffers() {
        return buffers;
    }

    /** A buffer that keeps nothing written to it, for a renderer writing outside any draw. */
    static VertexConsumer discard() {
        return DISCARD;
    }

    // The writer for one render type in this draw, made the first time the renderer asks for that type.
    private VertexConsumer buffer(RenderType type) {
        int known = types.indexOf(type);
        if (known >= 0) return writers.get(known);
        Writer writer = new Writer(Vertices.obtain(light, overlay));
        types.add(type);
        writers.add(writer);
        return writer;
    }

    /** Submits each render type's vertices when {@code collector} is given, drops them otherwise, and ends the draw. */
    void finish(SubmitNodeCollector collector) {
        try {
            // A renderer that kept the buffer source or a buffer past its draw now writes into nothing.
            buffers.owner = null;
            unlink();
            for (int i = 0; i < types.size(); i++) {
                Vertices recorded = writers.get(i).close();
                if (collector != null && recorded.count > 0) {
                    collector.submitCustomGeometry(IDENTITY, types.get(i), recorded);
                } else {
                    recorded.release();
                }
            }
            if (collector != null && !deferred.isEmpty()) submitDeferred(collector);
        } finally {
            types.clear();
            writers.clear();
            deferred.clear();
            DEPTH.decrementAndGet();
            synchronized (LegacyItemGeometry.class) {
                busy = false;
            }
        }
    }

    // Runs the items and entities the renderer drew; a nested draw for one of them sees this draw still counted.
    private void submitDeferred(SubmitNodeCollector collector) {
        if (DEPTH.get() > MAX_DEPTH) {
            LegacyLog.warnOnce("legacy draw depth", "An old renderer drew an item whose own renderer drew it again, " + MAX_DEPTH
                    + " draws deep, so Retromod stopped there");
            return;
        }
        try {
            for (Consumer<SubmitNodeCollector> submit : deferred) submit.accept(collector);
        } catch (RuntimeException | LinkageError failure) {
            LegacyLog.warnOnce("legacy deferred submit " + failure.getClass().getName(), "Minecraft could not take an item or entity"
                    + " an old renderer drew, so Retromod skipped the rest of that draw: " + failure);
        }
    }

    // Takes this draw off the list of open draws; draws end in the reverse order they began.
    private void unlink() {
        OPEN.removeFirstOccurrence(link);
        link[0] = null;
        link[1] = null;
        link[2] = null;
    }

    // Closes and returns whatever an unfinished earlier draw left in this recorder.
    private void drop() {
        if (buffers != null) buffers.owner = null;
        deferred.clear();
        // A draw nobody finished still counts as open; it ends here instead.
        if (link[1] != null) {
            unlink();
            DEPTH.decrementAndGet();
        }
        for (Writer writer : writers) {
            Vertices left = writer.close();
            if (left != null) left.release();
        }
        types.clear();
        writers.clear();
    }

    private static MethodHandle setUv3() {
        try {
            return MethodHandles.publicLookup().findVirtual(VertexConsumer.class, "setUv3",
                    MethodType.methodType(VertexConsumer.class, float.class, float.class));
        } catch (ReflectiveOperationException | RuntimeException absent) {
            return null;
        }
    }

    /** One render type's vertices, recorded as the renderer writes them and written again into the frame's buffer. */
    static final class Vertices implements VertexConsumer, SubmitNodeCollector.CustomGeometryRenderer {
        // Per vertex: x, y, z, u, v, normal x, y, z and line width; then color, overlay and light.
        private static final int FLOATS = 9;
        private static final int INTS = 3;
        private static final int NO_VERTEX = -1;

        private int defaultLight;
        private int defaultOverlay;
        private float[] floats = new float[FLOATS * 24];
        private int[] ints = new int[INTS * 24];
        private int count;
        private int current = NO_VERTEX;

        private Vertices() {}

        static Vertices obtain(int light, int overlay) {
            Vertices vertices;
            synchronized (POOL) {
                vertices = POOL.poll();
            }
            if (vertices == null) vertices = new Vertices();
            vertices.count = 0;
            vertices.current = NO_VERTEX;
            vertices.defaultLight = light;
            vertices.defaultOverlay = overlay;
            return vertices;
        }

        void release() {
            count = 0;
            current = NO_VERTEX;
            if (floats.length > FLOATS * MAX_KEPT_VERTICES) {
                floats = new float[FLOATS * 24];
                ints = new int[INTS * 24];
            }
            synchronized (POOL) {
                if (POOL.size() < MAX_POOLED) POOL.push(this);
            }
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            if (count * FLOATS == floats.length) {
                floats = Arrays.copyOf(floats, floats.length * 2);
                ints = Arrays.copyOf(ints, ints.length * 2);
            }
            current = count++;
            // What a renderer leaves unset keeps the draw's own light and overlay, white, an upward normal and a thin line.
            int f = current * FLOATS;
            floats[f] = x;
            floats[f + 1] = y;
            floats[f + 2] = z;
            floats[f + 3] = 0f;
            floats[f + 4] = 0f;
            floats[f + 5] = 0f;
            floats[f + 6] = 1f;
            floats[f + 7] = 0f;
            floats[f + 8] = 1f;
            ints[current * INTS] = -1;
            ints[current * INTS + 1] = defaultOverlay;
            ints[current * INTS + 2] = defaultLight;
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            return setColor((alpha & 0xFF) << 24 | (red & 0xFF) << 16 | (green & 0xFF) << 8 | (blue & 0xFF));
        }

        @Override
        public VertexConsumer setColor(int argb) {
            if (current != NO_VERTEX) ints[current * INTS] = argb;
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            if (current != NO_VERTEX) {
                floats[current * FLOATS + 3] = u;
                floats[current * FLOATS + 4] = v;
            }
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            if (current != NO_VERTEX) ints[current * INTS + 1] = packed(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            if (current != NO_VERTEX) ints[current * INTS + 2] = packed(u, v);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            if (current != NO_VERTEX) {
                floats[current * FLOATS + 5] = x;
                floats[current * FLOATS + 6] = y;
                floats[current * FLOATS + 7] = z;
            }
            return this;
        }

        @Override
        public VertexConsumer setLineWidth(float width) {
            if (current != NO_VERTEX) floats[current * FLOATS + 8] = width;
            return this;
        }

        // Minecraft 26.3 made this abstract; no old renderer writes it, and the replay gives it a default.
        public VertexConsumer setUv3(float u, float v) {
            return this;
        }

        /** Writes every recorded vertex with every element the host knows; its buffer keeps those its format has. */
        @Override
        public void render(PoseStack.Pose pose, VertexConsumer out) {
            for (int vertex = 0; vertex < count; vertex++) {
                int f = vertex * FLOATS;
                int i = vertex * INTS;
                out.addVertex(floats[f], floats[f + 1], floats[f + 2]);
                out.setColor(ints[i]);
                out.setUv(floats[f + 3], floats[f + 4]);
                out.setUv1(ints[i + 1] & 0xFFFF, ints[i + 1] >>> 16);
                out.setUv2(ints[i + 2] & 0xFFFF, ints[i + 2] >>> 16);
                out.setNormal(floats[f + 5], floats[f + 6], floats[f + 7]);
                out.setLineWidth(floats[f + 8]);
                // A 26.3 glint format's buffer would refuse a vertex left without UV3.
                if (SET_UV3 != null) setUv3(out);
            }
            // The host's custom geometry renderer draws each submitted piece once a frame, so the buffers go back now.
            release();
        }

        private static void setUv3(VertexConsumer out) {
            try {
                VertexConsumer ignored = (VertexConsumer) SET_UV3.invokeExact(out, 0f, 0f);
            } catch (RuntimeException | Error failure) {
                throw failure;
            } catch (Throwable unexpected) {
                throw new IllegalStateException(unexpected);
            }
        }

        private static int packed(int u, int v) {
            return (u & 0xFFFF) | (v & 0xFFFF) << 16;
        }
    }

    /** One draw's buffer source; once the draw ends it answers a buffer that keeps nothing. */
    private static final class Buffers implements MultiBufferSource {
        private LegacyItemGeometry owner;

        Buffers(LegacyItemGeometry owner) {
            this.owner = owner;
        }

        @Override
        public VertexConsumer getBuffer(RenderType type) {
            LegacyItemGeometry draw = owner;
            if (draw != null) return draw.buffer(type);
            LegacyLog.warnOnce("stale item renderer buffer source", "An old item renderer asked for a buffer after its draw had"
                    + " ended, so Retromod dropped what it wrote");
            return DISCARD;
        }
    }

    /** One draw's buffer for one render type; once the draw ends what is written to it goes nowhere. */
    private static final class Writer implements VertexConsumer {
        private Vertices target;

        Writer(Vertices target) {
            this.target = target;
        }

        Vertices close() {
            Vertices closed = target;
            target = null;
            return closed;
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            if (target != null) target.addVertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            if (target != null) target.setColor(red, green, blue, alpha);
            return this;
        }

        @Override
        public VertexConsumer setColor(int argb) {
            if (target != null) target.setColor(argb);
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            if (target != null) target.setUv(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            if (target != null) target.setUv1(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            if (target != null) target.setUv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            if (target != null) target.setNormal(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setLineWidth(float width) {
            if (target != null) target.setLineWidth(width);
            return this;
        }

        // Abstract on Minecraft 26.3; no old renderer writes it.
        public VertexConsumer setUv3(float u, float v) {
            return this;
        }
    }

    /** A buffer that keeps nothing, for writes that belong to no draw. */
    private static final class Discard implements VertexConsumer {
        @Override public VertexConsumer addVertex(float x, float y, float z) { return this; }
        @Override public VertexConsumer setColor(int red, int green, int blue, int alpha) { return this; }
        @Override public VertexConsumer setColor(int argb) { return this; }
        @Override public VertexConsumer setUv(float u, float v) { return this; }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
        @Override public VertexConsumer setLineWidth(float width) { return this; }
        public VertexConsumer setUv3(float u, float v) { return this; }
    }
}
