/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.Assumptions;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** A Forge 26.2 or 26.3 client in miniature: the real EventBus 7, IModBusEvent and HUD layer tree, stand-in Minecraft classes, and old mods' copies of the rebuilt overlay API. */
final class LegacyHudGame {

    static final String ROOT = LegacyGuiOverlayBridge.ROOT;
    static final List<String> HOSTS = List.of("26.2-65.0.0 with eventbus 7.0.1", "26.3-66.1.2 with eventbus 7.0.6");
    private static final Path LEGACY_CLASSES = Path.of("target", "legacyapi-classes");
    private static final String EVENTS = "com/retromod/legacyapi/forge/eventbus/LegacyEvents";

    // The host's own layer classes, run as they are so the tree and its methods cannot drift from Forge's.
    private static final List<String> HOST_LAYER_CLASSES = List.of("net/minecraftforge/client/gui/overlay/ForgeLayeredDraw",
            "net/minecraftforge/client/gui/overlay/ForgeLayeredDraw$LayerOffset", "net/minecraftforge/client/gui/overlay/ForgeLayer");

    /** One old mod: its id, the package its copy is relocated under ("" for none), what its RegisterGuiOverlaysEvent listener calls, and whether it subscribes its listeners. */
    record Mod(String id, String copy, String registrations, boolean subscribes) {
        Mod(String id, String copy, String registrations) {
            this(id, copy, registrations, true);
        }
    }

    private final ClassLoader loader;
    private final List<Mod> mods;

    private LegacyHudGame(ClassLoader loader, List<Mod> mods) {
        this.loader = loader;
        this.mods = mods;
    }

    /** A client with {@code mods}' translated listeners and copies, nothing constructed yet. */
    static LegacyHudGame create(String host, Path dir, RetromodTransformer transformer, List<Mod> mods) throws Exception {
        return create(host, dir, transformer, mods, Set.of());
    }

    /** The same client without the host classes named in {@code missing}, as a host that lacks them. */
    static LegacyHudGame create(String host, Path dir, RetromodTransformer transformer, List<Mod> mods, Set<String> missing)
            throws Exception {
        Path eventBus = hostJar(host, "eventbus");
        Path fmlcore = hostJar(host, "fmlcore");
        Path universal = hostJar(host, "universal");
        Assumptions.assumeTrue(eventBus != null && fmlcore != null && universal != null,
                "no EventBus 7, fmlcore or universal jar for Forge " + host);
        Map<String, byte[]> classes = new HashMap<>(jarClasses(eventBus, name -> true));
        // The real IModBusEvent, whose getBus every mod-bus post goes through, and Forge's own HUD layer stacks.
        classes.putAll(jarClasses(fmlcore, name -> name.equals("net/minecraftforge/fml/event/IModBusEvent")));
        classes.putAll(jarClasses(universal, HOST_LAYER_CLASSES::contains));

        ForgeHostStandIns hostStandIns = hostStandIns(missing).classpath(eventBus, fmlcore, universal, LEGACY_CLASSES);
        List<String> stoodIn = new ArrayList<>(hostStandIns.names());
        stoodIn.addAll(missing);
        for (String root : List.of("net", "com/mojang", "com/google")) {
            try (Stream<Path> files = Files.walk(LEGACY_CLASSES.resolve(root))) {
                for (Path file : files.filter(f -> f.toString().endsWith(".class")).toList()) {
                    String name = LEGACY_CLASSES.relativize(file).toString().replace('\\', '/');
                    name = name.substring(0, name.length() - ".class".length());
                    if (name.startsWith("net/minecraftforge/eventbus/") || stoodIn.contains(name) || classes.containsKey(name)) continue;
                    classes.put(name, Files.readAllBytes(file));
                }
            }
        }

        // Each mod's old listeners, compiled against Forge 1.20.1's shapes and translated as the mod's jar would be.
        ForgeHostStandIns oldMods = oldApi();
        for (Mod mod : mods) oldMods.add(mod.id() + "/Listeners.java", listeners(mod));
        Map<String, byte[]> compiled = oldMods.compiled(Files.createDirectories(dir.resolve("old-mods")));
        for (Mod mod : mods) {
            String name = mod.id() + "/Listeners";
            classes.put(name, relocate(transformer.transformClass(compiled.get(name), name + ".class"), mod.copy()));
            // Every copy after the first is relocated under its own package, as the embedder does.
            if (mod.copy().isEmpty()) continue;
            transformer.getSyntheticClasses().forEach((synthetic, bytes) -> {
                if (synthetic.startsWith("com/retromod/legacyapi/")) classes.put(mod.copy() + synthetic, relocate(bytes, mod.copy()));
            });
        }
        ClassLoader loader = hostStandIns.loader(Files.createDirectories(dir.resolve("host")), transformer, classes);
        LegacyHudGame game = new LegacyHudGame(loader, mods);
        // EventBus 7 posts on a started group only.
        Object group = game.type("net/minecraftforge/eventbus/api/bus/BusGroup").getField("DEFAULT").get(null);
        group.getClass().getMethod("startup").invoke(group);
        return game;
    }

    // ---- the game's phases ----

    /** Each mod's {@code @Mod} constructor in mod order: the copy lists itself first, then the mod adds its listeners. */
    @SuppressWarnings("unchecked")
    LegacyHudGame construct() throws Exception {
        for (Mod mod : mods) {
            Object container = type("net/minecraftforge/fml/ModContainer").getConstructor(String.class).newInstance(mod.id());
            ((List<Object>) staticField("net/minecraftforge/fml/ModList", "MODS")).add(container);
            type("net/minecraftforge/fml/ModLoadingContext").getField("active").set(null, container);
            call(mod.copy() + ROOT + "LegacyOverlayHub", "attach");
            if (!mod.subscribes()) continue;

            Class<?> listeners = type(mod.id() + "/Listeners");
            Object game = type("net/minecraftforge/eventbus/api/bus/BusGroup").getField("DEFAULT").get(null);
            for (Method method : type(mod.copy() + EVENTS).getMethods()) {
                if (method.getName().equals("subscribe")) method.invoke(null, game, listeners);
            }
            // modBus.addListener(Listeners::register)
            Class<?> register = type(mod.copy() + ROOT + "event/RegisterGuiOverlaysEvent");
            Object modGroup = container.getClass().getMethod("getModBusGroup").invoke(container);
            Object bus = type("net/minecraftforge/fml/event/IModBusEvent").getMethod("getBus",
                    type("net/minecraftforge/eventbus/api/bus/BusGroup"), Class.class).invoke(null, modGroup, register);
            Method handler = listeners.getMethod("register", register);
            Consumer<Object> listener = event -> {
                try {
                    handler.invoke(null, event);
                } catch (InvocationTargetException failure) {
                    throw failure.getCause() instanceof RuntimeException runtime ? runtime : new IllegalStateException(failure.getCause());
                } catch (IllegalAccessException failure) {
                    throw new IllegalStateException(failure);
                }
            };
            type("net/minecraftforge/eventbus/api/bus/EventBus").getMethod("addListener", Consumer.class).invoke(bus, listener);
        }
        return this;
    }

    /** {@code new Hud(minecraft)}: Forge's own init builds the vanilla tree and posts AddGuiOverlayLayersEvent; only then is Minecraft.gui set. */
    LegacyHudGame buildHud() throws Exception {
        Object minecraft = minecraft();
        Object hud = type("net/minecraft/client/gui/Hud").getConstructor().newInstance();
        call("net/minecraftforge/client/gui/overlay/ForgeLayeredDraw", "init", hud, minecraft);
        Object gui = type("net/minecraft/client/gui/Gui").getConstructor(hud.getClass()).newInstance(hud);
        minecraft.getClass().getField("gui").set(minecraft, gui);
        return this;
    }

    /** {@code ForgeHooksClient.initClientHooks}: RegisterKeyMappingsEvent, after which Forge 1.20.1 registered overlays. */
    LegacyHudGame initClientHooks() throws Exception {
        Class<?> keys = type("net/minecraftforge/client/event/RegisterKeyMappingsEvent");
        Object bus = keys.getField("BUS").get(null);
        type("net/minecraftforge/eventbus/api/bus/EventBus").getMethod("post", type("net/minecraftforge/eventbus/internal/Event"))
                .invoke(bus, keys.getConstructor().newInstance());
        return this;
    }

    /** A started game: every mod constructed, the HUD built and the overlays registered. */
    LegacyHudGame started() throws Exception {
        return construct().buildHud().initClientHooks();
    }

    /** One HUD frame, as {@code Hud.extractRenderState} draws it, and the log lines it wrote. */
    List<String> frame() throws Exception {
        log().clear();
        Object minecraft = minecraft();
        Object delta = minecraft.getClass().getMethod("getDeltaTracker").invoke(minecraft);
        Object graphics = type("net/minecraft/client/gui/GuiGraphicsExtractor").getConstructor().newInstance();
        Class<?> layered = type("net/minecraftforge/client/gui/overlay/ForgeLayeredDraw");
        layered.getMethod("extractRenderState", graphics.getClass(), type("net/minecraft/client/DeltaTracker")).invoke(null, graphics, delta);
        return List.copyOf(log());
    }

    // ---- what tests set and read ----

    @SuppressWarnings("unchecked")
    List<String> log() throws Exception {
        return (List<String>) staticField("probe/Log", "LINES");
    }

    /** Turns on a switch the old listeners and the stand-in layers read, such as "moda cancel minecraft:crosshair". */
    @SuppressWarnings("unchecked")
    LegacyHudGame flag(String flag) throws Exception {
        ((Set<String>) staticField("probe/Log", "FLAGS")).add(flag);
        return this;
    }

    @SuppressWarnings("unchecked")
    LegacyHudGame unflag(String flag) throws Exception {
        ((Set<String>) staticField("probe/Log", "FLAGS")).remove(flag);
        return this;
    }

    /** Sets a field of the client's player, such as health, absorption, underwater or vehicle. */
    LegacyHudGame player(String field, Object value) throws Exception {
        Object player = minecraft().getClass().getField("player").get(minecraft());
        field(player.getClass(), field).set(player, value);
        return this;
    }

    /** A living entity to ride, with {@code maxHealth}. */
    Object mount(float maxHealth) throws Exception {
        Object mount = type("net/minecraft/world/entity/LivingEntity").getConstructor().newInstance();
        field(mount.getClass(), "maxHealth").set(mount, maxHealth);
        return mount;
    }

    /** The host layers Forge logged as replaced, in replace order. */
    List<String> replaced() throws Exception {
        return hostLog().stream().filter(line -> line.startsWith("replaced ")).toList();
    }

    /** What Forge's layer stacks warned about, such as a layer or stack that was not present. */
    List<String> hostWarnings() throws Exception {
        return hostLog().stream().filter(line -> line.startsWith("warn ")).toList();
    }

    /** How often anything read the player's health. */
    int healthReads() throws Exception {
        return (Integer) staticField("probe/Log", "HEALTH_READS");
    }

    @SuppressWarnings("unchecked")
    private List<String> hostLog() throws Exception {
        return (List<String>) staticField("probe/HostLog", "LINES");
    }

    Class<?> type(String internalName) throws ClassNotFoundException {
        return Class.forName(internalName.replace('/', '.'), true, loader);
    }

    Object call(String owner, String name, Object... arguments) throws Exception {
        for (Method method : type(owner).getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == arguments.length) {
                try {
                    return method.invoke(null, arguments);
                } catch (InvocationTargetException failure) {
                    throw new AssertionError(owner + "." + name + " threw", failure.getCause());
                }
            }
        }
        throw new AssertionError(owner + " has no " + name);
    }

    private Object minecraft() throws Exception {
        return type("net/minecraft/client/Minecraft").getMethod("getInstance").invoke(null);
    }

    private Object staticField(String owner, String name) throws Exception {
        return type(owner).getField(name).get(null);
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getField(name);
            } catch (NoSuchFieldException notHere) {
                // Declared further up.
            }
        }
        throw new NoSuchFieldException(name);
    }

    // ---- the old mods ----

    /** A mod's old listeners: one of each HUD event, every one noting what it saw and acting on the switches. */
    private static String listeners(Mod mod) {
        return """
                package MODID;

                import net.minecraft.client.Minecraft;
                import net.minecraft.resources.Identifier;
                import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
                import net.minecraftforge.client.event.RenderGuiEvent;
                import net.minecraftforge.client.event.RenderGuiOverlayEvent;
                import net.minecraftforge.client.gui.overlay.ForgeGui;
                import net.minecraftforge.client.gui.overlay.IGuiOverlay;
                import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
                import net.minecraftforge.eventbus.api.EventPriority;
                import net.minecraftforge.eventbus.api.SubscribeEvent;
                import probe.Log;

                public class Listeners {
                    public static void register(RegisterGuiOverlaysEvent event) {
                        REGISTRATIONS
                    }

                    static IGuiOverlay draws(String id) {
                        return (gui, graphics, partialTick, width, height) -> {
                            Log.add("draw " + id + " " + gui.leftHeight + "/" + gui.rightHeight);
                            if (Log.on("throw " + id)) throw new NoSuchMethodError("Gone.member()V");
                            if (Log.on("raise " + id)) gui.rightHeight += 10;
                        };
                    }

                    @SubscribeEvent(priority = EventPriority.HIGH)
                    public static void onPre(RenderGuiOverlayEvent.Pre event) {
                        Identifier id = event.getOverlay().id();
                        note("pre", id);
                        if (Log.on("MODID throw " + id)) throw new IllegalStateException("a broken listener");
                        if (Log.on("MODID cancel " + id)) event.setCanceled(true);
                        if (event.getOverlay() == VanillaGuiOverlay.CROSSHAIR.type()) Log.add("MODID crosshair is the vanilla type");
                    }

                    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
                    public static void onPreWatch(RenderGuiOverlayEvent.Pre event) {
                        if (!event.isCanceled()) return;
                        Identifier id = event.getOverlay().id();
                        Log.add("MODID sees cancelled " + id);
                        if (Log.on("MODID throw watching " + id)) throw new IllegalStateException("a broken watcher");
                        if (Log.on("MODID uncancel " + id)) event.setCanceled(false);
                    }

                    @SubscribeEvent
                    public static void onPost(RenderGuiOverlayEvent.Post event) {
                        note("post", event.getOverlay().id());
                    }

                    @SubscribeEvent
                    public static void onGuiPre(RenderGuiEvent.Pre event) {
                        Log.add("MODID gui pre");
                        if (Log.on("MODID cancel gui")) event.setCanceled(true);
                    }

                    @SubscribeEvent
                    public static void onGuiPost(RenderGuiEvent.Post event) {
                        Log.add("MODID gui post");
                    }

                    private static void note(String kind, Identifier id) {
                        ForgeGui gui = (ForgeGui) Minecraft.getInstance().gui;
                        Log.add("MODID " + kind + " " + id + " " + gui.leftHeight + "/" + gui.rightHeight);
                    }
                }
                """.replace("MODID", mod.id()).replace("REGISTRATIONS", mod.registrations());
    }

    /** Forge 1.20.1's overlay API as old mods compiled against it, with Minecraft 26.x's vanilla names. */
    private static ForgeHostStandIns oldApi() {
        return new ForgeHostStandIns()
                .add("probe/Log.java", LOG)
                .add("net/minecraftforge/eventbus/api/Event.java", """
                        package net.minecraftforge.eventbus.api;
                        public class Event {
                            public boolean isCanceled() { return false; }
                            public void setCanceled(boolean cancel) {}
                        }""")
                .add("net/minecraftforge/eventbus/api/EventPriority.java",
                        "package net.minecraftforge.eventbus.api; public enum EventPriority { HIGHEST, HIGH, NORMAL, LOW, LOWEST }")
                .add("net/minecraftforge/eventbus/api/SubscribeEvent.java", """
                        package net.minecraftforge.eventbus.api;
                        @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                        public @interface SubscribeEvent {
                            EventPriority priority() default EventPriority.NORMAL;
                            boolean receiveCanceled() default false;
                        }""")
                .add("net/minecraftforge/client/event/RenderGuiOverlayEvent.java", """
                        package net.minecraftforge.client.event;
                        public abstract class RenderGuiOverlayEvent extends net.minecraftforge.eventbus.api.Event {
                            public net.minecraftforge.client.gui.overlay.NamedGuiOverlay getOverlay() { return null; }
                            public static class Pre extends RenderGuiOverlayEvent {}
                            public static class Post extends RenderGuiOverlayEvent {}
                        }""")
                .add("net/minecraftforge/client/event/RenderGuiEvent.java", """
                        package net.minecraftforge.client.event;
                        public abstract class RenderGuiEvent extends net.minecraftforge.eventbus.api.Event {
                            public static class Pre extends RenderGuiEvent {}
                            public static class Post extends RenderGuiEvent {}
                        }""")
                .add("net/minecraftforge/client/event/RegisterGuiOverlaysEvent.java", """
                        package net.minecraftforge.client.event;
                        import net.minecraft.resources.Identifier;
                        import net.minecraftforge.client.gui.overlay.IGuiOverlay;
                        public class RegisterGuiOverlaysEvent {
                            public void registerBelowAll(String id, IGuiOverlay overlay) {}
                            public void registerBelow(Identifier other, String id, IGuiOverlay overlay) {}
                            public void registerAbove(Identifier other, String id, IGuiOverlay overlay) {}
                            public void registerAboveAll(String id, IGuiOverlay overlay) {}
                        }""")
                .add("net/minecraftforge/client/gui/overlay/IGuiOverlay.java", """
                        package net.minecraftforge.client.gui.overlay;
                        public interface IGuiOverlay {
                            void render(ForgeGui gui, net.minecraft.client.gui.GuiGraphicsExtractor graphics, float partialTick,
                                    int width, int height);
                        }""")
                .add("net/minecraftforge/client/gui/overlay/NamedGuiOverlay.java", """
                        package net.minecraftforge.client.gui.overlay;
                        public class NamedGuiOverlay { public net.minecraft.resources.Identifier id() { return null; } }""")
                .add("net/minecraftforge/client/gui/overlay/ForgeGui.java", """
                        package net.minecraftforge.client.gui.overlay;
                        public class ForgeGui extends net.minecraft.client.gui.Gui { public int leftHeight; public int rightHeight; }""")
                .add("net/minecraftforge/client/gui/overlay/VanillaGuiOverlay.java", """
                        package net.minecraftforge.client.gui.overlay;
                        public enum VanillaGuiOverlay {
                            VIGNETTE, SPYGLASS, HELMET, FROSTBITE, PORTAL, HOTBAR, CROSSHAIR, BOSS_EVENT_PROGRESS, PLAYER_HEALTH,
                            ARMOR_LEVEL, FOOD_LEVEL, AIR_LEVEL, MOUNT_HEALTH, JUMP_BAR, EXPERIENCE_BAR, ITEM_NAME, SLEEP_FADE,
                            POTION_ICONS, DEBUG_TEXT, FPS_GRAPH, RECORD_OVERLAY, TITLE_TEXT, SUBTITLES, SCOREBOARD, CHAT_PANEL,
                            PLAYER_LIST;
                            public net.minecraft.resources.Identifier id() { return null; }
                            public NamedGuiOverlay type() { return null; }
                        }""")
                .add("net/minecraft/resources/Identifier.java", """
                        package net.minecraft.resources;
                        public class Identifier {
                            public static Identifier fromNamespaceAndPath(String namespace, String path) { return null; }
                        }""")
                .add("net/minecraft/client/gui/Gui.java", "package net.minecraft.client.gui; public class Gui {}")
                .add("net/minecraft/client/gui/GuiGraphicsExtractor.java", "package net.minecraft.client.gui; public class GuiGraphicsExtractor {}")
                .add("net/minecraft/client/Minecraft.java", """
                        package net.minecraft.client;
                        public class Minecraft {
                            public net.minecraft.client.gui.Gui gui;
                            public static Minecraft getInstance() { return null; }
                        }""");
    }

    private static final String LOG = """
            package probe;
            public final class Log {
                public static final java.util.List<String> LINES = new java.util.ArrayList<>();
                public static final java.util.Set<String> FLAGS = new java.util.HashSet<>();
                public static int HEALTH_READS;
                public static void add(String line) { LINES.add(line); }
                public static boolean on(String flag) { return FLAGS.contains(flag); }
            }""";

    // ---- the host ----

    /** Stand-ins for the Minecraft and Forge classes the coordinator and Forge's layer stacks touch, less {@code missing}. */
    private static ForgeHostStandIns hostStandIns(Set<String> missing) {
        ForgeHostStandIns standIns = new ForgeHostStandIns();
        HOST_SOURCES.forEach((name, source) -> {
            if (!missing.contains(name)) standIns.add(name + ".java", source);
        });
        return standIns;
    }

    // Each stand-in notes what it draws in the log, under the name of the host layer that calls it.
    private static final Map<String, String> HOST_SOURCES = Map.ofEntries(
            Map.entry("probe/Log", LOG),
            Map.entry("probe/HostLog", """
                    package probe;
                    public final class HostLog implements org.slf4j.Logger {
                        public static final java.util.List<String> LINES = new java.util.ArrayList<>();
                        public void debug(String format, Object... arguments) {
                            if (format.contains("was replaced by")) LINES.add("replaced " + arguments[1] + " " + arguments[0]);
                        }
                        public void warn(String format, Object argument) { LINES.add("warn " + format + " " + argument); }
                        public void warn(String format, Object first, Object second) {
                            LINES.add("warn " + format + " " + first + " " + second);
                        }
                    }"""),
            Map.entry("org/slf4j/Logger", """
                    package org.slf4j;
                    public interface Logger {
                        void debug(String format, Object... arguments);
                        void warn(String format, Object argument);
                        void warn(String format, Object first, Object second);
                    }"""),
            Map.entry("com/mojang/logging/LogUtils", """
                    package com.mojang.logging;
                    public final class LogUtils {
                        private static final org.slf4j.Logger LOGGER = new probe.HostLog();
                        public static org.slf4j.Logger getLogger() { return LOGGER; }
                    }"""),
            Map.entry("net/minecraft/resources/Identifier", """
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
                    }"""),
            Map.entry("com/mojang/blaze3d/platform/Window", "package com.mojang.blaze3d.platform; public final class Window {}"),
            Map.entry("net/minecraft/client/gui/GuiGraphicsExtractor", """
                    package net.minecraft.client.gui;
                    public class GuiGraphicsExtractor {
                        public int guiWidth() { return 320; }
                        public int guiHeight() { return 240; }
                        public void nextStratum() { probe.Log.add("draw stratum_change"); }
                    }"""),
            Map.entry("net/minecraft/client/DeltaTracker", """
                    package net.minecraft.client;
                    public interface DeltaTracker { float getGameTimeDeltaPartialTick(boolean runsNormally); }"""),
            Map.entry("net/minecraft/client/gui/Font", "package net.minecraft.client.gui; public class Font {}"),
            Map.entry("net/minecraft/client/gui/components/DebugScreenOverlay",
                    "package net.minecraft.client.gui.components; public class DebugScreenOverlay {}"),
            Map.entry("net/minecraft/client/gui/components/spectator/SpectatorGui", """
                    package net.minecraft.client.gui.components.spectator;
                    public class SpectatorGui {
                        public void extractHotbar(net.minecraft.client.gui.GuiGraphicsExtractor graphics) { probe.Log.add("draw spectator_hotbar"); }
                        public void extractAction(net.minecraft.client.gui.GuiGraphicsExtractor graphics) { probe.Log.add("draw spectator_action"); }
                    }"""),
            Map.entry("net/minecraft/client/gui/contextualbar/ContextualBar", """
                    package net.minecraft.client.gui.contextualbar;
                    public interface ContextualBar {
                        static void extractExperienceLevel(net.minecraft.client.gui.GuiGraphicsExtractor graphics, net.minecraft.client.gui.Font font, int level) {
                            probe.Log.add("draw experience_level");
                        }
                    }"""),
            Map.entry("net/minecraft/client/gui/screens/Screen", """
                    package net.minecraft.client.gui.screens;
                    public class Screen { public boolean isInGameUi() { return true; } }"""),
            Map.entry("net/minecraft/client/gui/Hud", """
                    package net.minecraft.client.gui;
                    import net.minecraft.client.DeltaTracker;
                    import probe.Log;
                    public class Hud {
                        private final net.minecraft.client.gui.components.spectator.SpectatorGui spectator = new net.minecraft.client.gui.components.spectator.SpectatorGui();
                        public boolean isHidden() { return Log.on("hidden"); }
                        public net.minecraft.client.gui.components.DebugScreenOverlay getDebugOverlay() { return null; }
                        public net.minecraft.client.gui.components.spectator.SpectatorGui getSpectatorGui() { return spectator; }
                        public void extractCameraOverlays(GuiGraphicsExtractor graphics, DeltaTracker delta) { Log.add("draw camera_overlay"); }
                        public void extractCrosshair(GuiGraphicsExtractor graphics, DeltaTracker delta) { Log.add("draw crosshair"); }
                        public void extractItemHotbar(GuiGraphicsExtractor graphics, DeltaTracker delta) { Log.add("draw item_hotbar"); }
                        public void extractPlayerHealth(GuiGraphicsExtractor graphics) { Log.add("draw health_bar"); }
                        public void extractVehicleHealth(GuiGraphicsExtractor graphics) { Log.add("draw vehicle_health"); }
                        public void updateContextualInfo(GuiGraphicsExtractor graphics, DeltaTracker delta) { Log.add("draw background"); }
                        public void extractContextualInfoState(GuiGraphicsExtractor graphics, DeltaTracker delta) { Log.add("draw contextual_info"); }
                        public void extractSelectedItemName(GuiGraphicsExtractor graphics) { Log.add("draw selected_item_name"); }
                        public void extractEffects(GuiGraphicsExtractor graphics, DeltaTracker delta) { Log.add("draw potion_effects"); }
                        public void extractBossOverlay(GuiGraphicsExtractor graphics, DeltaTracker delta) { Log.add("draw boss_overlay"); }
                        public void extractSleepOverlay(GuiGraphicsExtractor graphics, DeltaTracker delta) { Log.add("draw sleep_overlay"); }
                        public void extractDemoOverlay(GuiGraphicsExtractor graphics, DeltaTracker delta) { Log.add("draw demo"); }
                        public void extractScoreboardSidebar(GuiGraphicsExtractor graphics, DeltaTracker delta) { Log.add("draw scoreboard"); }
                        public void extractOverlayMessage(GuiGraphicsExtractor graphics, DeltaTracker delta) { Log.add("draw hotbar_message"); }
                        public void extractTitle(GuiGraphicsExtractor graphics, DeltaTracker delta) { Log.add("draw title"); }
                        public void extractChat(GuiGraphicsExtractor graphics, DeltaTracker delta) { Log.add("draw chat_overlay"); }
                        public void extractTabList(GuiGraphicsExtractor graphics, DeltaTracker delta) { Log.add("draw tab_list"); }
                        public void extractSubtitleOverlay(GuiGraphicsExtractor graphics, boolean inGameUi) { Log.add("draw subtitle"); }
                    }"""),
            Map.entry("net/minecraft/client/gui/Gui", """
                    package net.minecraft.client.gui;
                    public class Gui {
                        public final Hud hud;
                        public Gui(Hud hud) { this.hud = hud; }
                        public net.minecraft.client.gui.screens.Screen screen() { return null; }
                    }"""),
            Map.entry("net/minecraft/client/multiplayer/MultiPlayerGameMode", """
                    package net.minecraft.client.multiplayer;
                    public class MultiPlayerGameMode {
                        public boolean canHurtPlayer() { return !probe.Log.on("creative"); }
                        public boolean hasExperience() { return !probe.Log.on("creative"); }
                        public boolean isSpectator() { return probe.Log.on("spectator"); }
                    }"""),
            Map.entry("net/minecraft/world/entity/Entity", """
                    package net.minecraft.world.entity;
                    public class Entity {
                        public Entity vehicle;
                        public boolean underwater;
                        public int air = 300;
                        public Entity getVehicle() { return vehicle; }
                        public boolean showVehicleHealth() { return this instanceof LivingEntity; }
                        public boolean isUnderWater() { return underwater; }
                        public int getAirSupply() { return air; }
                        public int getMaxAirSupply() { return 300; }
                    }"""),
            Map.entry("net/minecraft/world/entity/LivingEntity", """
                    package net.minecraft.world.entity;
                    public class LivingEntity extends Entity {
                        public float health = 20;
                        public float maxHealth = 20;
                        public float absorption;
                        public float getHealth() { probe.Log.HEALTH_READS++; return health; }
                        public final float getMaxHealth() { return maxHealth; }
                        public float getAbsorptionAmount() { return absorption; }
                    }"""),
            Map.entry("net/minecraft/world/entity/PlayerRideableJumping",
                    "package net.minecraft.world.entity; public interface PlayerRideableJumping {}"),
            Map.entry("net/minecraft/world/entity/player/Player",
                    "package net.minecraft.world.entity.player; public class Player extends net.minecraft.world.entity.LivingEntity {}"),
            Map.entry("net/minecraft/client/player/LocalPlayer", """
                    package net.minecraft.client.player;
                    public class LocalPlayer extends net.minecraft.world.entity.player.Player {
                        public int experienceLevel = 5;
                        public net.minecraft.world.entity.PlayerRideableJumping jumping;
                        public net.minecraft.world.entity.PlayerRideableJumping jumpableVehicle() { return jumping; }
                    }"""),
            // Minecraft.gui is set only after the Hud is built, as in Minecraft's constructor.
            Map.entry("net/minecraft/client/Minecraft", """
                    package net.minecraft.client;
                    public class Minecraft {
                        private static final Minecraft INSTANCE = new Minecraft();
                        private static final com.mojang.blaze3d.platform.Window WINDOW = new com.mojang.blaze3d.platform.Window();
                        public net.minecraft.client.gui.Gui gui;
                        public final net.minecraft.client.gui.Font font = new net.minecraft.client.gui.Font();
                        public final net.minecraft.client.multiplayer.MultiPlayerGameMode gameMode = new net.minecraft.client.multiplayer.MultiPlayerGameMode();
                        public final net.minecraft.client.player.LocalPlayer player = new net.minecraft.client.player.LocalPlayer();
                        public static Minecraft getInstance() { return INSTANCE; }
                        public net.minecraft.world.entity.Entity getCameraEntity() { return player; }
                        public com.mojang.blaze3d.platform.Window getWindow() { return WINDOW; }
                        public DeltaTracker getDeltaTracker() { return running -> 0.25f; }
                    }"""),
            Map.entry("net/minecraftforge/api/distmarker/Dist",
                    "package net.minecraftforge.api.distmarker; public enum Dist { CLIENT, DEDICATED_SERVER }"),
            Map.entry("net/minecraftforge/fml/loading/FMLEnvironment", """
                    package net.minecraftforge.fml.loading;
                    public class FMLEnvironment { public static final net.minecraftforge.api.distmarker.Dist dist = net.minecraftforge.api.distmarker.Dist.CLIENT; }"""),
            Map.entry("net/minecraftforge/fml/ModLoader", """
                    package net.minecraftforge.fml;
                    public final class ModLoader {
                        public static boolean isLoadingStateValid() { return true; }
                        public static boolean hasCompletedState(IModLoadingState state) { return true; }
                    }"""),
            Map.entry("net/minecraftforge/fml/ModContainer", """
                    package net.minecraftforge.fml;
                    import net.minecraftforge.eventbus.api.bus.BusGroup;
                    public class ModContainer {
                        private final String id;
                        private final BusGroup group;
                        public ModContainer(String id) {
                            this.id = id;
                            this.group = BusGroup.create(id, net.minecraftforge.fml.event.IModBusEvent.class);
                            group.startup();
                        }
                        public String getModId() { return id; }
                        public String getNamespace() { return id; }
                        public BusGroup getModBusGroup() { return group; }
                    }"""),
            Map.entry("net/minecraftforge/fml/ModList", """
                    package net.minecraftforge.fml;
                    public final class ModList {
                        public static final java.util.List<ModContainer> MODS = new java.util.ArrayList<>();
                        public static void forEachModInOrder(java.util.function.Consumer<ModContainer> action) { MODS.forEach(action); }
                        public static java.util.List<ModContainer> getLoadedMods() { return MODS; }
                    }"""),
            Map.entry("net/minecraftforge/fml/ModLoadingContext", """
                    package net.minecraftforge.fml;
                    public class ModLoadingContext {
                        public static ModContainer active;
                        private static final ModLoadingContext CONTEXT = new ModLoadingContext();
                        public static ModLoadingContext get() { return CONTEXT; }
                        public ModContainer getContainer() { return active; }
                        public String getActiveNamespace() { return active.getNamespace(); }
                    }"""),
            Map.entry("net/minecraftforge/client/event/AddGuiOverlayLayersEvent", """
                    package net.minecraftforge.client.event;
                    import net.minecraftforge.eventbus.api.bus.EventBus;
                    public record AddGuiOverlayLayersEvent(net.minecraftforge.client.gui.overlay.ForgeLayeredDraw getLayeredDraw)
                            implements net.minecraftforge.eventbus.api.event.RecordEvent {
                        public static final EventBus<AddGuiOverlayLayersEvent> BUS = EventBus.create(AddGuiOverlayLayersEvent.class);
                    }"""),
            // Forge's resolveLayers posts the layer event through this hook.
            Map.entry("net/minecraftforge/client/event/ForgeEventFactoryClient", """
                    package net.minecraftforge.client.event;
                    public final class ForgeEventFactoryClient {
                        public static void onComputeLayerOrder(net.minecraftforge.client.gui.overlay.ForgeLayeredDraw draw) {
                            AddGuiOverlayLayersEvent.BUS.post(new AddGuiOverlayLayersEvent(draw));
                        }
                    }"""),
            Map.entry("net/minecraftforge/client/event/RegisterKeyMappingsEvent", """
                    package net.minecraftforge.client.event;
                    import net.minecraftforge.eventbus.api.bus.EventBus;
                    public final class RegisterKeyMappingsEvent extends net.minecraftforge.eventbus.api.event.MutableEvent {
                        public static final EventBus<RegisterKeyMappingsEvent> BUS = EventBus.create(RegisterKeyMappingsEvent.class);
                    }"""));

    // ---- jars ----

    private static Map<String, byte[]> jarClasses(Path jar, java.util.function.Predicate<String> wanted) throws Exception {
        Map<String, byte[]> classes = new HashMap<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (ZipEntry entry : java.util.Collections.list(zip.entries())) {
                String name = entry.getName();
                if (!name.endsWith(".class") || name.endsWith("module-info.class")) continue;
                name = name.substring(0, name.length() - ".class".length());
                if (wanted.test(name)) classes.put(name, zip.getInputStream(entry).readAllBytes());
            }
        }
        return classes;
    }

    /** The host's EventBus 7, fmlcore or Forge universal jar, from Prism's libraries or the local Forge 26.3 copies. */
    static Path hostJar(String host, String artifact) {
        boolean forge262 = host.startsWith("26.2");
        String home = System.getProperty("user.home");
        String prism = home + "/Library/Application Support/PrismLauncher/libraries/net/minecraftforge/";
        Path local263 = Path.of(home, ".claude/jobs/3da2a019/tmp/forge263");
        List<Path> candidates = new ArrayList<>();
        if (artifact.equals("eventbus")) {
            String version = forge262 ? "7.0.1" : "7.0.6";
            candidates.add(Path.of(prism + "eventbus/" + version + "/eventbus-" + version + ".jar"));
            candidates.add(local263.resolve("eventbus-" + version + ".jar"));
        } else if (artifact.equals("universal")) {
            String version = forge262 ? "26.2-65.0.0" : "26.3-66.1.2";
            candidates.add(Path.of(prism + "forge/" + version + "/forge-" + version + "-universal.jar"));
            candidates.add(local263.resolve("forge-" + version + "-universal.jar"));
        } else {
            String version = forge262 ? "26.2-65.0.0" : "26.3-66.1.2";
            candidates.add(Path.of(prism + "fmlcore/" + version + "/fmlcore-" + version + ".jar"));
            candidates.add(local263.resolve("fmlcore-" + version + ".jar"));
        }
        return candidates.stream().filter(Files::isRegularFile).findFirst().orElse(null);
    }

    private static byte[] relocate(byte[] bytes, String copy) {
        if (copy.isEmpty()) return bytes;
        Remapper relocation = new Remapper() {
            @Override
            public String map(String internalName) {
                return internalName.startsWith("com/retromod/legacyapi/") ? copy + internalName : internalName;
            }
        };
        ClassWriter writer = new ClassWriter(0);
        new ClassReader(bytes).accept(new ClassRemapper(writer, relocation), 0);
        return writer.toByteArray();
    }
}
