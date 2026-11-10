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
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static com.retromod.shim.forge.LegacyBakedModelRuntimeTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Old mods' geometry loaders and Retromod's own forge: loaders, each old mod through its own embedded copy, over stand-in Forge 26.2 and 26.3 baking. */
class GeometryLoaderAdapterTest {

    private static final String MODEL = LegacyForgeBakedModelBridge.ROOT;

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

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {ModelHostFixtures.HOST_262, ModelHostFixtures.HOST_263})
    @DisplayName("Old loaders register under their mods' namespaces at a reload and bake fixed and nested quads, through both copies")
    void registeredLoadersBakeFixedQuads(String host) throws Throwable {
        Game game = game(host);
        Map<Object, Object> loaders = game.registerLoaders();
        assertTrue(loaders.keySet().containsAll(List.of(game.id("moda", "cube"), game.id("modb", "spinner"), game.id("modc", "gear"))),
                "each registration took the namespace of the mod whose class made it: " + loaders.keySet());
        assertEquals(MODEL + "LegacyGeometryLoaderAdapter", name(loaders.get(game.id("moda", "cube"))));
        assertEquals(SECOND + MODEL + "LegacyGeometryLoaderAdapter", name(loaders.get(game.id("modb", "spinner"))),
                "the second mod's loader goes through its own copy");
        for (String builtin : List.of("composite", "item_layers", "elements", "separate_transforms")) {
            assertNotNull(loaders.get(game.id("forge", builtin)), "Retromod's forge:" + builtin + " is registered once");
        }

        // lootr's barrel: a nested model read as an old BlockModel, its parent a file only the geometry names.
        Object cube = game.read(loaders, "moda", "cube", "{\"tint\":3,\"inner\":{\"parent\":\"moda:block/inner\"}}");
        game.clearNotes();
        Object quads = game.bake(cube, game.slots("side", "moda:block/side"), "moda:block/cube", game.context(Set.of(), null));
        assertEquals(List.of(3, 4), game.tints(quads), "the cube's own quad, then the nested model's");
        assertTrue(game.notes().contains("A bake moda:block/cube moda:block/cube side=moda:block/side has=true/false"
                + " missing=minecraft:missingno"), game.notes().toString());
        assertTrue(game.notes().contains("sprite moda:block/side for moda:block/cube"), "the texture came from the baker's atlas");
        assertFalse(game.notes().stream().anyMatch(note -> note.startsWith("baker model moda:")),
                "the nested parent was read from the resources, not asked of the baker, which has not discovered it");

        // A render_type on the model draws all of it in that layer.
        Object cutout = game.bake(cube, game.slots("side", "moda:block/side"), "moda:block/cube",
                game.context(Set.of(), game.id("minecraft", "cutout")));
        assertEquals(List.of("CUTOUT", "CUTOUT"), game.layers(cutout));

        // Sophisticated Backpacks' part model, built on a parent file and read through its elements.
        Object spinner = game.read(loaders, "modb", "spinner", "{}");
        game.clearNotes();
        Object spun = game.bake(spinner, game.slots(), "modb:block/spinner", game.context(Set.of(), null));
        assertEquals(List.of(20, 7), game.tints(spun), "read once without model data: the spinner's quad and the part's");
        assertTrue(game.notes().contains("B part 1 1"), game.notes().toString());
        assertTrue(game.notes().contains("sprite modb:block/plank for modb:block/spinner"), "the part's own texture: " + game.notes());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {ModelHostFixtures.HOST_262, ModelHostFixtures.HOST_263})
    @DisplayName("A nested model whose parents go three files deep bakes through Forge's own context, as lootr's barrel does")
    void deepNestedParentsBake(String host) throws Throwable {
        Game game = game(host);
        Map<Object, Object> loaders = game.registerLoaders();
        // lootr's opened barrel names its own model, whose parent is cube_bottom_top, whose parent is cube.
        game.putResource("moda:models/block/opened.json", "{\"parent\":\"moda:block/barrel_base\"}");
        game.putResource("moda:models/block/barrel_base.json", "{\"parent\":\"moda:block/cube_base\"}");
        game.putResource("moda:models/block/cube_base.json", "{\"elements\":[{\"tint\":5}]}");
        Object barrel = game.read(loaders, "moda", "cube", "{\"tint\":3,\"inner\":{\"parent\":\"moda:block/opened\"}}");

        Object quads = game.bake(barrel, game.slots("side", "moda:block/side"), "moda:block/barrel", game.context(Set.of(), null));

        assertEquals(List.of(3, 5), game.tints(quads), "the nested model's own quad, not the missing model's (99)");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {ModelHostFixtures.HOST_262, ModelHostFixtures.HOST_263})
    @DisplayName("A model drawn per model data is put back at ModifyBakingResult for its block and item; a fixed one stays")
    void dynamicResultsAreSwappedBack(String host) throws Throwable {
        Game game = game(host);
        Map<Object, Object> loaders = game.registerLoaders();
        Object spun = game.bake(game.read(loaders, "modb", "spinner", "{}"), game.slots(), "modb:block/spinner",
                game.context(Set.of(), null));
        Object fixed = game.bake(game.read(loaders, "moda", "cube", "{\"tint\":3}"), game.slots(), "moda:block/cube",
                game.context(Set.of(), null));
        Object spinState = game.world("lampLit");
        Object cubeState = game.world("lampOff");
        Map<Object, Object> blocks = new HashMap<>(Map.of(spinState, game.variant(spun), cubeState, game.variant(fixed)));
        Object cubeModel = blocks.get(cubeState);
        Map<Object, Object> items = new HashMap<>(Map.of(game.id("modb", "spinner"), game.cuboid(game.all(spun)),
                game.id("moda", "cube"), game.cuboid(game.all(fixed))));

        game.post("net/minecraftforge/client/event/ModelEvent$ModifyBakingResult", game.modifyEvent(blocks, items));

        Object spinModel = blocks.get(spinState);
        assertEquals(SECOND + MODEL + "LegacyBlockStateModel", name(spinModel), "the spinner draws through its own copy's adapter");
        assertEquals(List.of(21, 7), game.partTints(spinModel, game.modelData("spin")), "with the block entity's model data");
        assertEquals(List.of(20, 7), game.partTints(spinModel, game.modelData("empty")));
        assertSame(cubeModel, blocks.get(cubeState), "a model with fixed quads keeps Minecraft's own");
        assertEquals(SECOND + MODEL + "LegacyItemModel", name(items.get(game.id("modb", "spinner"))));
        assertEquals("net.minecraft.client.renderer.item.CuboidItemModelWrapper", items.get(game.id("moda", "cube")).getClass().getName());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {ModelHostFixtures.HOST_262, ModelHostFixtures.HOST_263})
    @DisplayName("A model that draws only from model data gets a quad of no size that names it, and is put back")
    void modelDataOnlyModelsAreSwappedBack(String host) throws Throwable {
        Game game = game(host);
        Map<Object, Object> loaders = game.registerLoaders();
        Object ghost = game.bake(game.read(loaders, "modb", "ghost", "{}"), game.slots(), "modb:block/ghost", game.context(Set.of(), null));
        List<Object> placeholder = game.all(ghost);
        assertEquals(1, placeholder.size(), "one quad stands in, where Minecraft would hand back its shared empty collection");
        assertTrue(game.flat(placeholder.get(0)), "the stand-in has no size and sits at the block's center, so it draws nothing");

        Object state = game.world("lampLit");
        Map<Object, Object> blocks = new HashMap<>(Map.of(state, game.variant(ghost)));
        Map<Object, Object> items = new HashMap<>(Map.of(game.id("modb", "ghost"), game.cuboid(placeholder)));
        game.post("net/minecraftforge/client/event/ModelEvent$ModifyBakingResult", game.modifyEvent(blocks, items));
        assertEquals(SECOND + MODEL + "LegacyBlockStateModel", name(blocks.get(state)));
        assertEquals(List.of(31), game.partTints(blocks.get(state), game.modelData("spin")), "the model data's quad");
        assertEquals(SECOND + MODEL + "LegacyItemModel", name(items.get(game.id("modb", "ghost"))));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {ModelHostFixtures.HOST_262, ModelHostFixtures.HOST_263})
    @DisplayName("A geometry whose bake meets a missing class or member draws the missing model, and Minecraft's bake goes on")
    void linkageErrorsStayInsideTheBake(String host) throws Throwable {
        Game game = game(host);
        Map<Object, Object> loaders = game.registerLoaders();
        Object broken = game.read(loaders, "modb", "broken", "{}");
        assertEquals(List.of(99), game.tints(game.bake(broken, game.slots(), "modb:block/broken", game.context(Set.of(), null))),
                "the missing model's own quad, with no NoSuchMethodError thrown into Minecraft's bake");

        // A model whose quads and methods name a missing class: no error leaves the bake, and the model is kept
        // to answer per block, since Retromod cannot tell what it draws.
        Object throwing = game.read(loaders, "modb", "throwing", "{}");
        List<Object> quads = game.all(game.bake(throwing, game.slots(), "modb:block/throwing", game.context(Set.of(), null)));
        assertEquals(1, quads.size(), "the stand-in quad of a model kept for the swap");
        assertTrue(game.flat(quads.get(0)));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {ModelHostFixtures.HOST_262, ModelHostFixtures.HOST_263})
    @DisplayName("A library's listener added on an addon's bus registers a path-only loader under the addon's namespace")
    void libraryListenerTakesTheAddonsNamespace(String host) throws Throwable {
        Game game = game(host);
        // modc constructs and calls into its library, modb, which adds a listener on modc's mod bus, as Moonlight does.
        game.hookWhileConstructing("modc", "modb/Listeners", "registerForAddon");
        Map<Object, Object> loaders = game.registerLoaders();
        assertNotNull(loaders.get(game.id("modc", "library_gear")),
                "the addon's namespace, as Forge 1.20.1 posted the event with the addon active: " + loaders.keySet());
        assertNull(loaders.get(game.id("modb", "library_gear")), "not the library's own");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {ModelHostFixtures.HOST_262, ModelHostFixtures.HOST_263})
    @DisplayName("Retromod's forge:composite, item_layers, elements and separate_transforms bake as Forge 1.20.1's loaders did")
    void builtinLoadersBake(String host) throws Throwable {
        Game game = game(host);
        Map<Object, Object> loaders = game.registerLoaders();
        String composite = "{\"children\":{\"a\":{\"elements\":[{\"tint\":3}]},\"b\":{\"parent\":\"moda:block/inner\"}}}";
        Object geometry = game.read(loaders, "forge", "composite", composite);
        assertEquals(List.of(3, 4), game.tints(game.bake(geometry, game.slots(), "moda:block/link", game.context(Set.of(), null))),
                "each child in order, a child on a parent file included");
        assertEquals(List.of(4), game.tints(game.bake(geometry, game.slots(), "moda:block/link", game.context(Set.of("a"), null))),
                "a child the model hides is left out");
        game.clearNotes();
        game.bake(geometry, game.slots(), "moda:block/link", game.context(Set.of(), null, true));
        assertTrue(game.notes().contains("root transform composed"), "the root transform moves the children");
        assertThrows(Exception.class, () -> game.read(loaders, "forge", "composite", "{}"), "a composite without children");

        Object elements = game.read(loaders, "forge", "elements", "{\"elements\":[{\"tint\":5},{\"tint\":6}]}");
        game.clearNotes();
        assertEquals(List.of(5, 6), game.tints(game.bake(elements, game.slots("all", "moda:block/x"), "moda:block/el",
                game.context(Set.of(), null))));
        assertTrue(game.notes().contains("sprite moda:block/x for moda:block/el"), game.notes().toString());

        Object layers = game.read(loaders, "forge", "item_layers", "{}");
        assertEquals(List.of(0, 1), game.tints(game.bake(layers, game.slots("layer0", "moda:item/a", "layer1", "moda:item/b"),
                "moda:item/layered", game.context(Set.of(), null))), "vanilla's generated geometry, one face per layer");

        Object separate = game.read(loaders, "forge", "separate_transforms", "{\"base\":{\"parent\":\"moda:block/inner\"}}");
        assertEquals(List.of(4), game.tints(game.bake(separate, game.slots(), "moda:item/umbrella", game.context(Set.of(), null))),
                "the base model, which a block or a definition-less item draws");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {ModelHostFixtures.HOST_262, ModelHostFixtures.HOST_263})
    @DisplayName("Patchouli's book overrides cast the missing model to BlockModel and build on the rebuilt ItemOverrides")
    void bookOverridesBuild(String host) throws Throwable {
        Game game = game(host);
        Object bakery = game.type("net/minecraft/client/resources/model/ModelBakery").getConstructor().newInstance();
        Object overrides = game.call("moda/Listeners", "bookOverrides", bakery);
        assertEquals(MODEL + "ItemOverrides", overrides.getClass().getSuperclass().getName().replace('.', '/'));
    }

    private static String name(Object value) {
        return value.getClass().getName().replace('.', '/');
    }

    // ---- the game ----

    private Game game(String host) throws Throwable {
        Path eventBus = eventBusJar(host);
        Assumptions.assumeTrue(eventBus != null, "no EventBus 7 jar for Forge " + host);
        Path joml = jomlJar();
        Assumptions.assumeTrue(joml != null, "no JOML jar in Prism's libraries");
        Path gson = Path.of(System.getProperty("user.home"), ".m2/repository/com/google/code/gson/gson/2.10.1/gson-2.10.1.jar");
        Assumptions.assumeTrue(Files.isRegularFile(gson), "no Gson jar in the local Maven repository");
        Path hostDir = dir.resolve("host");
        Map<String, byte[]> standIns = compile(hostDir, GeometryHostFixtures.hostSources(host), eventBus, joml, gson);
        Map<String, byte[]> classes = new HashMap<>(jarClasses(eventBus));
        classes.putAll(jarClasses(joml));
        classes.putAll(jarClasses(gson));
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
        // Forge's own model context and model data run as the host ships them, so its cast of each parent it walks applies.
        Path universal = LegacyHudGame.hostJar(host, "universal");
        Assumptions.assumeTrue(universal != null, "no Forge universal jar for " + host);
        classes.putAll(hostClasses(universal, "net/minecraftforge/client/model/geometry/ModelContext",
                "net/minecraftforge/client/model/ForgeBlockModelData"));
        transformer.getSyntheticClasses().forEach((name, bytes) -> {
            classes.put(name, bytes);
            // A second jar's copy, relocated under its own package as the embedder does.
            if (RELOCATED.stream().anyMatch(name::startsWith)) classes.put(SECOND + name, remap(bytes));
        });
        // The old mods, compiled against Forge 1.20.1's geometry API and translated as Retromod translates them.
        // modb and modc share the second jar's copy, as two mods of one jar do.
        Map<String, String> old = new HashMap<>(GeometryHostFixtures.oldApiSources());
        old.putAll(GeometryHostFixtures.oldModSources());
        old.putAll(constructors());
        Map<String, byte[]> oldMods = compile(dir.resolve("old"), old, hostDir.resolve("out"), eventBus, joml, gson);
        for (Map.Entry<String, byte[]> entry : oldMods.entrySet()) {
            String name = entry.getKey();
            if (!name.startsWith("moda/") && !name.startsWith("modb/") && !name.startsWith("modc/")) continue;
            // A class the old mod names that the game does not have.
            if (name.equals("modb/Absent")) continue;
            byte[] translated = transformer.transformClass(entry.getValue(), name + ".class");
            classes.put(name, name.startsWith("moda/") ? translated : remap(translated));
        }
        Game game = new Game(loader(classes));
        game.type("net/minecraftforge/eventbus/api/bus/BusGroup").getMethod("startup").invoke(game.defaultGroup());
        game.putResource("moda:models/block/inner.json", "{\"elements\":[{\"tint\":4}]}");
        game.putResource("modb:models/block/part.json", "{\"textures\":{\"all\":\"modb:block/plank\"},\"elements\":[{\"tint\":7}]}");
        game.construct("moda", "moda.ModA");
        game.construct("modb", "modb.ModB");
        game.construct("modc", "modc.ModC");
        return game;
    }

    // The named classes, read from a host jar.
    private static Map<String, byte[]> hostClasses(Path jar, String... names) throws Exception {
        Map<String, byte[]> classes = new HashMap<>();
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar.toFile())) {
            for (String name : names) {
                java.util.zip.ZipEntry entry = zip.getEntry(name + ".class");
                assertNotNull(entry, name + " is in " + jar.getFileName());
                classes.put(name, zip.getInputStream(entry).readAllBytes());
            }
        }
        return classes;
    }

    // Each mod's constructor, with the install call Retromod's constructor hook puts at its head; modc's listener
    // registers a loader of its own from a mod sharing the second copy.
    private static Map<String, String> constructors() {
        Map<String, String> sources = new LinkedHashMap<>();
        for (String mod : List.of("moda.ModA", "modb.ModB", "modc.ModC")) {
            String pkg = mod.substring(0, mod.indexOf('.'));
            String simple = mod.substring(mod.indexOf('.') + 1);
            sources.put(pkg + "/" + simple + ".java", """
                    package %s;
                    public class %s {
                        public %s() {
                            com.retromod.legacyapi.forge.client.LegacyClientRenderBootstrap.install();
                            com.retromod.legacyapi.forge.client.model.LegacyGeometryLoaders.install();
                        }
                    }""".formatted(pkg, simple, simple));
        }
        sources.put("modc/Listeners.java", """
                package modc;
                import net.minecraftforge.client.event.ModelEvent;
                public class Listeners {
                    public static void onRegister(ModelEvent.RegisterGeometryLoaders event) { event.register("gear", new modb.SpinnerLoader()); }
                }""");
        return sources;
    }

    /** Reflection over one test game's classes. */
    private record Game(ClassLoader loader) {

        Class<?> type(String internalName) throws ClassNotFoundException {
            return Class.forName(internalName.replace('/', '.'), true, loader);
        }

        Object defaultGroup() throws Exception {
            return type("net/minecraftforge/eventbus/api/bus/BusGroup").getField("DEFAULT").get(null);
        }

        Object id(String namespace, String path) throws Exception {
            return type("net/minecraft/resources/Identifier").getMethod("fromNamespaceAndPath", String.class, String.class)
                    .invoke(null, namespace, path);
        }

        Object world(String member) throws Exception {
            return type("testhost/World").getMethod(member).invoke(null);
        }

        @SuppressWarnings("unchecked")
        void putResource(String id, String text) throws Exception {
            ((Map<String, String>) type("testhost/Resources").getField("FILES").get(null)).put(id, text);
        }

        @SuppressWarnings("unchecked")
        List<String> notes() throws Exception {
            return new ArrayList<>((List<String>) type("testhost/Notes").getField("SEEN").get(null));
        }

        @SuppressWarnings("unchecked")
        void clearNotes() throws Exception {
            ((List<String>) type("testhost/Notes").getField("SEEN").get(null)).clear();
        }

        /** One mod constructing: FML names it, and its constructor runs the install calls. */
        @SuppressWarnings("unchecked")
        void construct(String modId, String modClass) throws Throwable {
            Class<?> busGroup = type("net/minecraftforge/eventbus/api/bus/BusGroup");
            Object group = busGroup.getMethod("create", String.class, Class.class).invoke(null, modId,
                    type("net/minecraftforge/fml/event/IModBusEvent"));
            busGroup.getMethod("startup").invoke(group);
            Object mod = type("net/minecraftforge/fml/ModContainer").getConstructor(String.class, busGroup).newInstance(modId, group);
            ((List<Object>) type("net/minecraftforge/fml/ModList").getField("MODS").get(null)).add(mod);
            type("net/minecraftforge/fml/ModLoadingContext").getField("active").set(null, mod);
            try {
                type(modClass.replace('.', '/')).getConstructor().newInstance();
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            } finally {
                type("net/minecraftforge/fml/ModLoadingContext").getField("active").set(null, null);
            }
        }

        /** Whether every corner of the quad sits at the block's center, (0.5, 0.5, 0.5). */
        boolean flat(Object quad) throws Exception {
            for (int vertex = 0; vertex < 4; vertex++) {
                Object position = quad.getClass().getMethod("position", int.class).invoke(quad, vertex);
                for (String axis : List.of("x", "y", "z")) {
                    if ((Float) position.getClass().getMethod(axis).invoke(position) != 0.5f) return false;
                }
            }
            return true;
        }

        /**
         * While {@code modId} constructs, its library adds a listener on that mod's bus through the bus bridge's
         * {@code hookBus}, the step every old {@code addListener(Consumer)} reaches.
         */
        @SuppressWarnings({"unchecked", "rawtypes"})
        void hookWhileConstructing(String modId, String library, String method) throws Throwable {
            Class<?> event = type("net/minecraftforge/client/event/ModelEvent$RegisterGeometryLoaders");
            Method listen = type(library).getMethod(method, event);
            Consumer listener = value -> {
                try {
                    listen.invoke(null, value);
                } catch (ReflectiveOperationException failure) {
                    throw new AssertionError(failure.getCause());
                }
            };
            Object mod = null;
            for (Object container : (List<?>) type("net/minecraftforge/fml/ModList").getField("MODS").get(null)) {
                if (container.getClass().getMethod("getModId").invoke(container).equals(modId)) mod = container;
            }
            assertNotNull(mod, modId + " constructed");
            Object group = mod.getClass().getMethod("getModBusGroup").invoke(mod);
            type("net/minecraftforge/fml/ModLoadingContext").getField("active").set(null, mod);
            try {
                type(ForgeEventBusSynthetics.LEB_IMPL).getMethod("hookBus", type("net/minecraftforge/eventbus/api/bus/BusGroup"),
                        Class.class, byte.class, boolean.class, Consumer.class).invoke(null, group, event, (byte) 0, false, listener);
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            } finally {
                type("net/minecraftforge/fml/ModLoadingContext").getField("active").set(null, null);
            }
        }

        /** A reload's RegisterGeometryLoaders, with each old mod's listener on the event's bus as the bus bridge puts it. */
        @SuppressWarnings({"unchecked", "rawtypes"})
        Map<Object, Object> registerLoaders() throws Exception {
            Class<?> event = type("net/minecraftforge/client/event/ModelEvent$RegisterGeometryLoaders");
            Object bus = event.getField("BUS").get(null);
            Method addListener = type("net/minecraftforge/eventbus/api/bus/EventBus").getMethod("addListener", Consumer.class);
            for (String mod : List.of("moda", "modb", "modc")) {
                Method onRegister = type(mod + "/Listeners").getMethod("onRegister", event);
                Consumer listener = value -> {
                    try {
                        onRegister.invoke(null, value);
                    } catch (ReflectiveOperationException failure) {
                        throw new AssertionError(failure.getCause());
                    }
                };
                addListener.invoke(bus, listener);
            }
            Map<Object, Object> loaders = new HashMap<>();
            post("net/minecraftforge/client/event/ModelEvent$RegisterGeometryLoaders", event.getConstructors()[0].newInstance(loaders));
            return loaders;
        }

        void post(String eventType, Object event) throws Exception {
            Object bus = type(eventType).getField("BUS").get(null);
            Method post = type("net/minecraftforge/eventbus/api/bus/EventBus").getMethod("post", type("net/minecraftforge/eventbus/internal/Event"));
            try {
                post.invoke(bus, event);
            } catch (InvocationTargetException failure) {
                throw new AssertionError("the post threw", failure.getCause());
            }
        }

        /** The loader reading a model's JSON with Minecraft's JSON context. */
        Object read(Map<Object, Object> loaders, String namespace, String name, String json) throws Throwable {
            Object loader = loaders.get(id(namespace, name));
            assertNotNull(loader, namespace + ":" + name + " is registered");
            Class<?> jsonObject = type("com/google/gson/JsonObject");
            Object parsed = type("com/google/gson/JsonParser").getMethod("parseString", String.class).invoke(null, json);
            Object context = type("testhost/JsonContext").getConstructor().newInstance();
            Method read = type("net/minecraftforge/client/model/geometry/IGeometryLoader").getMethod("read", jsonObject,
                    type("com/google/gson/JsonDeserializationContext"));
            try {
                return read.invoke(loader, type("com/google/gson/JsonElement").getMethod("getAsJsonObject").invoke(parsed), context);
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
        }

        /** Texture slots from name and texture pairs. */
        Object slots(String... pairs) throws Exception {
            Class<?> jsonObject = type("com/google/gson/JsonObject");
            Object json = jsonObject.getConstructor().newInstance();
            for (int i = 0; i < pairs.length; i += 2) {
                jsonObject.getMethod("addProperty", String.class, String.class).invoke(json, pairs[i], pairs[i + 1]);
            }
            Class<?> textureSlots = type("net/minecraft/client/resources/model/sprite/TextureSlots");
            Object data = textureSlots.getMethod("parseTextureMap", jsonObject).invoke(null, json);
            Object resolver = type("net/minecraft/client/resources/model/sprite/TextureSlots$Resolver").getConstructor().newInstance();
            resolver.getClass().getMethod("addLast", data.getClass()).invoke(resolver, data);
            return resolver.getClass().getMethod("resolve", type("net/minecraft/client/resources/model/ModelDebugName"))
                    .invoke(resolver, debugName("slots"));
        }

        Object context(Set<String> hidden, Object hint) throws Exception {
            return context(hidden, hint, false);
        }

        Object context(Set<String> hidden, Object hint, boolean rooted) throws Exception {
            Class<?> transformation = type("com/mojang/math/Transformation");
            Object root = transformation.getField("IDENTITY").get(null);
            if (rooted) {
                // A root transform that doubles the model's size.
                Object matrix = type("org/joml/Matrix4f").getConstructor().newInstance();
                matrix.getClass().getMethod("scale", float.class).invoke(matrix, 2f);
                root = transformation.getConstructor(type("org/joml/Matrix4fc")).newInstance(matrix);
            }
            return type("testhost/Context").getConstructors()[0].newInstance(hidden, hint, root);
        }

        Object debugName(String name) throws Exception {
            Class<?> debug = type("net/minecraft/client/resources/model/ModelDebugName");
            return Proxy.newProxyInstance(loader, new Class<?>[]{debug}, (proxy, method, args) ->
                    method.getName().equals("debugName") ? name : method.getName().equals("toString") ? name : null);
        }

        /** Minecraft baking the geometry with Forge's context, as its resolved model does. */
        Object bake(Object geometry, Object slots, String name, Object context) throws Throwable {
            Class<?> state = type("net/minecraft/client/renderer/block/dispatch/ModelState");
            Object modelState = Proxy.newProxyInstance(loader, new Class<?>[]{state}, (proxy, method, args) -> null);
            Object baker = type("testhost/Baker").getConstructor().newInstance();
            Method bake = geometry.getClass().getMethod("bake", type("net/minecraft/client/resources/model/sprite/TextureSlots"),
                    type("net/minecraft/client/resources/model/ModelBaker"), state, type("net/minecraft/client/resources/model/ModelDebugName"),
                    type("net/minecraftforge/client/model/geometry/IGeometryBakingContext"));
            bake.setAccessible(true);
            try {
                return bake.invoke(geometry, slots, baker, modelState, debugName(name), context);
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
        }

        @SuppressWarnings("unchecked")
        List<Object> all(Object quads) throws Exception {
            return (List<Object>) quads.getClass().getMethod("getAll").invoke(quads);
        }

        List<Integer> tints(Object quads) throws Exception {
            List<Integer> tints = new ArrayList<>();
            for (Object quad : all(quads)) tints.add(tint(quad));
            return tints;
        }

        List<String> layers(Object quads) throws Exception {
            List<String> layers = new ArrayList<>();
            for (Object quad : all(quads)) {
                Object info = quad.getClass().getMethod("materialInfo").invoke(quad);
                layers.add(info.getClass().getMethod("layer").invoke(info).toString());
            }
            return layers;
        }

        private int tint(Object quad) throws Exception {
            Object info = quad.getClass().getMethod("materialInfo").invoke(quad);
            return (Integer) info.getClass().getMethod("tintIndex").invoke(info);
        }

        /** A block state's single model, as Minecraft bakes a plain variant. */
        Object variant(Object quads) throws Exception {
            Class<?> wrapper = type("net/minecraft/client/resources/model/SimpleModelWrapper");
            Object part = wrapper.getConstructors()[0].newInstance(quads, true, null);
            return type("net/minecraft/client/renderer/block/dispatch/SingleVariant")
                    .getConstructor(type("net/minecraft/client/renderer/block/dispatch/BlockStateModelPart")).newInstance(part);
        }

        Object cuboid(List<Object> quads) throws Exception {
            Class<?> properties = type("net/minecraft/client/renderer/item/ModelRenderProperties");
            Object props = properties.getConstructors()[0].newInstance(true, null,
                    type("net/minecraft/client/resources/model/cuboid/ItemTransforms").getField("NO_TRANSFORMS").get(null));
            return type("net/minecraft/client/renderer/item/CuboidItemModelWrapper").getConstructor(List.class, properties)
                    .newInstance(quads, props);
        }

        Object modifyEvent(Map<Object, Object> blocks, Map<Object, Object> items) throws Exception {
            Class<?> missingType = type("net/minecraft/client/resources/model/ModelBakery$MissingModels");
            Object missing = missingType.getConstructors()[0].newInstance(type("testhost/World").getMethod("hostModel", int.class).invoke(null, 99));
            Object baked = type("net/minecraft/client/resources/model/ModelBakery$BakingResult").getConstructors()[0]
                    .newInstance(missing, blocks, items);
            return type("net/minecraftforge/client/event/ModelEvent$ModifyBakingResult").getConstructors()[0].newInstance(null, baked);
        }

        Object modelData(String tag) throws Exception {
            return type("net/minecraftforge/client/model/data/ModelData").getMethod("of", String.class).invoke(null, tag);
        }

        /** The tint of every quad of every part the model collects with this model data. */
        List<Integer> partTints(Object model, Object data) throws Exception {
            List<Object> parts = new ArrayList<>();
            Object random = type("net/minecraft/util/RandomSource").getMethod("create", long.class).invoke(null, 42L);
            model.getClass().getMethod("collectParts", type("net/minecraft/util/RandomSource"), List.class, data.getClass())
                    .invoke(model, random, parts, data);
            List<Integer> tints = new ArrayList<>();
            Class<?> direction = type("net/minecraft/core/Direction");
            for (Object part : parts) {
                Method getQuads = part.getClass().getMethod("getQuads", direction);
                for (Object side : direction.getEnumConstants()) for (Object quad : (List<?>) getQuads.invoke(part, side)) tints.add(tint(quad));
                for (Object quad : (List<?>) getQuads.invoke(part, (Object) null)) tints.add(tint(quad));
            }
            return tints;
        }

        Object call(String owner, String name, Object argument) throws Throwable {
            for (Method method : type(owner).getMethods()) {
                if (method.getName().equals(name)) {
                    try {
                        return method.invoke(null, argument);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                }
            }
            throw new AssertionError(owner + " has no " + name);
        }
    }
}
