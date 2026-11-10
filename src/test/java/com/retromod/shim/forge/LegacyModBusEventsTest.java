/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.tree.ClassNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static com.retromod.shim.forge.LegacyAutoSubscribeBridgeTest.OBJECT;
import static com.retromod.shim.forge.LegacyAutoSubscribeBridgeTest.enumValue;
import static com.retromod.shim.forge.LegacyAutoSubscribeBridgeTest.handler;
import static com.retromod.shim.forge.LegacyAutoSubscribeBridgeTest.hasAutoSubscriber;
import static com.retromod.shim.forge.LegacyAutoSubscribeBridgeTest.subscribeLists;
import static com.retromod.shim.forge.LegacyAutoSubscribeBridgeTest.subscriber;
import static org.junit.jupiter.api.Assertions.*;

/** An old mod-bus subscriber class whose event left the mod bus is subscribed from its @Mod constructor on Forge 26.2 and 26.3. */
class LegacyModBusEventsTest {

    static final String OLD = "Lnet/minecraftforge/eventbus/api/SubscribeEvent;";
    static final String EB7 = LegacyAutoSubscribeBridgeTest.EB7;
    static final String KEYS = "net/minecraftforge/client/event/RegisterKeyMappingsEvent";
    static final String TABS = "net/minecraftforge/event/BuildCreativeModeTabContentsEvent";
    static final String CLIENT_SETUP = "net/minecraftforge/fml/event/lifecycle/FMLClientSetupEvent";
    static final String COMMON_SETUP = "net/minecraftforge/fml/event/lifecycle/FMLCommonSetupEvent";
    static final List<Object> MOD_BUS = List.of("bus", enumValue("Lnet/minecraftforge/fml/common/Mod$EventBusSubscriber$Bus;", "MOD"));

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        transformer.clearRedirectsForTesting();
    }

    private void register(String target, boolean forge, boolean neoForge) {
        RetromodVersion.TARGET_MC_VERSION = target;
        McReflect.setForceForge(forge);
        McReflect.setForceNeoForge(neoForge);
        transformer.clearRedirectsForTesting();
        new Forge_26_1_to_26_2().registerRedirects(transformer);
    }

    private static byte[] modClass() {
        return ModConstructorHooksTest.modClass("mod/MyMod", "mymod", OBJECT, (writer, owner) -> { });
    }

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("A mod-bus class with a handler for an event that left the mod bus is subscribed whole from the constructor, and FML no longer sees it")
    void movedEventSubscriberJoinsTheConstructor(String host) {
        register(host, true, false);
        byte[] mod = modClass();
        byte[] client = subscriber("mod/ClientModEvents", MOD_BUS,
                handler("onKeys", KEYS, OLD), handler("onClientSetup", CLIENT_SETUP, OLD));
        byte[] setupOnly = subscriber("mod/SetupEvents", MOD_BUS,
                handler("onCommonSetup", COMMON_SETUP, OLD), handler("onClientSetup", CLIENT_SETUP, OLD));

        ModConstructorHooks.JarHooks hooks = ModConstructorHooks.forJar(transformer, List.of(mod, client, setupOnly));
        assertNotNull(hooks, "the moved event gives the jar a plan");
        assertEquals(List.of("mod.ClientModEvents MOD CLIENT,DEDICATED_SERVER"), subscribeLists(hooks.apply(mod)),
                "one call at the constructor tail, with the class's bus and Forge 1.20.1's default sides");

        byte[] handedOver = hooks.apply(client);
        assertFalse(hasAutoSubscriber(handedOver), "FML would put the class on the mod's group, which refuses the event");
        List<String> companions = new ArrayList<>();
        assertSame(handedOver, ForgeEventBusSynthetics.stripLenientAutoSubscriber(handedOver,
                (name, bytes) -> companions.add(name)), "nothing is left for the lenient strip to change");
        assertEquals(List.of(), companions, "no handler is hooked a second time");

        assertSame(setupOnly, hooks.apply(setupOnly), "a class with only mod-bus events stays with FML");
    }

    @Test
    @DisplayName("A class with only true mod-bus events, a game-bus class and an EventBus 7 class are left to FML")
    void otherSubscribersStayWithFml() {
        register("26.2", true, false);
        byte[] setupOnly = subscriber("mod/SetupEvents", MOD_BUS,
                handler("onCommonSetup", COMMON_SETUP, OLD), handler("onClientSetup", CLIENT_SETUP, OLD));
        // FML 26.2's default bus puts a game event on its own bus, so this class already works.
        byte[] gameBus = subscriber("mod/GameEvents", List.of(),
                handler("onTabs", TABS, OLD), handler("onKeys", KEYS, OLD));
        // A newer mod's EventBus 7 handlers are the mod's own business.
        byte[] newer = subscriber("mod/NewerEvents", MOD_BUS,
                handler("onKeys", KEYS, EB7), handler("onClientSetup", CLIENT_SETUP, EB7));

        assertNull(ModConstructorHooks.forJar(transformer, List.of(modClass(), setupOnly, gameBus, newer)),
                "none of these classes is handed to the constructor");

        byte[] transformed = transformer.transformClass(setupOnly, "mod/SetupEvents.class");
        assertTrue(hasAutoSubscriber(ForgeEventBusSynthetics.stripLenientAutoSubscriber(transformed, (name, bytes) -> { })),
                "FML still subscribes the true mod-bus class");
    }

    @Test
    @DisplayName("A running host's own classes decide: an event still on the mod bus keeps its class with FML")
    void hostClassesDecide() {
        register("26.2", true, false);
        byte[] mod = modClass();
        byte[] client = subscriber("mod/ClientModEvents", MOD_BUS,
                handler("onKeys", KEYS, OLD), handler("onClientSetup", CLIENT_SETUP, OLD));
        Map<String, byte[]> classes = Map.of("mod/MyMod", mod, "mod/ClientModEvents", client);
        Map<String, String> modIds = Map.of("mod/MyMod", "mymod");

        // Forge 26.2's shape: the event is a game event with a BUS of its own.
        Function<String, ClassNode> forge262 = host(Map.of(KEYS, List.of("net/minecraftforge/eventbus/api/event/MutableEvent")));
        ModConstructorHooks.JarPlan moved = LegacyAutoSubscribeBridge.plan(classes, modIds,
                event -> ForgeHostShape.isOutsideModBus(event, LegacyModBusEvents.LEFT_ON_FORGE_26, forge262));
        assertNotNull(moved, "the host shows the event left the mod bus");
        assertFalse(hasAutoSubscriber(moved.adjust("mod/ClientModEvents", client)));

        // A host whose event still implements IModBusEvent, as Forge 1.20.1's did.
        Function<String, ClassNode> modBusHost = host(Map.of(KEYS,
                List.of("net/minecraftforge/eventbus/api/Event", ForgeHostShape.MOD_BUS_EVENT)));
        assertNull(LegacyAutoSubscribeBridge.plan(classes, modIds,
                event -> ForgeHostShape.isOutsideModBus(event, LegacyModBusEvents.LEFT_ON_FORGE_26, modBusHost)),
                "the class stays with FML, whatever the offline list says");
    }

    @Test
    @DisplayName("A removed mod-bus event a bridge rebuilds on a bus of its own re-homes its subscriber classes too")
    void rebuiltEventsJoinTheConstructor() {
        register("26.2", true, false);
        String removed = "net/minecraftforge/client/event/RegisterGuiOverlaysEvent";
        List<byte[]> jar = List.of(modClass(), subscriber("mod/OverlayEvents", MOD_BUS,
                handler("onOverlays", removed, OLD), handler("onClientSetup", CLIENT_SETUP, OLD)));
        // The overlay bridge lists the jar's copy from the constructor; nothing subscribes the class there.
        assertEquals(List.of(), subscribeListsOf(jar), "a missing class is no moved event by itself");

        LegacyModBusEvents.registerRebuilt(transformer, removed);
        ModConstructorHooks.JarHooks hooks = ModConstructorHooks.forJar(transformer, jar);
        assertNotNull(hooks);
        assertEquals(List.of("mod.OverlayEvents MOD CLIENT,DEDICATED_SERVER"), subscribeLists(hooks.apply(jar.get(0))));

        transformer.clearRedirectsForTesting();
        new Forge_26_1_to_26_2().registerRedirects(transformer);
        assertEquals(List.of(), subscribeListsOf(jar), "the mark leaves with the registrations that made it");
    }

    // The lists the jar's @Mod constructor hands the subscriber, none when no bridge hooks the jar.
    private List<String> subscribeListsOf(List<byte[]> jar) {
        ModConstructorHooks.JarHooks hooks = ModConstructorHooks.forJar(transformer, jar);
        return hooks == null ? List.of() : subscribeLists(hooks.apply(jar.get(0)));
    }

    @Test
    @DisplayName("Nothing is re-homed on NeoForge, on an EventBus 6 Forge host, on Forge 26.1, or for a Fabric target")
    void hostGate() {
        List<byte[]> jar = List.of(modClass(), subscriber("mod/ClientModEvents", MOD_BUS,
                handler("onKeys", KEYS, OLD), handler("onClientSetup", CLIENT_SETUP, OLD)));
        register("26.2", false, true);
        assertNull(ModConstructorHooks.forJar(transformer, jar), "NeoForge keeps its own event API");
        register("1.21.1", true, false);
        assertNull(ModConstructorHooks.forJar(transformer, jar), "EventBus 6 still carries the event on the mod bus");
        register("26.1", true, false);
        assertNull(ModConstructorHooks.forJar(transformer, jar), "Forge 26.1 is not a checked host");
        register("26.2", false, false);
        assertNull(ModConstructorHooks.forJar(transformer, jar), "Fabric and Quilt have no Forge event bus");
    }

    /** A running Forge host that ships the anchor, IModBusEvent, and each class with its superclass then its interfaces. */
    static Function<String, ClassNode> host(Map<String, List<String>> types) {
        Map<String, ClassNode> shipped = new HashMap<>();
        shipped.put(ForgeHostShape.ANCHOR, node(ForgeHostShape.ANCHOR, List.of("java/lang/Object")));
        shipped.put(ForgeHostShape.MOD_BUS_EVENT, node(ForgeHostShape.MOD_BUS_EVENT, List.of("java/lang/Object")));
        types.forEach((name, supertypes) -> shipped.put(name, node(name, supertypes)));
        return shipped::get;
    }

    private static ClassNode node(String name, List<String> supertypes) {
        ClassNode node = new ClassNode();
        node.name = name;
        node.superName = supertypes.get(0);
        node.interfaces = new ArrayList<>(supertypes.subList(1, supertypes.size()));
        return node;
    }
}
