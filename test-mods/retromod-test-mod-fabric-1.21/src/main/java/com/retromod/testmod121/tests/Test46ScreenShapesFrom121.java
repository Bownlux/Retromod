/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod121.tests;

import com.retromod.testmod121.RetromodTestMod121;
import com.retromod.testmod121.Test;
import com.retromod.testmod121.TestResult;
import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.rendering.v1.GatherDebugTextEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudLayerRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.IdentifiedLayer;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/** HUD, tooltip and debug text shapes from 1.21 to 1.21.8, compiled against stand-ins where 1.21.1 lacks them. */
public final class Test46ScreenShapesFrom121 implements Test {

    static final String TOOLTIP = "retromod-test 1.21 tooltip";
    private static int hudCalls;
    // The listeners stay registered, so they add lines only while this test fires the event.
    private static boolean firing;

    @Override
    public String description() {
        return "1.21 HUD, tooltip and debug text shapes, and on a client the 1.21.4 to 1.21.8 HUD ids";
    }

    @Override
    public TestResult run() {
        // The 1.20.5 tooltip shape keeps the host's own event.
        ItemTooltipCallback.EVENT.register((stack, tooltipContext, type, lines) -> {
            if (firing) lines.add(Text.literal(TOOLTIP));
        });
        List<Text> lines = new ArrayList<>();
        firing = true;
        try {
            ItemTooltipCallback.EVENT.invoker().getTooltip(ItemStack.EMPTY, null, null, lines);
        } finally {
            firing = false;
        }
        if (lines.size() != 1 || !TOOLTIP.equals(lines.get(0).getString())) {
            return TestResult.fail("the tooltip event gave " + lines);
        }

        // The 1.21.5 debug text events, read through their old invokers.
        GatherDebugTextEvents.LEFT.register(left -> left.add("left"));
        GatherDebugTextEvents.RIGHT.register(right -> right.add("right"));
        List<String> left = new ArrayList<>();
        List<String> right = new ArrayList<>();
        GatherDebugTextEvents.LEFT.invoker().onGatherLeftDebugText(left);
        GatherDebugTextEvents.RIGHT.invoker().onGatherRightDebugText(right);
        if (!left.equals(List.of("left")) || !right.equals(List.of("right"))) {
            return TestResult.fail("the debug text events gave " + left + " and " + right);
        }

        String clientProblem = FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT
                ? ClientShapes.place() : null;
        return clientProblem == null ? TestResult.success() : TestResult.fail(clientProblem);
    }

    /** The HUD shapes a client places; a dedicated server has no GUI classes to build a HUD lambda from. */
    static final class ClientShapes {
        static String place() {
            // The 1.21 HUD shape, fired through the old invoker as a mod drawing into its own screen did.
            HudRenderCallback.EVENT.register((context, tickCounter) -> hudCalls++);
            HudRenderCallback.EVENT.invoker().onHudRender(null, null);
            if (hudCalls != 1) return "the 1.21 HUD callback ran " + hudCalls + " times";

            Identifier layer = Identifier.of(RetromodTestMod121.MOD_ID, "s1_after_bars");
            Identifier beforeChat = Identifier.of(RetromodTestMod121.MOD_ID, "s1_before_chat");
            // The 1.21.4 API fires once the client has started; Retromod logs where each layer went.
            HudLayerRegistrationCallback.EVENT.register(drawer -> {
                drawer.attachLayerAfter(IdentifiedLayer.HOTBAR_AND_BARS, IdentifiedLayer.of(layer, (context, tick) -> { }));
                drawer.attachLayerBefore(IdentifiedLayer.CHAT, beforeChat, (context, tick) -> { });
                drawer.removeLayer(IdentifiedLayer.DEBUG);
            });
            // The 1.21.6 ids 26.x removed: the debug overlay and the renamed status effects.
            HudElementRegistry.attachElementBefore(VanillaHudElements.DEBUG,
                    Identifier.of(RetromodTestMod121.MOD_ID, "s1_before_debug"), (context, tick) -> { });
            HudElementRegistry.attachElementAfter(VanillaHudElements.STATUS_EFFECTS,
                    Identifier.of(RetromodTestMod121.MOD_ID, "s1_after_effects"), (context, tick) -> { });
            return null;
        }
    }
}
