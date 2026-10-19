/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Old 1.20.1 HUD and screen render callbacks on a client; each logs its first frame, which a result cannot wait for. */
public final class Test45ClientHudAndScreenEvents implements Test {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-Test");
    private static int hudLambda;
    private static int hudClass;
    private static float lastTickDelta = -1;
    private static boolean beforeLogged;
    private static boolean afterLogged;
    private static boolean hudLogged;

    @Override
    public String description() {
        return "old HUD callbacks of the 1.20 shape and old screen render callbacks";
    }

    @Override
    public TestResult run() {
        HudRenderCallback.EVENT.register((context, tickDelta) -> {
            hudLambda++;
            lastTickDelta = tickDelta;
            if (context != null && !hudLogged) {
                hudLogged = true;
                LOGGER.info("[Retromod-Test] Test45: the old HUD callback drew a frame");
            }
        });
        HudRenderCallback.EVENT.register(new ClassHud());
        // A mod that draws its HUD into its own screen fires the old invoker itself.
        HudRenderCallback.EVENT.invoker().onHudRender(null, 0.5f);
        if (hudLambda != 1 || hudClass != 1 || lastTickDelta != 0.5f) {
            return TestResult.fail("the old invoker reached " + hudLambda + " lambda and " + hudClass
                    + " class callbacks with partial tick " + lastTickDelta);
        }

        // Screen render callbacks on the screen open now and on every screen after it.
        Screen open = MinecraftClient.getInstance().currentScreen;
        if (open != null) watch(open);
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> watch(screen));
        return TestResult.success();
    }

    private static void watch(Screen screen) {
        ScreenEvents.beforeRender(screen).register((current, context, mouseX, mouseY, tickDelta) -> {
            if (!beforeLogged) {
                beforeLogged = true;
                LOGGER.info("[Retromod-Test] Test45: the old before-render callback ran on {}", current);
            }
        });
        ScreenEvents.afterRender(screen).register(new AfterRender());
    }

    /** The old HUD callback as a class, which keeps its 1.20 method. */
    static final class ClassHud implements HudRenderCallback {
        @Override
        public void onHudRender(DrawContext context, float tickDelta) {
            hudClass++;
        }
    }

    /** The old after-render callback as a class. */
    static final class AfterRender implements ScreenEvents.AfterRender {
        @Override
        public void afterRender(Screen screen, DrawContext context, int mouseX, int mouseY, float tickDelta) {
            if (!afterLogged) {
                afterLogged = true;
                LOGGER.info("[Retromod-Test] Test45: the old after-render callback ran on {}", screen);
            }
        }
    }
}
