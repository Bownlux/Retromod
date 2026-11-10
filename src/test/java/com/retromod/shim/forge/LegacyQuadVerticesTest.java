/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Array;
import java.lang.reflect.RecordComponent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.retromod.shim.forge.ModelHostFixtures.HOST_262;
import static com.retromod.shim.forge.ModelHostFixtures.HOST_263;
import static com.retromod.shim.forge.ModelHostFixtures.HOST_263_EXTRA;
import static org.junit.jupiter.api.Assertions.*;

/** Forge 1.20.1's int[] quads, quad transformers and quad constructors over Minecraft 26.2's and 26.3's record quads. */
class LegacyQuadVerticesTest {

    private static final String VERTICES = LegacyForgeModelBridge.QUAD_VERTICES;
    private static final String TRANSFORMERS = LegacyForgeModelBridge.QUAD_TRANSFORMERS;
    private static final String MATERIALS = LegacyForgeModelBridge.QUAD_MATERIALS;
    private static final String QUAD = LegacyForgeModelBridge.QUAD;
    private static final int WHITE = -1;
    private static final int NORMAL_UP = 127 << 8;

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @TempDir
    Path dir;

    @BeforeEach
    void forge262() {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        McReflect.setForceNeoForge(false);
        McReflect.setForceForge(true);
        transformer.clearRedirectsForTesting();
        new Forge_26_1_to_26_2().registerRedirects(transformer);
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        transformer.clearRedirectsForTesting();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {HOST_262, HOST_263})
    @DisplayName("An old quad built from an int[32] keeps its positions, UVs, tint, direction, shade and occlusion")
    void oldConstructorRoundTrips(String host) throws Exception {
        ModelHostFixtures.Host game = ModelHostFixtures.load(host, dir, transformer);
        int[] vertices = upFace(0);
        Object sprite = game.sprite("leaves", true, false);
        Object quad = game.call(VERTICES, "create", vertices, 2, game.direction("UP"), sprite, true, false);

        assertSame(vertices, game.call(VERTICES, "getVertices", quad), "getVertices hands back the mod's own array");
        assertArrayEquals(vertices, (int[]) game.call(VERTICES, "toVertices", quad),
                "positions, UVs, white, no light and the up normal all come back from the host quad");
        assertSame(game.direction("UP"), game.invoke(quad, "direction"));
        assertEquals(2, game.call(VERTICES, "getTintIndex", quad));
        assertSame(sprite, game.call(VERTICES, "getSprite", quad));
        assertEquals(true, game.call(VERTICES, "isShade", quad));
        assertEquals(false, game.call(VERTICES, "hasAmbientOcclusion", quad));
        Object material = game.invoke(quad, "materialInfo");
        assertSame(game.layer("CUTOUT"), game.invoke(material, "layer"), "the host picks the layer from the sprite");
        long uv = (Long) game.invoke(quad, "packedUV", 1);
        assertEquals(Float.floatToIntBits(0.25f), (int) (uv >>> 32), "u sits in the high half");
        assertEquals(Float.floatToIntBits(0.75f), (int) uv, "v sits in the low half");

        // An unshaded quad keeps that on both hosts, 26.3 spelling it as an upward face.
        Object flat = game.call(VERTICES, "create", upFace(0), -1, game.direction("UP"), sprite, false);
        assertEquals(false, game.call(VERTICES, "isShade", flat));
        assertEquals(true, game.call(VERTICES, "hasAmbientOcclusion", flat), "the five-argument constructor meant occlusion on");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {HOST_262, HOST_263})
    @DisplayName("A host quad reads as an int[32], and edits to that array come back as a new quad on sync")
    void hostQuadSyncsEdits(String host) throws Exception {
        ModelHostFixtures.Host game = ModelHostFixtures.load(host, dir, transformer);
        Object sprite = game.sprite("stone", false, false);
        Object material = game.call(MATERIALS, "create", sprite, 0, true, 5);
        Object quad = game.call(VERTICES, "build", upFace(0), game.direction("UP"), material);

        int[] vertices = (int[]) game.call(VERTICES, "getVertices", quad);
        assertEquals(WHITE, vertices[LegacyQuadVerticesTest.COLOR]);
        assertEquals(5 << 4 | 5 << 20, vertices[6], "the light emission reads back as a lightmap, block and sky");
        assertEquals(NORMAL_UP, vertices[7]);
        assertSame(vertices, game.call(VERTICES, "getVertices", quad), "the same array every time");
        assertSame(quad, game.call(VERTICES, "sync", quad), "an untouched quad syncs to itself");

        vertices[0] = Float.floatToRawIntBits(0.5f);
        Object rebuilt = game.call(VERTICES, "sync", quad);
        assertNotSame(quad, rebuilt);
        assertEquals(0.5f, x(game, rebuilt, 0));
        assertEquals(game.invoke(quad, "materialInfo"), game.invoke(rebuilt, "materialInfo"), "the material is kept");
        assertSame(rebuilt, game.call(VERTICES, "sync", quad), "a second sync with no new edit gives the same quad");
        assertSame(vertices, game.call(VERTICES, "getVertices", rebuilt), "later edits reach the rebuilt quad too");

        // Equal records are still separate quads, each with its own array.
        Object twin = game.call(VERTICES, "build", upFace(0), game.direction("UP"), material);
        Object twin2 = game.call(VERTICES, "build", upFace(0), game.direction("UP"), material);
        assertEquals(twin, twin2);
        assertNotSame(game.call(VERTICES, "getVertices", twin), game.call(VERTICES, "getVertices", twin2));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {HOST_262, HOST_263})
    @DisplayName("The transformers apply, set emissivity and light, and keep Forge 1.20.1's constants")
    void transformers(String host) throws Exception {
        ModelHostFixtures.Host game = ModelHostFixtures.load(host, dir, transformer);
        Class<?> transformerType = game.type(LegacyForgeModelBridge.QUAD_TRANSFORMER);
        Map<String, Integer> constants = new LinkedHashMap<>();
        for (String name : List.of("STRIDE", "POSITION", "COLOR", "UV0", "UV1", "UV2", "NORMAL")) {
            constants.put(name, transformerType.getField(name).getInt(null));
        }
        assertEquals(Map.of("STRIDE", 8, "POSITION", 0, "COLOR", 3, "UV0", 4, "UV1", -1, "UV2", 6, "NORMAL", 7), constants,
                "DefaultVertexFormat.BLOCK's layout; UV1 is not in it, so it was -1");

        Object sprite = game.sprite("stone", false, false);
        Object quad = game.call(VERTICES, "create", upFace(0), 0, game.direction("UP"), sprite, true);

        // A translation moves the positions and leaves the normal.
        Object moved = process(game, game.call(TRANSFORMERS, "applying", transformation(game, "translation", 1f, 2f, 3f)), quad);
        assertEquals(1f, x(game, moved, 0));
        assertEquals(3f, ((Number) game.invoke(game.invoke(moved, "position", 0), "y")).floatValue());
        assertEquals(NORMAL_UP, ((int[]) game.call(VERTICES, "getVertices", moved))[7]);
        assertSame(game.direction("UP"), game.invoke(moved, "direction"), "the face direction stays, as Forge 1.20.1 left it");
        assertEquals(0f, x(game, quad, 0), "process changes a copy, not the quad");

        // A quarter turn about X turns the up normal to face south.
        Object turned = process(game, game.call(TRANSFORMERS, "applying", transformation(game, "rotationX", (float) (Math.PI / 2))),
                quad);
        int normal = ((int[]) game.call(VERTICES, "getVertices", turned))[7];
        assertEquals(List.of(0, 0, 127), List.of((int) (byte) normal, (int) (byte) (normal >> 8), (int) (byte) (normal >> 16)));

        Object glowing = process(game, game.call(TRANSFORMERS, "settingEmissivity", 10), quad);
        assertEquals(10, game.invoke(game.invoke(glowing, "materialInfo"), "lightEmission"));
        assertEquals(15, game.invoke(game.invoke(process(game, game.call(TRANSFORMERS, "settingMaxEmissivity"), quad),
                "materialInfo"), "lightEmission"));
        assertThrows(IllegalArgumentException.class, () -> game.call(TRANSFORMERS, "settingEmissivity", 16));

        // The two-number lightmap takes block light first; only the block half survives.
        Object lit = process(game, game.call(TRANSFORMERS, "applyingLightmap", 7, 15), quad);
        assertEquals(7, game.invoke(game.invoke(lit, "materialInfo"), "lightEmission"));
        assertEquals(7 << 4 | 15 << 20, ((int[]) game.call(VERTICES, "getVertices", lit))[6]);
        Object skyOnly = process(game, game.call(TRANSFORMERS, "applyingLightmap", 15 << 20), quad);
        assertEquals(0, game.invoke(game.invoke(skyOnly, "materialInfo"), "lightEmission"));

        Object both = game.invoke(game.call(TRANSFORMERS, "applying", transformation(game, "translation", 1f, 0f, 0f)),
                "andThen", game.call(TRANSFORMERS, "settingEmissivity", 3));
        Object chained = process(game, both, quad);
        assertEquals(1f, x(game, chained, 0));
        assertEquals(3, game.invoke(game.invoke(chained, "materialInfo"), "lightEmission"));

        assertEquals(game.invoke(quad, "materialInfo"), game.invoke(process(game, game.call(TRANSFORMERS, "empty"), quad),
                "materialInfo"));
        assertEquals(0x11443322, game.call(TRANSFORMERS, "toABGR", 0x11223344));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {HOST_262, HOST_263})
    @DisplayName("processInPlace edits survive in the side table and replace the quads of a list that allows it")
    void processInPlaceSurvivesThroughTheSideTable(String host) throws Exception {
        ModelHostFixtures.Host game = ModelHostFixtures.load(host, dir, transformer);
        Object sprite = game.sprite("stone", false, false);
        Object quad = game.call(VERTICES, "create", upFace(0), 0, game.direction("UP"), sprite, true);
        Object shift = game.call(TRANSFORMERS, "applying", transformation(game, "translation", 2f, 0f, 0f));

        game.invoke(shift, "processInPlace", quad);
        assertEquals(0f, x(game, quad, 0), "the record itself cannot change");
        assertEquals(2f, x(game, game.call(VERTICES, "sync", quad), 0), "a sync reads the edit back");

        Object other = game.call(VERTICES, "create", upFace(0), 0, game.direction("UP"), sprite, true);
        List<Object> quads = new ArrayList<>(List.of(other));
        game.invoke(shift, "processInPlace", quads);
        assertNotSame(other, quads.get(0));
        assertEquals(2f, x(game, quads.get(0), 0), "a list that can change gets the rebuilt quad");

        Object fixed = game.call(VERTICES, "create", upFace(0), 0, game.direction("UP"), sprite, true);
        List<Object> readOnly = List.of(fixed);
        game.invoke(shift, "processInPlace", readOnly);
        assertSame(fixed, readOnly.get(0));
        assertEquals(2f, x(game, game.call(VERTICES, "sync", fixed), 0), "a read-only list leaves the edit for a later sync");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {HOST_262, HOST_263})
    @DisplayName("A vertex color edit is dropped, noted once, and the rest of the quad still syncs")
    void colorEditIsDroppedAndNotedOnce(String host) throws Exception {
        ModelHostFixtures.Host game = ModelHostFixtures.load(host, dir, transformer);
        Object sprite = game.sprite("stone", false, false);
        Object quad = game.call(VERTICES, "create", upFace(0), 0, game.direction("UP"), sprite, true);
        Object red = game.call(TRANSFORMERS, "applyingColor", 0xFFFF0000);
        Object colored = process(game, red, quad);
        assertEquals(game.invoke(quad, "materialInfo"), game.invoke(colored, "materialInfo"));
        process(game, red, quad);
        java.lang.reflect.Field warned = game.type("com/retromod/shim/common/embedded/LegacyLog").getDeclaredField("WARNED");
        warned.setAccessible(true);
        Set<?> notes = (Set<?>) warned.get(null);
        assertEquals(1, notes.stream().filter(note -> note.toString().startsWith("legacy quad color")).count(),
                "one note however many quads lose their color: " + notes);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {HOST_262, HOST_263, HOST_263_EXTRA})
    @DisplayName("The material helper builds through the running host's own record, six fields on 26.2 and eight on 26.3")
    void materialHelperPicksTheHostConstructor(String host) throws Exception {
        ModelHostFixtures.Host game = ModelHostFixtures.load(host, dir, transformer);
        List<String> expected = switch (host) {
            case HOST_262 -> List.of("sprite", "layer", "itemRenderType", "tintIndex", "shade", "lightEmission");
            case HOST_263 -> List.of("sprite", "layer", "itemRenderType", "itemGlintRenderType", "itemGlintSpecialRenderType",
                    "tintIndex", "shadeDirectionOverride", "lightEmission");
            default -> List.of("sprite", "layer", "itemRenderType", "itemGlintRenderType", "itemGlintSpecialRenderType",
                    "tintIndex", "shadeDirectionOverride", "lightEmission", "extraFlag");
        };
        Object names = game.call(MATERIALS, "fieldNames");
        List<Object> actual = new ArrayList<>();
        for (int i = 0; i < Array.getLength(names); i++) actual.add(Array.get(names, i));
        assertEquals(expected, actual);

        Object glass = game.sprite("glass", true, true);
        Object material = game.call(MATERIALS, "create", glass, 1, false, 4);
        Map<String, Object> fields = fields(material);
        assertSame(glass, fields.get("sprite"));
        assertSame(game.layer("TRANSLUCENT"), fields.get("layer"), "the host's own factory picked the layer");
        assertEquals("translucent_item", String.valueOf(fields.get("itemRenderType")));
        assertEquals(1, fields.get("tintIndex"));
        assertEquals(4, fields.get("lightEmission"));
        if (host.equals(HOST_262)) {
            assertEquals(false, fields.get("shade"));
        } else {
            assertSame(game.direction("UP"), fields.get("shadeDirectionOverride"), "26.3 lights an unshaded quad like an up face");
            assertEquals("glint", String.valueOf(fields.get("itemGlintRenderType")));
        }
        assertEquals(false, game.call(MATERIALS, "shade", material));

        // Changing one field keeps every other, including one the helper has no name for.
        Object solid = game.call(MATERIALS, "withLayer", material, game.layer("SOLID"));
        Map<String, Object> changed = fields(solid);
        assertSame(game.layer("SOLID"), changed.remove("layer"));
        fields.remove("layer");
        assertEquals(fields, changed);
        if (host.equals(HOST_263_EXTRA)) assertEquals(7, fields.get("extraFlag"));
        assertSame(material, game.call(MATERIALS, "withLayer", material, game.layer("TRANSLUCENT")), "no change, same material");

        Object shaded = game.call(MATERIALS, "withShade", material, true);
        assertEquals(true, game.call(MATERIALS, "shade", shaded));
        assertEquals(9, fields(game.call(MATERIALS, "withLightEmission", material, 9)).get("lightEmission"));
        assertEquals(15, fields(game.call(MATERIALS, "withLightEmission", material, 99)).get("lightEmission"));
        assertEquals(3, fields(game.call(MATERIALS, "withTintIndex", material, 3)).get("tintIndex"));

        // No sprite: the canonical constructor with a solid layer and empty render types.
        Map<String, Object> bare = fields(game.call(MATERIALS, "create", null, -1, true, 0));
        assertNull(bare.get("sprite"));
        assertSame(game.layer("SOLID"), bare.get("layer"));
        assertNull(bare.get("itemRenderType"));
    }

    @org.junit.jupiter.api.Test
    @DisplayName("On a host whose material cannot be read, old quads still build, keep their material and never throw")
    void materialHelperFallsBackOnAnUnknownHost() throws Exception {
        ModelHostFixtures.Host game = ModelHostFixtures.load(ModelHostFixtures.HOST_UNKNOWN, dir, transformer);
        assertEquals(0, Array.getLength(game.call(MATERIALS, "fieldNames")));
        Object sprite = game.sprite("glass", true, true);
        Object material = game.call(MATERIALS, "create", sprite, 1, false, 4);
        assertNotNull(material, "the widest constructor, filled by type");
        assertSame(sprite, game.invoke(material, "sprite"));
        assertSame(game.layer("SOLID"), game.invoke(material, "layer"));
        assertSame(material, game.call(MATERIALS, "withLayer", material, game.layer("CUTOUT")), "a change keeps the material");
        assertSame(material, game.call(MATERIALS, "withShade", material, true));
        assertEquals(true, game.call(MATERIALS, "shade", material));

        Object quad = game.call(VERTICES, "create", upFace(0), 1, game.direction("UP"), sprite, true);
        int[] vertices = (int[]) game.call(VERTICES, "getVertices", quad);
        vertices[0] = Float.floatToRawIntBits(0.5f);
        Object synced = game.call(VERTICES, "sync", quad);
        assertEquals(0.5f, x(game, synced, 0), "positions still sync");
        assertSame(synced, game.call(VERTICES, "sync", quad));
        assertSame(game.invoke(quad, "materialInfo"), game.invoke(game.call(VERTICES, "withLayer", synced, game.layer("CUTOUT")),
                "materialInfo"), "the stamp keeps the quad's own material");

        java.lang.reflect.Field warned = game.type("com/retromod/shim/common/embedded/LegacyLog").getDeclaredField("WARNED");
        warned.setAccessible(true);
        assertEquals(1, ((Set<?>) warned.get(null)).stream().filter(note -> note.toString().startsWith("legacy quad material"))
                .count(), "the unknown host is noted once");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {HOST_262, HOST_263})
    @DisplayName("The side table holds only quads old code read or built: not a copy's source, not a stamped quad")
    void sideTableHoldsOnlyWhatOldCodeTouched(String host) throws Exception {
        ModelHostFixtures.Host game = ModelHostFixtures.load(host, dir, transformer);
        Object sprite = game.sprite("stone", false, false);
        Object material = game.call(MATERIALS, "create", sprite, 0, true, 0);
        Object hostQuad = game.call(VERTICES, "build", upFace(0), game.direction("UP"), material);
        List<Object> kept = new ArrayList<>(List.of(hostQuad));
        assertEquals(0, tableSize(game));

        kept.add(game.call(VERTICES, "copy", hostQuad));
        assertEquals(1, tableSize(game), "the copy is listed, so edits to it can sync; its source is not");
        Object stamped = game.call(VERTICES, "withLayer", hostQuad, game.layer("CUTOUT"));
        kept.add(stamped);
        assertSame(game.layer("CUTOUT"), game.invoke(game.invoke(stamped, "materialInfo"), "layer"));
        assertEquals(1, tableSize(game), "a stamped quad is not listed");
        game.call(VERTICES, "getVertices", hostQuad);
        assertEquals(2, tableSize(game), "a quad whose array old code read is listed");
        java.lang.ref.Reference.reachabilityFence(kept);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {HOST_262, HOST_263})
    @DisplayName("Conversions from several threads at once agree: one array per quad, and every edit syncs")
    void conversionsAcrossThreads(String host) throws Exception {
        ModelHostFixtures.Host game = ModelHostFixtures.load(host, dir, transformer);
        Object sprite = game.sprite("stone", false, false);
        Object material = game.call(MATERIALS, "create", sprite, 0, true, 0);
        Object shared = game.call(VERTICES, "build", upFace(0), game.direction("UP"), material);
        Object shift = game.call(TRANSFORMERS, "applying", transformation(game, "translation", 1f, 0f, 0f));
        Set<int[]> sharedArrays = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            List<java.util.concurrent.Future<?>> work = new ArrayList<>();
            for (int thread = 0; thread < 8; thread++) {
                int offset = thread * 1000;
                work.add(pool.submit(() -> {
                    for (int i = 0; i < 300; i++) {
                        sharedArrays.add((int[]) game.call(VERTICES, "getVertices", shared));
                        float at = offset + i;
                        int[] mine = upFace(0);
                        Object quad = game.call(VERTICES, "create", mine, 0, game.direction("UP"), sprite, true);
                        mine[0] = Float.floatToRawIntBits(at);
                        Object synced = game.call(VERTICES, "sync", quad);
                        assertEquals(at, x(game, synced, 0));
                        assertEquals(at + 1, x(game, process(game, shift, quad), 0));
                        assertEquals(at, x(game, game.call(VERTICES, "copy", quad), 0));
                        assertSame(game.layer("CUTOUT"), game.invoke(game.invoke(
                                game.call(VERTICES, "withLayer", quad, game.layer("CUTOUT")), "materialInfo"), "layer"));
                    }
                    return null;
                }));
            }
            for (java.util.concurrent.Future<?> done : work) done.get();
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, sharedArrays.size(), "every thread read the same array for the one shared quad");
    }

    // ---- helpers ----

    private static int tableSize(ModelHostFixtures.Host game) throws Exception {
        java.lang.reflect.Field table = game.type(VERTICES).getDeclaredField("ARRAYS");
        table.setAccessible(true);
        return ((Map<?, ?>) table.get(null)).size();
    }

    private static final int COLOR = 3;

    /** An up face from (0,1,0) to (1,1,1), shifted along x, with UVs, white, no light and the up normal, in Forge 1.20.1's layout. */
    static int[] upFace(float shift) {
        float[][] positions = {{0, 1, 0}, {0, 1, 1}, {1, 1, 1}, {1, 1, 0}};
        float[][] uvs = {{0.25f, 0.5f}, {0.25f, 0.75f}, {0.5f, 0.75f}, {0.5f, 0.5f}};
        int[] vertices = new int[32];
        for (int vertex = 0; vertex < 4; vertex++) {
            int base = vertex * 8;
            vertices[base] = Float.floatToRawIntBits(positions[vertex][0] + shift);
            vertices[base + 1] = Float.floatToRawIntBits(positions[vertex][1]);
            vertices[base + 2] = Float.floatToRawIntBits(positions[vertex][2]);
            vertices[base + 3] = WHITE;
            vertices[base + 4] = Float.floatToRawIntBits(uvs[vertex][0]);
            vertices[base + 5] = Float.floatToRawIntBits(uvs[vertex][1]);
            vertices[base + 6] = 0;
            vertices[base + 7] = NORMAL_UP;
        }
        return vertices;
    }

    private static Object process(ModelHostFixtures.Host game, Object quadTransformer, Object quad) throws Exception {
        for (java.lang.reflect.Method method : game.type(LegacyForgeModelBridge.QUAD_TRANSFORMER).getMethods()) {
            if (method.getName().equals("process") && method.getParameterTypes()[0] == game.type(QUAD)) {
                return method.invoke(quadTransformer, quad);
            }
        }
        throw new AssertionError("no process(BakedQuad)");
    }

    private static float x(ModelHostFixtures.Host game, Object quad, int vertex) throws Exception {
        return ((Number) game.invoke(game.invoke(quad, "position", vertex), "x")).floatValue();
    }

    /** A Transformation over JOML's {@code new Matrix4f().<factory>(args)}. */
    private static Object transformation(ModelHostFixtures.Host game, String factory, float... args) throws Exception {
        Class<?> matrixType = game.type("org/joml/Matrix4f");
        Object matrix = matrixType.getConstructor().newInstance();
        Class<?>[] params = new Class<?>[args.length];
        Arrays.fill(params, float.class);
        Object[] values = new Object[args.length];
        for (int i = 0; i < args.length; i++) values[i] = args[i];
        matrixType.getMethod(factory, params).invoke(matrix, values);
        return game.type("com/mojang/math/Transformation").getConstructor(game.type("org/joml/Matrix4fc")).newInstance(matrix);
    }

    private static Map<String, Object> fields(Object material) throws Exception {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (RecordComponent component : material.getClass().getRecordComponents()) {
            fields.put(component.getName(), component.getAccessor().invoke(material));
        }
        return fields;
    }
}
