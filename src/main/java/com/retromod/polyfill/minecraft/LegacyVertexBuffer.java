/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.polyfill.minecraft;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Stand-in for the 1.21.4 {@code VertexBuffer}, which 1.21.5 removed together with
 * {@code BufferUsage}, {@code CoreShaders}, and {@code RenderSystem.setShader}.
 *
 * <p>A 1.21.4 overlay mod built a static buffer once, then drew it every frame with its own
 * model-view and projection matrices. 26.x has no immediate draw call for that. This class keeps
 * a copy of the uploaded {@code POSITION_COLOR} geometry and, when the mod draws, turns it into
 * world positions with the camera that frame. The positions are submitted on Fabric API's next
 * {@code LevelRenderEvents.COLLECT_SUBMITS} through {@code SubmitNodeCollector.submitCustomGeometry},
 * using the debug quad render type for quads and the line render type for line lists.
 *
 * <p>The mod's projection matrix and shader are ignored, because the level pipeline supplies its
 * own. Other vertex formats and topologies are dropped with one log line, and so is everything
 * on a host without Fabric API's level render events.
 *
 * <p>Like {@link RetroClientEnv}, it has no compile-time Minecraft dependency and reaches
 * Minecraft through public members only.
 */
public final class LegacyVertexBuffer implements AutoCloseable {

    /** Stand-in for the removed {@code BufferUsage}; the usage hint has no successor. */
    public static final class Usage {
        public static final Usage STATIC_WRITE = new Usage();
        public static final Usage DYNAMIC_WRITE = new Usage();
        public static final Usage STATIC_READ = new Usage();
        public static final Usage DYNAMIC_READ = new Usage();

        private Usage() {}
    }

    /** Stand-in for the removed {@code ShaderProgram}; the level pipeline picks the shader. */
    public static final class Shader {
        private Shader() {}
    }

    /** Stand-in for the removed {@code CoreShaders} constants a 1.21.4 mod passed to setShader. */
    public static final class CoreShaders {
        public static final Shader POSITION = new Shader();
        public static final Shader POSITION_COLOR = new Shader();
        public static final Shader POSITION_TEX = new Shader();
        public static final Shader POSITION_TEX_COLOR = new Shader();
        public static final Shader POSITION_COLOR_LIGHTMAP = new Shader();
        public static final Shader POSITION_COLOR_TEX_LIGHTMAP = new Shader();
        public static final Shader RENDERTYPE_LINES = new Shader();

        private CoreShaders() {}
    }

    private static final int MAX_PENDING_DRAWS = 512;
    private static final float LINE_WIDTH = 2.0F;

    private static final List<Draw> PENDING = new ArrayList<>();
    private static volatile boolean listenerRegistered;
    private static volatile boolean warned;

    private float[] positions = new float[0];
    private int[] colors = new int[0];
    private boolean lines;

    public LegacyVertexBuffer(Usage usage) {}

    public void bind() {}

    public static void unbind() {}

    @Override
    public void close() {
        positions = new float[0];
        colors = new int[0];
    }

    /**
     * Replacement for {@code RenderSystem.getShader()}. It returns {@code null} because the call
     * site casts the result back to the host's program type, which no stand-in can satisfy; the
     * value only ever reaches {@link #drawWithShader}, which ignores it.
     */
    public static Object getShader() {
        return null;
    }

    /** Replacement for {@code RenderSystem.setShader(ShaderProgram)}; see {@link #getShader()}. */
    public static Object setShader(Object program) {
        return null;
    }

    /**
     * Replacement for {@code RenderSystem.getProjectionMatrix()}. {@link #drawWithShader} ignores
     * it, but a mod can also project world positions to the screen with it, so it returns the
     * main camera's projection: its view-rotation-projection with the view rotation undone.
     * Before the camera exists it is the identity.
     */
    public static Object getProjectionMatrix() {
        Object identity;
        try {
            identity = Class.forName("org.joml.Matrix4f").getConstructor().newInstance();
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
        try {
            Class<?> matrixType = identity.getClass();
            Object camera = camera();
            Object projection = call(camera, "getViewRotationProjectionMatrix",
                    matrixType.getConstructor().newInstance());
            Object rotation = call(camera, "getViewRotationMatrix", matrixType.getConstructor().newInstance());
            matrixType.getMethod("invert").invoke(rotation);
            return matrixType.getMethod("mul", Class.forName("org.joml.Matrix4fc")).invoke(projection, rotation);
        } catch (Exception e) {
            return identity;
        }
    }

    /** Copies {@code POSITION_COLOR} geometry out of the mesh, then frees the mesh. */
    public void upload(Object meshData) {
        try {
            Object drawState = call(meshData, "drawState");
            int count = (Integer) call(drawState, "vertexCount");
            Object format = call(drawState, "format");
            int stride = (Integer) call(format, "getVertexSize");
            String topology = String.valueOf(call(drawState, firstMethod(drawState, "primitiveTopology", "mode")));
            ByteBuffer buffer = ((ByteBuffer) call(meshData, "vertexBuffer")).duplicate()
                    .order(ByteOrder.nativeOrder());
            if (stride != 16 || !(topology.equals("QUADS") || topology.contains("LINES"))) {
                warnOnce("Retromod: a legacy vertex buffer used " + topology + " with a " + stride
                        + "-byte vertex; only POSITION_COLOR quads and lines are drawn on this version.");
                positions = new float[0];
                colors = new int[0];
                return;
            }
            lines = topology.contains("LINES");
            float[] xyz = new float[count * 3];
            int[] argb = new int[count];
            int base = buffer.position();
            for (int i = 0; i < count; i++) {
                int at = base + i * stride;
                xyz[i * 3] = buffer.getFloat(at);
                xyz[i * 3 + 1] = buffer.getFloat(at + 4);
                xyz[i * 3 + 2] = buffer.getFloat(at + 8);
                int r = buffer.get(at + 12) & 0xFF;
                int g = buffer.get(at + 13) & 0xFF;
                int b = buffer.get(at + 14) & 0xFF;
                int a = buffer.get(at + 15) & 0xFF;
                argb[i] = (a << 24) | (r << 16) | (g << 8) | b;
            }
            positions = xyz;
            colors = argb;
        } catch (Throwable t) {
            warnOnce("Retromod: could not copy a legacy vertex buffer (" + t + ").");
        } finally {
            try {
                call(meshData, "close");
            } catch (Throwable ignored) {
                // The mesh owns native memory; a failed close only leaks one buffer.
            }
        }
    }

    /**
     * Places the buffer in the world for this frame. The mod's model-view matrix carries the
     * camera rotation and the camera-relative offset; undoing the rotation with the live camera
     * leaves the offset, and adding the camera position gives fixed world coordinates.
     */
    public void drawWithShader(Object modelView, Object projection, Object shader) {
        if (positions.length == 0 || !ensureListener()) return;
        try {
            Object camera = camera();
            float[] view = new float[16];
            call(modelView, "get", (Object) view);
            float[] toCameraSpace = multiply(rotationMatrix(call(camera, "rotation")), view);
            double[] eye = position(camera);
            double[] world = new double[positions.length];
            for (int i = 0; i < positions.length; i += 3) {
                float x = positions[i];
                float y = positions[i + 1];
                float z = positions[i + 2];
                world[i] = toCameraSpace[0] * x + toCameraSpace[4] * y + toCameraSpace[8] * z
                        + toCameraSpace[12] + eye[0];
                world[i + 1] = toCameraSpace[1] * x + toCameraSpace[5] * y + toCameraSpace[9] * z
                        + toCameraSpace[13] + eye[1];
                world[i + 2] = toCameraSpace[2] * x + toCameraSpace[6] * y + toCameraSpace[10] * z
                        + toCameraSpace[14] + eye[2];
            }
            synchronized (PENDING) {
                if (PENDING.size() < MAX_PENDING_DRAWS) PENDING.add(new Draw(world, colors, lines));
            }
        } catch (Throwable t) {
            warnOnce("Retromod: could not place a legacy vertex buffer (" + t + ").");
        }
    }

    // ---- frame submission ------------------------------------------------------------------

    private record Draw(double[] world, int[] colors, boolean lines) {}

    private static boolean ensureListener() {
        if (listenerRegistered) return true;
        synchronized (PENDING) {
            if (listenerRegistered) return true;
            try {
                ClassLoader loader = LegacyVertexBuffer.class.getClassLoader();
                Class<?> events = Class.forName(
                        "net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents", false, loader);
                Class<?> callback = Class.forName(
                        "net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents$CollectSubmits",
                        false, loader);
                Object event = events.getField("COLLECT_SUBMITS").get(null);
                Object listener = Proxy.newProxyInstance(loader, new Class<?>[]{callback},
                        (proxy, method, args) -> {
                            if (method.getName().equals("collectSubmits")) {
                                flush(args[0]);
                                return null;
                            }
                            return objectMethod(proxy, method, args);
                        });
                call(event, "register", listener);
                listenerRegistered = true;
            } catch (Throwable t) {
                warnOnce("Retromod: legacy vertex buffers cannot be drawn without Fabric API's "
                        + "level render events (" + t + ").");
            }
            return listenerRegistered;
        }
    }

    private static void flush(Object context) {
        List<Draw> draws;
        synchronized (PENDING) {
            if (PENDING.isEmpty()) return;
            draws = new ArrayList<>(PENDING);
            PENDING.clear();
        }
        try {
            Object collector = call(context, "submitNodeCollector");
            Object poseStack = call(context, "poseStack");
            double[] eye = position(camera());
            ClassLoader loader = LegacyVertexBuffer.class.getClassLoader();
            Class<?> renderTypes = Class.forName("net.minecraft.client.renderer.rendertype.RenderTypes", false, loader);
            Class<?> geometry = Class.forName(
                    "net.minecraft.client.renderer.SubmitNodeCollector$CustomGeometryRenderer", false, loader);
            Object quadType = renderTypes.getMethod("debugQuads").invoke(null);
            Object lineType = renderTypes.getMethod("lines").invoke(null);
            for (Draw draw : draws) {
                Object renderer = Proxy.newProxyInstance(loader, new Class<?>[]{geometry},
                        (proxy, method, args) -> {
                            if (method.getName().equals("render")) {
                                emit(draw, eye, args[0], args[1]);
                                return null;
                            }
                            return objectMethod(proxy, method, args);
                        });
                call(collector, "submitCustomGeometry", poseStack,
                        draw.lines() ? lineType : quadType, renderer);
            }
        } catch (Throwable t) {
            warnOnce("Retromod: could not submit legacy vertex buffers (" + t + ").");
        }
    }

    private static void emit(Draw draw, double[] eye, Object pose, Object consumer) throws Exception {
        Method addVertex = null;
        Method setColor = null;
        Method setNormal = null;
        Method setLineWidth = null;
        for (Method method : consumer.getClass().getMethods()) {
            Class<?>[] params = method.getParameterTypes();
            if (method.getName().equals("addVertex") && params.length == 4 && params[1] == float.class
                    && !params[0].isPrimitive() && params[0].isInstance(pose)) {
                addVertex = method;
            } else if (method.getName().equals("setColor") && params.length == 1 && params[0] == int.class) {
                setColor = method;
            } else if (method.getName().equals("setNormal") && params.length == 4 && params[1] == float.class) {
                setNormal = method;
            } else if (method.getName().equals("setLineWidth") && params.length == 1) {
                setLineWidth = method;
            }
        }
        if (addVertex == null || setColor == null) return;
        double[] world = draw.world();
        int[] colors = draw.colors();
        for (int v = 0; v < colors.length; v++) {
            float x = (float) (world[v * 3] - eye[0]);
            float y = (float) (world[v * 3 + 1] - eye[1]);
            float z = (float) (world[v * 3 + 2] - eye[2]);
            Object vertex = addVertex.invoke(consumer, pose, x, y, z);
            setColor.invoke(vertex, colors[v]);
            if (draw.lines() && setNormal != null && setLineWidth != null) {
                // A line vertex needs the segment direction as its normal.
                int other = (v % 2 == 0) ? v + 1 : v - 1;
                if (other >= colors.length) other = v;
                float dx = (float) (world[other * 3] - world[v * 3]) * (v % 2 == 0 ? 1 : -1);
                float dy = (float) (world[other * 3 + 1] - world[v * 3 + 1]) * (v % 2 == 0 ? 1 : -1);
                float dz = (float) (world[other * 3 + 2] - world[v * 3 + 2]) * (v % 2 == 0 ? 1 : -1);
                float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (length > 0) {
                    dx /= length;
                    dy /= length;
                    dz /= length;
                }
                setNormal.invoke(vertex, pose, dx, dy, dz);
                setLineWidth.invoke(vertex, LINE_WIDTH);
            }
        }
    }

    // ---- camera and matrix helpers ---------------------------------------------------------

    private static Object camera() throws Exception {
        Class<?> minecraft = Class.forName("net.minecraft.client.Minecraft", false,
                LegacyVertexBuffer.class.getClassLoader());
        Object client = minecraft.getMethod("getInstance").invoke(null);
        Object renderer = minecraft.getField("gameRenderer").get(client);
        return call(renderer, firstMethod(renderer, "mainCamera", "getMainCamera"));
    }

    private static double[] position(Object camera) throws Exception {
        Object vec = call(camera, "position");
        Class<?> type = vec.getClass();
        return new double[]{type.getField("x").getDouble(vec), type.getField("y").getDouble(vec),
                type.getField("z").getDouble(vec)};
    }

    /** Column-major rotation matrix of a JOML quaternion, read through its public fields. */
    static float[] rotationMatrix(Object quaternion) throws Exception {
        Class<?> type = quaternion.getClass();
        float x = type.getField("x").getFloat(quaternion);
        float y = type.getField("y").getFloat(quaternion);
        float z = type.getField("z").getFloat(quaternion);
        float w = type.getField("w").getFloat(quaternion);
        return rotationMatrix(x, y, z, w);
    }

    static float[] rotationMatrix(float x, float y, float z, float w) {
        return new float[]{
                1 - 2 * (y * y + z * z), 2 * (x * y + z * w), 2 * (x * z - y * w), 0,
                2 * (x * y - z * w), 1 - 2 * (x * x + z * z), 2 * (y * z + x * w), 0,
                2 * (x * z + y * w), 2 * (y * z - x * w), 1 - 2 * (x * x + y * y), 0,
                0, 0, 0, 1};
    }

    /** Column-major {@code a * b}. */
    static float[] multiply(float[] a, float[] b) {
        float[] out = new float[16];
        for (int column = 0; column < 4; column++) {
            for (int row = 0; row < 4; row++) {
                float sum = 0;
                for (int k = 0; k < 4; k++) sum += a[k * 4 + row] * b[column * 4 + k];
                out[column * 4 + row] = sum;
            }
        }
        return out;
    }

    // ---- reflection ------------------------------------------------------------------------

    private static String firstMethod(Object target, String... names) {
        for (String name : names) {
            for (Method method : target.getClass().getMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == 0) return name;
            }
        }
        return names[0];
    }

    private static Object call(Object target, String name, Object... args) throws Exception {
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != args.length) continue;
            Class<?>[] params = method.getParameterTypes();
            boolean fits = true;
            for (int i = 0; i < params.length && fits; i++) {
                fits = args[i] == null || params[i].isPrimitive() || params[i].isInstance(args[i]);
            }
            if (fits) return method.invoke(target, args);
        }
        throw new NoSuchMethodException(target.getClass().getName() + "." + name);
    }

    private static Object objectMethod(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            case "toString" -> "RetromodLegacyVertexBuffer";
            default -> null;
        };
    }

    private static void warnOnce(String message) {
        if (warned) return;
        warned = true;
        System.err.println(message);
    }
}
