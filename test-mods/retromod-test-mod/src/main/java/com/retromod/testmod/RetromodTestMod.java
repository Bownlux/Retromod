/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

/**
 * Fabric client entry point. Three lifecycle hooks, three phases:
 *
 * <ol>
 *   <li><b>onInitializeClient</b> - runs the {@code init}-phase tests
 *       (core APIs, static registries, math, NBT, etc.).</li>
 *   <li><b>ClientLifecycleEvents.CLIENT_STARTED</b> - runs the
 *       {@code client-started}-phase tests (data-component-dependent stuff
 *       like {@code new ItemStack}).</li>
 *   <li><b>ClientPlayConnectionEvents.JOIN</b> - runs the {@code world-join}
 *       phase tests (dynamic-registry stuff like enchantments and mob
 *       effects in MC 1.21+, where they're data-driven).</li>
 * </ol>
 *
 * <p>The {@code TestRunner} is loader-agnostic; a Forge or NeoForge wrapper
 * added later can hook the same three phases through their own event
 * systems and call the same three methods.
 */
public class RetromodTestMod implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // Phase 1: init - most tests (vanilla MC surface only, no Fabric API)
        TestRunner.runImmediate();

        // Phases 2+3 hook Fabric API events - but Fabric API can legitimately
        // be ABSENT (a brand-new MC snapshot before any fabric-api build is
        // tagged for it; 26.2-rc-1 was the first to bite). The harness must
        // degrade to phase-1-only there, not crash the whole game with a
        // NoClassDefFoundError out of the client entrypoint. The references
        // live in a separate method so this method never resolves them.
        try {
            registerDeferredPhases();
        } catch (NoClassDefFoundError e) {
            System.err.println("[Retromod-Test] Fabric API not installed - "
                    + "client-started and world-join test phases skipped ("
                    + e.getMessage() + ")");
        }

        // Bridge verification: registers listeners through the OLD (1.20.1)
        // Fabric APIs this mod is compiled against, exercising Retromod's
        // bridges end-to-end on 26.1+ hosts. Same fabric-api-absent guard.
        try {
            BridgeVerification.registerAll();
        } catch (NoClassDefFoundError e) {
            System.err.println("[Retromod-Test] Fabric API not installed - "
                    + "bridge verification skipped (" + e.getMessage() + ")");
        }
    }

    private void registerDeferredPhases() {
        // Phase 2: client-started - fires once, right before the title screen.
        // Static + data-component registries are fully bootstrapped by then.
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> TestRunner.runOnClientStarted());

        // Phase 3: world-join - fires every time the player joins a world or
        // server. Dynamic registries are populated by then. Idempotent: if
        // you join multiple worlds the suite just runs each time.
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> TestRunner.runOnWorldJoin());

        // The client half of the old channel echo; the server half runs at server start.
        com.retromod.testmod.tests.Test38ClientChannelEcho.install();
    }
}
