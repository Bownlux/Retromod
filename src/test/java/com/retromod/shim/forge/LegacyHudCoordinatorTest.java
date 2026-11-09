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
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/** The HUD coordinator draws two mods' old overlays and posts their old events inside Forge 26.2's and 26.3's HUD layers. */
class LegacyHudCoordinatorTest {

    private static final String SECOND = "com/retromod/embedded/second/";

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

    // The shared HUD slots live in system properties, so no test sees another's overlays or coordinator.
    private static void clearSharedHud() {
        System.getProperties().keySet().removeIf(key -> key.toString().startsWith("retromod.shared.forge.hud."));
    }

    static List<String> hosts() {
        return LegacyHudGame.HOSTS;
    }

    /** Two old mods: the first registers around vanilla overlays, the second next to the first's. */
    private LegacyHudGame game(String host) throws Exception {
        return LegacyHudGame.create(host, dir, transformer, twoMods());
    }

    private static List<LegacyHudGame.Mod> twoMods() {
        return List.of(
                new LegacyHudGame.Mod("moda", "", """
                        event.registerBelowAll("bottom", draws("moda:bottom"));
                        event.registerAbove(VanillaGuiOverlay.HOTBAR.id(), "after_hotbar", draws("moda:after_hotbar"));
                        event.registerBelow(VanillaGuiOverlay.CROSSHAIR.id(), "below_crosshair", draws("moda:below_crosshair"));
                        event.registerAbove(VanillaGuiOverlay.FOOD_LEVEL.id(), "food", draws("moda:food"));
                        event.registerAbove(VanillaGuiOverlay.DEBUG_TEXT.id(), "debug", draws("moda:debug"));
                        event.registerAboveAll("top", draws("moda:top"));
                        Log.add("moda registers while Minecraft.gui is " + (Minecraft.getInstance().gui != null ? "set" : "null"));
                        """),
                new LegacyHudGame.Mod("modb", SECOND, """
                        event.registerAbove(Identifier.fromNamespaceAndPath("moda", "after_hotbar"), "chained", draws("modb:chained"));
                        try {
                            event.registerAboveAll("chained", draws("modb:again"));
                        } catch (IllegalArgumentException duplicate) {
                            Log.add("modb: " + duplicate.getMessage());
                        }
                        try {
                            event.registerAbove(Identifier.fromNamespaceAndPath("nobody", "x"), "lost", draws("modb:lost"));
                        } catch (IllegalStateException unknown) {
                            Log.add("modb: " + unknown.getMessage());
                        }
                        """));
    }

    // Forge's layers as they draw with no old overlay or listener in the game.
    private static final List<String> HOST_DRAWS = List.of("draw camera_overlay", "draw crosshair", "draw stratum_change",
            "draw item_hotbar", "draw health_bar", "draw vehicle_health", "draw background", "draw experience_level",
            "draw contextual_info", "draw selected_item_name", "draw potion_effects", "draw boss_overlay", "draw sleep_overlay",
            "draw demo", "draw scoreboard", "draw hotbar_message", "draw title", "draw chat_overlay", "draw tab_list",
            "draw subtitle");

    @ParameterizedTest(name = "{0}")
    @MethodSource("hosts")
    @DisplayName("Overlays from two mods register in mod order and draw where Forge 1.20.1 drew them, by one coordinator")
    void overlaysDrawInTheirOldPlaces(String host) throws Exception {
        LegacyHudGame game = game(host).construct().buildHud();
        game.log().clear();
        game.initClientHooks();
        assertEquals(List.of("moda registers while Minecraft.gui is set", "modb: Overlay already registered: modb:chained",
                "modb: Attempted to order against an unregistered overlay. Only order against vanilla's and your own."),
                game.log(), "registered after the key mappings, in mod order, with the old exceptions");

        assertEquals(List.of(
                "draw moda:bottom 39/39", "draw camera_overlay", "draw crosshair", "draw stratum_change", "draw item_hotbar",
                "draw moda:after_hotbar 39/39", "draw modb:chained 39/39", "draw moda:below_crosshair 39/39",
                "draw health_bar", "draw moda:food 59/49", "draw vehicle_health", "draw background", "draw experience_level",
                "draw contextual_info", "draw selected_item_name", "draw potion_effects", "draw boss_overlay",
                "draw sleep_overlay", "draw moda:debug 59/49", "draw demo", "draw scoreboard", "draw hotbar_message",
                "draw title", "draw chat_overlay", "draw tab_list", "draw subtitle", "draw moda:top 59/49"),
                draws(game.frame()), "each overlay after the nearest vanilla overlay before it, the hearts and food counted");

        // Both copies attached, and only the first one's coordinator wrapped each host layer, once, with no complaint
        // from Forge's own layer stacks about a missing stack or layer.
        assertEquals(20, game.replaced().size(), game.replaced().toString());
        assertEquals(20, game.replaced().stream().distinct().count(), game.replaced().toString());
        assertEquals(List.of(), game.hostWarnings());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("hosts")
    @DisplayName("With no old listener and no old overlay, Forge's layers draw as they are and nothing reads the player")
    void nothingRunsWithoutListenersOrOverlays(String host) throws Exception {
        LegacyHudGame game = LegacyHudGame.create(host, dir, transformer, List.of(new LegacyHudGame.Mod("quiet", "", "", false)))
                .started();
        assertEquals(HOST_DRAWS, game.frame());
        assertEquals(0, game.healthReads(), "no status is read for a frame nothing listens to");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("hosts")
    @DisplayName("When another mod replaces a wrapped layer later, the next layer still posts its events and draws the overlays after it")
    void replacedLayerStillPostsItsEvents(String host) throws Exception {
        LegacyHudGame game = game(host);
        // A Forge 26.x mod replacing the hearts layer at LOWEST, after Retromod's LOW listener wrapped it.
        Class<?> layer = game.type("net/minecraftforge/client/gui/overlay/ForgeLayer");
        Object nativeHealth = Proxy.newProxyInstance(layer.getClassLoader(), new Class<?>[]{layer}, (proxy, method, args) ->
                switch (method.getName()) {
                    case "extract" -> {
                        game.log().add("draw native health");
                        yield null;
                    }
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> "native health";
                });
        Class<?> layers = game.type("net/minecraftforge/client/event/AddGuiOverlayLayersEvent");
        Class<?> identifier = game.type("net/minecraft/resources/Identifier");
        java.util.function.Consumer<Object> replace = event -> {
            try {
                Object draw = layers.getMethod("getLayeredDraw").invoke(event);
                Object stack = identifier.getMethod("withDefaultNamespace", String.class).invoke(null, "hotbar");
                Object health = identifier.getMethod("withDefaultNamespace", String.class).invoke(null, "health_bar");
                draw.getClass().getMethod("replace", identifier, identifier, layer).invoke(draw, stack, health, nativeHealth);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(failure);
            }
        };
        Object bus = layers.getField("BUS").get(null);
        game.type("net/minecraftforge/eventbus/api/bus/EventBus").getMethod("addListener", byte.class, java.util.function.Consumer.class)
                .invoke(bus, (byte) -127, replace);

        String err = capturingErr(() -> {
            game.started();
            return null;
        });
        assertEquals(1, count(err, "replaced the HUD layer minecraft:health_bar"), err);
        List<String> frame = game.frame();
        List<String> around = between(frame, "draw native health", "draw vehicle_health");
        assertTrue(around.containsAll(List.of("moda pre minecraft:player_health 39/39", "moda post minecraft:air_level 59/49",
                "draw moda:food 59/49")), "the next layer ran the hearts' events and overlays: " + around);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("hosts")
    @DisplayName("On a host without AddGuiOverlayLayersEvent every mod still constructs, and one line says the old overlays are not drawn")
    void hostWithoutTheLayerEventStillConstructs(String host) throws Exception {
        LegacyHudGame game = LegacyHudGame.create(host, dir, transformer, twoMods(), java.util.Set.of(
                "net/minecraftforge/client/event/AddGuiOverlayLayersEvent", "net/minecraftforge/client/event/ForgeEventFactoryClient"));
        String err = capturingErr(() -> {
            game.construct();
            return null;
        });
        assertEquals(1, count(err, "old HUD overlays are not drawn"), err);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("hosts")
    @DisplayName("Each frame posts RenderGuiEvent around the HUD and each overlay's Pre and Post in mod order, as ForgeGui did")
    void eventsWrapEachOverlayInModOrder(String host) throws Exception {
        LegacyHudGame game = game(host).started();
        List<String> frame = game.frame();
        assertTrue(game.healthReads() > 0, "the status bars read the player");
        assertEquals(List.of("moda gui pre", "modb gui pre"), frame.subList(0, 2));
        assertEquals(List.of("moda gui post", "modb gui post"), frame.subList(frame.size() - 2, frame.size()));

        // The status bars: every Pre before the shared layer draws, then the Posts with the overlay registered between.
        assertEquals(List.of(
                "moda pre minecraft:player_health 39/39", "modb pre minecraft:player_health 39/39",
                "moda pre minecraft:armor_level 49/39", "modb pre minecraft:armor_level 49/39",
                "moda pre minecraft:food_level 59/39", "modb pre minecraft:food_level 59/39",
                "moda pre minecraft:air_level 59/49", "modb pre minecraft:air_level 59/49",
                "draw health_bar",
                "moda post minecraft:player_health 49/39", "modb post minecraft:player_health 49/39",
                "moda post minecraft:armor_level 59/39", "modb post minecraft:armor_level 59/39",
                "moda post minecraft:food_level 59/49", "modb post minecraft:food_level 59/49",
                "moda pre moda:food 59/49", "modb pre moda:food 59/49", "draw moda:food 59/49",
                "moda post moda:food 59/49", "modb post moda:food 59/49",
                "moda post minecraft:air_level 59/49", "modb post minecraft:air_level 59/49"),
                between(frame, "moda pre minecraft:player_health", "modb post minecraft:air_level"));
        assertTrue(frame.contains("moda crosshair is the vanilla type"), "getOverlay() is VanillaGuiOverlay.CROSSHAIR.type()");
        assertTrue(frame.contains("modb crosshair is the vanilla type"), "in the second mod's copy too");
        assertTrue(frame.containsAll(List.of("moda pre minecraft:debug_text 59/49", "moda post minecraft:fps_graph 59/49")),
                "the debug text and graph keep their events where they have no layer");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("hosts")
    @DisplayName("A cancelled Pre skips the overlay and its Post in every mod, and a later mod's receiveCanceled listener sees it")
    void cancelStopsTheDrawAndCarriesAcrossMods(String host) throws Exception {
        LegacyHudGame game = game(host).started().flag("moda cancel minecraft:crosshair").flag("moda cancel modb:chained");
        List<String> frame = game.frame();
        assertFalse(frame.contains("draw crosshair"));
        assertFalse(frame.stream().anyMatch(line -> line.contains("post minecraft:crosshair")), "no Post after a cancel");
        assertFalse(frame.stream().anyMatch(line -> line.startsWith("modb pre minecraft:crosshair")),
                "the second mod's ordinary listener does not run for a cancelled Pre");
        assertTrue(frame.containsAll(List.of("moda sees cancelled minecraft:crosshair", "modb sees cancelled minecraft:crosshair")));
        assertFalse(frame.stream().anyMatch(line -> line.startsWith("draw modb:chained")), "a mod overlay's Pre cancels it too");
        assertTrue(frame.contains("draw moda:below_crosshair 39/39"), "the overlays after it still draw");

        // The second mod takes the cancel back, as a LOWEST receiveCanceled listener could on Forge 1.20.1's one bus.
        game.flag("modb uncancel minecraft:crosshair");
        frame = game.frame();
        assertTrue(frame.contains("draw crosshair"));
        assertTrue(frame.containsAll(List.of("moda post minecraft:crosshair 39/39", "modb post minecraft:crosshair 39/39")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("hosts")
    @DisplayName("A cancelled RenderGuiEvent.Pre leaves out every old overlay and wrapped layer, and its Post")
    void hudCancelLeavesTheHudOut(String host) throws Exception {
        LegacyHudGame game = game(host).started().flag("moda cancel gui");
        List<String> frame = game.frame();
        // Only the layers Retromod does not wrap still draw.
        assertEquals(List.of("moda gui pre", "draw stratum_change", "draw demo"), frame);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("hosts")
    @DisplayName("Cancelling part of a shared layer still draws it; cancelling all of it hides it, and each Post follows its own Pre")
    void sharedLayersHideOnlyWhenEveryPartIsCancelled(String host) throws Exception {
        LegacyHudGame game = game(host).started().flag("moda cancel minecraft:player_health");
        String err = capturingErr(() -> {
            List<String> frame = game.frame();
            assertTrue(frame.contains("draw health_bar"), "the hearts cannot be hidden alone");
            assertFalse(frame.stream().anyMatch(line -> line.contains("post minecraft:player_health")));
            assertTrue(frame.contains("moda pre minecraft:armor_level 39/39"), "no hearts counted after their cancel");
            assertTrue(frame.contains("moda post minecraft:armor_level 49/39"));
            game.frame();
            return null;
        });
        assertEquals(1, count(err, "although a mod cancelled"), "the note comes once: " + err);

        for (String bar : List.of("armor_level", "food_level", "air_level")) game.flag("moda cancel minecraft:" + bar);
        List<String> frame = game.frame();
        assertFalse(frame.contains("draw health_bar"));
        assertTrue(frame.contains("draw moda:food 39/39"), "the overlay registered between the bars still draws");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("hosts")
    @DisplayName("JUMP_BAR and EXPERIENCE_BAR both post every frame, and only the bar shown can hide the contextual layers")
    void jumpBarAndExperienceBar(String host) throws Exception {
        LegacyHudGame game = game(host).started().flag("moda cancel minecraft:experience_bar");
        Predicate<List<String>> both = frame -> frame.containsAll(List.of("moda pre minecraft:jump_bar 59/49",
                "moda pre minecraft:experience_bar 59/49", "moda post minecraft:jump_bar 59/49"));
        Predicate<List<String>> contextual = frame -> frame.containsAll(List.of("draw background", "draw contextual_info"));
        List<String> walking = game.frame();
        assertTrue(both.test(walking), walking.toString());
        assertFalse(walking.stream().anyMatch(line -> line.startsWith("draw background")), "walking shows the experience bar");

        Class<?> jumping = game.type("net/minecraft/world/entity/PlayerRideableJumping");
        game.player("jumping", Proxy.newProxyInstance(jumping.getClassLoader(), new Class<?>[]{jumping}, (proxy, method, args) -> null));
        String err = capturingErr(() -> {
            List<String> riding = game.frame();
            assertTrue(contextual.test(riding), "riding shows the jump bar, which nothing cancelled: " + riding);
            return null;
        });
        assertEquals(0, count(err, "although a mod cancelled"), "a cancel of the bar not shown is no partial cancel: " + err);

        game.unflag("moda cancel minecraft:experience_bar").flag("moda cancel minecraft:jump_bar");
        assertFalse(contextual.test(game.frame()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("hosts")
    @DisplayName("An overlay that needs a missing member is turned off alone with one line, and the rest of the HUD keeps drawing")
    void missingMemberTurnsOffOneOverlay(String host) throws Exception {
        LegacyHudGame game = game(host).started().flag("throw moda:after_hotbar");
        String err = capturingErr(() -> {
            assertTrue(game.frame().contains("draw moda:after_hotbar 39/39"));
            for (int i = 0; i < 2; i++) {
                List<String> frame = game.frame();
                assertFalse(frame.stream().anyMatch(line -> line.startsWith("draw moda:after_hotbar")), "turned off");
                assertTrue(frame.contains("draw modb:chained 39/39"));
                assertTrue(frame.contains("draw item_hotbar"));
            }
            return null;
        });
        assertEquals(1, count(err, "stopped drawing the HUD overlay moda:after_hotbar"), err);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("hosts")
    @DisplayName("A listener that throws counts as not cancelling: the overlay draws, the frame goes on, and one line names the mod")
    void throwingListenerDoesNotStopTheHud(String host) throws Exception {
        LegacyHudGame game = game(host).started().flag("modb throw minecraft:hotbar");
        String err = capturingErr(() -> {
            for (int i = 0; i < 2; i++) {
                List<String> frame = game.frame();
                assertTrue(frame.containsAll(List.of("draw item_hotbar", "moda post minecraft:hotbar 39/39", "modb gui post")));
            }
            return null;
        });
        assertEquals(1, count(err, "RenderGuiOverlayEvent.Pre listener of modb threw"), err);

        // A receiveCanceled listener that throws after an earlier mod's cancel leaves that cancel in place.
        game.flag("moda cancel minecraft:hotbar").flag("modb throw watching minecraft:hotbar");
        List<String> frame = game.frame();
        assertTrue(frame.contains("modb sees cancelled minecraft:hotbar"));
        assertFalse(frame.contains("draw item_hotbar"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("hosts")
    @DisplayName("With the HUD hidden, no old event fires and the layers drawn anyway draw as they are")
    void hiddenHudPostsNothing(String host) throws Exception {
        LegacyHudGame game = game(host).started().flag("hidden");
        assertEquals(List.of("draw sleep_overlay"), game.frame());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("hosts")
    @DisplayName("Another mod's NamedGuiOverlay for a vanilla id draws the host layer as it was, with no events")
    void vanillaOverlayDrawsThroughTheHostLayer(String host) throws Exception {
        LegacyHudGame game = game(host).started();
        game.log().clear();
        Object identifier = game.call("net/minecraft/resources/Identifier", "withDefaultNamespace", "crosshair");
        Object named = game.call(SECOND + LegacyHudGame.ROOT + "gui/overlay/GuiOverlayManager", "findOverlay", identifier);
        Object overlay = named.getClass().getMethod("overlay").invoke(named);
        Class<?> forgeGui = game.type(SECOND + LegacyHudGame.ROOT + "gui/overlay/ForgeGui");
        Object gui = forgeGui.getMethod("cast", Object.class).invoke(null, minecraftGui(game));
        Class<?> graphics = game.type("net/minecraft/client/gui/GuiGraphicsExtractor");
        game.type(SECOND + LegacyHudGame.ROOT + "gui/overlay/IGuiOverlay")
                .getMethod("render", forgeGui, graphics, float.class, int.class, int.class)
                .invoke(overlay, gui, graphics.getConstructor().newInstance(), 0f, 320, 240);
        assertEquals(List.of("draw crosshair"), game.log());
    }

    // ---- helpers ----

    private static Object minecraftGui(LegacyHudGame game) throws Exception {
        Class<?> minecraft = game.type("net/minecraft/client/Minecraft");
        return minecraft.getField("gui").get(minecraft.getMethod("getInstance").invoke(null));
    }

    static List<String> draws(List<String> frame) {
        return frame.stream().filter(line -> line.startsWith("draw ")).toList();
    }

    /** The lines from the first one starting with {@code first} to the first after it starting with {@code last}. */
    static List<String> between(List<String> frame, String first, String last) {
        int from = -1;
        for (int i = 0; i < frame.size(); i++) {
            if (from < 0 && frame.get(i).startsWith(first)) from = i;
            if (from >= 0 && frame.get(i).startsWith(last)) return frame.subList(from, i + 1);
        }
        throw new AssertionError("no lines from " + first + " to " + last + " in " + frame);
    }

    // Retromod's game-log helper falls back to standard error where no SLF4J is present, as in these tests.
    static String capturingErr(Callable<Void> body) throws Exception {
        PrintStream saved = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            body.call();
        } finally {
            System.setErr(saved);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    static int count(String text, String part) {
        int count = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + 1)) count++;
        return count;
    }
}
