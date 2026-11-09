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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.File;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** The shared level-stage pass, run on Forge's real EventBus 7 with stand-in client classes and three mods' copies. */
class LegacyRenderStageDispatchTest {

    private static final Path LEGACY_CLASSES = Path.of("target", "legacyapi-classes");
    private static final String LEGACYAPI = "com/retromod/legacyapi/";
    private static final String SECOND = "com/retromod/embedded/second/";
    private static final String THIRD = "com/retromod/embedded/third/";
    private static final String OLD_EVENT = LegacyForgeRenderBridge.OLD_EVENT;
    private static final String OLD_STAGE = LegacyForgeRenderBridge.OLD_STAGE;
    private static final String OLD_REGISTER = LegacyForgeRenderBridge.OLD_REGISTER_STAGE;
    private static final String NOTES = "testhost/Notes";
    private static final String SHARED_SLOTS = "retromod.shared.forge.levelStages.";
    private static final List<String> VANILLA_STAGES = List.of("after_sky", "after_solid_blocks", "after_cutout_mipped_blocks",
            "after_cutout_blocks", "after_entities", "after_block_entities", "after_translucent_blocks", "after_tripwire_blocks",
            "after_particles", "after_weather", "after_level");
    // FML's loading states are left out, so the rebuilt dispatch treats loading as done, as on a running client.
    private static final List<String> LEFT_OUT = List.of("net/minecraftforge/fml/ModLoader", "net/minecraftforge/fml/core/ModStateProvider");

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
        transformer.clearRedirectsForTesting();
    }

    // The shared slots live in system properties, so no test sees another's copies.
    private static void clearSharedSlots() {
        System.getProperties().keySet().removeIf(key -> key.toString().startsWith(SHARED_SLOTS));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"26.2-65.0.0 with eventbus 7.0.1", "26.3-66.1.2 with eventbus 7.0.6"})
    @DisplayName("Two mods' copies hear every stage in Forge 1.20.1's order, stage by stage in mod order, by priority within a mod")
    void stagesFireStageMajorAcrossCopies(String host) throws Throwable {
        Game game = game(host);
        // FML's mod order is moda, modb, modc, but modb's copy installs first.
        for (String mod : List.of("moda", "modb", "modc")) game.modContainer(mod);
        game.construct("modb", SECOND);
        game.construct("moda", "");
        game.construct("moda", "");
        game.construct("modc", THIRD);
        game.subscribe("", "mod/ListenersA", null);
        game.subscribe("", "mod/RegisterA", game.modGroup("moda"));
        game.subscribe(SECOND, "mod/ListenersB", null);
        List<Integer> flushed = new ArrayList<>();
        List<String> notes = game.notes();
        game.flushAfterEachStage(SECOND, () -> flushed.add(notes.size()));

        assertEquals(List.of(), game.passes(), "nothing is added before the world renderer exists");
        Object levelRenderer = game.worldRendererBuilt();
        game.postAddFramePass();
        assertEquals(1, game.passes().size(), "the first copy adds one pass for every copy, however often each installs");
        assertEquals("retromod:legacy_level_stages", game.passes().get(0)[0].toString());
        assertEquals(List.of("register moda"), game.notes(), "RegisterStageEvent reaches the mod that listens, once");

        Object pass = game.passes().get(0)[1];
        List<String> passCalls = game.extracts(pass, host);
        assertEquals(List.of("readsAndWrites main", "disableCulling"), passCalls, "the pass keeps the main target in the frame");

        game.notes().clear();
        Object state = game.levelRenderState(1234L);
        game.executes(pass, state);

        List<String> expected = new ArrayList<>();
        for (String path : VANILLA_STAGES) {
            String stage = "minecraft:" + path;
            String same = path.equals("after_particles") ? " same" : "";
            expected.addAll(List.of("A high " + stage, "A normal " + stage + same, "A low " + stage));
            if (path.equals("after_entities")) expected.add("A leak " + stage);
            expected.add("B normal " + stage + same);
            if (path.equals("after_weather")) expected.add("B rude " + stage);
        }
        expected.addAll(List.of("A high moda:custom", "A normal moda:custom", "A low moda:custom"));
        assertEquals(expected, game.notes(), "stage by stage across copies in mod order; a pose one mod leaves pushed,"
                + " with or without throwing, does not reach the next");
        assertEquals(12, flushed.size(), "the flush runs after each of the eleven stages and the registered ones");
        assertEquals(List.of(4, 8, 12), flushed.subList(0, 3), "each flush follows its stage's listeners");

        // The frame's members, as the last listener saw them.
        Object event = game.lastEvent();
        assertEquals(0.25f, (Float) game.call(event, "getPartialTick"));
        assertEquals(4321, (Integer) game.call(event, "getRenderTick"), "the client tick count, from the HUD");
        assertSame(game.camera(), game.call(event, "getCamera"));
        assertSame(game.frustum(state), game.call(event, "getFrustum"));
        assertSame(levelRenderer, game.call(event, "getLevelRenderer"));
        assertEquals(game.bobbedProjection(), game.call(event, "getProjectionMatrix"),
                "the projection carries GameRenderer's hurt tilt and view bobbing, as Forge 1.20.1's did");
        assertNotEquals(game.projection(), game.call(event, "getProjectionMatrix"));
        assertEquals(game.viewRotation(), game.call(event, "getModelViewMatrix"));
        Object poseStack = game.call(event, "getPoseStack");
        assertEquals(game.viewRotation(), game.call(game.call(poseStack, "last"), "pose"),
                "Forge 1.20.1 kept the camera rotation in the event's PoseStack");
        for (String path : VANILLA_STAGES) {
            Object expectedModelView = path.equals("after_weather") ? game.viewRotation() : game.identity();
            assertEquals(expectedModelView, game.modelViewAt("minecraft:" + path),
                    "the model-view matrix at " + path + ": the camera rotation at AFTER_WEATHER only, as on Forge 1.20.1");
        }
        assertEquals(game.outerModelView(), game.modelView(), "and is back to the frame's own afterwards");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"26.2-65.0.0 with eventbus 7.0.1", "26.3-66.1.2 with eventbus 7.0.6"})
    @DisplayName("An event class without a static BUS is looked up once, however often a frame asks whether anything listens")
    void busFieldIsLookedUpOncePerClass(String host) throws Throwable {
        Game game = game(host);
        Method listens = game.type(LegacyForgeRenderBridge.EVENT).getMethod("hasListeners");
        int before = game.busFieldLookups();
        listens.invoke(null);
        assertEquals(before + 1, game.busFieldLookups(), "the first ask looks the class up");
        for (int frame = 0; frame < 50; frame++) listens.invoke(null);
        assertEquals(before + 1, game.busFieldLookups(), "later asks read the cached answer");

        // A whole frame with listeners asks again for every copy and stage, and still looks up nothing new.
        game.construct("moda", "");
        game.subscribe("", "mod/ListenersA", null);
        game.worldRendererBuilt();
        game.postAddFramePass();
        Object pass = game.passes().get(0)[1];
        game.executes(pass, game.levelRenderState(1L));
        int afterFirstFrame = game.busFieldLookups();
        game.executes(pass, game.levelRenderState(2L));
        assertEquals(afterFirstFrame, game.busFieldLookups());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"26.2-65.0.0 with eventbus 7.0.1", "26.3-66.1.2 with eventbus 7.0.6"})
    @DisplayName("A pass whose copies have no listeners builds no frame and leaves the model-view matrix alone")
    void copiesWithoutListenersAreSkipped(String host) throws Throwable {
        Game game = game(host);
        game.construct("modc", THIRD);
        game.worldRendererBuilt();
        game.postAddFramePass();
        assertEquals(1, game.passes().size());
        game.executes(game.passes().get(0)[1], game.levelRenderState(1L));
        assertEquals(0, game.deltaReads(), "no frame was built");
        assertEquals(game.outerModelView(), game.modelView());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"26.2-65.0.0 with eventbus 7.0.1", "26.3-66.1.2 with eventbus 7.0.6"})
    @DisplayName("A first copy installed after AddFramePassEvent adds the pass on the render thread, once")
    void lateInstallAddsThePassOnTheRenderThread(String host) throws Throwable {
        Game game = game(host);
        game.worldRendererBuilt();
        game.construct("moda", "");
        game.construct("modb", SECOND);
        assertEquals(1, game.tasks().size(), "one task for the render thread");
        assertEquals(List.of(), game.passes(), "Forge's pass list is not touched from the constructing thread");
        game.postAddFramePass();
        assertEquals(List.of(), game.passes(), "no listener waits for the event that already fired");
        game.tasks().forEach(Runnable::run);
        assertEquals(1, game.passes().size());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"26.2-65.0.0 with eventbus 7.0.1"})
    @DisplayName("fromRenderType answers the three block layers, collapsing cutout mipped and tripwire, and a registered stage")
    void fromRenderTypeMapsTheBlockLayers(String host) throws Exception {
        Game game = game(host);
        Class<?> stage = game.type(LegacyForgeRenderBridge.EVENT + "$Stage");
        Method from = stage.getMethod("fromRenderType", game.type("net/minecraft/client/renderer/rendertype/RenderType"));
        assertSame(stage.getField("AFTER_SOLID_BLOCKS").get(null), from.invoke(null, game.renderType("solidMovingBlock")));
        assertSame(stage.getField("AFTER_CUTOUT_BLOCKS").get(null), from.invoke(null, game.renderType("cutoutMovingBlock")));
        assertSame(stage.getField("AFTER_TRANSLUCENT_BLOCKS").get(null), from.invoke(null, game.renderType("translucentMovingBlock")));
        Object custom = game.type("net/minecraft/client/renderer/rendertype/RenderType").getConstructor(String.class).newInstance("glow");
        assertNull(from.invoke(null, custom));

        Object register = game.type(LegacyForgeRenderBridge.EVENT + "$RegisterStageEvent").getConstructor().newInstance();
        Method add = register.getClass().getMethod("register", game.type("net/minecraft/resources/Identifier"),
                game.type("net/minecraft/client/renderer/rendertype/RenderType"));
        Object glow = add.invoke(register, game.id("moda", "glow"), custom);
        assertSame(glow, from.invoke(null, custom));
        assertEquals("moda:glow", glow.toString());
        Throwable taken = assertThrows(InvocationTargetException.class,
                () -> add.invoke(register, game.id("moda", "solid"), game.renderType("solidMovingBlock"))).getCause();
        assertEquals(IllegalArgumentException.class, taken.getClass(), "a block layer already has its stage");
    }

    @Test
    @DisplayName("On a dedicated server the constructor's installs return before loading any client class")
    void dedicatedServerInstallLoadsNoClientClass() throws Throwable {
        Path eventBus = eventBusJar("26.2");
        Assumptions.assumeTrue(eventBus != null, "no EventBus 7 jar at " + eventBusCandidates("26.2"));
        Map<String, byte[]> classes = new HashMap<>(jarClasses(eventBus));
        transformer.getSyntheticClasses().forEach(classes::put);
        classes.putAll(compile(dir.resolve("server"), Map.of(
                "net/minecraftforge/fml/ModContainer.java", """
                        package net.minecraftforge.fml;
                        public class ModContainer {
                            public final String getNamespace() { return "servermod"; }
                            public net.minecraftforge.eventbus.api.bus.BusGroup getModBusGroup() { return null; }
                        }""",
                "net/minecraftforge/fml/ModLoadingContext.java", """
                        package net.minecraftforge.fml;
                        public class ModLoadingContext {
                            public static ModLoadingContext get() { return new ModLoadingContext(); }
                            public String getActiveNamespace() { return "servermod"; }
                            public ModContainer getContainer() { return new ModContainer(); }
                        }"""), eventBus));
        List<String> requested = Collections.synchronizedList(new ArrayList<>());
        ClassLoader server = loader(classes, requested);

        // Called as the constructor calls them: reflection over every method would load each parameter type.
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        lookup.findStatic(Class.forName(dotted(LegacyClientRenderBootstrapInjector.BOOTSTRAP), true, server), "install",
                MethodType.methodType(void.class)).invoke();
        lookup.findStatic(Class.forName(dotted(LegacyForgeRenderBridge.LEVEL_STAGES), true, server), "install",
                MethodType.methodType(void.class)).invoke();

        List<String> client = requested.stream().filter(name -> name.startsWith("net.minecraft.client.")).distinct().toList();
        assertEquals(List.of("net.minecraft.client.Minecraft"), client, "only the client probe, which finds nothing");
        assertTrue(requested.stream().noneMatch(name -> name.endsWith("LegacyLevelStagePass") || name.endsWith("RenderLevelStageEvent")),
                "the pass and the event, which name client classes, never load: " + requested);
        assertTrue(System.getProperties().keySet().stream().noneMatch(key -> key.toString().startsWith(SHARED_SLOTS)),
                "a server lists no copy for the pass");
    }

    // ---- the game ----

    /** One client: the real EventBus and JOML, stand-in client and FML classes, three mods' copies, and the old listeners. */
    private Game game(String host) throws Exception {
        Path eventBus = eventBusJar(host);
        Path joml = jomlJar();
        Assumptions.assumeTrue(eventBus != null, "no EventBus 7 jar for Forge " + host + " at " + eventBusCandidates(host));
        Assumptions.assumeTrue(joml != null, "no JOML jar at " + jomlCandidates());
        Map<String, byte[]> standIns = compile(dir.resolve(host.substring(0, 4)), standInSources(host.startsWith("26.3")), eventBus, joml);
        Map<String, byte[]> classes = new HashMap<>(jarClasses(eventBus));
        classes.putAll(jarClasses(joml));
        // Every other host class is its compile-only stub.
        for (String root : List.of("net", "com/mojang")) {
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
        transformer.getSyntheticClasses().forEach(classes::put);
        // Two more jars' copies, relocated under their own packages as the embedder does.
        for (String copy : List.of(SECOND, THIRD)) {
            transformer.getSyntheticClasses().forEach((name, bytes) -> {
                if (name.startsWith(LEGACYAPI)) classes.put(copy + name, remap(bytes, relocation(copy)));
            });
        }
        classes.put("mod/ListenersA", transformer.transformClass(listeners("mod/ListenersA", "A",
                new String[][]{{"high", "HIGH"}, {"normal", null}, {"low", "LOW"}, {"leak", "LOWEST"}}), "mod/ListenersA.class"));
        classes.put("mod/RegisterA", transformer.transformClass(registerListener("mod/RegisterA"), "mod/RegisterA.class"));
        classes.put("mod/ListenersB", remap(transformer.transformClass(listeners("mod/ListenersB", "B",
                new String[][]{{"normal", null}, {"rude", "LOWEST"}}), "mod/ListenersB.class"), relocation(SECOND)));
        Game game = new Game(loader(classes, null));
        game.startGroup(game.group());
        return game;
    }

    private static Remapper relocation(String copy) {
        return new Remapper() {
            @Override
            public String map(String internalName) {
                return internalName.startsWith(LEGACYAPI) ? copy + internalName : internalName;
            }
        };
    }

    /** Reflection over one test game's classes. */
    private record Game(ClassLoader loader) {

        Class<?> type(String internalName) throws ClassNotFoundException {
            return Class.forName(dotted(internalName), true, loader);
        }

        Object group() throws Exception {
            return type("net/minecraftforge/eventbus/api/bus/BusGroup").getField("DEFAULT").get(null);
        }

        void startGroup(Object group) throws Exception {
            type("net/minecraftforge/eventbus/api/bus/BusGroup").getMethod("startup").invoke(group);
        }

        Object minecraft() throws Exception {
            return type("net/minecraft/client/Minecraft").getMethod("getInstance").invoke(null);
        }

        /** One mod constructing: FML names it, then the two install calls Retromod put at the head of its constructor. */
        void construct(String modId, String copy) throws Throwable {
            Object mod = modContainer(modId);
            type("net/minecraftforge/fml/ModLoadingContext").getField("active").set(null, mod);
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            lookup.findStatic(type(copy + LegacyClientRenderBootstrapInjector.BOOTSTRAP), "install",
                    MethodType.methodType(void.class)).invoke();
            lookup.findStatic(type(copy + LegacyForgeRenderBridge.LEVEL_STAGES), "install", MethodType.methodType(void.class)).invoke();
            type("net/minecraftforge/fml/ModLoadingContext").getField("active").set(null, null);
        }

        @SuppressWarnings("unchecked")
        Object modContainer(String modId) throws Exception {
            Class<?> list = type("net/minecraftforge/fml/ModList");
            List<Object> mods = (List<Object>) list.getField("MODS").get(null);
            for (Object mod : mods) {
                if (mod.getClass().getMethod("getModId").invoke(mod).equals(modId)) return mod;
            }
            Class<?> busGroup = type("net/minecraftforge/eventbus/api/bus/BusGroup");
            Object group = busGroup.getMethod("create", String.class, Class.class)
                    .invoke(null, modId, type("net/minecraftforge/fml/event/IModBusEvent"));
            startGroup(group);
            Object mod = type("net/minecraftforge/fml/ModContainer").getConstructor(String.class, busGroup).newInstance(modId, group);
            mods.add(mod);
            return mod;
        }

        Object modGroup(String modId) throws Exception {
            Object mod = modContainer(modId);
            return mod.getClass().getMethod("getModBusGroup").invoke(mod);
        }

        /** {@code LegacyEvents.subscribe(group, Listeners.class)} in one copy, as the bus bridge's register does. */
        void subscribe(String copy, String listeners, Object group) throws Exception {
            Class<?> events = type(copy + LEGACYAPI + "forge/eventbus/LegacyEvents");
            for (Method method : events.getMethods()) {
                if (method.getName().equals("subscribe") && method.getParameterCount() == 2) {
                    method.invoke(null, group == null ? group() : group, type(listeners));
                    return;
                }
            }
            throw new AssertionError("LegacyEvents has no subscribe");
        }

        void flushAfterEachStage(String copy, Runnable flush) throws Exception {
            type(copy + LegacyForgeRenderBridge.LEVEL_STAGES).getMethod("flushAfterEachStage", Runnable.class).invoke(null, flush);
        }

        /** Minecraft builds its world renderer, after which it posts AddFramePassEvent. */
        Object worldRendererBuilt() throws Exception {
            Object renderer = type("net/minecraft/client/renderer/LevelRenderer").getConstructor().newInstance();
            minecraft().getClass().getField("levelRenderer").set(minecraft(), renderer);
            return renderer;
        }

        void postAddFramePass() throws Exception {
            Class<?> event = type("net/minecraftforge/client/event/AddFramePassEvent");
            Object bus = event.getField("BUS").get(null);
            Method post = type("net/minecraftforge/eventbus/api/bus/EventBus").getMethod("post",
                    type("net/minecraftforge/eventbus/internal/Event"));
            post.invoke(bus, event.getConstructor().newInstance());
        }

        @SuppressWarnings("unchecked")
        List<Object[]> passes() throws Exception {
            return (List<Object[]>) type("net/minecraftforge/client/ForgeHooksClient").getField("PASSES").get(null);
        }

        @SuppressWarnings("unchecked")
        List<Runnable> tasks() throws Exception {
            return (List<Runnable>) minecraft().getClass().getField("tasks").get(minecraft());
        }

        int deltaReads() throws Exception {
            return minecraft().getClass().getField("deltaReads").getInt(minecraft());
        }

        /** {@code pass.extracts(targets, framePass[, state])} through Forge's interface, with a frame pass that records its calls. */
        List<String> extracts(Object pass, String host) throws Exception {
            List<String> calls = new ArrayList<>();
            Class<?> handle = type("com/mojang/blaze3d/resource/ResourceHandle");
            Object before = Proxy.newProxyInstance(loader, new Class<?>[]{handle}, (proxy, method, args) -> "main");
            Object after = Proxy.newProxyInstance(loader, new Class<?>[]{handle}, (proxy, method, args) -> "written");
            Class<?> framePass = type("com/mojang/blaze3d/framegraph/FramePass");
            Object recorder = Proxy.newProxyInstance(loader, new Class<?>[]{framePass}, (proxy, method, args) -> {
                calls.add(method.getName() + (args == null ? "" : " " + args[0].toString()));
                return method.getName().equals("readsAndWrites") ? after : null;
            });
            Class<?> bundle = type("net/minecraft/client/renderer/LevelTargetBundle");
            Object targets = bundle.getConstructor().newInstance();
            bundle.getField("main").set(targets, before);
            Class<?> definition = type("net/minecraftforge/client/FramePassManager$PassDefinition");
            if (host.startsWith("26.3")) {
                Class<?> state = type("net/minecraft/client/renderer/state/level/LevelRenderState");
                definition.getMethod("extracts", bundle, framePass, state).invoke(pass, targets, recorder, levelRenderState(0L));
            } else {
                definition.getMethod("extracts", bundle, framePass).invoke(pass, targets, recorder);
            }
            assertSame(after, bundle.getField("main").get(targets), "the main target handle is the pass's written one");
            return calls;
        }

        void executes(Object pass, Object state) throws Exception {
            Class<?> definition = type("net/minecraftforge/client/FramePassManager$PassDefinition");
            try {
                definition.getMethod("executes", type("net/minecraft/client/renderer/state/level/LevelRenderState")).invoke(pass, state);
            } catch (InvocationTargetException failure) {
                throw new AssertionError("the pass threw", failure.getCause());
            }
        }

        /** A frame's state: a camera turned by viewRotation, with a projection and frustum, at {@code gameTime}. */
        Object levelRenderState(long gameTime) throws Exception {
            Class<?> cameraState = type("net/minecraft/client/renderer/state/level/CameraRenderState");
            Object camera = cameraState.getConstructor().newInstance();
            cameraState.getField("viewRotationMatrix").set(camera, viewRotation());
            cameraState.getField("projectionMatrix").set(camera, projection());
            cameraState.getField("cullFrustum").set(camera, type("net/minecraft/client/renderer/culling/Frustum")
                    .getConstructor().newInstance());
            Class<?> levelState = type("net/minecraft/client/renderer/state/level/LevelRenderState");
            Object state = levelState.getConstructor().newInstance();
            levelState.getField("cameraRenderState").set(state, camera);
            levelState.getField("gameTime").setLong(state, gameTime);
            return state;
        }

        Object frustum(Object state) throws Exception {
            Object camera = state.getClass().getField("cameraRenderState").get(state);
            return camera.getClass().getField("cullFrustum").get(camera);
        }

        Object camera() throws Exception {
            Object renderer = minecraft().getClass().getField("gameRenderer").get(minecraft());
            return renderer.getClass().getMethod("mainCamera").invoke(renderer);
        }

        Object viewRotation() throws Exception {
            return matrix().getMethod("rotateX", float.class).invoke(identity(), 0.5f);
        }

        Object projection() throws Exception {
            return matrix().getMethod("perspective", float.class, float.class, float.class, float.class)
                    .invoke(identity(), 1.0f, 1.5f, 0.05f, 100f);
        }

        /** The projection with the stand-in GameRenderer's hurt tilt and view bobbing applied. */
        Object bobbedProjection() throws Exception {
            Object tilt = type("net/minecraft/client/renderer/GameRenderer").getMethod("tilt").invoke(null);
            return matrix().getMethod("mul", type("org/joml/Matrix4fc")).invoke(projection(), tilt);
        }

        int busFieldLookups() throws Exception {
            java.lang.reflect.Field count = type(LEGACYAPI + "forge/eventbus/LegacyEvents").getDeclaredField("busFieldLookups");
            count.setAccessible(true);
            return ((java.util.concurrent.atomic.AtomicInteger) count.get(null)).get();
        }

        Object outerModelView() throws Exception {
            return matrix().getMethod("rotateY", float.class).invoke(identity(), 0.3f);
        }

        Object identity() throws Exception {
            return matrix().getConstructor().newInstance();
        }

        private Class<?> matrix() throws ClassNotFoundException {
            return type("org/joml/Matrix4f");
        }

        Object modelView() throws Exception {
            Object stack = type("com/mojang/blaze3d/systems/RenderSystem").getMethod("getModelViewStack").invoke(null);
            return matrix().getConstructor(type("org/joml/Matrix4fc")).newInstance(stack);
        }

        Object modelViewAt(String stage) throws Exception {
            return ((Map<?, ?>) type(NOTES).getField("MODEL_VIEW").get(null)).get(stage);
        }

        @SuppressWarnings("unchecked")
        List<String> notes() throws Exception {
            return (List<String>) type(NOTES).getField("SEEN").get(null);
        }

        Object lastEvent() throws Exception {
            return type(NOTES).getField("last").get(null);
        }

        Object renderType(String token) throws Exception {
            return type("net/minecraft/client/renderer/rendertype/RenderTypes").getMethod(token).invoke(null);
        }

        Object id(String namespace, String path) throws Exception {
            return type("net/minecraft/resources/Identifier").getMethod("fromNamespaceAndPath", String.class, String.class)
                    .invoke(null, namespace, path);
        }

        Object call(Object target, String method) throws Exception {
            Method found = target.getClass().getMethod(method);
            found.setAccessible(true);
            return found.invoke(target);
        }
    }

    // ---- stand-in host classes ----

    /** Client and FML classes that behave as far as the pass needs, with Forge 26.2's or 26.3's PassDefinition. */
    private static Map<String, String> standInSources(boolean forge263) {
        Map<String, String> sources = new HashMap<>();
        sources.put("net/minecraft/resources/Identifier.java", """
                package net.minecraft.resources;
                public final class Identifier {
                    private final String namespace;
                    private final String path;
                    private Identifier(String namespace, String path) { this.namespace = namespace; this.path = path; }
                    public static Identifier fromNamespaceAndPath(String namespace, String path) { return new Identifier(namespace, path); }
                    @Override public String toString() { return namespace + ":" + path; }
                }""");
        sources.put("net/minecraft/client/Minecraft.java", """
                package net.minecraft.client;
                public class Minecraft {
                    private static final Minecraft INSTANCE = new Minecraft();
                    public net.minecraft.client.renderer.LevelRenderer levelRenderer;
                    public final net.minecraft.client.renderer.GameRenderer gameRenderer = new net.minecraft.client.renderer.GameRenderer();
                    public final net.minecraft.client.gui.Gui gui = new net.minecraft.client.gui.Gui();
                    public final java.util.List<Runnable> tasks = new java.util.ArrayList<>();
                    public int deltaReads;
                    public static Minecraft getInstance() { return INSTANCE; }
                    public DeltaTracker getDeltaTracker() { deltaReads++; return running -> running ? 0.75f : 0.25f; }
                    public void execute(Runnable task) { tasks.add(task); }
                }""");
        sources.put("net/minecraft/client/Camera.java", "package net.minecraft.client; public class Camera {}");
        sources.put("net/minecraft/client/renderer/GameRenderer.java", """
                package net.minecraft.client.renderer;
                import com.mojang.blaze3d.vertex.PoseStack;
                import net.minecraft.client.renderer.state.GameRenderState;
                import net.minecraft.client.renderer.state.OptionsRenderState;
                import net.minecraft.client.renderer.state.level.CameraRenderState;
                public class GameRenderer {
                    private final net.minecraft.client.Camera camera = new net.minecraft.client.Camera();
                    private final GameRenderState state = new GameRenderState();
                    public GameRenderer() {
                        state.optionsRenderState = new OptionsRenderState();
                        state.optionsRenderState.bobView = true;
                    }
                    public net.minecraft.client.Camera mainCamera() { return camera; }
                    public GameRenderState gameRenderState() { return state; }
                    private void bobHurt(CameraRenderState camera, PoseStack pose) { pose.mulPose(new org.joml.Matrix4f().rotateZ(0.2f)); }
                    private void bobView(CameraRenderState camera, PoseStack pose) {
                        pose.mulPose(new org.joml.Matrix4f().translation(0.01f, 0.02f, 0f));
                    }
                    /** What bobHurt then bobView multiply in. */
                    public static org.joml.Matrix4f tilt() {
                        return new org.joml.Matrix4f().rotateZ(0.2f).mul(new org.joml.Matrix4f().translation(0.01f, 0.02f, 0f));
                    }
                }""");
        sources.put("net/minecraft/client/gui/Gui.java", "package net.minecraft.client.gui; public class Gui { public final Hud hud = new Hud(); }");
        sources.put("net/minecraft/client/gui/Hud.java", "package net.minecraft.client.gui; public class Hud { public int getGuiTicks() { return 4321; } }");
        sources.put("net/minecraft/client/renderer/LevelRenderer.java", "package net.minecraft.client.renderer; public class LevelRenderer {}");
        sources.put("net/minecraft/client/renderer/culling/Frustum.java", "package net.minecraft.client.renderer.culling; public class Frustum {}");
        sources.put("com/mojang/blaze3d/vertex/PoseStack.java", """
                package com.mojang.blaze3d.vertex;
                public class PoseStack {
                    private final java.util.ArrayList<Pose> poses = new java.util.ArrayList<>(java.util.List.of(new Pose(new org.joml.Matrix4f())));
                    public void pushPose() { poses.add(new Pose(new org.joml.Matrix4f(last().pose()))); }
                    public void popPose() { poses.remove(poses.size() - 1); }
                    public Pose last() { return poses.get(poses.size() - 1); }
                    public boolean isEmpty() { return poses.size() == 1; }
                    public void setIdentity() { last().pose().identity(); }
                    public void mulPose(org.joml.Matrix4fc pose) { last().pose().mul(pose); }
                    public static final class Pose {
                        private final org.joml.Matrix4f pose;
                        Pose(org.joml.Matrix4f pose) { this.pose = pose; }
                        public org.joml.Matrix4f pose() { return pose; }
                    }
                }""");
        sources.put("com/mojang/blaze3d/systems/RenderSystem.java", """
                package com.mojang.blaze3d.systems;
                public final class RenderSystem {
                    private static final org.joml.Matrix4fStack MODEL_VIEW = new org.joml.Matrix4fStack(4);
                    static { MODEL_VIEW.rotateY(0.3f); }
                    public static org.joml.Matrix4fStack getModelViewStack() { return MODEL_VIEW; }
                }""");
        sources.put("net/minecraft/client/renderer/rendertype/RenderType.java", """
                package net.minecraft.client.renderer.rendertype;
                public class RenderType {
                    private final String name;
                    public RenderType(String name) { this.name = name; }
                    @Override public String toString() { return name; }
                }""");
        sources.put("net/minecraft/client/renderer/rendertype/RenderTypes.java", """
                package net.minecraft.client.renderer.rendertype;
                public final class RenderTypes {
                    private static final RenderType SOLID = new RenderType("solid");
                    private static final RenderType CUTOUT = new RenderType("cutout");
                    private static final RenderType TRANSLUCENT = new RenderType("translucent");
                    public static RenderType solidMovingBlock() { return SOLID; }
                    public static RenderType cutoutMovingBlock() { return CUTOUT; }
                    public static RenderType translucentMovingBlock() { return TRANSLUCENT; }
                }""");
        String extracts = forge263
                ? "void extracts(net.minecraft.client.renderer.LevelTargetBundle targets, com.mojang.blaze3d.framegraph.FramePass pass,"
                        + " net.minecraft.client.renderer.state.level.LevelRenderState state);"
                : "void extracts(net.minecraft.client.renderer.LevelTargetBundle targets, com.mojang.blaze3d.framegraph.FramePass pass);";
        sources.put("net/minecraftforge/client/FramePassManager.java", """
                package net.minecraftforge.client;
                public class FramePassManager {
                    public interface PassDefinition {
                        %s
                        void executes(net.minecraft.client.renderer.state.level.LevelRenderState state);
                    }
                }""".formatted(extracts));
        sources.put("net/minecraftforge/client/ForgeHooksClient.java", """
                package net.minecraftforge.client;
                public final class ForgeHooksClient {
                    public static final java.util.List<Object[]> PASSES = new java.util.ArrayList<>();
                    public static void addFramePass(net.minecraft.resources.Identifier name, FramePassManager.PassDefinition pass) {
                        for (Object[] added : PASSES) {
                            if (added[0].toString().equals(name.toString())) throw new IllegalArgumentException("Duplicate pass " + name);
                        }
                        PASSES.add(new Object[]{name, pass});
                    }
                }""");
        sources.put("net/minecraftforge/client/event/AddFramePassEvent.java", """
                package net.minecraftforge.client.event;
                public record AddFramePassEvent() implements net.minecraftforge.eventbus.api.event.RecordEvent {
                    public static final net.minecraftforge.eventbus.api.bus.EventBus<AddFramePassEvent> BUS =
                            net.minecraftforge.eventbus.api.bus.EventBus.create(AddFramePassEvent.class);
                    public void addPass(net.minecraft.resources.Identifier name, net.minecraftforge.client.FramePassManager.PassDefinition pass) {
                        net.minecraftforge.client.ForgeHooksClient.addFramePass(name, pass);
                    }
                }""");
        sources.put("net/minecraftforge/fml/ModContainer.java", """
                package net.minecraftforge.fml;
                public class ModContainer {
                    private final String id;
                    private final net.minecraftforge.eventbus.api.bus.BusGroup group;
                    public ModContainer(String id, net.minecraftforge.eventbus.api.bus.BusGroup group) { this.id = id; this.group = group; }
                    public final String getModId() { return id; }
                    public final String getNamespace() { return id; }
                    public net.minecraftforge.eventbus.api.bus.BusGroup getModBusGroup() { return group; }
                }""");
        sources.put("net/minecraftforge/fml/ModLoadingContext.java", """
                package net.minecraftforge.fml;
                public class ModLoadingContext {
                    public static ModContainer active;
                    public static ModLoadingContext get() { return new ModLoadingContext(); }
                    public String getActiveNamespace() { return active == null ? null : active.getNamespace(); }
                    public ModContainer getContainer() { return active; }
                }""");
        sources.put("net/minecraftforge/fml/ModList.java", """
                package net.minecraftforge.fml;
                public final class ModList {
                    public static final java.util.List<ModContainer> MODS = new java.util.ArrayList<>();
                    public static void forEachModInOrder(java.util.function.Consumer<ModContainer> action) { MODS.forEach(action); }
                }""");
        sources.put("testhost/Notes.java", """
                package testhost;
                import java.util.ArrayList;
                import java.util.List;
                public final class Notes {
                    public static final List<String> SEEN = new ArrayList<>();
                    public static Object last;
                    public static final java.util.Map<String, org.joml.Matrix4f> MODEL_VIEW = new java.util.HashMap<>();
                    public static void note(String tag, Object event, boolean particles) throws Exception {
                        Object stage = event.getClass().getMethod("getStage").invoke(event);
                        com.mojang.blaze3d.vertex.PoseStack pose =
                                (com.mojang.blaze3d.vertex.PoseStack) event.getClass().getMethod("getPoseStack").invoke(event);
                        SEEN.add(tag + " " + stage + (particles ? " same" : "") + (pose.isEmpty() ? "" : " pushed"));
                        last = event;
                        MODEL_VIEW.putIfAbsent(stage.toString(),
                                new org.joml.Matrix4f(com.mojang.blaze3d.systems.RenderSystem.getModelViewStack()));
                    }
                    public static boolean leak(String tag, Object event) throws Exception {
                        Object stage = event.getClass().getMethod("getStage").invoke(event);
                        if (!stage.toString().equals("minecraft:after_entities")) return false;
                        SEEN.add(tag + " leak " + stage);
                        return true;
                    }
                    public static boolean rude(String tag, Object event) throws Exception {
                        Object stage = event.getClass().getMethod("getStage").invoke(event);
                        if (!stage.toString().equals("minecraft:after_weather")) return false;
                        SEEN.add(tag + " rude " + stage);
                        return true;
                    }
                    public static void register(Object event) throws Exception {
                        SEEN.add("register moda");
                        event.getClass().getMethod("register", net.minecraft.resources.Identifier.class,
                                net.minecraft.client.renderer.rendertype.RenderType.class)
                                .invoke(event, net.minecraft.resources.Identifier.fromNamespaceAndPath("moda", "custom"), null);
                    }
                }""");
        return sources;
    }

    // ---- the old listeners ----

    /**
     * Forge 1.20.1 listeners on RenderLevelStageEvent, one per {name, priority}: each notes the stage, and whether it is
     * AFTER_PARTICLES by identity; one named {@code rude} pushes a pose and cancels the uncancelable event at AFTER_WEATHER,
     * and one named {@code leak} pushes a pose at AFTER_ENTITIES and returns.
     */
    private static byte[] listeners(String name, String tag, String[][] handlers) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String a, String b) {
                return "java/lang/Object";
            }
        };
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", null);
        for (String[] handler : handlers) {
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, handler[0], "(L" + OLD_EVENT + ";)V", null, null);
            AnnotationVisitor subscribe = mv.visitAnnotation(LegacyForgeRenderBridgeTest.OLD_SUBSCRIBE, true);
            if (handler[1] != null) subscribe.visitEnum("priority", "Lnet/minecraftforge/eventbus/api/EventPriority;", handler[1]);
            subscribe.visitEnd();
            mv.visitCode();
            if (handler[0].equals("rude") || handler[0].equals("leak")) {
                // if (Notes.rude(tag, event)) { event.getPoseStack().pushPose(); event.setCanceled(true); }, and leak
                // the same without the cancel, so it throws nothing.
                Label skip = new Label();
                mv.visitLdcInsn(tag);
                mv.visitVarInsn(ALOAD, 0);
                mv.visitMethodInsn(INVOKESTATIC, NOTES, handler[0], "(Ljava/lang/String;Ljava/lang/Object;)Z", false);
                mv.visitJumpInsn(IFEQ, skip);
                mv.visitVarInsn(ALOAD, 0);
                mv.visitMethodInsn(INVOKEVIRTUAL, OLD_EVENT, "getPoseStack", "()L" + LegacyForgeRenderBridgeTest.POSE_STACK + ";", false);
                mv.visitMethodInsn(INVOKEVIRTUAL, LegacyForgeRenderBridgeTest.POSE_STACK, "pushPose", "()V", false);
                if (handler[0].equals("rude")) {
                    mv.visitVarInsn(ALOAD, 0);
                    mv.visitInsn(ICONST_1);
                    mv.visitMethodInsn(INVOKEVIRTUAL, OLD_EVENT, "setCanceled", "(Z)V", false);
                }
                mv.visitLabel(skip);
            } else {
                // Notes.note(tag + " " + name, event, event.getStage() == Stage.AFTER_PARTICLES)
                Label other = new Label();
                Label call = new Label();
                mv.visitLdcInsn(tag + " " + handler[0]);
                mv.visitVarInsn(ALOAD, 0);
                mv.visitVarInsn(ALOAD, 0);
                mv.visitMethodInsn(INVOKEVIRTUAL, OLD_EVENT, "getStage", "()L" + OLD_STAGE + ";", false);
                mv.visitFieldInsn(GETSTATIC, OLD_STAGE, "AFTER_PARTICLES", "L" + OLD_STAGE + ";");
                mv.visitJumpInsn(IF_ACMPNE, other);
                mv.visitInsn(handler[0].equals("normal") ? ICONST_1 : ICONST_0);
                mv.visitJumpInsn(GOTO, call);
                mv.visitLabel(other);
                mv.visitInsn(ICONST_0);
                mv.visitLabel(call);
                mv.visitMethodInsn(INVOKESTATIC, NOTES, "note", "(Ljava/lang/String;Ljava/lang/Object;Z)V", false);
            }
            mv.visitInsn(RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A Forge 1.20.1 mod-bus listener on RegisterStageEvent that registers {@code moda:custom}. */
    private static byte[] registerListener(String name) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "onRegister", "(L" + OLD_REGISTER + ";)V", null, null);
        mv.visitAnnotation(LegacyForgeRenderBridgeTest.OLD_SUBSCRIBE, true).visitEnd();
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESTATIC, NOTES, "register", "(Ljava/lang/Object;)V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    // ---- class loading ----

    /** A loader over {@code classes} with only the platform loader above it; {@code requested}, when given, notes each name asked for. */
    private static ClassLoader loader(Map<String, byte[]> classes, List<String> requested) {
        return new ClassLoader(ClassLoader.getPlatformClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (requested != null) requested.add(name);
                return super.loadClass(name, resolve);
            }

            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classes.get(name.replace('.', '/'));
                if (bytes == null) throw new ClassNotFoundException(name);
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
    }

    /** Compiles the stand-in sources against the given jars, then the compiled stubs. */
    private static Map<String, byte[]> compile(Path work, Map<String, String> sources, Path... jars) throws Exception {
        Path src = work.resolve("src");
        Path out = work.resolve("out");
        List<String> classpath = new ArrayList<>();
        for (Path jar : jars) classpath.add(jar.toString());
        classpath.add(LEGACY_CLASSES.toString());
        List<String> args = new ArrayList<>(List.of("-d", out.toString(), "--release", "17", "-proc:none",
                "-classpath", String.join(File.pathSeparator, classpath)));
        for (Map.Entry<String, String> source : sources.entrySet()) {
            Path file = src.resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
            args.add(file.toString());
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "the tests need a JDK to compile the host stand-ins");
        assertEquals(0, compiler.run(null, null, null, args.toArray(new String[0])), "the host stand-ins must compile");
        Map<String, byte[]> classes = new HashMap<>();
        try (Stream<Path> files = Files.walk(out)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".class")).toList()) {
                String name = out.relativize(file).toString().replace(File.separatorChar, '/');
                classes.put(name.substring(0, name.length() - ".class".length()), Files.readAllBytes(file));
            }
        }
        return classes;
    }

    private static Map<String, byte[]> jarClasses(Path jar) throws Exception {
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

    private static byte[] remap(byte[] bytes, Remapper remapper) {
        ClassWriter writer = new ClassWriter(0);
        new ClassReader(bytes).accept(new ClassRemapper(writer, remapper), 0);
        return writer.toByteArray();
    }

    private static String dotted(String internalName) {
        return internalName.replace('/', '.');
    }

    /** Where the EventBus 7 jar of Forge {@code host} may be: a system property, Prism's libraries, then a local copy. */
    private static List<Path> eventBusCandidates(String host) {
        String version = host.startsWith("26.2") ? "7.0.1" : "7.0.6";
        String home = System.getProperty("user.home");
        List<Path> candidates = new ArrayList<>();
        String configured = System.getProperty("retromod.test.eventbus" + version.replace(".", ""));
        if (configured != null && !configured.isBlank()) candidates.add(Path.of(configured));
        candidates.add(Path.of(home, "Library/Application Support/PrismLauncher/libraries/net/minecraftforge/eventbus",
                version, "eventbus-" + version + ".jar"));
        if (version.equals("7.0.6")) candidates.add(Path.of(home, ".claude/jobs/3da2a019/tmp/forge263/eventbus-7.0.6.jar"));
        return candidates;
    }

    private static Path eventBusJar(String host) {
        return eventBusCandidates(host).stream().filter(Files::isRegularFile).findFirst().orElse(null);
    }

    /** JOML, which Minecraft 26.2 and 26.3 ship: a system property, then Prism's libraries. */
    private static List<Path> jomlCandidates() {
        List<Path> candidates = new ArrayList<>();
        String configured = System.getProperty("retromod.test.joml");
        if (configured != null && !configured.isBlank()) candidates.add(Path.of(configured));
        Path libraries = Path.of(System.getProperty("user.home"), "Library/Application Support/PrismLauncher/libraries/org/joml/joml");
        for (String version : List.of("1.10.8", "1.10.9", "1.10.5")) {
            candidates.add(libraries.resolve(version).resolve("joml-" + version + ".jar"));
        }
        return candidates;
    }

    private static Path jomlJar() {
        return jomlCandidates().stream().filter(Files::isRegularFile).findFirst().orElse(null);
    }
}
