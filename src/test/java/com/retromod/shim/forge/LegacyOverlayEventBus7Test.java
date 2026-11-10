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

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static com.retromod.shim.forge.LegacyGuiOverlayBridgeTest.FORGE_GUI;
import static com.retromod.shim.forge.LegacyGuiOverlayBridgeTest.MINECRAFT;
import static com.retromod.shim.forge.LegacyGuiOverlayBridgeTest.NAMED;
import static com.retromod.shim.forge.LegacyGuiOverlayBridgeTest.OLD_EVENT;
import static com.retromod.shim.forge.LegacyGuiOverlayBridgeTest.OLD_SUBSCRIBE;
import static com.retromod.shim.forge.LegacyGuiOverlayBridgeTest.PRE;
import static com.retromod.shim.forge.LegacyGuiOverlayBridgeTest.VANILLA;
import static com.retromod.shim.forge.LegacyGuiOverlayBridgeTest.dotted;
import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** The rebuilt overlay events, registration and ForgeGui, run on Forge's real EventBus 7 with stand-in client classes. */
class LegacyOverlayEventBus7Test {

    private static final Path LEGACY_CLASSES = Path.of("target", "legacyapi-classes");
    private static final String ROOT = LegacyGuiOverlayBridge.ROOT;
    private static final String EVENTS = "com/retromod/legacyapi/forge/eventbus/LegacyEvents";
    private static final String SECOND = "com/retromod/embedded/second/";
    private static final String BASE = OLD_EVENT + "RenderGuiOverlayEvent";
    private static final String OVERLAY_POST = BASE + "$Post";
    private static final String GUI_POST = OLD_EVENT + "RenderGuiEvent$Post";
    private static final String LISTENERS = "mod/HudListeners";
    // Host classes the tests need to behave; every other host class is its compile-only stub.
    private static final List<String> STAND_INS = List.of("net/minecraft/resources/Identifier", "com/mojang/blaze3d/platform/Window",
            "net/minecraft/client/gui/GuiGraphicsExtractor", "net/minecraft/client/DeltaTracker", MINECRAFT,
            "net/minecraft/client/gui/Gui", "net/minecraft/client/gui/Hud", "net/minecraft/client/gui/components/DebugScreenOverlay");
    // Without FML's loading states the rebuilt dispatch treats loading as done, as on a running client.
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
        clearSharedHud();
    }

    @AfterEach
    void restore() {
        clearSharedHud();
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        transformer.clearRedirectsForTesting();
    }

    // The shared HUD slots live in system properties, so no test sees another's overlays.
    private static void clearSharedHud() {
        System.getProperties().keySet().removeIf(key -> key.toString().startsWith("retromod.shared.forge.hud."));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"26.2-65.0.0 with eventbus 7.0.1", "26.3-66.1.2 with eventbus 7.0.6"})
    @DisplayName("Old overlay listeners keep Forge 1.20.1's priority order, cancel, receiveCanceled and base-class rules")
    void oldListenersKeepTheOldRules(String host) throws Exception {
        Game game = game(host);
        game.subscribe(LISTENERS);
        List<String> notes = game.notes();
        game.events("addListener", game.group(), game.type(ROOT + "event/RenderGuiOverlayEvent$Pre"), (byte) 0, false, null,
                (Consumer<Object>) event -> notes.add("lambda"));

        assertEquals(false, game.hub("pre", game.id("hotbar"), game.window(), game.graphics(), 0.5f));
        assertEquals(List.of("high", "normal", "lambda", "base", "low", "watch false"), notes,
                "by priority; at one priority the event's own listeners before its base class's, then in registration order");
        assertArrayEquals(new int[]{39, 49}, game.heights(), "the LOW listener's ForgeGui height reaches the shared HUD");

        notes.clear();
        assertEquals(true, game.hub("pre", game.id("crosshair"), game.window(), game.graphics(), 0.5f),
                "a cancel in the HIGH listener reaches the coordinator");
        assertEquals(List.of("high", "watch true"), notes, "later listeners skip a cancelled event unless they take cancelled ones");

        notes.clear();
        assertEquals(false, game.hub("post", game.id("hotbar"), game.window(), game.graphics(), 0.5f));
        assertEquals(List.of("post", "base"), notes, "a listener on the base class hears Post too");

        notes.clear();
        assertEquals(false, game.hub("guiPre", game.window(), game.graphics(), 0.5f));
        game.hub("guiPost", game.window(), game.graphics(), 0.5f);
        assertEquals(List.of("gui post"), notes);
        assertEquals(true, game.hub("listens"));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"26.2-65.0.0 with eventbus 7.0.1", "26.3-66.1.2 with eventbus 7.0.6"})
    @DisplayName("A Pre that arrives cancelled from an earlier mod reaches only receiveCanceled listeners, which can keep or undo it")
    void cancelledPreReachesOnlyWatchers(String host) throws Exception {
        Game game = game(host);
        game.subscribe(LISTENERS);
        assertEquals(true, game.hub("pre", game.id("hotbar"), game.window(), game.graphics(), 0.5f, true));
        assertEquals(List.of("watch true"), game.notes(), "the ordinary listeners pass a cancelled event by");

        game.notes().clear();
        assertEquals(false, game.hub("pre", game.id("hotbar"), game.window(), game.graphics(), 0.5f, false));
        assertTrue(game.notes().contains("high"), "a Pre that arrives uncancelled runs every listener: " + game.notes());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"26.2-65.0.0 with eventbus 7.0.1", "26.3-66.1.2 with eventbus 7.0.6"})
    @DisplayName("The register request posts RegisterGuiOverlaysEvent on a real mod bus group made with IModBusEvent")
    void registerPostsOnARealModBusGroup(String host) throws Exception {
        Game game = game(host);
        Class<?> busGroup = game.type("net/minecraftforge/eventbus/api/bus/BusGroup");
        Class<?> modBusEvent = game.type("net/minecraftforge/fml/event/IModBusEvent");
        Object group = busGroup.getMethod("create", String.class, Class.class).invoke(null, "moda", modBusEvent);
        busGroup.getMethod("startup").invoke(group);
        assertEquals(false, game.hub("register", group, "moda"), "nothing listens yet");

        Class<?> register = game.type(ROOT + "event/RegisterGuiOverlaysEvent");
        Object bus = modBusEvent.getMethod("getBus", busGroup, Class.class).invoke(null, group, register);
        Object probe = game.overlay("", new ArrayList<>(), null);
        Object hotbar = game.id("hotbar");
        Consumer<Object> listener = event -> {
            try {
                game.register(event, "registerAbove", hotbar, "probe", probe);
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        };
        game.type("net/minecraftforge/eventbus/api/bus/EventBus").getMethod("addListener", Consumer.class).invoke(bus, listener);
        assertEquals(true, game.hub("register", group, "moda"));
        assertEquals(game.order().indexOf("minecraft:hotbar") + 1, game.order().indexOf("moda:probe"),
                "the overlay is named for the mod whose bus carried the event");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"26.2-65.0.0 with eventbus 7.0.1", "26.3-66.1.2 with eventbus 7.0.6"})
    @DisplayName("EventBus.create gives the dispatch's own bus, and listeners subscribed after the first post still hear later ones")
    void busesAreCreatedOnceInEitherOrder(String host) throws Exception {
        Game game = game(host);
        Class<?> pre = game.type(ROOT + "event/RenderGuiOverlayEvent$Pre");
        assertEquals(false, game.hub("pre", game.id("hotbar"), game.window(), game.graphics(), 0.5f));
        assertEquals(false, game.hub("listens"), "nothing listens yet");

        game.subscribe(LISTENERS);
        Class<?> eventBus = game.type("net/minecraftforge/eventbus/api/bus/EventBus");
        Object created = eventBus.getMethod("create", game.type("net/minecraftforge/eventbus/api/bus/BusGroup"), Class.class)
                .invoke(null, game.group(), pre);
        assertSame(created, game.events("busOf", game.group(), pre), "one bus per event type in the group");
        game.hub("pre", game.id("hotbar"), game.window(), game.graphics(), 0.5f);
        assertTrue(game.notes().contains("high"), "a listener subscribed after the bus was made still runs: " + game.notes());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"26.2-65.0.0 with eventbus 7.0.1", "26.3-66.1.2 with eventbus 7.0.6"})
    @DisplayName("Overlays register into one order across mods, with the old index rules, exceptions, lookups and drawing")
    void registrationKeepsTheOldOrder(String host) throws Exception {
        Game game = game(host);
        List<Object[]> drawn = new ArrayList<>();
        Object probe = game.overlay("", drawn, null);
        Object registerA = game.newEvent("", "moda");

        game.register(registerA, "registerAbove", game.id("hotbar"), "probe", probe);
        game.register(registerA, "registerBelow", game.id("crosshair"), "under", probe);
        game.register(registerA, "registerAboveAll", null, "top", probe);
        game.register(registerA, "registerBelowAll", null, "bottom", probe);
        List<String> order = game.order();
        assertEquals("moda:bottom", order.get(0));
        assertEquals("moda:top", order.get(order.size() - 1));
        assertEquals(order.indexOf("minecraft:hotbar") + 1, order.indexOf("moda:probe"), "above goes right after the anchor");
        assertEquals(order.indexOf("minecraft:crosshair") - 1, order.indexOf("moda:under"), "below goes right before it");
        assertEquals(30, order.size());

        Throwable duplicate = assertThrows(InvocationTargetException.class,
                () -> game.register(registerA, "registerAboveAll", null, "probe", probe)).getCause();
        assertEquals(IllegalArgumentException.class, duplicate.getClass());
        assertEquals("Overlay already registered: moda:probe", duplicate.getMessage());
        Throwable unknown = assertThrows(InvocationTargetException.class,
                () -> game.register(registerA, "registerAbove", game.id("nothing"), "lost", probe)).getCause();
        assertEquals(IllegalStateException.class, unknown.getClass());
        assertEquals("Attempted to order against an unregistered overlay. Only order against vanilla's and your own.",
                unknown.getMessage());

        // Another jar's copy registers into the same order, after the first mod's overlay.
        Object registerB = game.newEvent(SECOND, "modb");
        game.register(registerB, "registerAbove", game.idOf("moda", "probe"), "after", game.overlay(SECOND, new ArrayList<>(), null));
        assertEquals(game.order().indexOf("moda:probe") + 1, game.order().indexOf("modb:after"));

        // Lookups give each mod's one NamedGuiOverlay, the object render events report.
        Class<?> manager = game.type(ROOT + "gui/overlay/GuiOverlayManager");
        Object food = game.type(ROOT + "gui/overlay/VanillaGuiOverlay").getMethod("valueOf", String.class)
                .invoke(null, "FOOD_LEVEL");
        assertSame(food.getClass().getMethod("type").invoke(food), game.find(manager, game.id("food_level")));
        assertNull(game.find(manager, game.id("nothing")));
        Object probeInB = game.find(game.type(SECOND + ROOT + "gui/overlay/GuiOverlayManager"), game.idOf("moda", "probe"));
        assertNotNull(probeInB, "another mod finds an overlay the first registered");

        // Drawing: the entry's drawer runs the overlay with the frame's partial tick and the screen size, and another
        // mod's NamedGuiOverlay draws through it.
        game.extract(game.entry("moda:probe")[1]);
        assertEquals(1, drawn.size());
        assertEquals(dotted(ROOT + "gui/overlay/ForgeGui"), drawn.get(0)[0].getClass().getName());
        assertEquals(List.of(0.25f, 320, 240), List.of(drawn.get(0)[2], drawn.get(0)[3], drawn.get(0)[4]));
        Object throughB = probeInB.getClass().getMethod("overlay").invoke(probeInB);
        game.render(throughB, SECOND);
        assertEquals(2, drawn.size(), "another mod's overlay() draws the first mod's overlay");

        // An overlay that needs a missing member is drawn once, then left out.
        List<Object[]> broken = new ArrayList<>();
        game.register(registerA, "registerAboveAll", null, "broken", game.overlay("", broken, new NoSuchMethodError("gone")));
        Object brokenDrawer = game.entry("moda:broken")[1];
        for (int frame = 0; frame < 3; frame++) game.extract(brokenDrawer);
        assertEquals(1, broken.size());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"26.2-65.0.0 with eventbus 7.0.1"})
    @DisplayName("ForgeGui's cast and check take the host's Gui and another mod's copy, and refuse anything else")
    void forgeGuiCastAcceptsTheHostGui(String host) throws Exception {
        Game game = game(host);
        Class<?> forgeGui = game.type(ROOT + "gui/overlay/ForgeGui");
        Class<?> secondGui = game.type(SECOND + ROOT + "gui/overlay/ForgeGui");
        Object gui = game.type(MINECRAFT).getField("gui").get(game.type(MINECRAFT).getMethod("getInstance").invoke(null));

        Object own = forgeGui.getMethod("cast", Object.class).invoke(null, gui);
        assertSame(forgeGui, own.getClass());
        assertSame(own, forgeGui.getMethod("cast", Object.class).invoke(null, own));
        Object other = secondGui.getMethod("cast", Object.class).invoke(null, gui);
        assertEquals(true, forgeGui.getMethod("isInstance", Object.class).invoke(null, other), "another mod's copy passes");
        assertSame(own, forgeGui.getMethod("cast", Object.class).invoke(null, other));
        assertEquals(false, forgeGui.getMethod("isInstance", Object.class).invoke(null, new Object()));
        assertEquals(false, forgeGui.getMethod("isInstance", Object.class).invoke(null, (Object) null));
        assertNull(forgeGui.getMethod("cast", Object.class).invoke(null, (Object) null));
        Throwable refused = assertThrows(InvocationTargetException.class,
                () -> forgeGui.getMethod("cast", Object.class).invoke(null, new Object())).getCause();
        assertEquals(ClassCastException.class, refused.getClass());
    }

    @Test
    @DisplayName("On a dedicated server a mod's constructor lists its copy, starts no HUD coordinator and loads no client or EventBus class")
    @SuppressWarnings("unchecked")
    void attachingLoadsNoClientClass() throws Throwable {
        // A dedicated server: FML's loading context and side, Minecraft's common Identifier and the rebuilt classes, and
        // no Minecraft client or EventBus class at all.
        ClassLoader server = new ForgeHostStandIns()
                .add("net/minecraftforge/api/distmarker/Dist.java",
                        "package net.minecraftforge.api.distmarker; public enum Dist { CLIENT, DEDICATED_SERVER }")
                .add("net/minecraftforge/fml/loading/FMLEnvironment.java", """
                        package net.minecraftforge.fml.loading;
                        public class FMLEnvironment {
                            public static final net.minecraftforge.api.distmarker.Dist dist = net.minecraftforge.api.distmarker.Dist.DEDICATED_SERVER;
                        }""")
                .add("net/minecraft/resources/Identifier.java", "package net.minecraft.resources; public final class Identifier {}")
                .add("net/minecraftforge/fml/ModContainer.java", """
                        package net.minecraftforge.fml;
                        public class ModContainer { public String getModId() { return "servermod"; } }""")
                .add("net/minecraftforge/fml/ModLoadingContext.java", """
                        package net.minecraftforge.fml;
                        public class ModLoadingContext {
                            private static final ModLoadingContext CONTEXT = new ModLoadingContext();
                            public static ModLoadingContext get() { return CONTEXT; }
                            public ModContainer getContainer() { return new ModContainer(); }
                        }""")
                .loader(dir, transformer, Map.of());
        Class<?> hub = Class.forName(dotted(ROOT + "LegacyOverlayHub"), true, server);
        // Called as the constructor calls it: reflection over every method would load each parameter type.
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        lookup.findStatic(hub, "attach", MethodType.methodType(void.class)).invoke();

        Map<?, ?> endpoints = (Map<?, ?>) lookup.findStatic(hub, "endpoints", MethodType.methodType(Map.class)).invoke();
        assertEquals(List.of("servermod"), List.copyOf(endpoints.keySet()), "the copy is listed under the constructing mod");
        assertEquals(1, ((Function<Object[], Object>) endpoints.get("servermod"))
                .apply(new Object[]{"protocol"}), "the endpoint answers without touching the client");
        assertFalse(System.getProperties().containsKey("retromod.shared.forge.hud.coordinator.v1"), "no coordinator on a server");
    }

    // ---- the game ----

    /** One client: the real EventBus 7, stand-in client classes, two mods' copies of the rebuilt API, and the old listeners. */
    private Game game(String host) throws Exception {
        Path eventBus = eventBusJar(host);
        Assumptions.assumeTrue(eventBus != null, "no EventBus 7 jar for Forge " + host + " at " + eventBusCandidates(host));
        Map<String, byte[]> classes = new HashMap<>();
        try (ZipFile jar = new ZipFile(eventBus.toFile())) {
            for (ZipEntry entry : java.util.Collections.list(jar.entries())) {
                String name = entry.getName();
                if (!name.endsWith(".class") || name.endsWith("module-info.class")) continue;
                classes.put(name.substring(0, name.length() - ".class".length()), jar.getInputStream(entry).readAllBytes());
            }
        }
        // The real IModBusEvent, whose getBus a mod-bus post goes through.
        Path fmlcore = LegacyHudGame.hostJar(host, "fmlcore");
        Assumptions.assumeTrue(fmlcore != null, "no fmlcore jar for Forge " + host);
        try (ZipFile jar = new ZipFile(fmlcore.toFile())) {
            String modBusEvent = "net/minecraftforge/fml/event/IModBusEvent";
            classes.put(modBusEvent, jar.getInputStream(jar.getEntry(modBusEvent + ".class")).readAllBytes());
        }
        for (String root : List.of("net", "com/mojang", "com/google")) {
            try (Stream<Path> files = Files.walk(LEGACY_CLASSES.resolve(root))) {
                for (Path file : files.filter(f -> f.toString().endsWith(".class")).toList()) {
                    String name = LEGACY_CLASSES.relativize(file).toString().replace('\\', '/');
                    name = name.substring(0, name.length() - ".class".length());
                    if (name.startsWith("net/minecraftforge/eventbus/") || STAND_INS.contains(name) || LEFT_OUT.contains(name)
                            || classes.containsKey(name)) {
                        continue;
                    }
                    classes.put(name, Files.readAllBytes(file));
                }
            }
        }
        // A second jar's copy, relocated under its own package as the embedder does.
        Remapper relocation = new Remapper() {
            @Override
            public String map(String internalName) {
                return internalName.startsWith("com/retromod/legacyapi/") ? SECOND + internalName : internalName;
            }
        };
        transformer.getSyntheticClasses().forEach((name, bytes) -> {
            if (name.startsWith("com/retromod/legacyapi/")) classes.put(SECOND + name, remap(bytes, relocation));
        });
        classes.put(LISTENERS, transformer.transformClass(listenersClass(), LISTENERS + ".class"));
        ClassLoader loader = standIns().loader(dir, transformer, classes);
        Game game = new Game(loader);
        // EventBus 7 posts on a started group only.
        game.type("net/minecraftforge/eventbus/api/bus/BusGroup").getMethod("startup").invoke(game.group());
        return game;
    }

    private static ForgeHostStandIns standIns() {
        return new ForgeHostStandIns()
                .add("net/minecraft/resources/Identifier.java", """
                        package net.minecraft.resources;
                        public final class Identifier {
                            private final String namespace;
                            private final String path;
                            private Identifier(String namespace, String path) { this.namespace = namespace; this.path = path; }
                            public static Identifier fromNamespaceAndPath(String namespace, String path) { return new Identifier(namespace, path); }
                            public static Identifier withDefaultNamespace(String path) { return new Identifier("minecraft", path); }
                            public String getNamespace() { return namespace; }
                            public String getPath() { return path; }
                            @Override public boolean equals(Object o) {
                                return o instanceof Identifier other && other.namespace.equals(namespace) && other.path.equals(path);
                            }
                            @Override public int hashCode() { return namespace.hashCode() * 31 + path.hashCode(); }
                            @Override public String toString() { return namespace + ":" + path; }
                        }""")
                .add("com/mojang/blaze3d/platform/Window.java", "package com.mojang.blaze3d.platform; public final class Window {}")
                .add("net/minecraft/client/gui/GuiGraphicsExtractor.java", """
                        package net.minecraft.client.gui;
                        public class GuiGraphicsExtractor {
                            public int guiWidth() { return 320; }
                            public int guiHeight() { return 240; }
                        }""")
                .add("net/minecraft/client/DeltaTracker.java", """
                        package net.minecraft.client;
                        public interface DeltaTracker { float getGameTimeDeltaPartialTick(boolean runsNormally); }""")
                .add("net/minecraft/client/gui/components/DebugScreenOverlay.java",
                        "package net.minecraft.client.gui.components; public class DebugScreenOverlay {}")
                .add("net/minecraft/client/gui/Hud.java", """
                        package net.minecraft.client.gui;
                        public class Hud {
                            public net.minecraft.client.gui.components.DebugScreenOverlay getDebugOverlay() { return null; }
                        }""")
                .add("net/minecraft/client/gui/Gui.java",
                        "package net.minecraft.client.gui; public class Gui { public final Hud hud = new Hud(); }")
                .add("net/minecraft/client/Minecraft.java", """
                        package net.minecraft.client;
                        public class Minecraft {
                            private static final Minecraft INSTANCE = new Minecraft();
                            public final net.minecraft.client.gui.Gui gui = new net.minecraft.client.gui.Gui();
                            public static Minecraft getInstance() { return INSTANCE; }
                            public DeltaTracker getDeltaTracker() { return running -> 0.25f; }
                        }""");
    }

    /** Reflection over one test game's classes. */
    private record Game(ClassLoader loader) {

        Class<?> type(String internalName) throws ClassNotFoundException {
            return Class.forName(dotted(internalName), true, loader);
        }

        Object group() throws Exception {
            return type("net/minecraftforge/eventbus/api/bus/BusGroup").getField("DEFAULT").get(null);
        }

        Object id(String path) throws Exception {
            return type("net/minecraft/resources/Identifier").getMethod("withDefaultNamespace", String.class).invoke(null, path);
        }

        Object idOf(String namespace, String path) throws Exception {
            return type("net/minecraft/resources/Identifier").getMethod("fromNamespaceAndPath", String.class, String.class)
                    .invoke(null, namespace, path);
        }

        Object window() throws Exception {
            return type("com/mojang/blaze3d/platform/Window").getConstructor().newInstance();
        }

        Object graphics() throws Exception {
            return type("net/minecraft/client/gui/GuiGraphicsExtractor").getConstructor().newInstance();
        }

        Object delta() throws Exception {
            Object minecraft = type(MINECRAFT).getMethod("getInstance").invoke(null);
            return minecraft.getClass().getMethod("getDeltaTracker").invoke(minecraft);
        }

        /** {@code LegacyEvents.subscribe(BusGroup.DEFAULT, Listeners.class)}, as the bus bridge's register does. */
        void subscribe(String listeners) throws Exception {
            events("subscribe", group(), type(listeners));
        }

        Object events(String name, Object... arguments) throws Exception {
            for (Method method : type(EVENTS).getMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == arguments.length) {
                    return method.invoke(null, arguments);
                }
            }
            throw new AssertionError("LegacyEvents has no " + name);
        }

        /** One coordinator request to the first copy's endpoint. */
        Object hub(Object... request) throws Exception {
            Method handle = type(ROOT + "LegacyOverlayHub").getDeclaredMethod("handle", Object[].class);
            handle.setAccessible(true);
            try {
                return handle.invoke(null, (Object) request);
            } catch (InvocationTargetException failure) {
                throw new AssertionError("the hub request " + request[0] + " threw", failure.getCause());
            }
        }

        int[] heights() throws Exception {
            return (int[]) type(ROOT + "LegacyOverlayHub").getMethod("heights").invoke(null);
        }

        @SuppressWarnings("unchecked")
        List<String> notes() throws Exception {
            return (List<String>) type(LISTENERS).getField("NOTES").get(null);
        }

        @SuppressWarnings("unchecked")
        List<String> order() throws Exception {
            List<String> ids = new ArrayList<>();
            for (Object id : (List<Object>) type(ROOT + "LegacyOverlayHub").getMethod("orderedIds").invoke(null)) {
                ids.add(id.toString());
            }
            return ids;
        }

        @SuppressWarnings("unchecked")
        Object[] entry(String id) throws Exception {
            for (Object[] entry : (List<Object[]>) type(ROOT + "LegacyOverlayHub").getMethod("order").invoke(null)) {
                if (entry[0].toString().equals(id)) return entry;
            }
            throw new AssertionError("no overlay " + id);
        }

        /** One copy's RegisterGuiOverlaysEvent for {@code namespace}, over the shared order. */
        Object newEvent(String copy, String namespace) throws Exception {
            Object order = type(copy + ROOT + "LegacyOverlayHub").getMethod("order").invoke(null);
            return type(copy + ROOT + "event/RegisterGuiOverlaysEvent").getConstructor(String.class, List.class)
                    .newInstance(namespace, order);
        }

        void register(Object event, String name, Object anchor, String id, Object overlay) throws Exception {
            for (Method method : event.getClass().getMethods()) {
                if (!method.getName().equals(name)) continue;
                if (anchor == null) {
                    method.invoke(event, id, overlay);
                } else {
                    method.invoke(event, anchor, id, overlay);
                }
                return;
            }
            throw new AssertionError("no " + name);
        }

        Object find(Class<?> manager, Object id) throws Exception {
            return manager.getMethod("findOverlay", type("net/minecraft/resources/Identifier")).invoke(null, id);
        }

        /** {@code layer.extract(graphics, delta)}, through the host interface, since a lambda's own class is hidden. */
        void extract(Object layer) throws Exception {
            type("net/minecraftforge/client/gui/overlay/ForgeLayer").getMethod("extract",
                    type("net/minecraft/client/gui/GuiGraphicsExtractor"), type("net/minecraft/client/DeltaTracker"))
                    .invoke(layer, graphics(), delta());
        }

        /** A copy's IGuiOverlay that records each render's arguments, and throws {@code failure} when given. */
        Object overlay(String copy, List<Object[]> drawn, Error failure) throws Exception {
            Class<?> iface = type(copy + ROOT + "gui/overlay/IGuiOverlay");
            return Proxy.newProxyInstance(loader, new Class<?>[]{iface}, (proxy, method, arguments) -> switch (method.getName()) {
                case "render" -> {
                    drawn.add(arguments);
                    if (failure != null) throw failure;
                    yield null;
                }
                case "equals" -> proxy == arguments[0];
                case "hashCode" -> System.identityHashCode(proxy);
                default -> "overlay";
            });
        }

        /** {@code overlay.render(copy's ForgeGui, graphics, 0, 1, 1)}. */
        void render(Object overlay, String copy) throws Exception {
            Class<?> forgeGui = type(copy + ROOT + "gui/overlay/ForgeGui");
            Object gui = forgeGui.getMethod("cast", Object.class).invoke(null,
                    type(MINECRAFT).getField("gui").get(type(MINECRAFT).getMethod("getInstance").invoke(null)));
            Class<?> iface = type(copy + ROOT + "gui/overlay/IGuiOverlay");
            iface.getMethod("render", forgeGui, type("net/minecraft/client/gui/GuiGraphicsExtractor"), float.class, int.class,
                    int.class).invoke(overlay, gui, graphics(), 0f, 1, 1);
        }
    }

    // ---- the old listeners ----

    /**
     * Forge 1.20.1 listeners, as an old mod writes them: a HIGH Pre listener that cancels the crosshair, NORMAL ones on Pre
     * and on the base class, a LOW one that raises ForgeGui's right height, a LOWEST receiveCanceled watcher, and
     * Post listeners for one overlay and for the whole HUD.
     */
    private static byte[] listenersClass() {
        ClassWriter cw = LegacyGuiOverlayBridgeTest.writer();
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, LISTENERS, null, "java/lang/Object", null);
        cw.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "NOTES", "Ljava/util/List;", null, null).visitEnd();
        MethodVisitor clinit = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitTypeInsn(NEW, "java/util/ArrayList");
        clinit.visitInsn(DUP);
        clinit.visitMethodInsn(INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false);
        clinit.visitFieldInsn(PUTSTATIC, LISTENERS, "NOTES", "Ljava/util/List;");
        clinit.visitInsn(RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();

        // onPreHigh: note; if (event.getOverlay() == VanillaGuiOverlay.CROSSHAIR.type()) event.setCanceled(true);
        MethodVisitor high = listener(cw, "onPreHigh", PRE, "HIGH", false);
        note(high, "high");
        Label keep = new Label();
        high.visitVarInsn(ALOAD, 0);
        high.visitMethodInsn(INVOKEVIRTUAL, PRE, "getOverlay", "()L" + NAMED + ";", false);
        high.visitFieldInsn(GETSTATIC, VANILLA, "CROSSHAIR", "L" + VANILLA + ";");
        high.visitMethodInsn(INVOKEVIRTUAL, VANILLA, "type", "()L" + NAMED + ";", false);
        high.visitJumpInsn(IF_ACMPNE, keep);
        high.visitVarInsn(ALOAD, 0);
        high.visitInsn(ICONST_1);
        high.visitMethodInsn(INVOKEVIRTUAL, PRE, "setCanceled", "(Z)V", false);
        high.visitLabel(keep);
        end(high);

        MethodVisitor normal = listener(cw, "onPre", PRE, null, false);
        note(normal, "normal");
        end(normal);

        MethodVisitor base = listener(cw, "onAny", BASE, null, false);
        note(base, "base");
        end(base);

        // onPreLow: ((ForgeGui) Minecraft.getInstance().gui).rightHeight += 10;
        MethodVisitor low = listener(cw, "onPreLow", PRE, "LOW", false);
        note(low, "low");
        low.visitMethodInsn(INVOKESTATIC, MINECRAFT, "getInstance", "()L" + MINECRAFT + ";", false);
        low.visitFieldInsn(GETFIELD, MINECRAFT, "gui", "Lnet/minecraft/client/gui/Gui;");
        low.visitTypeInsn(CHECKCAST, FORGE_GUI);
        low.visitInsn(DUP);
        low.visitFieldInsn(GETFIELD, FORGE_GUI, "rightHeight", "I");
        low.visitIntInsn(BIPUSH, 10);
        low.visitInsn(IADD);
        low.visitFieldInsn(PUTFIELD, FORGE_GUI, "rightHeight", "I");
        end(low);

        // onWatch: NOTES.add("watch " + event.isCanceled());
        MethodVisitor watch = listener(cw, "onWatch", PRE, "LOWEST", true);
        watch.visitFieldInsn(GETSTATIC, LISTENERS, "NOTES", "Ljava/util/List;");
        Label yes = new Label();
        Label add = new Label();
        watch.visitVarInsn(ALOAD, 0);
        watch.visitMethodInsn(INVOKEVIRTUAL, PRE, "isCanceled", "()Z", false);
        watch.visitJumpInsn(IFNE, yes);
        watch.visitLdcInsn("watch false");
        watch.visitJumpInsn(GOTO, add);
        watch.visitLabel(yes);
        watch.visitLdcInsn("watch true");
        watch.visitLabel(add);
        watch.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true);
        watch.visitInsn(POP);
        end(watch);

        MethodVisitor post = listener(cw, "onPost", OVERLAY_POST, null, false);
        note(post, "post");
        end(post);

        MethodVisitor guiPost = listener(cw, "onGuiPost", GUI_POST, null, false);
        note(guiPost, "gui post");
        end(guiPost);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static MethodVisitor listener(ClassWriter cw, String name, String event, String priority, boolean receiveCanceled) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name, "(L" + event + ";)V", null, null);
        AnnotationVisitor subscribe = mv.visitAnnotation(OLD_SUBSCRIBE, true);
        if (priority != null) subscribe.visitEnum("priority", "Lnet/minecraftforge/eventbus/api/EventPriority;", priority);
        if (receiveCanceled) subscribe.visit("receiveCanceled", true);
        subscribe.visitEnd();
        mv.visitCode();
        return mv;
    }

    private static void note(MethodVisitor mv, String text) {
        mv.visitFieldInsn(GETSTATIC, LISTENERS, "NOTES", "Ljava/util/List;");
        mv.visitLdcInsn(text);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true);
        mv.visitInsn(POP);
    }

    private static void end(MethodVisitor mv) {
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static byte[] remap(byte[] bytes, Remapper remapper) {
        ClassWriter writer = new ClassWriter(0);
        new ClassReader(bytes).accept(new ClassRemapper(writer, remapper), 0);
        return writer.toByteArray();
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
}
