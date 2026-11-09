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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

import static com.retromod.shim.forge.ModelHostFixtures.HOST_262;
import static com.retromod.shim.forge.ModelHostFixtures.HOST_263;
import static com.retromod.shim.forge.ModelHostFixtures.SECOND;
import static org.junit.jupiter.api.Assertions.*;

/** Forge 1.20.1's ChunkRenderTypeSet, block layer table and render type groups over Minecraft 26.2's three block layers. */
class ChunkRenderTypeSetTest {

    private static final String SET = LegacyForgeModelBridge.CHUNK_RENDER_TYPE_SET;
    private static final String LAYERS = LegacyForgeModelBridge.RENDER_LAYERS;
    private static final String GROUPS = LegacyForgeModelBridge.RENDER_TYPE_GROUPS;
    private static final String POLYFILL = "com/retromod/polyfill/minecraft/RetroItemBlockRenderTypes";
    private static final String SHARED_TABLE = "retromod.shared.renderLayers.v1";
    private static final String READING_MARK = "retromod.shared.forge.renderLayers.reading.v1";

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
        System.getProperties().remove(SHARED_TABLE);
        System.getProperties().remove(READING_MARK);
    }

    @AfterEach
    void restore() {
        System.getProperties().remove(SHARED_TABLE);
        System.getProperties().remove(READING_MARK);
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        transformer.clearRedirectsForTesting();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {HOST_262, HOST_263})
    @DisplayName("Loading the set, and none, all and its bits, initialize no render type and no chunk layer")
    void loadingTheSetTouchesNoRenderType(String host) throws Exception {
        ModelHostFixtures.Host game = ModelHostFixtures.load(host, dir, transformer);
        Class.forName(SET.replace('/', '.'), true, game.loader());
        assertEquals(true, game.invoke(game.call(SET, "none"), "isEmpty"));
        assertEquals(false, game.invoke(game.call(SET, "all"), "isEmpty"));
        assertEquals(5, game.invoke(game.call(SET, "ofBits", 5), "bits"));
        assertEquals(List.of(), game.initialized(), "an old static initializer may build sets before render types can load");

        game.invoke(game.call(SET, "all"), "contains", game.renderType("cutoutMovingBlock"));
        assertEquals(List.of("RenderTypes"), game.initialized(), "render types resolve on first real use");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {HOST_262, HOST_263})
    @DisplayName("A set of the cutout token holds CUTOUT, and sets keep Forge 1.20.1's order, union and intersection")
    void setsHoldLayers(String host) throws Exception {
        ModelHostFixtures.Host game = ModelHostFixtures.load(host, dir, transformer);
        Object solid = game.renderType("solidMovingBlock");
        Object cutout = game.renderType("cutoutMovingBlock");
        Object translucent = game.renderType("translucentMovingBlock");
        Object renderTypes = java.lang.reflect.Array.newInstance(cutout.getClass(), 1);
        java.lang.reflect.Array.set(renderTypes, 0, cutout);

        Object cutoutSet = game.call(SET, "of", renderTypes);
        assertEquals(true, game.invoke(cutoutSet, "contains", cutout));
        assertEquals(true, game.invoke(cutoutSet, "contains", game.layer("CUTOUT")));
        assertEquals(false, game.invoke(cutoutSet, "contains", solid));
        assertEquals(List.of(cutout), game.invoke(cutoutSet, "asList"));
        assertEquals(List.of(game.layer("CUTOUT")), game.invoke(cutoutSet, "layers"));

        List<Object> walked = new ArrayList<>();
        ((Iterable<?>) game.call(SET, "all")).forEach(walked::add);
        assertEquals(List.of(solid, cutout, translucent), walked, "solid, cutout, translucent, as the layers draw");

        Object translucentSet = game.call(SET, "of", List.of(translucent));
        Object union = game.call(SET, "union", List.of(cutoutSet, translucentSet));
        assertEquals(List.of(cutout, translucent), game.invoke(union, "asList"));
        assertEquals(true, game.invoke(game.call(SET, "intersection", List.of(cutoutSet, translucentSet)), "isEmpty"));
        assertEquals(game.call(SET, "all"), game.call(SET, "intersection", List.of()), "no sets at all is every layer");
        assertEquals(List.of(translucent), game.invoke(game.call(SET, "matching", (Predicate<Object>) type -> type == translucent),
                "asList"));

        assertSame(game.layer("SOLID"), game.call(SET, "layerOf", solid));
        assertSame(game.layer("TRANSLUCENT"), game.call(SET, "layerOf", translucent));
        assertSame(cutout, game.call(SET, "renderTypeOf", game.layer("CUTOUT")));
        Object entity = game.type("net/minecraft/client/renderer/rendertype/RenderType").getConstructor(String.class)
                .newInstance("entity_cutout");
        assertNull(game.call(SET, "layerOf", entity));
        assertEquals(false, game.invoke(game.call(SET, "all"), "contains", entity));
        assertThrows(IllegalArgumentException.class, () -> game.call(SET, "of", List.of(entity)),
                "a render type that is no block layer is refused, as Forge 1.20.1 refused it");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {HOST_262, HOST_263})
    @DisplayName("Layers set through any old setter are read back by every mod's copy; an unset block reads solid")
    void renderLayersAreSharedAcrossCopies(String host) throws Exception {
        ModelHostFixtures.Host game = ModelHostFixtures.load(host, dir, transformer);
        Object cutout = game.renderType("cutoutMovingBlock");
        Object translucent = game.renderType("translucentMovingBlock");
        Class<?> blockType = game.type("net/minecraft/world/level/block/Block");
        Class<?> stateType = game.type("net/minecraft/world/level/block/state/BlockState");
        Object leaves = blockType.getConstructor(String.class).newInstance("leaves");
        Object glass = blockType.getConstructor(String.class).newInstance("glass");
        Object both = blockType.getConstructor(String.class).newInstance("both");
        Object plain = blockType.getConstructor(String.class).newInstance("plain");

        // One mod's copy records through the common polyfill, with a render type and with a predicate.
        game.call(POLYFILL, "setRenderLayer", leaves, cutout);
        game.call(POLYFILL, "setRenderLayer", glass, (Predicate<Object>) type -> type == translucent);
        // Another mod's copy records a whole set.
        Object set = game.call(SECOND + SET, "ofBits", 6);
        game.call(SECOND + LAYERS, "setRenderLayer", both, set);

        for (String copy : List.of("", SECOND)) {
            assertEquals(List.of(game.layer("CUTOUT")), layers(game, copy, stateType, leaves), copy);
            assertEquals(List.of(game.layer("TRANSLUCENT")), layers(game, copy, stateType, glass), copy);
            assertEquals(List.of(game.layer("CUTOUT"), game.layer("TRANSLUCENT")), layers(game, copy, stateType, both), copy);
            assertEquals(List.of(game.layer("SOLID")), layers(game, copy, stateType, plain),
                    "no call and no model to read: Forge 1.20.1's default");
        }
        assertNull(game.call(LAYERS, "recorded", plain), "the model adapters can tell an unset block from a solid one");
        assertNotNull(game.call(LAYERS, "recorded", leaves));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {HOST_262, HOST_263})
    @DisplayName("Old RenderTypeGroup constructors put the token's layer in the host group, and block() gives the token back")
    void renderTypeGroupsMapTokensToLayers(String host) throws Exception {
        ModelHostFixtures.Host game = ModelHostFixtures.load(host, dir, transformer);
        Object cutout = game.renderType("cutoutMovingBlock");
        Object entity = game.type("net/minecraft/client/renderer/rendertype/RenderType").getConstructor(String.class)
                .newInstance("entity_cutout");
        Object fabulous = game.type("net/minecraft/client/renderer/rendertype/RenderType").getConstructor(String.class)
                .newInstance("entity_fabulous");

        Object group = game.call(GROUPS, "create", cutout, entity);
        assertSame(game.layer("CUTOUT"), game.invoke(group, "block"));
        assertSame(entity, game.invoke(group, "entity"));
        assertSame(entity, game.invoke(group, "entityFabulous"), "the two-argument form uses the entity type twice");
        assertSame(cutout, game.call(GROUPS, "block", group));

        Object three = game.call(GROUPS, "create", game.renderType("translucentMovingBlock"), entity, fabulous);
        assertSame(game.layer("TRANSLUCENT"), game.invoke(three, "block"));
        assertSame(fabulous, game.invoke(three, "entityFabulous"));

        Object empty = game.type("net/minecraftforge/client/RenderTypeGroup").getField("EMPTY").get(null);
        assertNull(game.call(GROUPS, "block", empty));
        assertSame(game.layer("SOLID"), game.invoke(game.call(GROUPS, "create", entity, entity), "block"),
                "a block type that is no layer draws solid");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {HOST_262, HOST_263})
    @DisplayName("A model that asks for its own layers while the host reads it gets solid back, and is read once")
    @SuppressWarnings("unchecked")
    void layerReadsDoNotLoopThroughTheModel(String host) throws Exception {
        ModelHostFixtures.Host game = ModelHostFixtures.load(host, dir, transformer);
        Class<?> models = game.type("testhost/Models");
        Class<?> blockType = game.type("net/minecraft/world/level/block/Block");
        Class<?> stateType = game.type("net/minecraft/world/level/block/state/BlockState");
        Object framed = stateType.getConstructor(blockType).newInstance(blockType.getConstructor(String.class).newInstance("framed"));
        Object glass = stateType.getConstructor(blockType).newInstance(blockType.getConstructor(String.class).newInstance("glass"));
        Map<Object, Object> modelLayers = (Map<Object, Object>) models.getField("LAYERS").get(null);
        modelLayers.put(framed, game.layer("CUTOUT"));
        modelLayers.put(glass, game.layer("TRANSLUCENT"));

        // The model asks again while it is read, as 1.20.1's default getRenderTypes does, here through another mod's copy.
        List<Object> nested = new ArrayList<>();
        Function<Object, Object> asksAgain = state -> {
            try {
                Object set = game.call(SECOND + LAYERS, "getRenderLayers", state);
                nested.add(game.invoke(set, "layers"));
                return set;
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        };
        models.getField("whileRead").set(null, asksAgain);
        Object layers = game.invoke(game.call(LAYERS, "getRenderLayers", framed), "layers");

        assertEquals(List.of(game.layer("CUTOUT")), layers, "the outer ask reads the model's own quad");
        assertEquals(1, models.getField("reads").getInt(null), "the host model is read once, not until the stack runs out");
        assertEquals(List.of(List.of(game.layer("SOLID"))), nested, "the nested ask answers solid without reading again");

        // The mark is gone once the read ends, so the next state reads its model as usual.
        models.getField("whileRead").set(null, null);
        assertEquals(List.of(game.layer("TRANSLUCENT")), game.invoke(game.call(SECOND + LAYERS, "getRenderLayers", glass), "layers"));
    }

    private static Object layers(ModelHostFixtures.Host game, String copy, Class<?> stateType, Object block) throws Exception {
        Object state = stateType.getConstructor(block.getClass()).newInstance(block);
        return game.invoke(game.call(copy + LAYERS, "getRenderLayers", state), "layers");
    }
}
