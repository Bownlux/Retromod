/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi.fabric.v26.screen;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.loaderapi.CliHarness;
import com.retromod.loaderapi.LoaderApiFixtures;
import com.retromod.loaderapi.LoaderApiTestSupport;
import com.retromod.loaderapi.fabric.v26.net.FakeHost;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Old mods built against Fabric API 0.92 to 0.128, translated through the CLI path and run on the fake 26.x host. */
class TranslatedScreenModOnFakeHostTest {

    private static final Path OLD_MODS = Path.of("src/test/resources/loaderapi/oldmods/s1");
    private static final String HUD_ELEMENT = "retromod:" + LegacyHud.ELEMENT_PATH;

    private AutoCloseable runtime;
    private String savedHost;

    @TempDir
    Path dir;

    @AfterEach
    void tearDown() throws Exception {
        if (runtime != null) runtime.close();
        RetromodTransformer.getInstance().clearRedirectsForTesting();
        if (savedHost != null) RetromodVersion.TARGET_MC_VERSION = savedHost;
    }

    @ParameterizedTest(name = "translated for {0}")
    @ValueSource(strings = {"26.1", "26.3"})
    @DisplayName("HUD callbacks of the 1.20 and 1.21 shapes draw as one host element after the vanilla ones")
    void hudCallbacksDrawAsOneElement(String host) throws Throwable {
        Map<String, byte[]> classes = new HashMap<>(translate("s1old", "1.20.1", compile("stubs-1.20.1", "mods-1.20.1",
                "fixture/s1old/"), host));
        classes.putAll(translate("s1new", "1.21.1", compile("stubs-1.21", "mods-1.21", "fixture/s1new/"), host));
        try (FakeHost fake = FakeHost.start()) {
            ClassLoader mods = fake.defineMod(classes);
            run(mods, "fixture.s1old.OldScreens", "registerHud");
            run(mods, "fixture.s1new.NewScreens", "register");

            assertEquals(List.of("last " + HUD_ELEMENT), log(fake), "one element serves every old callback");
            assertEquals(HUD_ELEMENT, last(order(fake)), "old Fabric API drew these after the vanilla HUD");
            events(fake).clear();
            fake.type("net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry")
                    .getMethod("renderForTesting", graphics(fake).getClass(), fake.type("net.minecraft.client.DeltaTracker"))
                    .invoke(null, graphics(fake), tracker(fake, 0.25f));
            assertEquals(List.of("old hud lambda frame 0.25", "old hud class 0.25", "new hud lambda frame tracker 0.25"),
                    withoutVanilla(events(fake)), "each callback gets its own shape of the frame");

            // A mod that fires the old 1.20 invoker itself reaches the 1.21 callback too.
            events(fake).clear();
            run(mods, "fixture.s1old.OldScreens", "fireHud", graphics(fake), 0.5f);
            assertEquals(List.of("old hud lambda frame 0.5", "old hud class 0.5", "new hud lambda frame legacy partial tick"),
                    events(fake));
        }
    }

    @ParameterizedTest(name = "translated for {0}")
    @ValueSource(strings = {"26.1", "26.3"})
    @DisplayName("Old before and after screen render callbacks reach the host's extract events")
    void screenRenderEventsReachTheHost(String host) throws Throwable {
        Map<String, byte[]> classes = translate("s1old", "1.20.1", compile("stubs-1.20.1", "mods-1.20.1",
                "fixture/s1old/"), host);
        try (FakeHost fake = FakeHost.start()) {
            ClassLoader mods = fake.defineMod(classes);
            Object screen = fake.type("net.minecraft.client.gui.screens.Screen").getConstructor(String.class)
                    .newInstance("title");
            run(mods, "fixture.s1old.OldScreens", "registerScreen", screen);

            fireScreen(fake, "beforeExtract", screen, 0.75f);
            fireScreen(fake, "afterExtract", screen, 0.75f);
            assertEquals(List.of("old before title frame 3 4 0.75", "old after class title 0.75"), events(fake),
                    "beforeRender used to go to a method that never existed");
        }
    }

    @ParameterizedTest(name = "translated for {0}")
    @ValueSource(strings = {"26.1", "26.3"})
    @DisplayName("Tooltip callbacks of every shape share the host's event, and the old invoker fires them all")
    void tooltipsShareTheHostEvent(String host) throws Throwable {
        Map<String, byte[]> classes = new HashMap<>(translate("s1old", "1.20.1", compile("stubs-1.20.1", "mods-1.20.1",
                "fixture/s1old/"), host));
        classes.putAll(translate("s1new", "1.21.1", compile("stubs-1.21", "mods-1.21", "fixture/s1new/"), host));
        try (FakeHost fake = FakeHost.start()) {
            ClassLoader mods = fake.defineMod(classes);
            run(mods, "fixture.s1old.OldScreens", "registerTooltips");
            run(mods, "fixture.s1new.NewScreens", "register");
            Object stack = fake.type("net.minecraft.world.item.ItemStack")
                    .getConstructor(fake.type("net.minecraft.world.item.Item"))
                    .newInstance(fake.type("net.minecraft.world.item.Item").getConstructor(String.class).newInstance("gem"));
            Object flag = fake.type("net.minecraft.world.item.TooltipFlag").getField("NORMAL").get(null);

            // The host fires its own event with a context; the old callbacks get theirs without one.
            Class<?> callback = fake.type("net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback");
            Class<?> context = fake.type("net.minecraft.world.item.Item$TooltipContext");
            Object invoker = invokeOn(callback.getField("EVENT").get(null), "invoker");
            callback.getMethod("getTooltip", stack.getClass(), context, fake.type("net.minecraft.world.item.TooltipFlag"),
                    List.class).invoke(invoker, stack, context.getField("EMPTY").get(null), flag, new ArrayList<>());
            List<String> everyShape = List.of("old tooltip lambda normal 0", "old tooltip class normal",
                    "v0 tooltip normal", "new tooltip empty normal");
            assertEquals(everyShape, events(fake), "1.20.5 and newer callbacks keep the real event (D2)");

            // An old mod that fires the event itself passes the world's context when there is a world.
            events(fake).clear();
            run(mods, "fixture.s1old.OldScreens", "fireTooltip", stack, flag, new ArrayList<>());
            assertEquals(everyShape, events(fake));
            Object client = fake.type("net.minecraft.client.Minecraft").getMethod("getInstance").invoke(null);
            client.getClass().getField("level").set(client,
                    fake.type("net.minecraft.client.multiplayer.ClientLevel").getConstructor().newInstance());
            events(fake).clear();
            run(mods, "fixture.s1old.OldScreens", "fireTooltip", stack, flag, new ArrayList<>());
            assertEquals("new tooltip level normal", last(events(fake)));
        }
    }

    @ParameterizedTest(name = "translated for {0}")
    @ValueSource(strings = {"26.1", "26.3"})
    @DisplayName("A library that takes the old callback types serves a mod translated apart from it")
    void libraryAndDependentShareTheTypes(String host) throws Throwable {
        Map<String, byte[]> compiled = compile("stubs-1.20.1", "mods-1.20.1", "fixture/s1lib/", "fixture/s1dep/");
        Map<String, byte[]> library = new LinkedHashMap<>();
        Map<String, byte[]> dependent = new LinkedHashMap<>();
        compiled.forEach((name, bytes) -> (name.startsWith("fixture/s1lib/") ? library : dependent).put(name, bytes));
        Map<String, byte[]> classes = new HashMap<>(translate("s1lib", "1.20.1", library, host));
        classes.putAll(translate("s1dep", "1.20.1", dependent, host));
        try (FakeHost fake = FakeHost.start()) {
            ClassLoader mods = fake.defineMod(classes);
            Class<?> hooks = Class.forName("fixture.s1lib.Hooks", true, mods);
            Class<?> shared = fake.type("com.retromod.loaderapi.fabric.v26.screen.shared.LegacyHudRenderCallback");
            assertNotNull(hooks.getMethod("onHud", shared), "the library takes the one shared HUD callback type");
            assertNotNull(hooks.getMethod("onTooltip",
                    fake.type("net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback")),
                    "the library takes the host's tooltip callback type");

            Object screen = fake.type("net.minecraft.client.gui.screens.Screen").getConstructor(String.class)
                    .newInstance("title");
            run(mods, "fixture.s1dep.Main", "register", screen);
            fake.type("net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry")
                    .getMethod("renderForTesting", graphics(fake).getClass(), fake.type("net.minecraft.client.DeltaTracker"))
                    .invoke(null, graphics(fake), tracker(fake, 0.25f));
            fireScreen(fake, "afterExtract", screen, 0.75f);
            Class<?> callback = fake.type("net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback");
            Class<?> context = fake.type("net.minecraft.world.item.Item$TooltipContext");
            callback.getMethod("getTooltip", fake.type("net.minecraft.world.item.ItemStack"), context,
                    fake.type("net.minecraft.world.item.TooltipFlag"), List.class)
                    .invoke(invokeOn(callback.getField("EVENT").get(null), "invoker"), null,
                            context.getField("EMPTY").get(null), null, new ArrayList<>());
            assertEquals(List.of("dep hud 0.25", "dep after 0.75", "dep tooltip lambda", "dep tooltip class"),
                    withoutVanilla(events(fake)));
        }
    }

    @ParameterizedTest(name = "translated for {0}")
    @ValueSource(strings = {"26.1", "26.3"})
    @DisplayName("A mod translated apart from a library draws and calls HUD callbacks typed as the library's sub-interface")
    void librarySubInterfaceServesDependent(String host) throws Throwable {
        Map<String, byte[]> compiled = compile("stubs-1.20.1", "mods-1.20.1", "fixture/s1lib/", "fixture/s1dep/");
        Map<String, byte[]> library = new LinkedHashMap<>();
        Map<String, byte[]> dependent = new LinkedHashMap<>();
        compiled.forEach((name, bytes) -> (name.startsWith("fixture/s1lib/") ? library : dependent).put(name, bytes));
        Map<String, byte[]> classes = new HashMap<>(translate("s1lib", "1.20.1", library, host));
        classes.putAll(translate("s1dep", "1.20.1", dependent, host));
        try (FakeHost fake = FakeHost.start()) {
            ClassLoader mods = fake.defineMod(classes);
            run(mods, "fixture.s1dep.Main", "registerLibHud");
            fake.type("net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry")
                    .getMethod("renderForTesting", graphics(fake).getClass(), fake.type("net.minecraft.client.DeltaTracker"))
                    .invoke(null, graphics(fake), tracker(fake, 0.25f));
            assertEquals(List.of("dep sub hud 0.25", "dep sub hud class 0.25"), withoutVanilla(events(fake)));

            // The dependent's call names the library's interface, which only the library's jar declares.
            events(fake).clear();
            run(mods, "fixture.s1dep.Main", "fireLibHud", graphics(fake), 0.5f);
            assertEquals(List.of("dep sub hud 0.5", "dep sub hud class 0.5", "lib sub hud 0.5"), events(fake));
        }
    }

    @ParameterizedTest(name = "translated for {0}")
    @ValueSource(strings = {"26.1", "26.3"})
    @DisplayName("The 1.21.4 HUD layer API adds, places and wraps layers in the host registry once the client starts")
    void hudLayersReachTheHostRegistry(String host) throws Throwable {
        Map<String, byte[]> classes = translate("s1layers", "1.21.4", compile("stubs-1.21", "mods-1.21",
                "fixture/s1layers/"), host);
        try (FakeHost fake = FakeHost.start()) {
            ClassLoader mods = fake.defineMod(classes);
            run(mods, "fixture.s1layers.Layers", "register", id(fake, "mod:own"), id(fake, "mod:before_chat"),
                    id(fake, "mod:after_bars"));
            assertEquals(List.of(), log(fake), "Fabric API fired the event while the client built its HUD");

            startClient(fake);
            List<String> order = order(fake);
            assertEquals("mod:own", last(order));
            assertEquals(order.indexOf("minecraft:chat") - 1, order.indexOf("mod:before_chat"));
            assertEquals(order.indexOf("minecraft:info_bar") + 1, order.indexOf("mod:after_bars"),
                    "a layer after the hotbar and bars comes before the experience level");
            assertTrue(log(fake).contains("replace minecraft:subtitles"));
            assertTrue(log(fake).stream().noneMatch(line -> line.startsWith("remove")),
                    "26.x draws the debug overlay outside the HUD elements, so removing it does nothing");

            events(fake).clear();
            fake.type("net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry")
                    .getMethod("renderForTesting", graphics(fake).getClass(), fake.type("net.minecraft.client.DeltaTracker"))
                    .invoke(null, graphics(fake), tracker(fake, 0.25f));
            List<String> drawn = events(fake);
            assertTrue(drawn.contains("layer class tracker 0.25"), drawn.toString());
            assertEquals(drawn.indexOf("vanilla minecraft:chat") - 1, drawn.indexOf("layer before chat"));
            assertEquals(drawn.indexOf("vanilla minecraft:subtitles") - 1, drawn.indexOf("wrapped minecraft:subtitles"),
                    "the replacement draws, then the original it wraps");
            assertEquals("layer own frame", last(drawn));
        }
    }

    @ParameterizedTest(name = "translated for {0}")
    @ValueSource(strings = {"26.1", "26.3"})
    @DisplayName("HUD ids 26.x removed land where they stood, and old debug text reaches one debug screen entry")
    void removedIdsAndDebugTextReachTheHost(String host) throws Throwable {
        Map<String, byte[]> classes = translate("s1debug", "1.21.8", compile("stubs-1.21", "mods-1.21",
                "fixture/s1debug/"), host);
        try (FakeHost fake = FakeHost.start()) {
            ClassLoader mods = fake.defineMod(classes);
            run(mods, "fixture.s1debug.Debug", "register", id(fake, "mod:before_debug"), id(fake, "mod:after_effects"),
                    id(fake, "mod:after_chat"));
            List<String> order = order(fake);
            assertEquals(order.indexOf("minecraft:scoreboard") - 1, order.indexOf("mod:before_debug"),
                    "the debug overlay sat between the demo timer and the scoreboard");
            assertEquals(order.indexOf("minecraft:mob_effects") + 1, order.indexOf("mod:after_effects"));
            assertEquals(order.indexOf("minecraft:chat") + 1, order.indexOf("mod:after_chat"),
                    "an id 26.x still has passes through");
            assertTrue(log(fake).stream().noneMatch(line -> line.startsWith("remove")));

            Object entry = fake.type("net.minecraft.client.gui.components.debug.DebugScreenEntries")
                    .getMethod("getEntry", fake.type("net.minecraft.resources.Identifier"))
                    .invoke(null, id(fake, "retromod:" + LegacyDebugText.ENTRY_PATH));
            assertNotNull(entry, "the old debug text events registered one debug screen entry");
            Map<String, Object> groups = new LinkedHashMap<>();
            Class<?> displayer = fake.type("net.minecraft.client.gui.components.debug.DebugScreenDisplayer");
            Object recording = Proxy.newProxyInstance(displayer.getClassLoader(), new Class<?>[]{displayer},
                    (proxy, method, args) -> {
                        if (method.getName().equals("addToGroup")) groups.put(String.valueOf(args[0]), args[1]);
                        return null;
                    });
            for (Method method : entry.getClass().getMethods()) {
                if (method.getName().equals("display")) method.invoke(entry, recording, null, null, null);
            }
            assertEquals(Map.of("retromod:legacy_debug_text_left", List.of("old left"),
                    "retromod:legacy_debug_text_right", List.of("old right")), groups);
        }
    }

    // ---- Old mods -----------------------------------------------------------------------------

    /**
     * Compiles one stub set and the old mod sources against it, as the mod's own build did, and
     * returns the classes under {@code packages}; the stubs stay out of the mod.
     */
    static Map<String, byte[]> compile(String stubs, String mods, String... packages) throws Exception {
        Path out = Files.createTempDirectory("retromod-s1-oldmod");
        List<String> args = new ArrayList<>(List.of("-d", out.toString(), "--release", "17", "-nowarn",
                "-Xlint:none", "-proc:none", "-cp", System.getProperty("java.class.path")));
        for (String folder : List.of(stubs, mods)) {
            try (Stream<Path> files = Files.walk(OLD_MODS.resolve(folder))) {
                files.filter(path -> path.toString().endsWith(".java")).forEach(path -> args.add(path.toString()));
            }
        }
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        if (javac.run(null, null, errors, args.toArray(new String[0])) != 0) {
            throw new IllegalStateException("The old mod did not compile:\n" + errors);
        }
        Map<String, byte[]> classes = new LinkedHashMap<>();
        try (Stream<Path> files = Files.walk(out)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".class")).toList()) {
                String name = out.relativize(file).toString().replace('\\', '/');
                for (String prefix : packages) {
                    if (name.startsWith(prefix)) classes.put(name, Files.readAllBytes(file));
                }
            }
        }
        return classes;
    }

    /** Translates one small mod built for {@code minecraft} through the CLI path. */
    private Map<String, byte[]> translate(String id, String minecraft, Map<String, byte[]> classes, String host)
            throws Exception {
        savedHost = RetromodVersion.TARGET_MC_VERSION;
        if (runtime == null) runtime = LoaderApiTestSupport.runtimeOn(LoaderApiTestSupport.fabricProvider());
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        entries.put("fabric.mod.json", ("{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"1.0\","
                + "\"depends\":{\"minecraft\":\"" + minecraft + "\",\"fabric-api\":\"*\"}}")
                .getBytes(StandardCharsets.UTF_8));
        entries.putAll(classes);
        Path input = dir.resolve(id + ".jar");
        Files.write(input, LoaderApiFixtures.jar(entries));
        Path output = dir.resolve(id + "-" + host + ".jar");
        CliHarness.transform(input, output, host);
        Map<String, byte[]> translated = new HashMap<>();
        try (JarFile file = new JarFile(output.toFile())) {
            for (var it = file.entries(); it.hasMoreElements(); ) {
                var entry = it.nextElement();
                if (!entry.getName().endsWith(".class")) continue;
                translated.put(entry.getName().substring(0, entry.getName().length() - 6),
                        file.getInputStream(entry).readAllBytes());
            }
        }
        return translated;
    }

    // ---- The fake host ----------------------------------------------------------------------

    private static Object run(ClassLoader mods, String className, String method, Object... args) throws Throwable {
        for (Method candidate : Class.forName(className, true, mods).getMethods()) {
            if (candidate.getName().equals(method) && candidate.getParameterCount() == args.length) {
                try {
                    return candidate.invoke(null, args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            }
        }
        throw new NoSuchMethodException(className + "." + method);
    }

    private static Object invokeOn(Object target, String method) throws ReflectiveOperationException {
        return target.getClass().getMethod(method).invoke(target);
    }

    private static void fireScreen(FakeHost fake, String event, Object screen, float tickDelta) throws Exception {
        Class<?> events = fake.type("net.fabricmc.fabric.api.client.screen.v1.ScreenEvents");
        Object hostEvent = events.getMethod(event, screen.getClass()).invoke(null, screen);
        Object invoker = invokeOn(hostEvent, "invoker");
        for (Method method : invoker.getClass().getMethods()) {
            if (method.getName().equals(event)) {
                method.setAccessible(true);
                method.invoke(invoker, screen, graphics(fake), 3, 4, tickDelta);
            }
        }
    }

    /** Fires {@code ClientLifecycleEvents.CLIENT_STARTED}, as the client does once it has started. */
    private static void startClient(FakeHost fake) throws Exception {
        Object client = fake.type("net.minecraft.client.Minecraft").getMethod("getInstance").invoke(null);
        Object event = fake.type("net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents")
                .getField("CLIENT_STARTED").get(null);
        Class<?> listenerType = fake.type(
                "net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents$ClientStarted");
        for (Object listener : (List<?>) event.getClass().getField("listeners").get(event)) {
            listenerType.getMethod("onClientStarted", client.getClass()).invoke(listener, client);
        }
    }

    private static Object graphics(FakeHost fake) throws Exception {
        return fake.type("net.minecraft.client.gui.GuiGraphicsExtractor").getConstructor(String.class).newInstance("frame");
    }

    private static Object tracker(FakeHost fake, float partialTick) throws Exception {
        return fake.type("net.minecraft.client.DeltaTracker").getMethod("of", float.class).invoke(null, partialTick);
    }

    private static Object id(FakeHost fake, String text) throws Exception {
        String[] parts = text.split(":", 2);
        return fake.type("net.minecraft.resources.Identifier").getMethod("fromNamespaceAndPath", String.class,
                String.class).invoke(null, parts[0], parts[1]);
    }

    @SuppressWarnings("unchecked")
    private static List<String> events(FakeHost fake) throws Exception {
        return (List<String>) fake.type("fixture.Recorder").getField("EVENTS").get(null);
    }

    @SuppressWarnings("unchecked")
    private static List<String> log(FakeHost fake) throws Exception {
        return (List<String>) fake.type("net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry")
                .getField("LOG").get(null);
    }

    @SuppressWarnings("unchecked")
    private static List<String> order(FakeHost fake) throws Exception {
        return (List<String>) fake.type("net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry")
                .getMethod("orderForTesting").invoke(null);
    }

    private static List<String> withoutVanilla(List<String> events) {
        return events.stream().filter(event -> !event.startsWith("vanilla ")).toList();
    }

    private static String last(List<String> values) {
        return values.isEmpty() ? null : values.get(values.size() - 1);
    }
}
