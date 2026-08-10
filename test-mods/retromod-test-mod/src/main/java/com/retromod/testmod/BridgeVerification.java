/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityFeatureRendererRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.TooltipComponentCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.registry.LandPathNodeTypesRegistry;
import net.fabricmc.fabric.api.tag.convention.v1.ConventionalItemTags;
import net.minecraft.block.Blocks;
import net.minecraft.client.model.ModelData;
import net.minecraft.client.model.TexturedModelData;
import net.minecraft.client.render.entity.model.EntityModelLayer;
import net.minecraft.entity.ai.pathing.PathNodeType;
import net.minecraft.util.Identifier;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * End-to-end verification of Retromod's Fabric API bridges - the rc.1 gate.
 *
 * <p>This class is compiled against fabric-api 0.92.0 (1.20.1), i.e. the REAL
 * old APIs, so the shipped jar carries genuine old-mod bytecode: intermediary
 * MC names, old fabric-api class names, and real {@code invokedynamic} lambda
 * sites against the old SAM methods. On a 26.1+ host Retromod must carry every
 * one of these through its bridges (the renamed-SAM synthetics, the reflective
 * event holders, the registry renames) - so each check here proves a bridge
 * end-to-end, not just "the class resolved".
 *
 * <p>Two result levels per bridge:
 * <ul>
 *   <li><b>registered</b> - the registration call went through the bridge
 *       without throwing (the lambda LINKED against the old SAM and the event
 *       accepted it - this alone catches every lambda-trap regression);</li>
 *   <li><b>FIRED</b> - the listener was actually invoked by the game. Some
 *       events need gameplay that a boot test can't produce (dimension change,
 *       tooltip hover); those legitimately stay at "registered".</li>
 * </ul>
 *
 * <p>Logs {@code [Retromod-Test] [bridges] ...} lines; the final summary prints
 * ~10s after world join.
 */
public final class BridgeVerification {

    /** insertion-ordered: name -> state ("FAILED: ..", "registered", "FIRED") */
    private static final Map<String, String> STATE = new LinkedHashMap<>();
    private static boolean finalReportDone = false;
    private static int ticksSinceJoin = -1;

    private BridgeVerification() {}

    private static void ok(String name) { STATE.put(name, "registered"); }
    private static void fail(String name, Throwable t) {
        STATE.put(name, "FAILED: " + t.getClass().getSimpleName() + ": " + t.getMessage());
    }
    private static void fired(String name) {
        if (!"FIRED".equals(STATE.get(name))) {
            STATE.put(name, "FIRED");
            System.out.println("[Retromod-Test] [bridges] " + name + ": FIRED");
        }
    }

    /** Call from onInitializeClient. Every registration goes through a Retromod bridge on 26.1+. */
    public static void registerAll() {
        // ── ItemGroupEvents (reflective holder + SAM rename + entry-method renames)
        try {
            ItemGroupEvents.MODIFY_ENTRIES_ALL.register((group, entries) -> fired("ItemGroupEvents.MODIFY_ENTRIES_ALL"));
            ok("ItemGroupEvents.MODIFY_ENTRIES_ALL");
        } catch (Throwable t) { fail("ItemGroupEvents.MODIFY_ENTRIES_ALL", t); }

        // ── ServerWorldEvents (holder fields + onWorldLoad SAM rename)
        try {
            ServerWorldEvents.LOAD.register((server, world) -> fired("ServerWorldEvents.LOAD"));
            ok("ServerWorldEvents.LOAD");
        } catch (Throwable t) { fail("ServerWorldEvents.LOAD", t); }

        // ── ServerEntityWorldChangeEvents (holder fields + afterChangeWorld SAM rename)
        try {
            ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register(
                    (player, origin, destination) -> fired("ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD"));
            ok("ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD"); // fires on dimension change only
        } catch (Throwable t) { fail("ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD", t); }

        // Tick-event holder fields renamed World -> Level with their callback interfaces in 26.1.
        // Pathmind hit START_WORLD_TICK during client init, so registration itself is the regression.
        try {
            ClientTickEvents.START_WORLD_TICK.register(world -> fired("ClientTickEvents.START_WORLD_TICK"));
            ClientTickEvents.END_WORLD_TICK.register(world -> fired("ClientTickEvents.END_WORLD_TICK"));
            ok("ClientTickEvents.START_WORLD_TICK");
            ok("ClientTickEvents.END_WORLD_TICK");
        } catch (Throwable t) {
            fail("ClientTickEvents.START_WORLD_TICK", t);
            fail("ClientTickEvents.END_WORLD_TICK", t);
        }
        try {
            ServerTickEvents.START_WORLD_TICK.register(world ->
                    fired("ServerTickEvents.START_WORLD_TICK"));
            ServerTickEvents.END_WORLD_TICK.register(world ->
                    fired("ServerTickEvents.END_WORLD_TICK"));
            ok("ServerTickEvents.START_WORLD_TICK");
            ok("ServerTickEvents.END_WORLD_TICK");
        } catch (Throwable t) {
            fail("ServerTickEvents.START_WORLD_TICK", t);
            fail("ServerTickEvents.END_WORLD_TICK", t);
        }

        // ── HudRenderCallback (extends-HudElement synthetic; fires every HUD frame in a world)
        try {
            HudRenderCallback.EVENT.register((ctx, delta) -> fired("HudRenderCallback.EVENT"));
            ok("HudRenderCallback.EVENT");
        } catch (Throwable t) { fail("HudRenderCallback.EVENT", t); }

        // ── EntityModelLayerRegistry (createModelData SAM rename; provider bakes at resource load)
        try {
            EntityModelLayerRegistry.registerModelLayer(
                    new EntityModelLayer(new Identifier("retromod_test", "bridge_probe"), "main"),
                    () -> {
                        fired("EntityModelLayerRegistry.registerModelLayer");
                        return TexturedModelData.of(new ModelData(), 16, 16);
                    });
            ok("EntityModelLayerRegistry.registerModelLayer");
        } catch (Throwable t) { fail("EntityModelLayerRegistry.registerModelLayer", t); }

        // ── LivingEntityFeatureRendererRegistrationCallback (registerRenderers → registerLayers synthetic)
        try {
            LivingEntityFeatureRendererRegistrationCallback.EVENT.register(
                    (entityType, renderer, helper, context) ->
                            fired("LivingEntityFeatureRendererRegistrationCallback.EVENT"));
            ok("LivingEntityFeatureRendererRegistrationCallback.EVENT");
        } catch (Throwable t) { fail("LivingEntityFeatureRendererRegistrationCallback.EVENT", t); }

        // ── TooltipComponentCallback (getComponent → getClientComponent synthetic; fires on tooltip hover only)
        try {
            TooltipComponentCallback.EVENT.register(data -> null);
            ok("TooltipComponentCallback.EVENT");
        } catch (Throwable t) { fail("TooltipComponentCallback.EVENT", t); }

        // ── LandPathNodeTypesRegistry (class + provider-SAM + getter renames)
        try {
            LandPathNodeTypesRegistry.register(Blocks.SPONGE, PathNodeType.DAMAGE_OTHER, PathNodeType.DANGER_OTHER);
            ok("LandPathNodeTypesRegistry.register");
        } catch (Throwable t) { fail("LandPathNodeTypesRegistry.register", t); }

        // ── ClientPlayNetworking v1 (raw-bytes bridge: receiver registration; canSend checked at join)
        try {
            ClientPlayNetworking.registerGlobalReceiver(new Identifier("retromod_test", "bridge_probe"),
                    (client, handler, buf, responseSender) -> fired("ClientPlayNetworking.receiver"));
            ok("ClientPlayNetworking.registerGlobalReceiver");
        } catch (Throwable t) { fail("ClientPlayNetworking.registerGlobalReceiver", t); }

        // ── Convention tags v1 (class redirect + field renames) - touching the field IS the test
        try {
            if (ConventionalItemTags.SHEARS != null) {
                STATE.put("ConventionalItemTags.SHEARS", "FIRED"); // resolution = full verification
            }
        } catch (Throwable t) { fail("ConventionalItemTags.SHEARS", t); }

        report("init");

        // Reporting hooks (these use 1.20.1 APIs too - tick + join - so they
        // double as live checks of the tick-event and connection-event paths).
        try {
            ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
                ticksSinceJoin = 0;
                try {
                    if (ClientPlayNetworking.canSend(new Identifier("retromod_test", "bridge_probe"))) {
                        fired("ClientPlayNetworking.canSend");
                    } else {
                        STATE.putIfAbsent("ClientPlayNetworking.canSend", "registered"); // callable, channel absent server-side
                    }
                } catch (Throwable t) { fail("ClientPlayNetworking.canSend", t); }
                report("world-join");
            });
            ClientTickEvents.END_CLIENT_TICK.register(client -> {
                if (ticksSinceJoin >= 0 && !finalReportDone && ++ticksSinceJoin >= 200) {
                    finalReportDone = true;
                    report("final(+10s)");
                }
            });
        } catch (Throwable t) {
            System.out.println("[Retromod-Test] [bridges] reporting hooks failed: " + t);
        }
    }

    private static void report(String phase) {
        int failed = 0, firedN = 0, registered = 0;
        for (Map.Entry<String, String> e : STATE.entrySet()) {
            System.out.println("[Retromod-Test] [bridges] [" + phase + "] " + e.getKey() + ": " + e.getValue());
            if (e.getValue().startsWith("FAILED")) failed++;
            else if (e.getValue().equals("FIRED")) firedN++;
            else registered++;
        }
        System.out.println("[Retromod-Test] [bridges] [" + phase + "] SUMMARY: "
                + firedN + " fired, " + registered + " registered, " + failed + " FAILED of " + STATE.size());
    }
}
