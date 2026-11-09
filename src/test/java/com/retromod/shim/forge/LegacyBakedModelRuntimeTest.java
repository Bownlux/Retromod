/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.File;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/** Two old mods' baked models, each through its own embedded copy, over stand-in Minecraft 26.2 and 26.3 models and Forge's real EventBus 7. */
class LegacyBakedModelRuntimeTest {

    static final Path LEGACY_CLASSES = Path.of("target", "legacyapi-classes");
    static final List<String> RELOCATED = List.of("com/retromod/legacyapi/", "com/retromod/polyfill/",
            "com/retromod/shim/common/embedded/");
    static final String SECOND = "com/retromod/embedded/second/";
    private static final String MODEL = LegacyForgeBakedModelBridge.ROOT;
    static final String SHARED_SLOTS = "retromod.shared.forge.";
    static final List<String> LEFT_OUT = List.of("net/minecraftforge/fml/ModLoader", "net/minecraftforge/fml/core/ModStateProvider");

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
        transformer.setModSourceVersion("1.20.1");
        clearSharedSlots();
    }

    @AfterEach
    void restore() {
        clearSharedSlots();
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        transformer.setModSourceVersion(null);
        transformer.clearRedirectsForTesting();
    }

    // The shared slots live in system properties, so no test sees another's copies.
    static void clearSharedSlots() {
        System.getProperties().keySet().removeIf(key -> key.toString().startsWith(SHARED_SLOTS));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {ModelHostFixtures.HOST_262, ModelHostFixtures.HOST_263})
    @DisplayName("ModifyBakingResult puts by 1.20.1 keys reach the host's block and item models, through both copies")
    void modifyBakingResultAcrossTwoCopies(String host) throws Throwable {
        Game game = game(host);
        Object lampLit = game.world("lampLit");
        Object lampOff = game.world("lampOff");
        Object dynamic = game.world("dynamic");
        Object hostLamp = game.hostModel(1);
        Object hostLampOff = game.hostModel(2);
        Object gemQuad = game.call("testhost/World", "quad", null, 7, game.world("GLASS"));
        Map<Object, Object> blocks = new HashMap<>(Map.of(lampLit, hostLamp, lampOff, hostLampOff, dynamic, game.hostModel(3)));
        Map<Object, Object> items = new HashMap<>(Map.of(game.id("moda", "gem"), game.cuboid(List.of(gemQuad), 0.5f),
                game.id("moda", "wand"), game.cuboid(List.of(gemQuad), 0f)));
        Object event = game.modifyEvent(blocks, items);

        // Forge posts the event on its own bus, where each copy keeps the bake; then the old listeners run in mod order.
        game.post("net/minecraftforge/client/event/ModelEvent$ModifyBakingResult", event);
        game.invoke("moda/LampModels", "onModify", event);
        game.invoke("modb/SecondModels", "onModify", event);

        assertEquals(List.of("A read moda:lamp#lit=true true", "A lamp keys 2", "A missing true"), game.notes(),
                "the lamp's key reads as Forge 1.20.1 printed it, both lamp states are keys, and the missing model is there");
        game.clearNotes();

        // modb wrapped moda's lamp; each asks its wrapped model for the cutout layer the host model draws in.
        Object lamp = blocks.get(lampLit);
        assertEquals(SECOND + MODEL + "LegacyBlockStateModel", lamp.getClass().getName().replace('.', '/'));
        List<?> parts = game.collectParts(lamp, game.modelData("empty"));
        assertEquals(1, parts.size(), "one part, for the one layer");
        // Each mod's wrapper is asked once per layer; modb reads moda's through a view, which builds moda's parts per side.
        List<String> notes = game.notes();
        assertEquals(1, Collections.frequency(notes, "B wrapper asked cutout"), notes.toString());
        assertTrue(notes.contains("A wrapper asked cutout"), notes.toString());
        assertTrue(notes.stream().allMatch(note -> note.endsWith("asked cutout")), "no other layer is asked for: " + notes);
        assertEquals(List.of(1), game.tints(parts.get(0)), "the host lamp's own quad, through both wrappers");
        assertEquals(List.of("CUTOUT"), game.layers(parts.get(0)));
        assertSame(hostLampOff, blocks.get(lampOff), "the other lamp state keeps its host model");

        // The dynamic model answers per side, layer and model data, each quad stamped with the layer it was asked for.
        Object dynamicModel = blocks.get(dynamic);
        assertEquals(MODEL + "LegacyBlockStateModel", dynamicModel.getClass().getName().replace('.', '/'));
        Object data = game.getModelData(dynamicModel, dynamic);
        assertEquals("lit-data", game.tag(data), "getModelData reaches the old model");
        List<?> dynamicParts = game.collectParts(dynamicModel, data);
        assertEquals(2, dynamicParts.size(), "one part per layer the old model names");
        assertEquals(List.of(11, 111), game.tints(dynamicParts.get(0)), "cutout: the up face and the unculled face, with data");
        assertEquals(List.of("CUTOUT", "CUTOUT"), game.layers(dynamicParts.get(0)));
        assertEquals(List.of(12, 112), game.tints(dynamicParts.get(1)));
        assertEquals(List.of("TRANSLUCENT", "TRANSLUCENT"), game.layers(dynamicParts.get(1)));
        assertEquals(List.of(1, 101), game.tints(game.collectParts(dynamicModel, game.modelData("empty")).get(0)),
                "without the data the old model answers differently");

        // The item: the old wrapper is drawn, its display transform turned into the layer's own transform.
        Object gem = items.get(game.id("moda", "gem"));
        assertEquals(MODEL + "LegacyItemModel", gem.getClass().getName().replace('.', '/'));
        game.clearNotes();
        Object gemState = game.draw(gem, game.stack("GEM", 1));
        Object layer = game.onlyLayer(gemState);
        assertEquals(List.of("A item wrapper quads"), game.notes());
        assertEquals(List.of(7), game.layerTints(layer), "the host item's quad reached the layer");
        // The host's GUI transform lifts by 0.5 and doubles the size; Forge 1.20.1 drew transform * T(-0.5).
        assertArrayEquals(new float[]{2, 2, 2, -1, -0.5f, -1}, game.composed(layer), 1e-5f,
                "the host's centring and the local transform compose to the display transform, then T(-0.5)");

        // The wand's overrides pick the model for each stack, and the GUI keys its cached picture on that model.
        Object wand = items.get(game.id("moda", "wand"));
        Object one = game.draw(wand, game.stack("WAND", 1));
        Object again = game.draw(wand, game.stack("WAND", 1));
        Object two = game.draw(wand, game.stack("WAND", 2));
        assertEquals(game.identity(one), game.identity(again), "the same stack draws with the same identity");
        assertNotEquals(game.identity(one), game.identity(two), "a stack whose overrides pick another model draws apart");
        assertArrayEquals(new float[]{1, 1, 1, -0.5f, 1.5f, -0.5f}, game.composed(game.onlyLayer(two)), 1e-5f,
                "an old applyTransform of its own composes to its pose * T(-0.5)");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {ModelHostFixtures.HOST_262, ModelHostFixtures.HOST_263})
    @DisplayName("BakingCompleted reads the last bake and refuses puts, and an unchangeable bake is skipped without a throw")
    void bakingCompletedAndUnchangeableMaps(String host) throws Throwable {
        Game game = game(host);
        Object lampLit = game.world("lampLit");
        Object hostLamp = game.hostModel(1);
        Object event = game.modifyEvent(Map.of(lampLit, hostLamp), Map.of());
        game.post("net/minecraftforge/client/event/ModelEvent$ModifyBakingResult", event);

        // Minecraft hands a bake of zero or one models over as Map.of.
        game.invoke("moda/LampModels", "onModify", event);
        assertEquals(List.of("A read moda:lamp#lit=true true", "A lamp keys 1", "A missing true"), game.notes());

        game.clearNotes();
        game.invoke("moda/LampModels", "onCompleted", game.completedEvent());
        assertEquals(List.of("A completed sees LegacyBlockStateModelView", "A completed read-only"), game.notes());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {ModelHostFixtures.HOST_262, ModelHostFixtures.HOST_263})
    @DisplayName("RegisterAdditional runs each time a reload reads item models, and its models are baked as item models getModel finds")
    void registerAdditionalProducesAModelTheHostFinds(String host) throws Throwable {
        Game game = game(host);
        game.subscribe("", "moda/LampModels", "moda");
        Path gameDir = Files.createDirectories(dir.resolve("game"));
        game.type("net/minecraftforge/fml/loading/FMLPaths").getField("root").set(null, gameDir);
        List<Object> sources = new ArrayList<>();
        game.postPackFinders(sources);
        assertEquals(2, sources.size(), "each copy offers a source");

        // Only moda's copy has a RegisterAdditional listener, so only its source lists a pack; listing posts nothing.
        List<Object> packs = game.listPacks(sources);
        assertEquals(1, packs.size(), "one client pack for the copies' extra models");
        assertEquals(List.of(), game.notes(), "the event waits until a reload reads the item models");
        Path root = gameDir.resolve("retromod-cache/extra-models/moda");
        assertTrue(Files.readString(root.resolve("pack.mcmeta")).contains("\"pack_format\":84"), "a stable folder, so the pack keeps its place");

        // The reload opens the pack, and reading its item models posts the event once for that reload.
        Object reload = game.openPack(packs.get(0));
        assertTrue(game.namespaces(reload).containsAll(List.of("moda", "modb", "minecraft")),
                "the pack claims every mod's namespace before the mods are asked");
        Map<String, String> definitions = game.items(reload, "moda");
        // Calls on the old location type answer from its path; instanceof tells a location from a plain id.
        assertEquals(List.of("A register book moda:item/book moda.book false true"), game.notes());
        assertEquals(Map.of("moda:items/retromod_extra/block/extra.json",
                "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"moda:block/extra\"}}",
                "moda:items/retromod_extra/item/book.json", "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"moda:item/book\"}}",
                "moda:items/retromod_extra/item/gem.json", "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"moda:item/gem\"}}"),
                definitions, "an inventory location reads the item model folder, as Forge 1.20.1 read it");
        try (Stream<Path> files = Files.walk(root)) {
            assertEquals(4, files.filter(Files::isRegularFile).count(), "no definition for the block state's location");
        }
        assertEquals(Map.of(), game.items(reload, "modb"), "one post per reload, whatever namespaces it reads");
        assertEquals(1, game.notes().size());

        // The next reload opens the pack again, posts again and writes the item models whole.
        Files.writeString(root.resolve("assets/moda/items/stale.json"), "{}");
        game.clearNotes();
        game.items(game.openPack(game.listPacks(sources).get(0)), "moda");
        assertEquals(1, game.notes().size());
        assertFalse(Files.exists(root.resolve("assets/moda/items/stale.json")), "the item models are written whole each time");

        // Minecraft bakes the definition into an item model, which getModel and getModels then find.
        Object extraQuad = game.call("testhost/World", "quad", null, 9, game.world("STONE"));
        Object extraModel = game.cuboid(List.of(extraQuad), 0f);
        Object bookQuad = game.call("testhost/World", "quad", null, 10, game.world("STONE"));
        Object gemQuad = game.call("testhost/World", "quad", null, 7, game.world("GLASS"));
        Object extraGemQuad = game.call("testhost/World", "quad", null, 11, game.world("GLASS"));
        Map<Object, Object> items = new HashMap<>(Map.of(game.id("moda", "retromod_extra/block/extra"), extraModel,
                game.id("moda", "retromod_extra/item/book"), game.cuboid(List.of(bookQuad), 0f),
                game.id("moda", "gem"), game.cuboid(List.of(gemQuad), 0f),
                game.id("moda", "retromod_extra/item/gem"), game.cuboid(List.of(extraGemQuad), 0f)));
        Object event = game.modifyEvent(new HashMap<>(), items);
        game.post("net/minecraftforge/client/event/ModelEvent$ModifyBakingResult", event);
        Object manager = game.manager(items);
        Object model = game.call(MODEL + "LegacyModelMaps", "getModel", manager, game.id("moda", "block/extra"));
        assertEquals(MODEL + "LegacyItemModelView", model.getClass().getName().replace('.', '/'));
        assertEquals(List.of(9), game.quadTints(model), "the baked extra model's quads");
        Object book = game.call(MODEL + "LegacyModelLocations", "of", game.id("moda", "book"), "inventory");
        assertEquals(List.of(10), game.quadTints(game.call(MODEL + "LegacyModelMaps", "getModel", manager, book)),
                "the inventory location finds its own extra model, not the plain one");
        Map<?, ?> models = (Map<?, ?>) game.call(MODEL + "LegacyModelMaps", "getModels", event);
        assertTrue(models.containsKey(book) && models.keySet().contains(book), "getModels lists the registered location");
        // An item's own inventory location keeps naming the item's model, even when a mod registered it as well.
        Object gem = game.call(MODEL + "LegacyModelLocations", "of", game.id("moda", "gem"), "inventory");
        assertEquals(List.of(7), game.quadTints(models.get(gem)), "getModels reaches the item's own model");
        assertEquals(List.of(7), game.quadTints(game.call(MODEL + "LegacyModelMaps", "getModel", manager, gem)));
        assertEquals("LegacyBlockStateModelView", game.call(MODEL + "LegacyModelMaps", "getModel", manager,
                game.id("moda", "block/missing_one")).getClass().getSimpleName(), "an unregistered model is the missing model");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {ModelHostFixtures.HOST_262, ModelHostFixtures.HOST_263})
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    @DisplayName("A RegisterAdditional listener that waits for common setup gets the event after setup, off the game's thread")
    void listenerWaitingForSetupGetsTheEventAfterSetup(String host) throws Throwable {
        Game game = game(host, SETUP_GATE, BOOK_SHELF);
        game.subscribe("", "moda/BookShelf", "moda");
        game.type("moda/BookShelf").getField("gameThread").set(null, Thread.currentThread());
        game.type("net/minecraftforge/fml/loading/FMLPaths").getField("root").set(null, Files.createDirectories(dir.resolve("game")));
        List<Object> sources = new ArrayList<>();
        game.postPackFinders(sources);

        // Forge 26.2 lists the packs on Minecraft's own thread, inside its constructor and before common setup.
        List<Object> packs = game.listPacks(sources);
        assertEquals(List.of(), game.notes(), "listing the packs posts nothing");
        Object reload = game.openPack(packs.get(0));
        assertEquals(Map.of(), game.items(reload, "moda"), "the game's own thread reads no item models before setup and never waits");
        assertEquals(List.of(), game.notes());

        // The reload's item loader runs on a worker, which waits until common setup is done.
        ForkJoinPool workers = new ForkJoinPool(1);
        try {
            CompletableFuture<Map<String, String>> itemLoader = CompletableFuture.supplyAsync(() -> {
                try {
                    return game.items(reload, "moda");
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            }, workers);
            Thread.sleep(300);
            assertFalse(itemLoader.isDone(), "the item loader waits for common setup");
            assertEquals(List.of(), game.notes(), "nothing is posted before common setup is done");
            // Common setup: the mod loads its books, then Forge marks the state done.
            game.call("moda/BookShelf", "loadBooks");
            game.type("net/minecraftforge/fml/ModLoader").getField("commonSetupDone").set(null, true);
            assertEquals(java.util.Set.of("moda:items/retromod_extra/item/book.json"), itemLoader.get(30, TimeUnit.SECONDS).keySet());
        } finally {
            workers.shutdownNow();
        }
        assertEquals(List.of("books after setup off the game thread"), game.notes(), "posted once, after setup, to a listener that never waited");

        // After setup every reload asks once, on whichever thread reads the item models first.
        game.clearNotes();
        Object next = game.openPack(game.listPacks(sources).get(0));
        game.items(next, "moda");
        game.items(next, "modb");
        game.items(next, "moda");
        assertEquals(List.of("books after setup on the game thread"), game.notes());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {ModelHostFixtures.HOST_262, ModelHostFixtures.HOST_263})
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    @DisplayName("Common setup queued behind two waiting item loaders on a one-thread pool still runs, and the event is posted once")
    void setupQueuedOnTheSamePoolStillRuns(String host) throws Throwable {
        Game game = game(host, SETUP_GATE, BOOK_SHELF);
        game.subscribe("", "moda/BookShelf", "moda");
        game.type("moda/BookShelf").getField("gameThread").set(null, Thread.currentThread());
        game.type("net/minecraftforge/fml/loading/FMLPaths").getField("root").set(null, Files.createDirectories(dir.resolve("game")));
        List<Object> sources = new ArrayList<>();
        game.postPackFinders(sources);
        Object reload = game.openPack(game.listPacks(sources).get(0));
        // Both loaders wait for setup on the pool's one thread; only a compensated wait leaves a thread for setup.
        ForkJoinPool workers = new ForkJoinPool(1);
        try {
            CompletableFuture<Map<String, String>> first = CompletableFuture.supplyAsync(() -> items(game, reload, "moda"), workers);
            Thread.sleep(300);
            // The second loader is already waiting when setup is queued, so setup needs a thread the second wait added.
            CompletableFuture<Map<String, String>> second = CompletableFuture.supplyAsync(() -> items(game, reload, "modb"), workers);
            Thread.sleep(300);
            CompletableFuture<Void> setup = CompletableFuture.runAsync(() -> {
                try {
                    game.call("moda/BookShelf", "loadBooks");
                    game.type("net/minecraftforge/fml/ModLoader").getField("commonSetupDone").set(null, true);
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            }, workers);
            setup.get(30, TimeUnit.SECONDS);
            assertEquals(java.util.Set.of("moda:items/retromod_extra/item/book.json"), first.get(30, TimeUnit.SECONDS).keySet());
            assertEquals(Map.of(), second.get(30, TimeUnit.SECONDS));
        } finally {
            workers.shutdownNow();
        }
        assertEquals(List.of("books after setup off the game thread"), game.notes(), "posted once for the reload, after setup");
    }

    private static Map<String, String> items(Game game, Object reload, String namespace) {
        try {
            return game.items(reload, namespace);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {ModelHostFixtures.HOST_262, ModelHostFixtures.HOST_263})
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    @DisplayName("When Forge's loading fails before common setup is done, the reload reads no extra models instead of waiting forever")
    void failedLoadingDoesNotHangTheReload(String host) throws Throwable {
        Game game = game(host, SETUP_GATE, BOOK_SHELF);
        game.subscribe("", "moda/BookShelf", "moda");
        game.type("net/minecraftforge/fml/loading/FMLPaths").getField("root").set(null, Files.createDirectories(dir.resolve("game")));
        List<Object> sources = new ArrayList<>();
        game.postPackFinders(sources);
        Object reload = game.openPack(game.listPacks(sources).get(0));
        game.type("net/minecraftforge/fml/ModLoader").getField("loadingValid").set(null, false);
        ForkJoinPool workers = new ForkJoinPool(1);
        try {
            CompletableFuture<Map<String, String>> itemLoader = CompletableFuture.supplyAsync(() -> {
                try {
                    return game.items(reload, "moda");
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            }, workers);
            assertEquals(Map.of(), itemLoader.get(30, TimeUnit.SECONDS));
        } finally {
            workers.shutdownNow();
        }
        assertEquals(List.of(), game.notes(), "no listener is asked once loading failed");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {ModelHostFixtures.HOST_262, ModelHostFixtures.HOST_263})
    @DisplayName("A view keeps a host model's parts only during one adapter call, so a later build reads the model again")
    void viewPartsDoNotOutliveTheAdapterCall(String host) throws Throwable {
        Game game = game(host);
        Object lampLit = game.world("lampLit");
        Object hostLamp = game.hostModel(1);
        Map<Object, Object> blocks = new HashMap<>(Map.of(lampLit, hostLamp));
        Object event = game.modifyEvent(blocks, new HashMap<>());
        game.invoke("moda/LampModels", "onModify", event);
        Object lamp = blocks.get(lampLit);
        // One model data object for both builds, as a block entity hands the same one until it changes.
        Object data = game.modelData("same");
        assertEquals(List.of(1), game.tints(game.collectParts(lamp, data).get(0)));

        hostLamp.getClass().getMethod("takePartOf", hostLamp.getClass()).invoke(hostLamp, game.hostModel(5));
        assertEquals(List.of(5), game.tints(game.collectParts(lamp, data).get(0)),
                "the second build, with the same seed and model data, reads the host model's new quads");
    }

    @org.junit.jupiter.api.Test
    @DisplayName("Where Identifier's constructor refuses '#', locations spell the variant with path characters and still round-trip")
    void closedIdentifierSpellsTheVariantInThePath() throws Throwable {
        Game game = game(BakedModelHostFixtures.HOST_262_CLOSED);
        String locations = MODEL + "LegacyModelLocations";
        Object lit = game.call(locations, "of", game.id("moda", "lamp"), "lit=true");
        assertEquals("moda:lamp/__retromod_variant__/lit.3dtrue", lit.toString());
        assertEquals("lit=true", game.call(locations, "getVariant", lit));
        assertEquals(game.id("moda", "lamp"), game.call(locations, "id", lit));
        assertEquals("lamp", game.call(locations, "path", lit));
        assertEquals(lit, game.call(locations, "forState", game.world("lampLit")), "the state's key is the same location");

        Object lampLit = game.world("lampLit");
        Object event = game.modifyEvent(new HashMap<>(Map.of(lampLit, game.hostModel(1), game.world("lampOff"), game.hostModel(2))),
                new HashMap<>());
        game.invoke("moda/LampModels", "onModify", event);
        assertEquals(List.of("A read moda:lamp/__retromod_variant__/lit.3dtrue true", "A lamp keys 2", "A missing true"), game.notes());
        Map<?, ?> models = (Map<?, ?>) game.call(MODEL + "LegacyModelMaps", "getModels", event);
        assertTrue(models.keySet().contains(lit), "the keys hold the same spelling");
        assertNotNull(models.get(lit));
    }

    // ---- the game ----

    // Forge's loading states as a test sets them: common setup starts unfinished and loading valid.
    private static final Map<String, String> SETUP_GATE = Map.of(
            "net/minecraftforge/fml/ModLoadingState.java", """
                    package net.minecraftforge.fml;
                    public record ModLoadingState(String name, String previous) implements IModLoadingState {}""",
            "net/minecraftforge/fml/core/ModStateProvider.java", """
                    package net.minecraftforge.fml.core;
                    import net.minecraftforge.fml.ModLoadingState;
                    public class ModStateProvider {
                        public static final ModLoadingState ERROR = new ModLoadingState("ERROR", "");
                        public static final ModLoadingState VALIDATE = new ModLoadingState("VALIDATE", "");
                        public static final ModLoadingState CONSTRUCT = new ModLoadingState("CONSTRUCT", "VALIDATE");
                        public static final ModLoadingState CONFIG_LOAD = new ModLoadingState("CONFIG_LOAD", "");
                        public static final ModLoadingState COMMON_SETUP = new ModLoadingState("COMMON_SETUP", "CONFIG_LOAD");
                        public static final ModLoadingState SIDED_SETUP = new ModLoadingState("SIDED_SETUP", "COMMON_SETUP");
                        public static final ModLoadingState ENQUEUE_IMC = new ModLoadingState("ENQUEUE_IMC", "");
                        public static final ModLoadingState PROCESS_IMC = new ModLoadingState("PROCESS_IMC", "ENQUEUE_IMC");
                        public static final ModLoadingState COMPLETE = new ModLoadingState("COMPLETE", "PROCESS_IMC");
                        public static final ModLoadingState DONE = new ModLoadingState("DONE", "");
                    }""",
            "net/minecraftforge/fml/ModLoader.java", """
                    package net.minecraftforge.fml;
                    public final class ModLoader {
                        public static volatile boolean commonSetupDone;
                        public static volatile boolean loadingValid = true;
                        public static boolean isLoadingStateValid() { return loadingValid; }
                        public static boolean hasCompletedState(IModLoadingState state) {
                            return state != net.minecraftforge.fml.core.ModStateProvider.COMMON_SETUP || commonSetupDone;
                        }
                    }""");

    // Like Patchouli's, the listener waits for books its own common setup loads.
    private static final Map<String, String> BOOK_SHELF = Map.of("moda/BookShelf.java", """
            package moda;
            import java.util.concurrent.locks.Condition;
            import java.util.concurrent.locks.Lock;
            import java.util.concurrent.locks.ReentrantLock;
            import net.minecraft.client.resources.model.ModelResourceLocation;
            import net.minecraft.resources.Identifier;
            import net.minecraftforge.client.event.ModelEvent;
            import net.minecraftforge.eventbus.api.SubscribeEvent;
            import testhost.Notes;
            public class BookShelf {
                public static Thread gameThread;
                private static final Lock LOCK = new ReentrantLock();
                private static final Condition LOADED = LOCK.newCondition();
                private static boolean booksLoaded;
                public static void loadBooks() {
                    LOCK.lock();
                    try { booksLoaded = true; LOADED.signalAll(); } finally { LOCK.unlock(); }
                }
                @SubscribeEvent
                public static void onRegister(ModelEvent.RegisterAdditional event) {
                    boolean waited = false;
                    LOCK.lock();
                    try {
                        while (!booksLoaded) { waited = true; LOADED.awaitUninterruptibly(); }
                    } finally { LOCK.unlock(); }
                    Notes.note("books " + (waited ? "after waiting" : "after setup")
                            + (Thread.currentThread() == gameThread ? " on the game thread" : " off the game thread"));
                    event.register(new ModelResourceLocation(Identifier.fromNamespaceAndPath("moda", "book"), "inventory"));
                }
            }""");

    private Game game(String host) throws Throwable {
        return game(host, Map.of(), Map.of());
    }

    /** The test game, with {@code hostExtras} added to the host's stand-ins and {@code modExtras} to the old mods. */
    private Game game(String host, Map<String, String> hostExtras, Map<String, String> modExtras) throws Throwable {
        Path eventBus = eventBusJar(host);
        Assumptions.assumeTrue(eventBus != null, "no EventBus 7 jar for Forge " + host);
        Path joml = jomlJar();
        Assumptions.assumeTrue(joml != null, "no JOML jar in Prism's libraries");
        Path hostDir = dir.resolve("host");
        Map<String, String> hostSources = new HashMap<>(BakedModelHostFixtures.hostSources(host));
        hostSources.putAll(hostExtras);
        Map<String, byte[]> standIns = compile(hostDir, hostSources, eventBus, joml);
        Map<String, byte[]> classes = new HashMap<>(jarClasses(eventBus));
        classes.putAll(jarClasses(joml));
        // Every other host class is its compile-only stub.
        for (String root : List.of("net", "com/mojang", "it")) {
            try (Stream<Path> files = Files.walk(LEGACY_CLASSES.resolve(root))) {
                for (Path file : files.filter(f -> f.toString().endsWith(".class")).toList()) {
                    String name = LEGACY_CLASSES.relativize(file).toString().replace(File.separatorChar, '/');
                    name = name.substring(0, name.length() - ".class".length());
                    if (name.startsWith("net/minecraftforge/eventbus/") || standIns.containsKey(name) || LEFT_OUT.contains(name)) continue;
                    classes.put(name, Files.readAllBytes(file));
                }
            }
        }
        classes.putAll(standIns);
        transformer.getSyntheticClasses().forEach((name, bytes) -> {
            classes.put(name, bytes);
            // A second jar's copy, relocated under its own package as the embedder does.
            if (RELOCATED.stream().anyMatch(name::startsWith)) classes.put(SECOND + name, remap(bytes));
        });
        // The old mods, compiled against Forge 1.20.1's model API and translated as Retromod translates them.
        Map<String, String> old = new HashMap<>(BakedModelHostFixtures.oldApiSources());
        old.putAll(BakedModelHostFixtures.oldModSources());
        old.putAll(modExtras);
        Map<String, byte[]> oldMods = compile(dir.resolve("old"), old, hostDir.resolve("out"), eventBus, joml);
        for (Map.Entry<String, byte[]> entry : oldMods.entrySet()) {
            String name = entry.getKey();
            if (!name.startsWith("moda/") && !name.startsWith("modb/")) continue;
            byte[] translated = transformer.transformClass(entry.getValue(), name + ".class");
            classes.put(name, name.startsWith("modb/") ? remap(translated) : translated);
        }
        Game game = new Game(loader(classes));
        game.type("net/minecraftforge/eventbus/api/bus/BusGroup").getMethod("startup").invoke(game.defaultGroup());
        // FML's mod order is moda, modb; each constructs and runs the install calls Retromod put in its constructor.
        game.modContainer("moda");
        game.modContainer("modb");
        game.construct("moda", "");
        game.construct("modb", SECOND);
        return game;
    }

    /** Reflection over one test game's classes. */
    private record Game(ClassLoader loader) {

        Class<?> type(String internalName) throws ClassNotFoundException {
            return Class.forName(internalName.replace('/', '.'), true, loader);
        }

        Object defaultGroup() throws Exception {
            return type("net/minecraftforge/eventbus/api/bus/BusGroup").getField("DEFAULT").get(null);
        }

        Object world(String member) throws Exception {
            Class<?> world = type("testhost/World");
            try {
                return world.getField(member).get(null);
            } catch (NoSuchFieldException state) {
                return world.getMethod(member).invoke(null);
            }
        }

        Object id(String namespace, String path) throws Exception {
            return type("net/minecraft/resources/Identifier").getMethod("fromNamespaceAndPath", String.class, String.class)
                    .invoke(null, namespace, path);
        }

        Object hostModel(int tint) throws Exception {
            return type("testhost/World").getMethod("hostModel", int.class).invoke(null, tint);
        }

        /** A host cuboid item model with these quads, whose GUI transform lifts the item by {@code lift}. */
        Object cuboid(List<Object> quads, float lift) throws Exception {
            Class<?> transform = type("net/minecraft/client/resources/model/cuboid/ItemTransform");
            Class<?> transforms = type("net/minecraft/client/resources/model/cuboid/ItemTransforms");
            Object gui = transforms.getConstructor(transform).newInstance(transform.getConstructor(float.class, float.class)
                    .newInstance(lift, 2f));
            Class<?> baked = type("net/minecraft/client/resources/model/sprite/Material$Baked");
            Object particle = baked.getConstructors()[0].newInstance(world("STONE"), false);
            Class<?> properties = type("net/minecraft/client/renderer/item/ModelRenderProperties");
            Object props = properties.getConstructors()[0].newInstance(true, particle, gui);
            return type("net/minecraft/client/renderer/item/CuboidItemModelWrapper").getConstructor(List.class, properties)
                    .newInstance(quads, props);
        }

        Object modifyEvent(Map<Object, Object> blocks, Map<Object, Object> items) throws Exception {
            Class<?> missingType = type("net/minecraft/client/resources/model/ModelBakery$MissingModels");
            Object missing = missingType.getConstructors()[0].newInstance(hostModel(99));
            Class<?> result = type("net/minecraft/client/resources/model/ModelBakery$BakingResult");
            Object baked = result.getConstructors()[0].newInstance(missing, blocks, items);
            Class<?> event = type("net/minecraftforge/client/event/ModelEvent$ModifyBakingResult");
            return event.getConstructors()[0].newInstance(null, baked);
        }

        Object completedEvent() throws Exception {
            Class<?> event = type("net/minecraftforge/client/event/ModelEvent$BakingCompleted");
            return event.getConstructors()[0].newInstance(type("net/minecraft/client/resources/model/ModelManager")
                    .getConstructor().newInstance(), null);
        }

        Object manager(Map<Object, Object> items) throws Exception {
            Class<?> type = type("net/minecraft/client/resources/model/ModelManager");
            Object manager = type.getConstructor().newInstance();
            type.getField("items").set(manager, items);
            Class<?> set = type("net/minecraft/client/renderer/block/BlockStateModelSet");
            type.getField("blocks").set(manager, set.getConstructors()[0].newInstance(Map.of(), hostModel(99)));
            return manager;
        }

        /** {@code BUS.post(event)} on the host event's own bus. */
        void post(String eventType, Object event) throws Exception {
            Object bus = type(eventType).getField("BUS").get(null);
            Method post = type("net/minecraftforge/eventbus/api/bus/EventBus").getMethod("post", type("net/minecraftforge/eventbus/internal/Event"));
            try {
                post.invoke(bus, event);
            } catch (InvocationTargetException failure) {
                throw new AssertionError("the post threw", failure.getCause());
            }
        }

        /** Forge 26.2 offering client packs: the event's source adder collects what each listener adds. */
        void postPackFinders(List<Object> sources) throws Exception {
            Class<?> packType = type("net/minecraft/server/packs/PackType");
            Object client = packType.getField("CLIENT_RESOURCES").get(null);
            Consumer<Object> adder = sources::add;
            Object event = type("net/minecraftforge/event/AddPackFindersEvent").getConstructors()[0].newInstance(client, adder);
            post("net/minecraftforge/event/AddPackFindersEvent", event);
        }

        /** Minecraft listing its packs: each source's loadPacks, and the packs they offer. */
        @SuppressWarnings({"unchecked", "rawtypes"})
        List<Object> listPacks(List<Object> sources) throws Exception {
            List<Object> packs = new ArrayList<>();
            Consumer<Object> sink = packs::add;
            for (Object source : sources) {
                type("net/minecraft/server/packs/repository/RepositorySource").getMethod("loadPacks", Consumer.class)
                        .invoke(source, (Consumer) sink);
            }
            for (Object pack : packs) {
                Object selection = pack.getClass().getField("selection").get(pack);
                assertEquals(true, selection.getClass().getMethod("required").invoke(selection), "the pack is always on");
            }
            return packs;
        }

        /** A resource reload opening the pack, through the supplier methods of this host. */
        Object openPack(Object pack) throws Exception {
            return pack.getClass().getMethod("open").invoke(pack);
        }

        @SuppressWarnings("unchecked")
        java.util.Set<String> namespaces(Object resources) throws Exception {
            Class<?> packType = type("net/minecraft/server/packs/PackType");
            return (java.util.Set<String>) type("net/minecraft/server/packs/PackResources").getMethod("getNamespaces", packType)
                    .invoke(resources, packType.getField("CLIENT_RESOURCES").get(null));
        }

        /** The item models a reload's item loader reads from the pack in one namespace, as text by id. */
        @SuppressWarnings("unchecked")
        Map<String, String> items(Object resources, String namespace) throws Exception {
            try {
                return (Map<String, String>) type("testhost/PackReads").getMethod("items",
                        type("net/minecraft/server/packs/PackResources"), String.class).invoke(null, resources, namespace);
            } catch (InvocationTargetException failure) {
                throw new AssertionError("reading the item models threw", failure.getCause());
            }
        }

        void invoke(String owner, String name, Object argument) throws Throwable {
            for (Method method : type(owner).getMethods()) {
                if (method.getName().equals(name)) {
                    try {
                        method.invoke(null, argument);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                    return;
                }
            }
            throw new AssertionError(owner + " has no " + name);
        }

        Object call(String owner, String name, Object... args) throws Exception {
            for (Method method : type(owner).getMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == args.length && accepts(method, args)) {
                    try {
                        return method.invoke(null, args);
                    } catch (InvocationTargetException failure) {
                        if (failure.getCause() instanceof Exception cause) throw cause;
                        throw failure;
                    }
                }
            }
            throw new AssertionError(owner + " has no static " + name);
        }

        private static boolean accepts(Method method, Object[] args) {
            Class<?>[] params = method.getParameterTypes();
            for (int i = 0; i < args.length; i++) {
                if (args[i] == null) continue;
                Class<?> param = params[i] == int.class ? Integer.class : params[i] == float.class ? Float.class : params[i];
                if (!param.isInstance(args[i])) return false;
            }
            return true;
        }

        Object modelData(String tag) throws Exception {
            return type("net/minecraftforge/client/model/data/ModelData").getMethod("of", String.class).invoke(null, tag);
        }

        String tag(Object data) throws Exception {
            return (String) data.getClass().getMethod("tag").invoke(data);
        }

        Object getModelData(Object model, Object state) throws Exception {
            Class<?> data = type("net/minecraftforge/client/model/data/ModelData");
            Method method = model.getClass().getMethod("getModelData", type("net/minecraft/client/renderer/block/BlockAndTintGetter"),
                    type("net/minecraft/core/BlockPos"), state.getClass(), data);
            return method.invoke(model, null, null, state, data.getField("EMPTY").get(null));
        }

        List<?> collectParts(Object model, Object data) throws Exception {
            List<Object> parts = new ArrayList<>();
            Object random = type("net/minecraft/util/RandomSource").getMethod("create", long.class).invoke(null, 42L);
            model.getClass().getMethod("collectParts", type("net/minecraft/util/RandomSource"), List.class, data.getClass())
                    .invoke(model, random, parts, data);
            return parts;
        }

        /** The tint index of each quad of a part, unculled ones last. */
        List<Integer> tints(Object part) throws Exception {
            List<Integer> tints = new ArrayList<>();
            for (Object quad : quads(part)) tints.add(tint(quad));
            return tints;
        }

        List<String> layers(Object part) throws Exception {
            List<String> layers = new ArrayList<>();
            for (Object quad : quads(part)) {
                Object info = quad.getClass().getMethod("materialInfo").invoke(quad);
                layers.add(info.getClass().getMethod("layer").invoke(info).toString());
            }
            return layers;
        }

        private List<Object> quads(Object part) throws Exception {
            List<Object> quads = new ArrayList<>();
            Class<?> direction = type("net/minecraft/core/Direction");
            Method getQuads = part.getClass().getMethod("getQuads", direction);
            for (Object side : direction.getEnumConstants()) quads.addAll((List<?>) getQuads.invoke(part, side));
            quads.addAll((List<?>) getQuads.invoke(part, (Object) null));
            return quads;
        }

        private int tint(Object quad) throws Exception {
            Object info = quad.getClass().getMethod("materialInfo").invoke(quad);
            return (Integer) info.getClass().getMethod("tintIndex").invoke(info);
        }

        /** The tint index of each quad an old model reads from a host item model, all sides. */
        List<Integer> quadTints(Object model) throws Exception {
            List<Integer> tints = new ArrayList<>();
            Class<?> direction = type("net/minecraft/core/Direction");
            Method getQuads = model.getClass().getMethod("getQuads", type("net/minecraft/world/level/block/state/BlockState"),
                    direction, type("net/minecraft/util/RandomSource"));
            List<Object> sides = new ArrayList<>(List.of(direction.getEnumConstants()));
            sides.add(null);
            for (Object side : sides) {
                for (Object quad : (List<?>) getQuads.invoke(model, null, side, null)) tints.add(tint(quad));
            }
            return tints;
        }

        Object stack(String item, int count) throws Exception {
            Object kind = world(item);
            return type("net/minecraft/world/item/ItemStack").getConstructor(kind.getClass(), int.class).newInstance(kind, count);
        }

        /** The item model drawn in the GUI, as Minecraft draws the inventory: the render state it filled. */
        Object draw(Object model, Object stack) throws Exception {
            Object state = type("net/minecraft/client/renderer/item/ItemStackRenderState").getConstructor().newInstance();
            Class<?> context = type("net/minecraft/world/item/ItemDisplayContext");
            Method update = type("net/minecraft/client/renderer/item/ItemModel").getMethods()[0];
            update.invoke(model, state, stack, null, context.getField("GUI").get(null), null, null, 0);
            return state;
        }

        Object onlyLayer(Object state) throws Exception {
            List<?> layers = (List<?>) state.getClass().getField("layers").get(state);
            assertEquals(1, layers.size(), "one layer for the one render pass");
            return layers.get(0);
        }

        List<?> identity(Object state) throws Exception {
            return (List<?>) state.getClass().getField("identity").get(state);
        }

        List<Integer> layerTints(Object layer) throws Exception {
            List<Integer> tints = new ArrayList<>();
            for (Object quad : (List<?>) layer.getClass().getField("quads").get(layer)) tints.add(tint(quad));
            return tints;
        }

        /** The pose the host draws a layer with: its item transform, then its local transform; scale and translation. */
        float[] composed(Object layer) throws Exception {
            Class<?> poseType = type("com/mojang/blaze3d/vertex/PoseStack$Pose");
            Object pose = poseType.getConstructor().newInstance();
            Object transform = layer.getClass().getField("itemTransform").get(layer);
            transform.getClass().getMethod("apply", boolean.class, poseType).invoke(transform, false, pose);
            poseType.getMethod("mulPose", type("org/joml/Matrix4fc")).invoke(pose, layer.getClass().getField("localTransform").get(layer));
            Object matrix = poseType.getMethod("pose").invoke(pose);
            float[] values = new float[6];
            String[] names = {"m00", "m11", "m22", "m30", "m31", "m32"};
            for (int i = 0; i < names.length; i++) values[i] = (Float) matrix.getClass().getMethod(names[i]).invoke(matrix);
            return values;
        }

        @SuppressWarnings("unchecked")
        List<String> notes() throws Exception {
            return new ArrayList<>((List<String>) type("testhost/Notes").getField("SEEN").get(null));
        }

        @SuppressWarnings("unchecked")
        void clearNotes() throws Exception {
            ((List<String>) type("testhost/Notes").getField("SEEN").get(null)).clear();
        }

        @SuppressWarnings("unchecked")
        Object modContainer(String modId) throws Exception {
            List<Object> mods = (List<Object>) type("net/minecraftforge/fml/ModList").getField("MODS").get(null);
            for (Object mod : mods) {
                if (mod.getClass().getMethod("getModId").invoke(mod).equals(modId)) return mod;
            }
            Class<?> busGroup = type("net/minecraftforge/eventbus/api/bus/BusGroup");
            Object group = busGroup.getMethod("create", String.class, Class.class)
                    .invoke(null, modId, type("net/minecraftforge/fml/event/IModBusEvent"));
            busGroup.getMethod("startup").invoke(group);
            Object mod = type("net/minecraftforge/fml/ModContainer").getConstructor(String.class, busGroup).newInstance(modId, group);
            mods.add(mod);
            return mod;
        }

        /** One mod constructing: FML names it, then the install calls Retromod put at the head of its constructor. */
        void construct(String modId, String copy) throws Throwable {
            type("net/minecraftforge/fml/ModLoadingContext").getField("active").set(null, modContainer(modId));
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            lookup.findStatic(type(copy + LegacyClientRenderBootstrapInjector.BOOTSTRAP), "install", MethodType.methodType(void.class)).invoke();
            lookup.findStatic(type(copy + LegacyForgeBakedModelBridge.INSTALLER), "install", MethodType.methodType(void.class)).invoke();
            type("net/minecraftforge/fml/ModLoadingContext").getField("active").set(null, null);
        }

        /** {@code LegacyEvents.subscribe(modGroup, Listeners.class)} in one copy, as the bus bridge's register does. */
        void subscribe(String copy, String listeners, String modId) throws Exception {
            Object mod = modContainer(modId);
            Object group = mod.getClass().getMethod("getModBusGroup").invoke(mod);
            for (Method method : type(copy + "com/retromod/legacyapi/forge/eventbus/LegacyEvents").getMethods()) {
                if (method.getName().equals("subscribe") && method.getParameterCount() == 2) {
                    method.invoke(null, group, type(listeners));
                    return;
                }
            }
            throw new AssertionError("LegacyEvents has no subscribe");
        }
    }

    // ---- class loading ----

    static byte[] remap(byte[] bytes) {
        ClassWriter writer = new ClassWriter(0);
        new ClassReader(bytes).accept(new ClassRemapper(writer, new Remapper() {
            @Override
            public String map(String internalName) {
                return RELOCATED.stream().anyMatch(internalName::startsWith) ? SECOND + internalName : internalName;
            }
        }), 0);
        return writer.toByteArray();
    }

    static ClassLoader loader(Map<String, byte[]> classes) {
        return new ClassLoader(ClassLoader.getPlatformClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classes.get(name.replace('.', '/'));
                if (bytes == null) throw new ClassNotFoundException(name);
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
    }

    /** Compiles sources against the given class path entries, then the compiled stubs. */
    static Map<String, byte[]> compile(Path work, Map<String, String> sources, Path... classpath) throws Exception {
        Path src = work.resolve("src");
        Path out = work.resolve("out");
        List<String> entries = new ArrayList<>();
        for (Path entry : classpath) entries.add(entry.toString());
        entries.add(LEGACY_CLASSES.toString());
        List<String> args = new ArrayList<>(List.of("-d", out.toString(), "--release", "17", "-proc:none", "-nowarn",
                "-classpath", String.join(File.pathSeparator, entries)));
        for (Map.Entry<String, String> source : new LinkedHashMap<>(sources).entrySet()) {
            Path file = src.resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
            args.add(file.toString());
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "the tests need a JDK to compile the stand-ins");
        assertEquals(0, compiler.run(null, null, null, args.toArray(new String[0])), "the stand-ins must compile");
        Map<String, byte[]> classes = new HashMap<>();
        try (Stream<Path> files = Files.walk(out)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".class")).toList()) {
                String name = out.relativize(file).toString().replace(File.separatorChar, '/');
                classes.put(name.substring(0, name.length() - ".class".length()), Files.readAllBytes(file));
            }
        }
        return classes;
    }

    static Map<String, byte[]> jarClasses(Path jar) throws Exception {
        Map<String, byte[]> classes = new HashMap<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (ZipEntry entry : Collections.list(zip.entries())) {
                String name = entry.getName();
                if (!name.endsWith(".class") || name.endsWith("module-info.class") || name.startsWith("META-INF/")) continue;
                classes.put(name.substring(0, name.length() - ".class".length()), zip.getInputStream(entry).readAllBytes());
            }
        }
        return classes;
    }

    /** The EventBus 7 jar of Forge {@code host}: a system property, Prism's libraries, then a local copy. */
    static Path eventBusJar(String host) {
        String version = host.startsWith("26.2") ? "7.0.1" : "7.0.6";
        List<Path> candidates = new ArrayList<>();
        String configured = System.getProperty("retromod.test.eventbus" + version.replace(".", ""));
        if (configured != null && !configured.isBlank()) candidates.add(Path.of(configured));
        String home = System.getProperty("user.home");
        candidates.add(Path.of(home, "Library/Application Support/PrismLauncher/libraries/net/minecraftforge/eventbus",
                version, "eventbus-" + version + ".jar"));
        if (version.equals("7.0.6")) candidates.add(Path.of(home, ".claude/jobs/3da2a019/tmp/forge263/eventbus-7.0.6.jar"));
        return candidates.stream().filter(Files::isRegularFile).findFirst().orElse(null);
    }

    /** JOML, which Minecraft 26.2 and 26.3 ship: a system property, then Prism's libraries. */
    static Path jomlJar() {
        List<Path> candidates = new ArrayList<>();
        String configured = System.getProperty("retromod.test.joml");
        if (configured != null && !configured.isBlank()) candidates.add(Path.of(configured));
        Path libraries = Path.of(System.getProperty("user.home"), "Library/Application Support/PrismLauncher/libraries/org/joml/joml");
        for (String version : List.of("1.10.8", "1.10.9", "1.10.5")) {
            candidates.add(libraries.resolve(version).resolve("joml-" + version + ".jar"));
        }
        return candidates.stream().filter(Files::isRegularFile).findFirst().orElse(null);
    }
}
