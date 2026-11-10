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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Constructor;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Old code that reads and builds 1.20.1 block model elements runs against Minecraft 26.2's and 26.3's cuboid records. */
class LegacyBlockElementBridgeTest {

    private static final String ELEMENT = LegacyBlockElementBridge.ELEMENT;

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @TempDir
    Path dir;

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("Every old field read becomes an accessor or helper call, so no read of a record's private field is left")
    void noFieldReadIsLeft() throws Exception {
        Map<String, byte[]> mod = translate("26.2");

        assertTrue(BridgeHarness.fields(mod.get("mod/Elements"), "read").isEmpty(),
                "reads left: " + BridgeHarness.fields(mod.get("mod/Elements"), "read"));
        assertTrue(BridgeHarness.calls(mod.get("mod/Elements"), "read").contains(ELEMENT + ".rotation()L"
                + LegacyBlockElementBridge.ROTATION + ";"), "the rotation is read through the record's accessor");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("An element Minecraft built reads as the old element did, its vectors, faces, rotation and shading")
    void hostElementReadsTheOldWay(String host) throws Throwable {
        BridgeHarness.Run run = run(host);
        Object element = hostElement(run, host);

        assertEquals("0.0,16.0,0,none,true", run.call("mod/Elements", "read", element));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("An element, face, UVs and rotation the old code builds read back the same, as Minecraft's records")
    void builtElementReadsBack(String host) throws Throwable {
        BridgeHarness.Run run = run(host);

        Object built = run.call("mod/Elements", "build");

        assertEquals(ELEMENT.replace('/', '.'), built.getClass().getName(), "the host's own record");
        assertEquals("1.0,5.0,1,Y:22.5:8.0,false", run.call("mod/Elements", "read", built));
        assertEquals("2,#all,90,0.0,16.0,null", run.call("mod/Elements", "face", built));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("A face loaded without \"uv\" reads corners from the element's bounds, as 1.20.1 filled them in")
    void faceWithoutUvReadsBoundsCorners(String host) throws Throwable {
        BridgeHarness.Run run = run(host);
        Object element = boxWithoutUvs(run, host);

        assertEquals("DOWN[2.0, 4.0, 14.0, 12.0] UP[0.0, 0.0, 16.0, 8.0] NORTH[2.0, 8.0, 14.0, 16.0] EAST[4.0, 8.0, 12.0, 16.0]"
                + " get[2.0, 8.0, 14.0, 16.0]", run.call("mod/Elements", "corners", element));
        Map<?, ?> own = (Map<?, ?>) element.getClass().getMethod("faces").invoke(element);
        assertEquals(3, own.size(), "removing a face through the old field changes the element's own map");
        assertEquals(2, own.values().stream().filter(face -> face.toString().contains("uvs=null")).count(),
                "the element's own faces keep no corners, so Minecraft still bakes them its way: " + own);
    }

    @Test
    @DisplayName("Nothing is registered for a host before 26.1, whose elements keep the old fields")
    void olderHostKeepsTheOldFields() throws Exception {
        Map<String, byte[]> mod = translate("1.21.11");

        java.util.List<String> reads = BridgeHarness.fields(mod.get("mod/Elements"), "read");
        assertFalse(reads.isEmpty());
        assertTrue(reads.stream().allMatch(read -> read.startsWith(LegacyBlockElementBridge.OLD_ELEMENT + ".")), reads.toString());
    }

    // ---- the game ----

    private Map<String, byte[]> translate(String host) throws Exception {
        Map<String, byte[]> old = BridgeHarness.compile(dir.resolve("old-" + host), oldSources());
        return BridgeHarness.transform(host, LegacyBlockElementBridge::register, BridgeHarness.only(old, "mod/"));
    }

    private BridgeHarness.Run run(String host) throws Exception {
        Map<String, byte[]> mod = translate(host);
        Map<String, byte[]> game = new HashMap<>(BridgeHarness.compile(dir.resolve("host-" + host), hostSources(host)));
        return new BridgeHarness.Run(game, mod);
    }

    // The unit cube vanilla's block/cube model holds, built the way the host's model reader builds it.
    private static Object hostElement(BridgeHarness.Run run, String host) throws Exception {
        Class<?> vector = run.loadClass("org.joml.Vector3f");
        Object from = vector.getConstructor(float.class, float.class, float.class).newInstance(0f, 0f, 0f);
        Object to = vector.getConstructor(float.class, float.class, float.class).newInstance(16f, 16f, 16f);
        Class<?> element = run.loadClass(ELEMENT.replace('/', '.'));
        Object shading = host.equals("26.2") ? (Object) true : null;
        return element.getConstructors()[0].newInstance(from, to, Map.of(), null, shading, 0);
    }

    // A box from (2, 0, 4) to (14, 8, 12) whose down, north and east faces name no corners, as a model file without "uv" loads.
    private static Object boxWithoutUvs(BridgeHarness.Run run, String host) throws Exception {
        @SuppressWarnings({"unchecked", "rawtypes"})
        Class<Enum> direction = (Class<Enum>) run.loadClass("net.minecraft.core.Direction");
        @SuppressWarnings({"unchecked", "rawtypes"})
        Object r0 = Enum.valueOf((Class<Enum>) run.loadClass("com.mojang.math.Quadrant"), "R0");
        Constructor<?> face = run.loadClass(LegacyBlockElementBridge.FACE.replace('/', '.')).getConstructors()[0];
        Object corners = run.loadClass(LegacyBlockElementBridge.FACE.replace('/', '.') + "$UVs").getConstructors()[0]
                .newInstance(0f, 0f, 16f, 8f);
        @SuppressWarnings({"unchecked", "rawtypes"})
        Map<Object, Object> faces = new EnumMap(direction);
        for (String side : List.of("DOWN", "NORTH", "EAST")) {
            faces.put(Enum.valueOf(direction, side), face.newInstance(null, -1, "#all", null, r0));
        }
        faces.put(Enum.valueOf(direction, "UP"), face.newInstance(null, -1, "#all", corners, r0));
        Class<?> vector = run.loadClass("org.joml.Vector3f");
        Object from = vector.getConstructor(float.class, float.class, float.class).newInstance(2f, 0f, 4f);
        Object to = vector.getConstructor(float.class, float.class, float.class).newInstance(14f, 8f, 12f);
        Object shading = host.equals("26.2") ? (Object) true : null;
        return run.loadClass(ELEMENT.replace('/', '.')).getConstructors()[0].newInstance(from, to, faces, null, shading, 0);
    }

    /** Minecraft 1.20.1's element classes as old mods compiled against them, and a mod that reads and builds them. */
    private static Map<String, String> oldSources() {
        Map<String, String> sources = new HashMap<>(joml());
        sources.put("net/minecraft/core/Direction.java", DIRECTION);
        String model = "package net.minecraft.client.renderer.block.model;\n";
        sources.put("net/minecraft/client/renderer/block/model/BlockElement.java", model + """
                public class BlockElement {
                    public final org.joml.Vector3f from;
                    public final org.joml.Vector3f to;
                    public final java.util.Map<net.minecraft.core.Direction, BlockElementFace> faces;
                    public final BlockElementRotation rotation;
                    public final boolean shade;
                    public BlockElement(org.joml.Vector3f from, org.joml.Vector3f to,
                            java.util.Map<net.minecraft.core.Direction, BlockElementFace> faces, BlockElementRotation rotation, boolean shade) {
                        this.from = from; this.to = to; this.faces = faces; this.rotation = rotation; this.shade = shade;
                    }
                }""");
        sources.put("net/minecraft/client/renderer/block/model/BlockElementRotation.java", model + """
                public record BlockElementRotation(org.joml.Vector3f origin, net.minecraft.core.Direction.Axis axis, float angle, boolean rescale) {}""");
        sources.put("net/minecraft/client/renderer/block/model/BlockElementFace.java", model + """
                public class BlockElementFace {
                    public final net.minecraft.core.Direction cullForDirection;
                    public final int tintIndex;
                    public final String texture;
                    public final BlockFaceUV uv;
                    public BlockElementFace(net.minecraft.core.Direction cullForDirection, int tintIndex, String texture, BlockFaceUV uv) {
                        this.cullForDirection = cullForDirection; this.tintIndex = tintIndex; this.texture = texture; this.uv = uv;
                    }
                }""");
        sources.put("net/minecraft/client/renderer/block/model/BlockFaceUV.java", model + """
                public class BlockFaceUV {
                    public float[] uvs;
                    public final int rotation;
                    public BlockFaceUV(float[] uvs, int rotation) { this.uvs = uvs; this.rotation = rotation; }
                    public float getU(int vertex) { return 0; }
                }""");
        // Sophisticated Backpacks' reads, and DynamicTrees' builds.
        sources.put("mod/Elements.java", """
                package mod;
                import java.util.Map;
                import net.minecraft.client.renderer.block.model.*;
                import net.minecraft.core.Direction;
                import org.joml.Vector3f;
                public class Elements {
                    public static String read(Object raw) {
                        BlockElement element = (BlockElement) raw;
                        String rotation = element.rotation == null ? "none" : element.rotation.axis() + ":" + element.rotation.angle()
                                + ":" + element.rotation.origin().y();
                        return element.from.x() + "," + element.to.y() + "," + element.faces.size() + "," + rotation + "," + element.shade;
                    }
                    public static Object build() {
                        BlockElementFace face = new BlockElementFace(null, 2, "#all", new BlockFaceUV(new float[]{0, 0, 16, 8}, 90));
                        return new BlockElement(new Vector3f(1, 2, 3), new Vector3f(4, 5, 6), Map.of(Direction.UP, face),
                                new BlockElementRotation(new Vector3f(8, 8, 8), Direction.Axis.Y, 22.5f, true), false);
                    }
                    // Each face's corners as old code read them, then a face removed through the field.
                    public static String corners(Object raw) {
                        BlockElement element = (BlockElement) raw;
                        StringBuilder out = new StringBuilder();
                        for (Map.Entry<Direction, BlockElementFace> entry : element.faces.entrySet()) {
                            out.append(entry.getKey()).append(java.util.Arrays.toString(entry.getValue().uv.uvs)).append(' ');
                        }
                        out.append("get").append(java.util.Arrays.toString(element.faces.get(Direction.NORTH).uv.uvs));
                        element.faces.remove(Direction.DOWN);
                        return out.toString();
                    }
                    public static String face(Object raw) {
                        BlockElementFace face = ((BlockElement) raw).faces.get(Direction.UP);
                        return face.tintIndex + "," + face.texture + "," + face.uv.rotation + "," + face.uv.getU(0) + ","
                                + face.uv.getU(2) + "," + face.cullForDirection;
                    }
                }""");
        return sources;
    }

    /** Minecraft 26.2's and 26.3's cuboid records, as javap shows them; 26.3 replaced the shade flag with a direction. */
    private static Map<String, String> hostSources(String host) {
        Map<String, String> sources = new HashMap<>(joml());
        sources.put("net/minecraft/core/Direction.java", DIRECTION);
        sources.put("com/mojang/math/Quadrant.java", "package com.mojang.math; public enum Quadrant { R0, R90, R180, R270 }");
        String cuboid = "package net.minecraft.client.resources.model.cuboid;\n";
        String shade = host.equals("26.2") ? "boolean shade" : "net.minecraft.core.Direction shadeDirectionOverride";
        sources.put("net/minecraft/client/resources/model/cuboid/CuboidModelElement.java", cuboid + """
                public record CuboidModelElement(org.joml.Vector3fc from, org.joml.Vector3fc to,
                        java.util.Map<net.minecraft.core.Direction, CuboidFace> faces, CuboidRotation rotation, %s, int lightEmission) {}"""
                .formatted(shade));
        sources.put("net/minecraft/client/resources/model/cuboid/CuboidRotation.java", cuboid + """
                public record CuboidRotation(org.joml.Vector3fc origin, RotationValue value, boolean rescale) {
                    public interface RotationValue {}
                    public record SingleAxisRotation(net.minecraft.core.Direction.Axis axis, float angle) implements RotationValue {}
                    public record EulerXYZRotation(float x, float y, float z) implements RotationValue {}
                }""");
        sources.put("net/minecraft/client/resources/model/cuboid/CuboidFace.java", cuboid + """
                public record CuboidFace(net.minecraft.core.Direction cullForDirection, int tintIndex, String texture, UVs uvs,
                        com.mojang.math.Quadrant rotation) {
                    // As on the host: corners 0 and 1 take the lower U, corners 0 and 3 the lower V.
                    public record UVs(float minU, float minV, float maxU, float maxV) {
                        public float getVertexU(int vertex) { return vertex == 0 || vertex == 1 ? minU : maxU; }
                        public float getVertexV(int vertex) { return vertex == 0 || vertex == 3 ? minV : maxV; }
                    }
                }""");
        return sources;
    }

    private static final String DIRECTION = """
            package net.minecraft.core;
            public enum Direction {
                DOWN, UP, NORTH, SOUTH, WEST, EAST;
                public enum Axis { X, Y, Z }
            }""";

    private static Map<String, String> joml() {
        return Map.of(
                "org/joml/Vector3fc.java", "package org.joml; public interface Vector3fc { float x(); float y(); float z(); }",
                "org/joml/Vector3f.java", """
                        package org.joml;
                        public class Vector3f implements Vector3fc {
                            public float x, y, z;
                            public Vector3f(float x, float y, float z) { this.x = x; this.y = y; this.z = z; }
                            public Vector3f(Vector3fc v) { this(v.x(), v.y(), v.z()); }
                            public float x() { return x; }
                            public float y() { return y; }
                            public float z() { return z; }
                        }""");
    }
}
