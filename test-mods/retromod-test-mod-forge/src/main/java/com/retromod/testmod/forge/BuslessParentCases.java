/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import java.util.ArrayList;
import java.util.List;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.LoggerFactory;

/**
 * Old listeners on ScreenEvent.Init and ScreenEvent.Render, which Forge 26.2 never posts itself: Forge 1.20.1 handed
 * them Pre and Post, as Create's config button, Jade's screen hook and Citadel's screen listeners rely on. Each logs
 * once, when the first screen has posted both. Client only.
 */
public final class BuslessParentCases {

    private static final List<String> METHOD = new ArrayList<>();
    private static final List<String> LAMBDA = new ArrayList<>();
    private static final List<String> RENDER = new ArrayList<>();

    private BuslessParentCases() {}

    static void register() {
        try {
            MinecraftForge.EVENT_BUS.register(BuslessParentCases.class);
            MinecraftForge.EVENT_BUS.addListener(BuslessParentCases::onInitLambda);
            MinecraftForge.EVENT_BUS.register(new RenderListener());
        } catch (Throwable t) {
            log("Old listeners on ScreenEvent.Init register (construction)",
                    "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    @SubscribeEvent
    public static void onInit(ScreenEvent.Init event) {
        note(METHOD, event, "(h) An old @SubscribeEvent method on ScreenEvent.Init hears Init.Pre and then Init.Post (first screen)");
    }

    private static void onInitLambda(ScreenEvent.Init event) {
        note(LAMBDA, event, "(i) An old addListener lambda on ScreenEvent.Init hears Init.Pre and then Init.Post (first screen)");
    }

    /** Citadel's shape: an object's handler on ScreenEvent.Render, which Forge 26.2 posts only as Render.Pre and Render.Post. */
    public static final class RenderListener {
        @SubscribeEvent
        public void onRender(ScreenEvent.Render event) {
            if (RENDER.contains("done")) return;
            RENDER.add(event instanceof ScreenEvent.Render.Pre ? "pre" : "post");
            if (!(event instanceof ScreenEvent.Render.Post)) return;
            log("(k) An old object's @SubscribeEvent method on ScreenEvent.Render hears Render.Pre and then Render.Post (first frame)",
                    RENDER.get(0).equals("pre") && RENDER.contains("post") ? "success" : "fail: heard " + RENDER);
            RENDER.add("done");
        }
    }

    // Logs once, on the first Post, whether the Pre of the same screen came first.
    private static void note(List<String> seen, ScreenEvent.Init event, String what) {
        if (seen.contains("done")) return;
        seen.add(event instanceof ScreenEvent.Init.Pre ? "pre" : "post");
        if (!(event instanceof ScreenEvent.Init.Post)) return;
        log(what, seen.equals(List.of("pre", "post")) ? "success" : "fail: heard " + seen);
        seen.add("done");
    }

    private static void log(String what, String result) {
        LoggerFactory.getLogger("Retromod-Test-Forge").info("[Retromod-Test-Forge] {}: {}", what, result);
    }
}
