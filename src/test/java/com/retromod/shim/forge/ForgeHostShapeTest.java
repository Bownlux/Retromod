/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodVersion;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.ClassNode;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Forge host-shape answer every Forge 26.x bridge registers through. */
class ForgeHostShapeTest {

    private static final String DIST_EXECUTOR = "net/minecraftforge/fml/DistExecutor";
    private static final String TICK_EVENT = "net/minecraftforge/event/TickEvent";

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @BeforeEach
    void forgeTarget() {
        McReflect.setForceNeoForge(false);
        McReflect.setForceForge(true);
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
    }

    @Test
    @DisplayName("A running Forge host answers from its own classes")
    void probeAnswersFromTheHost() {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        Function<String, ClassNode> forge262 = host(ForgeHostShape.ANCHOR, TICK_EVENT);

        assertTrue(lacks(DIST_EXECUTOR, forge262), "26.2 ships no DistExecutor");
        assertFalse(lacks(TICK_EVENT, forge262), "26.2 still ships TickEvent");
        assertTrue(ForgeHostShape.matches(List.of(TICK_EVENT), List.of(DIST_EXECUTOR), "26.2", forge262));
        assertFalse(ForgeHostShape.matches(List.of(DIST_EXECUTOR), List.of(), "26.2", forge262),
                "a class the host lacks fails the has side");
    }

    @Test
    @DisplayName("Offline, a Forge target from the first checked version stands in for the host")
    void offlineFallsBackToTheTargetVersion() {
        Function<String, ClassNode> offline = host();
        for (String target : List.of("26.2", "26.3")) {
            RetromodVersion.TARGET_MC_VERSION = target;
            assertTrue(lacks(DIST_EXECUTOR, offline), target);
        }
        for (String target : List.of("26.1", "1.21.11", "1.20.1")) {
            RetromodVersion.TARGET_MC_VERSION = target;
            assertFalse(lacks(DIST_EXECUTOR, offline), target);
        }
        RetromodVersion.TARGET_MC_VERSION = "1.21.11";
        assertTrue(ForgeHostShape.matches(List.of(), List.of("net/minecraftforge/common/crafting/CraftingHelper"),
                "1.21.11", offline), "a caller can name an earlier version it checked");
    }

    @Test
    @DisplayName("A Forge 1.20.1 host answers no, running or offline")
    void forge1201AnswersNo() {
        RetromodVersion.TARGET_MC_VERSION = "1.20.1";
        assertFalse(lacks(DIST_EXECUTOR, host(ForgeHostShape.ANCHOR, DIST_EXECUTOR, TICK_EVENT)));
        assertFalse(lacks(DIST_EXECUTOR, host()));
    }

    @Test
    @DisplayName("NeoForge, and a run with no Forge target, answer no even where the class is missing")
    void otherTargetsAnswerNo() {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        McReflect.setForceNeoForge(true);
        assertFalse(lacks(DIST_EXECUTOR, host(ForgeHostShape.ANCHOR)), "a NeoForge target wins over the Forge flag");
        assertFalse(lacks(DIST_EXECUTOR, host()));

        McReflect.setForceNeoForge(false);
        McReflect.setForceForge(false);
        assertFalse(lacks(DIST_EXECUTOR, host()), "Fabric, Quilt and an unnamed loader never take a Forge bridge");
    }

    @Test
    @DisplayName("The mod-bus probe walks Forge's own supertypes on a running host and reads the checked list offline")
    void modBusProbe() {
        String keys = "net/minecraftforge/client/event/RegisterKeyMappingsEvent";
        String renderers = "net/minecraftforge/client/event/EntityRenderersEvent$RegisterRenderers";
        String clientSetup = "net/minecraftforge/fml/event/lifecycle/FMLClientSetupEvent";
        String parallel = "net/minecraftforge/fml/event/lifecycle/ParallelDispatchEvent";
        String lifecycle = "net/minecraftforge/fml/event/lifecycle/ModLifecycleEvent";
        Set<String> offline = Set.of(keys);
        RetromodVersion.TARGET_MC_VERSION = "26.2";

        Function<String, ClassNode> forge262 = LegacyModBusEventsTest.host(Map.of(
                keys, List.of("net/minecraftforge/eventbus/api/event/MutableEvent",
                        "net/minecraftforge/eventbus/api/event/characteristic/SelfDestructing"),
                renderers, List.of("java/lang/Record", "net/minecraftforge/client/event/EntityRenderersEvent"),
                "net/minecraftforge/client/event/EntityRenderersEvent", List.of("java/lang/Object"),
                clientSetup, List.of(parallel),
                parallel, List.of(lifecycle),
                lifecycle, List.of("java/lang/Object", ForgeHostShape.MOD_BUS_EVENT)));
        assertTrue(ForgeHostShape.isOutsideModBus(keys, offline, forge262), "EventBus types never lead to FML's marker");
        assertTrue(ForgeHostShape.isOutsideModBus(renderers, offline, forge262),
                "a Forge interface without the marker keeps the answer, though the list leaves it out");
        assertFalse(ForgeHostShape.isOutsideModBus(clientSetup, offline, forge262), "the marker two Forge classes up counts");
        assertFalse(ForgeHostShape.isOutsideModBus("net/minecraftforge/client/event/RegisterGuiOverlaysEvent", offline,
                forge262), "a class the host lacks is no moved event");
        assertFalse(ForgeHostShape.isOutsideModBus(clientSetup, offline, LegacyModBusEventsTest.host(Map.of(
                clientSetup, List.of(parallel)))), "a Forge supertype that cannot be read leaves the class with FML");

        Function<String, ClassNode> offlineRun = host();
        assertTrue(ForgeHostShape.isOutsideModBus(keys, offline, offlineRun), "offline, the checked list answers");
        assertFalse(ForgeHostShape.isOutsideModBus(renderers, offline, offlineRun));
        RetromodVersion.TARGET_MC_VERSION = "26.1";
        assertFalse(ForgeHostShape.isOutsideModBus(keys, offline, offlineRun), "the list holds from Forge 26.2");
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        McReflect.setForceNeoForge(true);
        assertFalse(ForgeHostShape.isOutsideModBus(keys, offline, forge262), "NeoForge never takes a Forge bridge");
    }

    private static boolean lacks(String name, Function<String, ClassNode> host) {
        return ForgeHostShape.matches(List.of(), List.of(name), ForgeHostShape.FIRST_CHECKED_VERSION, host);
    }

    /** A host that ships exactly these classes. */
    private static Function<String, ClassNode> host(String... classes) {
        Set<String> shipped = Set.of(classes);
        return name -> {
            if (!shipped.contains(name)) return null;
            ClassNode node = new ClassNode();
            node.name = name;
            return node;
        };
    }
}
