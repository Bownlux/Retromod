/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Old VertexConsumer calls write, through the 1.21 builder, the same vertex data 1.20.1's builder chain wrote. */
class LegacyVertexApiBridgeTest {

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("Each old call writes the vertex 1.20.1 wrote: the chain, the whole-vertex call, matrices, a fixed color and BufferBuilder")
    void oldCallsWriteTheSameVertices(@TempDir Path dir) throws Throwable {
        Worlds worlds = Worlds.build(dir);
        for (String draw : List.of("chain", "cube", "matrix", "fixed")) {
            List<String> expected = worlds.oldRecord(draw, "com.mojang.blaze3d.vertex.Recorder");
            assertFalse(expected.isEmpty(), draw + " must draw something in 1.20.1");
            assertEquals(expected, worlds.newRecord(draw, "com.mojang.blaze3d.vertex.Recorder"), draw);
        }
        assertEquals(worlds.oldRecord("builder", "com.mojang.blaze3d.vertex.BufferBuilder"),
                worlds.newRecord("builder", "com.mojang.blaze3d.vertex.BufferBuilder"), "a call named on BufferBuilder");
    }

    @Test
    @DisplayName("A mod's own old wrapper still tints what old code draws through it, with each vertex ended once")
    void oldWrapperKeepsItsWork(@TempDir Path dir) throws Throwable {
        Worlds worlds = Worlds.build(dir);
        List<String> expected = worlds.oldRecord("tinted", "com.mojang.blaze3d.vertex.Recorder");
        assertEquals(7, expected.size(), "three chained vertices and a four-corner face");
        assertEquals(expected, worlds.newRecord("tinted", "com.mojang.blaze3d.vertex.Recorder"));
        byte[] tint = worlds.transformed.get("mod/Tint");
        assertDoesNotThrow(() -> new ClassReader(tint).accept(new CheckClassAdapter(new ClassWriter(0), true), 0));
        ClassNode node = new ClassNode();
        new ClassReader(tint).accept(node, 0);
        assertTrue(node.interfaces.contains(LegacyVertexApiBridge.HELPER + "$OldConsumer"), node.interfaces.toString());
    }

    @Test
    @DisplayName("A mod's own old collector fed by 1.21 host code gets each whole vertex ended, and a single-setter vertex when the next starts")
    void hostCodeReachesAnOldCollector(@TempDir Path dir) throws Throwable {
        Worlds worlds = Worlds.build(dir);
        BridgeHarness.Run run = new BridgeHarness.Run(worlds.newHost, worlds.transformed);
        Object collector = run.loadClass("mod.Collector").getConstructor().newInstance();
        run.call("com/mojang/blaze3d/vertex/HostDraws", "feed", collector);
        String bulk = "1.0,2.0,3.0 | 64,32,16,128 | 0.5,0.25 | 3,10 | 240,240 | 0.0,1.0,0.0";
        String single = "4.0,5.0,6.0 | 9,8,7,6 | 0.1,0.2 | 1,2 | 3,4 | 1.0,0.0,0.0";
        assertEquals(List.of(bulk, bulk, single, single, bulk), vertices(collector));
    }

    @Test
    @DisplayName("A Fabric 1.20.1 mod's intermediary calls on a Fabric 1.21.x host write the same vertices")
    void intermediaryCallsWriteTheSameVertices(@TempDir Path dir) throws Throwable {
        Worlds worlds = Worlds.build(dir);
        Map<String, byte[]> oldHost = Intermediary.old(worlds.oldHost);
        Map<String, byte[]> mod = Intermediary.old(BridgeHarness.only(worlds.oldCompiled, "mod/"));
        Map<String, byte[]> newHost = Intermediary.current(worlds.newHost);
        Map<String, byte[]> transformed = BridgeHarness.transform("1.21.1", LegacyVertexApiBridge::registerIntermediary, mod, newHost);
        for (String draw : List.of("chain", "cube", "matrix", "fixed", "tinted")) {
            List<String> expected = record(new BridgeHarness.Run(oldHost, mod), draw, "com.mojang.blaze3d.vertex.Recorder");
            assertEquals(expected, record(new BridgeHarness.Run(newHost, transformed), draw, "com.mojang.blaze3d.vertex.Recorder"), draw);
        }
    }

    @Test
    @DisplayName("An old line drawn into a 1.21.11+ lines format gets the line width 1.20.1's lines used, so the vertex is complete")
    void oldLineGetsTheLineWidth(@TempDir Path dir) throws Throwable {
        Worlds worlds = Worlds.build(dir);
        assertEquals(List.of("0.0,0.0,0.0 | 255,0,0,255 | 1.0,0.0,0.0 | 2.5", "1.0,0.0,0.0 | 255,0,0,255 | 1.0,0.0,0.0 | 2.5"),
                worlds.newRecord("line", "com.mojang.blaze3d.vertex.LinesRecorder"));
    }

    @Test
    @DisplayName("A 1.21.1-shaped host, with no line width, takes the same vertices and is never asked for one")
    void hostWithoutLineWidthIsNotAsked(@TempDir Path dir) throws Throwable {
        Map<String, byte[]> old = BridgeHarness.compile(dir.resolve("old"), Sources.oldWorld());
        Map<String, byte[]> oldHost = BridgeHarness.only(old, "com/");
        oldHost.putAll(BridgeHarness.only(old, "org/"));
        Map<String, byte[]> host = BridgeHarness.compile(dir.resolve("new"), Sources.newWorld(false));
        Map<String, byte[]> mod = BridgeHarness.transform("1.21.1", LegacyVertexApiBridge::register, BridgeHarness.only(old, "mod/"), host);
        for (String draw : List.of("chain", "cube", "matrix")) {
            List<String> expected = record(new BridgeHarness.Run(oldHost, BridgeHarness.only(old, "mod/")), draw, "com.mojang.blaze3d.vertex.Recorder");
            assertEquals(expected, record(new BridgeHarness.Run(host, mod), draw, "com.mojang.blaze3d.vertex.Recorder"), draw);
        }
    }

    @Test
    @DisplayName("A mod's own consumer gets the whole-vertex call's color cut to whole steps but not masked, as 1.20.1 passed it")
    void ownConsumerGetsTheUnmaskedColor(@TempDir Path dir) throws Throwable {
        Worlds worlds = Worlds.build(dir);
        List<String> expected = worlds.oldRecord("bright", "mod.Collector");
        assertEquals("0.0,0.0,0.0 | 382,127,0,255 | 0.0,0.0 | 0,0 | 0,0 | 0.0,1.0,0.0", expected.get(0));
        assertEquals(expected, worlds.newRecord("bright", "mod.Collector"));
    }

    /** The old host, the 1.21 host and the mod, compiled, with the mod transformed for a 26.2 host. */
    private record Worlds(Map<String, byte[]> oldCompiled, Map<String, byte[]> oldHost, Map<String, byte[]> newHost,
            Map<String, byte[]> transformed) {
        static Worlds build(Path dir) throws Exception {
            Map<String, byte[]> oldCompiled = BridgeHarness.compile(dir.resolve("old"), Sources.oldWorld());
            Map<String, byte[]> oldHost = BridgeHarness.only(oldCompiled, "com/");
            oldHost.putAll(BridgeHarness.only(oldCompiled, "org/"));
            Map<String, byte[]> newHost = BridgeHarness.compile(dir.resolve("new"), Sources.newWorld(true));
            Map<String, byte[]> transformed = BridgeHarness.transform("26.2", LegacyVertexApiBridge::register,
                    BridgeHarness.only(oldCompiled, "mod/"), newHost);
            return new Worlds(oldCompiled, oldHost, newHost, transformed);
        }

        List<String> oldRecord(String draw, String consumer) throws Throwable {
            return record(new BridgeHarness.Run(oldHost, BridgeHarness.only(oldCompiled, "mod/")), draw, consumer);
        }

        List<String> newRecord(String draw, String consumer) throws Throwable {
            return record(new BridgeHarness.Run(newHost, transformed), draw, consumer);
        }
    }

    private static List<String> record(BridgeHarness.Run run, String draw, String consumer) throws Throwable {
        Object recorder = run.loadClass(consumer).getConstructor().newInstance();
        run.call("mod/Draws", draw, recorder);
        return vertices(recorder);
    }

    @SuppressWarnings("unchecked")
    private static List<String> vertices(Object consumer) throws Exception {
        Method vertices = consumer.getClass().getMethod("vertices");
        return (List<String>) vertices.invoke(consumer);
    }

    /** Renames compiled stand-ins into Fabric intermediary, as a Fabric 1.20.1 mod and a 1.21.x host carry them. */
    private static final class Intermediary {
        static final String VC = "com/mojang/blaze3d/vertex/VertexConsumer";
        static final List<String> OWNERS = List.of(VC, "com/mojang/blaze3d/vertex/BufferBuilder", "com/mojang/blaze3d/vertex/Recorder",
                "com/mojang/blaze3d/vertex/LinesRecorder", "mod/Tint", "mod/Collector");

        // 1.20.1's ids.
        static Map<String, byte[]> old(Map<String, byte[]> classes) {
            String r = "Lcom/mojang/blaze3d/vertex/VertexConsumer;";
            return rename(classes, Map.ofEntries(Map.entry("vertex(DDD)" + r, "method_22912"), Map.entry("color(IIII)" + r, "method_1336"),
                    Map.entry("uv(FF)" + r, "method_22913"), Map.entry("overlayCoords(II)" + r, "method_22917"),
                    Map.entry("uv2(II)" + r, "method_22921"), Map.entry("normal(FFF)" + r, "method_22914"), Map.entry("endVertex()V", "method_1344"),
                    Map.entry("vertex(FFFFFFFFFIIFFF)V", "method_23919"), Map.entry("defaultColor(IIII)V", "method_22901"),
                    Map.entry("unsetDefaultColor()V", "method_35666"), Map.entry("color(FFFF)" + r, "method_22915"),
                    Map.entry("color(I)" + r, "method_39415"), Map.entry("uv2(I)" + r, "method_22916"), Map.entry("overlayCoords(I)" + r, "method_22922"),
                    Map.entry("vertex(Lorg/joml/Matrix4f;FFF)" + r, "method_22918"), Map.entry("normal(Lorg/joml/Matrix3f;FFF)" + r, "method_23763")));
        }

        // 1.21's ids, the same from 1.21 to 1.21.11.
        static Map<String, byte[]> current(Map<String, byte[]> classes) {
            String r = "Lcom/mojang/blaze3d/vertex/VertexConsumer;";
            return rename(classes, Map.ofEntries(Map.entry("addVertex(FFF)" + r, "method_22912"), Map.entry("setColor(IIII)" + r, "method_1336"),
                    Map.entry("setColor(I)" + r, "method_39415"), Map.entry("setUv(FF)" + r, "method_22913"), Map.entry("setUv1(II)" + r, "method_60796"),
                    Map.entry("setUv2(II)" + r, "method_22921"), Map.entry("setNormal(FFF)" + r, "method_22914"),
                    Map.entry("setLineWidth(F)" + r, "method_75298"), Map.entry("addVertex(FFFIFFIIFFF)V", "method_23919"),
                    Map.entry("setColor(FFFF)" + r, "method_22915"), Map.entry("setOverlay(I)" + r, "method_22922"),
                    Map.entry("setLight(I)" + r, "method_60803"), Map.entry("addVertex(Lorg/joml/Matrix4f;FFF)" + r, "method_22918")));
        }

        private static Map<String, byte[]> rename(Map<String, byte[]> classes, Map<String, String> members) {
            Map<String, String> names = new HashMap<>();
            names.put(VC, "net/minecraft/class_4588");
            names.put("com/mojang/blaze3d/vertex/BufferBuilder", "net/minecraft/class_287");
            names.put("net/minecraft/client/Minecraft", "net/minecraft/class_310");
            names.put("com/mojang/blaze3d/platform/Window", "net/minecraft/class_1041");
            names.put("net/minecraft/client/Minecraft.getInstance()Lnet/minecraft/client/Minecraft;", "method_1551");
            names.put("net/minecraft/client/Minecraft.getWindow()Lcom/mojang/blaze3d/platform/Window;", "method_22683");
            names.put("com/mojang/blaze3d/platform/Window.getAppropriateLineWidth()F", "method_75291");
            for (String owner : OWNERS) members.forEach((member, id) -> names.put(owner + "." + member, id));
            org.objectweb.asm.commons.SimpleRemapper remapper = new org.objectweb.asm.commons.SimpleRemapper(names);
            Map<String, byte[]> renamed = new HashMap<>();
            classes.forEach((name, bytes) -> {
                ClassWriter out = new ClassWriter(0);
                new ClassReader(bytes).accept(new org.objectweb.asm.commons.ClassRemapper(out, remapper), 0);
                renamed.put(remapper.map(name) == null ? name : remapper.map(name), out.toByteArray());
            });
            return renamed;
        }
    }

    /** Stand-ins written for these tests: the two builders, a shared JOML, and the old mod code. */
    private static final class Sources {
        static final String JOML_MATRIX4 = """
                package org.joml;
                public class Matrix4f {
                    public float m00 = 1, m01, m02, m03, m10, m11 = 1, m12, m13, m20, m21, m22 = 1, m23, m30, m31, m32, m33 = 1;
                    public Vector4f transform(Vector4f v) {
                        return v.set(m00 * v.x + m10 * v.y + m20 * v.z + m30 * v.w, m01 * v.x + m11 * v.y + m21 * v.z + m31 * v.w,
                                m02 * v.x + m12 * v.y + m22 * v.z + m32 * v.w, m03 * v.x + m13 * v.y + m23 * v.z + m33 * v.w);
                    }
                }""";
        static final String JOML_VECTOR4 = """
                package org.joml;
                public class Vector4f {
                    public float x, y, z, w;
                    public Vector4f(float x, float y, float z, float w) { set(x, y, z, w); }
                    public Vector4f set(float x, float y, float z, float w) { this.x = x; this.y = y; this.z = z; this.w = w; return this; }
                    public float x() { return x; }
                    public float y() { return y; }
                    public float z() { return z; }
                }""";
        static final String JOML_MATRIX3 = """
                package org.joml;
                public class Matrix3f {
                    public float m00 = 1, m01, m02, m10, m11 = 1, m12, m20, m21, m22 = 1;
                    public Vector3f transform(Vector3f v) {
                        return new Vector3f(m00 * v.x + m10 * v.y + m20 * v.z, m01 * v.x + m11 * v.y + m21 * v.z, m02 * v.x + m12 * v.y + m22 * v.z);
                    }
                }""";
        static final String JOML_VECTOR3 = """
                package org.joml;
                public class Vector3f {
                    public float x, y, z;
                    public Vector3f(float x, float y, float z) { this.x = x; this.y = y; this.z = z; }
                    public float x() { return x; }
                    public float y() { return y; }
                    public float z() { return z; }
                }""";

        static Map<String, String> joml(Map<String, String> sources) {
            sources.put("org/joml/Matrix4f.java", JOML_MATRIX4);
            sources.put("org/joml/Vector4f.java", JOML_VECTOR4);
            sources.put("org/joml/Matrix3f.java", JOML_MATRIX3);
            sources.put("org/joml/Vector3f.java", JOML_VECTOR3);
            return sources;
        }

        // 1.20.1's builder chain as these tests read it: a vertex is written when endVertex ends it.
        static Map<String, String> oldWorld() {
            Map<String, String> s = joml(new HashMap<>());
            s.put("com/mojang/blaze3d/vertex/VertexConsumer.java", """
                    package com.mojang.blaze3d.vertex;
                    import org.joml.*;
                    public interface VertexConsumer {
                        VertexConsumer vertex(double x, double y, double z);
                        VertexConsumer color(int r, int g, int b, int a);
                        VertexConsumer uv(float u, float v);
                        VertexConsumer overlayCoords(int u, int v);
                        VertexConsumer uv2(int u, int v);
                        VertexConsumer normal(float x, float y, float z);
                        void endVertex();
                        void defaultColor(int r, int g, int b, int a);
                        void unsetDefaultColor();
                        default void vertex(float x, float y, float z, float r, float g, float b, float a, float u, float v, int overlay,
                                int light, float nx, float ny, float nz) {
                            vertex(x, y, z); color(r, g, b, a); uv(u, v); overlayCoords(overlay); uv2(light); normal(nx, ny, nz); endVertex();
                        }
                        default VertexConsumer color(float r, float g, float b, float a) {
                            return color((int) (r * 255.0F), (int) (g * 255.0F), (int) (b * 255.0F), (int) (a * 255.0F));
                        }
                        default VertexConsumer color(int argb) { return color(argb >> 16 & 255, argb >> 8 & 255, argb & 255, argb >>> 24); }
                        default VertexConsumer uv2(int packed) { return uv2(packed & 0xFFFF, packed >> 16 & 0xFFFF); }
                        default VertexConsumer overlayCoords(int packed) { return overlayCoords(packed & 0xFFFF, packed >> 16 & 0xFFFF); }
                        default VertexConsumer vertex(Matrix4f m, float x, float y, float z) {
                            Vector4f v = m.transform(new Vector4f(x, y, z, 1.0F));
                            return vertex(v.x(), v.y(), v.z());
                        }
                        default VertexConsumer normal(Matrix3f m, float x, float y, float z) {
                            Vector3f v = m.transform(new Vector3f(x, y, z));
                            return normal(v.x(), v.y(), v.z());
                        }
                    }""");
            s.put("com/mojang/blaze3d/vertex/Recorder.java", """
                    package com.mojang.blaze3d.vertex;
                    import java.util.*;
                    public class Recorder implements VertexConsumer {
                        private final List<String> done = new ArrayList<>();
                        private String pos, color, uv, overlay, light, normal;
                        private int[] fixed;
                        public VertexConsumer vertex(double x, double y, double z) {
                            pos = (float) x + "," + (float) y + "," + (float) z;
                            if (fixed != null) color = fixed[0] + "," + fixed[1] + "," + fixed[2] + "," + fixed[3];
                            return this;
                        }
                        public VertexConsumer color(int r, int g, int b, int a) {
                            if (fixed == null) color = (r & 255) + "," + (g & 255) + "," + (b & 255) + "," + (a & 255);
                            return this;
                        }
                        public VertexConsumer uv(float u, float v) { uv = u + "," + v; return this; }
                        public VertexConsumer overlayCoords(int u, int v) { overlay = (u & 0xFFFF) + "," + (v & 0xFFFF); return this; }
                        public VertexConsumer uv2(int u, int v) { light = (u & 0xFFFF) + "," + (v & 0xFFFF); return this; }
                        public VertexConsumer normal(float x, float y, float z) { normal = x + "," + y + "," + z; return this; }
                        public void endVertex() { done.add(String.join(" | ", pos, color, uv, overlay, light, normal)); }
                        public void defaultColor(int r, int g, int b, int a) { fixed = new int[] {r, g, b, a}; }
                        public void unsetDefaultColor() { fixed = null; }
                        public List<String> vertices() { return done; }
                    }""");
            s.put("com/mojang/blaze3d/vertex/BufferBuilder.java", """
                    package com.mojang.blaze3d.vertex;
                    public class BufferBuilder extends Recorder {}""");
            s.put("mod/Draws.java", """
                    package mod;
                    import com.mojang.blaze3d.vertex.*;
                    import org.joml.*;
                    public final class Draws {
                        public static void chain(VertexConsumer c) {
                            c.vertex(1.5, 2.25, -3.0).color(10, 20, 30, 40).uv(0.25f, 0.75f).overlayCoords(3, 10).uv2(240, 15).normal(0f, 1f, 0f).endVertex();
                            c.vertex(0.1, 0.2, 0.3).color(0.5f, 0.25f, 1.0f, 0.999f).uv(1f, 0f).overlayCoords(0x000A0003).uv2(0x00F000F0)
                                    .normal(0f, 0f, -1f).endVertex();
                            c.vertex(7, 8, 9).color(0x11223344).uv(0.5f, 0.5f).overlayCoords(1, 2).uv2(3, 4).normal(1f, 0f, 0f).endVertex();
                        }
                        // A cube face drawn as GeckoLib 4 draws one: four whole-vertex calls.
                        public static void cube(VertexConsumer c) {
                            float[][] corners = {{0, 0, 0}, {1, 0, 0}, {1, 1, 0}, {0, 1, 0}};
                            for (float[] p : corners) {
                                c.vertex(p[0], p[1], p[2], 1f, 0.5f, 0.25f, 1f, p[0], p[1], 0x000A0003, 0x00F000F0, 0f, 0f, 1f);
                            }
                        }
                        public static void matrix(VertexConsumer c) {
                            Matrix4f m = new Matrix4f();
                            m.m00 = 2; m.m11 = 3; m.m22 = 4; m.m30 = 10; m.m31 = 20; m.m32 = 30; m.m10 = 0.5f;
                            Matrix3f n = new Matrix3f();
                            n.m01 = 1; n.m10 = -1; n.m00 = 0; n.m11 = 0;
                            c.vertex(m, 1f, 1f, 1f).color(255, 255, 255, 255).uv(0f, 0f).overlayCoords(0).uv2(0).normal(n, 0f, 1f, 0f).endVertex();
                        }
                        public static void fixed(VertexConsumer c) {
                            c.defaultColor(1, 2, 3, 4);
                            c.vertex(1, 1, 1).uv(0f, 0f).overlayCoords(0, 0).uv2(0, 0).normal(0f, 1f, 0f).endVertex();
                            c.vertex(1f, 1f, 1f, 0f, 0f, 0f, 0f, 0.5f, 0.5f, 0, 0, 0f, 1f, 0f);
                            c.unsetDefaultColor();
                            c.vertex(2, 2, 2).color(5, 6, 7, 8).uv(0f, 0f).overlayCoords(0, 0).uv2(0, 0).normal(0f, 1f, 0f).endVertex();
                        }
                        public static void builder(BufferBuilder b) {
                            b.vertex(1, 2, 3).color(1, 2, 3, 4).uv(0f, 1f).overlayCoords(5, 6).uv2(7, 8).normal(0f, 0f, 1f).endVertex();
                            b.vertex(3, 2, 1).uv(1f, 0f).overlayCoords(0).uv2(0).normal(1f, 0f, 0f).endVertex();
                        }
                        // A line as old debug and outline code drew one: no texture, overlay or light.
                        public static void line(VertexConsumer c) {
                            c.vertex(0, 0, 0).color(255, 0, 0, 255).normal(1f, 0f, 0f).endVertex();
                            c.vertex(1, 0, 0).color(255, 0, 0, 255).normal(1f, 0f, 0f).endVertex();
                        }
                        // A whole vertex with a channel above 1, whose color an old consumer received unmasked.
                        public static void bright(VertexConsumer c) {
                            c.vertex(0f, 0f, 0f, 1.5f, 0.5f, 0f, 1f, 0f, 0f, 0, 0, 0f, 1f, 0f);
                        }
                        public static void tinted(VertexConsumer c) {
                            chain(new Tint(c));
                            cube(new Tint(c));
                        }
                    }""");
            // A mod's own wrapper, as Create's and Iceberg's: halves the color and passes the rest on.
            s.put("mod/Tint.java", """
                    package mod;
                    import com.mojang.blaze3d.vertex.VertexConsumer;
                    public final class Tint implements VertexConsumer {
                        private final VertexConsumer to;
                        public Tint(VertexConsumer to) { this.to = to; }
                        public VertexConsumer vertex(double x, double y, double z) { to.vertex(x, y, z); return this; }
                        public VertexConsumer color(int r, int g, int b, int a) { to.color(r / 2, g / 2, b / 2, a); return this; }
                        public VertexConsumer uv(float u, float v) { to.uv(u, v); return this; }
                        public VertexConsumer overlayCoords(int u, int v) { to.overlayCoords(u, v); return this; }
                        public VertexConsumer uv2(int u, int v) { to.uv2(u, v); return this; }
                        public VertexConsumer normal(float x, float y, float z) { to.normal(x, y, z); return this; }
                        public void endVertex() { to.endVertex(); }
                        public void defaultColor(int r, int g, int b, int a) { to.defaultColor(r, g, b, a); }
                        public void unsetDefaultColor() { to.unsetDefaultColor(); }
                    }""");
            // A mod's own collector, as Iceberg's: keeps each vertex when endVertex ends it.
            s.put("mod/Collector.java", """
                    package mod;
                    import com.mojang.blaze3d.vertex.VertexConsumer;
                    import java.util.ArrayList;
                    import java.util.List;
                    public final class Collector implements VertexConsumer {
                        private final List<String> done = new ArrayList<>();
                        private String pos, color, uv, overlay, light, normal;
                        public VertexConsumer vertex(double x, double y, double z) { pos = (float) x + "," + (float) y + "," + (float) z; return this; }
                        public VertexConsumer color(int r, int g, int b, int a) { color = r + "," + g + "," + b + "," + a; return this; }
                        public VertexConsumer uv(float u, float v) { uv = u + "," + v; return this; }
                        public VertexConsumer overlayCoords(int u, int v) { overlay = u + "," + v; return this; }
                        public VertexConsumer uv2(int u, int v) { light = u + "," + v; return this; }
                        public VertexConsumer normal(float x, float y, float z) { normal = x + "," + y + "," + z; return this; }
                        public void endVertex() { done.add(String.join(" | ", pos, color, uv, overlay, light, normal)); }
                        public void defaultColor(int r, int g, int b, int a) {}
                        public void unsetDefaultColor() {}
                        public List<String> vertices() { return done; }
                    }""");
            return s;
        }

        // 1.21's builder as these tests read it: setters fill the open vertex, and the next addVertex or the end writes it.
        static Map<String, String> newWorld(boolean lineWidth) {
            Map<String, String> s = joml(new HashMap<>());
            if (!lineWidth) {
                // 1.21.1: no line width setter, and no window to ask for one.
                Map<String, String> plain = newWorld(true);
                plain.remove("net/minecraft/client/Minecraft.java");
                plain.remove("com/mojang/blaze3d/platform/Window.java");
                plain.remove("com/mojang/blaze3d/vertex/LinesRecorder.java");
                plain.replaceAll((file, source) -> source.replace("VertexConsumer setLineWidth(float width);", "")
                        .replace("public VertexConsumer setLineWidth(float width) { return this; }", ""));
                return plain;
            }
            s.put("net/minecraft/client/Minecraft.java", """
                    package net.minecraft.client;
                    public class Minecraft {
                        private static final Minecraft INSTANCE = new Minecraft();
                        public static Minecraft getInstance() { return INSTANCE; }
                        public com.mojang.blaze3d.platform.Window getWindow() { return new com.mojang.blaze3d.platform.Window(); }
                    }""");
            s.put("com/mojang/blaze3d/platform/Window.java", """
                    package com.mojang.blaze3d.platform;
                    public class Window {
                        public float getAppropriateLineWidth() { return 2.5f; }
                    }""");
            // A lines format from 1.21.11: a vertex without its line width is refused, as the host's builder refuses it.
            s.put("com/mojang/blaze3d/vertex/LinesRecorder.java", """
                    package com.mojang.blaze3d.vertex;
                    import java.util.*;
                    public class LinesRecorder implements VertexConsumer {
                        private final List<String> done = new ArrayList<>();
                        private String pos, color, normal, width;
                        private void close() {
                            if (pos == null) return;
                            if (width == null) throw new IllegalStateException("Missing elements in vertex: LineWidth");
                            done.add(String.join(" | ", pos, color, normal, width));
                            pos = null;
                            width = null;
                        }
                        public VertexConsumer addVertex(float x, float y, float z) { close(); pos = x + "," + y + "," + z; return this; }
                        public VertexConsumer setColor(int r, int g, int b, int a) { color = r + "," + g + "," + b + "," + a; return this; }
                        public VertexConsumer setColor(int argb) { return setColor(argb >> 16 & 255, argb >> 8 & 255, argb & 255, argb >>> 24); }
                        public VertexConsumer setUv(float u, float v) { return this; }
                        public VertexConsumer setUv1(int u, int v) { return this; }
                        public VertexConsumer setUv2(int u, int v) { return this; }
                        public VertexConsumer setNormal(float x, float y, float z) { normal = x + "," + y + "," + z; return this; }
                        public VertexConsumer setLineWidth(float w) { width = String.valueOf(w); return this; }
                        public List<String> vertices() { close(); return done; }
                    }""");
            s.put("com/mojang/blaze3d/vertex/VertexConsumer.java", """
                    package com.mojang.blaze3d.vertex;
                    public interface VertexConsumer {
                        VertexConsumer addVertex(float x, float y, float z);
                        VertexConsumer setColor(int r, int g, int b, int a);
                        VertexConsumer setColor(int argb);
                        VertexConsumer setUv(float u, float v);
                        VertexConsumer setUv1(int u, int v);
                        VertexConsumer setUv2(int u, int v);
                        VertexConsumer setNormal(float x, float y, float z);
                        VertexConsumer setLineWidth(float width);
                        default void addVertex(float x, float y, float z, int argb, float u, float v, int overlay, int light, float nx, float ny,
                                float nz) {
                            addVertex(x, y, z); setColor(argb); setUv(u, v); setOverlay(overlay); setLight(light); setNormal(nx, ny, nz);
                        }
                        default VertexConsumer setColor(float r, float g, float b, float a) {
                            return setColor((int) Math.floor(r * 255.0F), (int) Math.floor(g * 255.0F), (int) Math.floor(b * 255.0F),
                                    (int) Math.floor(a * 255.0F));
                        }
                        default VertexConsumer setOverlay(int packed) { return setUv1(packed & 0xFFFF, packed >> 16 & 0xFFFF); }
                        default VertexConsumer setLight(int packed) { return setUv2(packed & 0xFFFF, packed >> 16 & 0xFFFF); }
                        default VertexConsumer addVertex(org.joml.Matrix4f m, float x, float y, float z) {
                            org.joml.Vector4f v = m.transform(new org.joml.Vector4f(x, y, z, 1.0F));
                            return addVertex(v.x, v.y, v.z);
                        }
                    }""");
            s.put("com/mojang/blaze3d/vertex/Recorder.java", """
                    package com.mojang.blaze3d.vertex;
                    import java.util.*;
                    public class Recorder implements VertexConsumer {
                        private final List<String> done = new ArrayList<>();
                        private String pos, color, uv, overlay, light, normal;
                        private void close() {
                            if (pos != null) done.add(String.join(" | ", pos, color, uv, overlay, light, normal));
                            pos = null;
                        }
                        public VertexConsumer addVertex(float x, float y, float z) { close(); pos = x + "," + y + "," + z; return this; }
                        public VertexConsumer setColor(int r, int g, int b, int a) { color = (r & 255) + "," + (g & 255) + "," + (b & 255) + "," + (a & 255); return this; }
                        public VertexConsumer setColor(int argb) { return setColor(argb >> 16 & 255, argb >> 8 & 255, argb & 255, argb >>> 24); }
                        public VertexConsumer setUv(float u, float v) { uv = u + "," + v; return this; }
                        public VertexConsumer setUv1(int u, int v) { overlay = (u & 0xFFFF) + "," + (v & 0xFFFF); return this; }
                        public VertexConsumer setUv2(int u, int v) { light = (u & 0xFFFF) + "," + (v & 0xFFFF); return this; }
                        public VertexConsumer setNormal(float x, float y, float z) { normal = x + "," + y + "," + z; return this; }
                        public VertexConsumer setLineWidth(float width) { return this; }
                        public List<String> vertices() { close(); return done; }
                    }""");
            s.put("com/mojang/blaze3d/vertex/BufferBuilder.java", """
                    package com.mojang.blaze3d.vertex;
                    public class BufferBuilder extends Recorder {}""");
            // Host code on the 1.21 builder: a model's whole vertices, and single setters as a font writes them.
            s.put("com/mojang/blaze3d/vertex/HostDraws.java", """
                    package com.mojang.blaze3d.vertex;
                    public final class HostDraws {
                        public static void feed(VertexConsumer c) {
                            for (int i = 0; i < 2; i++) c.addVertex(1f, 2f, 3f, 0x80402010, 0.5f, 0.25f, 0x000A0003, 0x00F000F0, 0f, 1f, 0f);
                            for (int i = 0; i < 2; i++) c.addVertex(4f, 5f, 6f).setColor(9, 8, 7, 6).setUv(0.1f, 0.2f).setUv1(1, 2).setUv2(3, 4).setNormal(1f, 0f, 0f);
                            c.addVertex(1f, 2f, 3f, 0x80402010, 0.5f, 0.25f, 0x000A0003, 0x00F000F0, 0f, 1f, 0f);
                        }
                    }""");
            return s;
        }
    }
}
