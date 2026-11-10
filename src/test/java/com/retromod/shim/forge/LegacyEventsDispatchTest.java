/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.forge.LegacyEventFixtures.Action;
import com.retromod.shim.forge.LegacyEventFixtures.Handler;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static com.retromod.shim.forge.EventBus7Host.*;
import static org.junit.jupiter.api.Assertions.*;

/** Old mods' own events dispatch on EventBus 7 with Forge 1.20.1's order, cancel, generic and phase rules. */
class LegacyEventsDispatchTest {

    private static final String SECOND = "com/retromod/embedded/second/";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();
    private EventBus7Host host;

    @BeforeEach
    void forge262() throws Exception {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        McReflect.setForceNeoForge(false);
        McReflect.setForceForge(true);
        transformer.clearRedirectsForTesting();
        new Forge_26_1_to_26_2().registerRedirects(transformer);
        host = new EventBus7Host();
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        transformer.clearRedirectsForTesting();
    }

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("Listeners run by priority, an event's own before its parent's within a priority, then in registration order")
    void priorityThenClassThenRegistrationOrder(String target) throws Exception {
        RetromodVersion.TARGET_MC_VERSION = target;
        transformer.clearRedirectsForTesting();
        new Forge_26_1_to_26_2().registerRedirects(transformer);
        ClassLoader mod = mod(Map.of(
                "mod/CancelEvent", LegacyEventFixtures.cancelableEvent("mod/CancelEvent"),
                "mod/SubEvent", LegacyEventFixtures.subclass("mod/SubEvent", "mod/CancelEvent"),
                "mod/Ordered", LegacyEventFixtures.listener("mod/Ordered",
                        Handler.of("parent-highest", "mod/CancelEvent", "HIGHEST"),
                        Handler.of("sub-low", "mod/SubEvent", "LOW"),
                        Handler.of("parent-normal", "mod/CancelEvent", null),
                        Handler.of("sub-normal", "mod/SubEvent", "NORMAL"),
                        Handler.of("parent-high", "mod/CancelEvent", "HIGH"))));
        host.completeLoading();
        Object bus = gameBus(mod);
        register(bus, create(mod, "mod/Ordered"));
        List<String> notes = notes(mod, "mod/Ordered");
        // Two lambdas on the subclass at NORMAL, added after the handlers, run after the handler there.
        for (String tag : List.of("first", "second")) {
            addListener(mod, bus, "NORMAL", false, mod.loadClass("mod.SubEvent"), event -> notes.add(tag));
        }

        assertFalse(post(bus, create(mod, "mod/SubEvent")));
        assertEquals(List.of("parent-highest", "parent-high", "sub-normal", "first", "second", "parent-normal", "sub-low"),
                notes);
        notes.clear();
        post(bus, create(mod, "mod/CancelEvent"));
        assertEquals(List.of("parent-highest", "parent-high", "parent-normal"), notes,
                "a parent event reaches none of its subclass's listeners");
    }

    @Test
    @DisplayName("A cancelled event skips listeners without receiveCanceled, and a later receiveCanceled listener can un-cancel it")
    void cancelSkipsAndUncancelRestores() throws Exception {
        ClassLoader mod = mod(Map.of(
                "mod/CancelEvent", LegacyEventFixtures.cancelableEvent("mod/CancelEvent"),
                "mod/Uncancels", LegacyEventFixtures.listener("mod/Uncancels",
                        Handler.of("high-cancel", "mod/CancelEvent", "HIGH").then(Action.CANCEL),
                        Handler.of("normal", "mod/CancelEvent", "NORMAL"),
                        Handler.of("low-uncancel", "mod/CancelEvent", "LOW").receivingCanceled().then(Action.UNCANCEL),
                        Handler.of("lowest", "mod/CancelEvent", "LOWEST"))));
        host.completeLoading();
        Object bus = gameBus(mod);
        register(bus, create(mod, "mod/Uncancels"));
        Object event = create(mod, "mod/CancelEvent");

        assertFalse(post(bus, event), "post reads the flag after the last listener, which un-cancelled it");
        assertEquals(List.of("high-cancel", "low-uncancel", "lowest"), notes(mod, "mod/Uncancels"));
        assertEquals(false, call(event, "isCanceled"));
    }

    @Test
    @DisplayName("post returns true when the event ends cancelled, and only receiveCanceled listeners see it after the cancel")
    void postReturnsTheFinalCancelState() throws Exception {
        ClassLoader mod = mod(Map.of(
                "mod/CancelEvent", LegacyEventFixtures.cancelableEvent("mod/CancelEvent"),
                "mod/Keeps", LegacyEventFixtures.listener("mod/Keeps",
                        Handler.of("high-cancel", "mod/CancelEvent", "HIGH").then(Action.CANCEL),
                        Handler.of("normal", "mod/CancelEvent", "NORMAL"),
                        Handler.of("low-sees", "mod/CancelEvent", "LOW").receivingCanceled())));
        host.completeLoading();
        Object bus = gameBus(mod);
        register(bus, create(mod, "mod/Keeps"));
        Object event = create(mod, "mod/CancelEvent");

        assertTrue(post(bus, event));
        assertEquals(List.of("high-cancel", "low-sees"), notes(mod, "mod/Keeps"));
        assertEquals(true, call(event, "isCanceled"));
    }

    @Test
    @DisplayName("A generic handler runs only for its type argument, a bare ? runs for every type, and an overriding getGenericType decides")
    void genericFilter() throws Exception {
        String registry = "Lmod/RegistryEvent<";
        ClassLoader mod = mod(Map.of(
                "mod/RegistryEvent", LegacyEventFixtures.genericEvent("mod/RegistryEvent"),
                "mod/ReportsInteger", LegacyEventFixtures.overridingGenericEvent("mod/ReportsInteger",
                        "mod/RegistryEvent", Integer.class),
                "mod/Registries", LegacyEventFixtures.listener("mod/Registries",
                        Handler.of("string", "mod/RegistryEvent", null).typed(registry + "Ljava/lang/String;>;"),
                        Handler.of("integer", "mod/RegistryEvent", null).typed(registry + "Ljava/lang/Integer;>;"),
                        Handler.of("any", "mod/RegistryEvent", null).typed(registry + "*>;"),
                        Handler.of("bounded", "mod/RegistryEvent", null).typed(registry + "+Ljava/lang/Number;>;"),
                        Handler.of("raw", "mod/RegistryEvent", null))));
        host.completeLoading();
        Object bus = gameBus(mod);
        register(bus, create(mod, "mod/Registries"));
        List<String> notes = notes(mod, "mod/Registries");

        post(bus, mod.loadClass("mod.RegistryEvent").getConstructor(Class.class).newInstance(String.class));
        assertEquals(List.of("any", "raw", "string"), notes.stream().sorted().toList());
        notes.clear();
        post(bus, mod.loadClass("mod.ReportsInteger").getConstructor(Class.class).newInstance(String.class));
        assertEquals(List.of("any", "integer", "raw"), notes.stream().sorted().toList(),
                "the subclass's own getGenericType answer is the one compared; a bounded wildcard matched nothing on Forge 1.20.1");
    }

    @Test
    @DisplayName("The game bus moves an event's phase to the lowest priority that had listeners; a mod bus leaves it unset")
    void phaseOnTheGameBusOnly() throws Exception {
        ClassLoader mod = mod(Map.of(
                "mod/CancelEvent", LegacyEventFixtures.cancelableEvent("mod/CancelEvent"),
                "mod/ModEvent", LegacyEventFixtures.modBusEvent("mod/ModEvent"),
                "mod/Phased", LegacyEventFixtures.listener("mod/Phased",
                        Handler.of("high", "mod/CancelEvent", "HIGH"),
                        Handler.of("low", "mod/CancelEvent", "LOW"),
                        Handler.of("mod-high", "mod/ModEvent", "HIGH"))));
        host.completeLoading();
        Object listener = create(mod, "mod/Phased");
        Object gameBus = gameBus(mod);
        Object modBus = modBus(mod, host.modGroup("phased"));
        register(gameBus, listener);
        register(modBus, listener);
        Object onGame = create(mod, "mod/CancelEvent");
        Object onMod = create(mod, "mod/ModEvent");

        assertNull(call(onGame, "getPhase"), "no phase before delivery");
        post(gameBus, onGame);
        assertEquals("LOW", ((Enum<?>) call(onGame, "getPhase")).name());
        post(modBus, onMod);
        assertTrue(notes(mod, "mod/Phased").contains("mod-high"), "the mod-bus handler ran");
        assertNull(call(onMod, "getPhase"), "Forge 1.20.1's mod buses tracked no phase");
    }

    @Test
    @DisplayName("A game-bus post before mod loading completes returns false and runs nothing; a mod-bus post is live")
    void gameBusStartsWhenLoadingCompletes() throws Exception {
        ClassLoader mod = mod(Map.of(
                "mod/CancelEvent", LegacyEventFixtures.cancelableEvent("mod/CancelEvent"),
                "mod/ModEvent", LegacyEventFixtures.modBusEvent("mod/ModEvent"),
                "mod/Early", LegacyEventFixtures.listener("mod/Early",
                        Handler.of("cancel", "mod/CancelEvent", null).then(Action.CANCEL),
                        Handler.of("mod-event", "mod/ModEvent", null))));
        Object listener = create(mod, "mod/Early");
        Object gameBus = gameBus(mod);
        Object modBus = modBus(mod, host.modGroup("early"));
        register(gameBus, listener);
        register(modBus, listener);
        List<String> notes = notes(mod, "mod/Early");

        assertFalse(post(gameBus, create(mod, "mod/CancelEvent")));
        assertEquals(List.of(), notes, "Forge 1.20.1's game bus was still shut down during construction");
        post(modBus, create(mod, "mod/ModEvent"));
        assertEquals(List.of("mod-event"), notes);

        host.completeLoading();
        assertTrue(post(gameBus, create(mod, "mod/CancelEvent")));
        assertEquals(List.of("mod-event", "cancel"), notes);
    }

    @Test
    @DisplayName("Two mods' copies of the rebuilt base share one bus per event and read each other's cancel flag")
    void copiesShareBusesAndFlags() throws Exception {
        ClassLoader library = mod(Map.of(
                "mod/CancelEvent", LegacyEventFixtures.cancelableEvent("mod/CancelEvent"),
                "mod/LibListener", LegacyEventFixtures.listener("mod/LibListener",
                        Handler.of("library", "mod/CancelEvent", "LOW"))));
        ClassLoader addon = host.mod(library, transformer, SECOND, Map.of(
                "modb/AddonListener", LegacyEventFixtures.listener("modb/AddonListener",
                        Handler.of("addon-cancel", "mod/CancelEvent", "HIGH").then(Action.CANCEL))));
        host.completeLoading();
        Object libraryBus = gameBus(library);
        Object addonBus = addon.loadClass((SECOND + ForgeEventBusSynthetics.LEB_IMPL).replace('/', '.'))
                .getField("GAME_BUS").get(null);
        assertNotSame(libraryBus.getClass(), addonBus.getClass(), "each mod embeds its own bus bridge");
        register(libraryBus, create(library, "mod/LibListener"));
        register(addonBus, addon.loadClass("modb.AddonListener").getConstructor().newInstance());

        assertTrue(post(libraryBus, create(library, "mod/CancelEvent")), "the library sees the addon's cancel");
        assertEquals(List.of("addon-cancel"), notes(addon, "modb/AddonListener"));
        assertEquals(List.of(), notes(library, "mod/LibListener"), "the cancelled event skips the library's own listener");
        Object shared = host.existingBus(host.gameGroup(), library.loadClass("mod.CancelEvent"));
        assertEquals(2, ((List<?>) shared.getClass().getMethod("priorities").invoke(shared)).size(),
                "both copies' listeners sit on one EventBus 7 bus");
    }

    @Test
    @DisplayName("A handler of a mod event built on a host event runs on that class's own bus, and its post is never held back")
    void modEventOnAHostEvent() throws Exception {
        host.define(LegacyEventFixtures.HOST_EVENT, LegacyEventFixtures.hostEvent(LegacyEventFixtures.HOST_EVENT));
        ClassLoader mod = mod(Map.of(
                "mod/TooltipEvent", LegacyEventFixtures.subclass("mod/TooltipEvent", "net/minecraftforge/event/HostEvent"),
                "mod/Tooltips", LegacyEventFixtures.listener("mod/Tooltips",
                        Handler.of("tooltip", "mod/TooltipEvent", "HIGH"))));
        Object bus = gameBus(mod);
        register(bus, create(mod, "mod/Tooltips"));

        assertFalse(post(bus, create(mod, "mod/TooltipEvent")), "a host event's own bus answers, and it never cancels");
        assertEquals(List.of("tooltip"), notes(mod, "mod/Tooltips"), "host events reach the host bus before loading completes");
        Object own = host.existingBus(host.gameGroup(), mod.loadClass("mod.TooltipEvent"));
        assertEquals(List.of((byte) 64), own.getClass().getMethod("priorities").invoke(own),
                "the handler sits on the subclass's own bus at EventBus 7's HIGH, with no class-depth band");
        Object parent = host.type("net/minecraftforge/event/HostEvent").getField("BUS").get(null);
        assertEquals(List.of(), parent.getClass().getMethod("priorities").invoke(parent));
    }

    // ---- helpers ----

    private ClassLoader mod(Map<String, byte[]> classes) {
        return host.mod(host, transformer, "", classes);
    }

    private static Object call(Object target, String name) throws Exception {
        return target.getClass().getMethod(name).invoke(target);
    }
}
