/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.LoggerFactory;

/** Old RenderLevelStageEvent listeners, which Forge 26.2 removed, each logging once whether AFTER_PARTICLES fired. Client only. */
public final class ClientRenderCases {

    // An @SubscribeEvent method and an addListener lambda each log at their first AFTER_PARTICLES stage in a world,
    // or after 200 client ticks in a world without one.
    private static final String METHOD = "An old @SubscribeEvent listener hears RenderLevelStageEvent AFTER_PARTICLES with its"
            + " PoseStack, Camera, Frustum and partial tick (first frame in a world)";
    private static final String LAMBDA = "An old addListener lambda hears RenderLevelStageEvent AFTER_PARTICLES (first frame in a world)";
    private static final int WAIT = 200;

    private static boolean methodReported;
    private static boolean lambdaReported;
    private static int ticksInWorld;

    private ClientRenderCases() {}

    static void register() {
        try {
            MinecraftForge.EVENT_BUS.register(ClientRenderCases.class);
            MinecraftForge.EVENT_BUS.addListener(ClientRenderCases::onStageLambda);
            MinecraftForge.EVENT_BUS.addListener(ClientRenderCases::onClientTick);
        } catch (Throwable t) {
            log("Old RenderLevelStageEvent listeners register (construction)",
                    "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    @SubscribeEvent
    public static void onStage(RenderLevelStageEvent event) {
        if (methodReported || event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        methodReported = true;
        float partialTick = event.getPartialTick();
        String result;
        if (event.getPoseStack() == null) {
            result = "fail: no PoseStack";
        } else if (event.getCamera() == null) {
            result = "fail: no Camera";
        } else if (event.getFrustum() == null) {
            result = "fail: no Frustum";
        } else if (partialTick < 0 || partialTick > 1) {
            result = "fail: partial tick " + partialTick;
        } else {
            result = "success";
        }
        log(METHOD, result);
    }

    private static void onStageLambda(RenderLevelStageEvent event) {
        if (lambdaReported || event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        lambdaReported = true;
        log(LAMBDA, "success");
    }

    // A world that renders for 200 client ticks without the stage means the stage never fired.
    private static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || Minecraft.getInstance().level == null) return;
        if (++ticksInWorld != WAIT) return;
        if (!methodReported) {
            methodReported = true;
            log(METHOD, "fail: no AFTER_PARTICLES stage in " + WAIT + " client ticks in a world");
        }
        if (!lambdaReported) {
            lambdaReported = true;
            log(LAMBDA, "fail: no AFTER_PARTICLES stage in " + WAIT + " client ticks in a world");
        }
    }

    private static void log(String what, String result) {
        LoggerFactory.getLogger("Retromod-Test-Forge").info("[Retromod-Test-Forge] {}: {}", what, result);
    }
}
