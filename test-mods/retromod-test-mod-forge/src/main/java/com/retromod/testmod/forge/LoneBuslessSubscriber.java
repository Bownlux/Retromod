/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import java.util.ArrayList;
import java.util.List;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.LoggerFactory;

/** Lootr's shape: one static handler in a subscriber class, on ScreenEvent.Render, which Forge 26.2 posts as Pre and Post. */
@Mod.EventBusSubscriber(modid = "retromod_test_mod_forge", value = Dist.CLIENT)
public final class LoneBuslessSubscriber {

    private static final List<String> SEEN = new ArrayList<>();

    private LoneBuslessSubscriber() {}

    @SubscribeEvent
    public static void onScreenRender(ScreenEvent.Render event) {
        // Logs once, on the first Post, whether a Pre came first, as Forge 1.20.1 posted both each frame.
        if (SEEN.contains("done")) return;
        SEEN.add(event instanceof ScreenEvent.Render.Pre ? "pre" : "post");
        if (!(event instanceof ScreenEvent.Render.Post)) return;
        LoggerFactory.getLogger("Retromod-Test-Forge").info("[Retromod-Test-Forge] {}: {}",
                "(l) A lone static @SubscribeEvent method on ScreenEvent.Render in a subscriber class hears Render.Pre and then Render.Post (first frame)",
                SEEN.get(0).equals("pre") ? "success" : "fail: heard " + SEEN);
        SEEN.add("done");
    }
}
