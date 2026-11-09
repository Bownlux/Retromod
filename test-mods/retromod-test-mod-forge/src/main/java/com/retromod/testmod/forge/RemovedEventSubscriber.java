/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * An old subscriber class with one static handler on an event Forge 26.2 removed. On a 26.2
 * client the mod must still load, with one Retromod warning naming {@code onRenderStage}, instead
 * of FML failing the whole mod on a handler it cannot bind.
 */
@Mod.EventBusSubscriber(modid = "retromod_test_mod_forge", value = Dist.CLIENT)
public final class RemovedEventSubscriber {

    private RemovedEventSubscriber() {}

    @SubscribeEvent
    public static void onRenderStage(RenderLevelStageEvent event) {
        // Never reached on 26.2; the class only has to leave the mod loading.
    }
}
