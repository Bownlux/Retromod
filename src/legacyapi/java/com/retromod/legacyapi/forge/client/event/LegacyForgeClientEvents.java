/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.client.event;

import com.retromod.legacyapi.forge.client.LegacyClientRenderBootstrap;
import com.retromod.shim.common.embedded.LegacyLog;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.RenderAvatarEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/** Posts Forge 1.20.1's shader and player render events, which Forge 26.2 removed, for each old mod that uses them. */
public final class LegacyForgeClientEvents {

    private static boolean listening;
    // The render state whose Pre failed, so its Post is skipped; both run on the render thread.
    private static Object failedPre;

    private LegacyForgeClientEvents() {}

    /** Called first in each old {@code @Mod} constructor of a jar that names the old events; on a client only. */
    public static void install() {
        LegacyClientRenderBootstrap.install();
        if (!LegacyClientRenderBootstrap.isClient()) return;
        try {
            // Client classes are reached only from here, so a dedicated server never loads them.
            Client.listen();
        } catch (RuntimeException | LinkageError failure) {
            LegacyLog.warn("Retromod did not set up the old shader and player render events of the mod constructing now: " + failure);
        }
    }

    private static final class Client {
        static void listen() {
            java.util.List<String> mods = LegacyClientRenderBootstrap.namespaces();
            BusGroup first = mods.isEmpty() ? null : LegacyClientRenderBootstrap.modBusGroup(mods.get(0));
            synchronized (LegacyForgeClientEvents.class) {
                if (listening || first == null) return;
                listening = true;
            }
            // The old event came with the first shader load, on the main thread, so it is queued there from client setup,
            // which runs on worker threads. Each mod of this copy gets it on its own group.
            FMLClientSetupEvent.getBus(first).addListener(event -> event.enqueueWork(() -> {
                for (String namespace : LegacyClientRenderBootstrap.namespaces()) {
                    BusGroup group = LegacyClientRenderBootstrap.modBusGroup(namespace);
                    if (group == null) continue;
                    try {
                        RegisterShadersEvent.postTo(group, Minecraft.getInstance().getResourceManager());
                    } catch (RuntimeException | LinkageError failure) {
                        LegacyLog.warn("An old shader listener of " + namespace + " failed: " + failure);
                    }
                }
            }));
            RenderAvatarEvent.Pre.BUS.addListener((java.util.function.Predicate<RenderAvatarEvent.Pre>) event -> post(true, event));
            RenderAvatarEvent.Post.BUS.addListener(event -> {
                post(false, event);
            });
        }

        // A listener that throws must not stop the player draw, so the failure is noted once.
        private static boolean post(boolean pre, RenderAvatarEvent event) {
            PoseStack pose = event.getPoseStack();
            if (!pre && event.getState() == failedPre) {
                failedPre = null;
                return false;
            }
            PoseStack.Pose mark = pose.last();
            try {
                return RenderPlayerEvent.post(pre, event.getState(), event.getRenderer(), pose, frameTick());
            } catch (RuntimeException | LinkageError failure) {
                // A listener that pushed and then threw leaves the stack deeper, which the level renderer refuses afterwards.
                while (pose.last() != mark && !pose.isEmpty()) pose.popPose();
                if (pre) failedPre = event.getState();
                LegacyLog.warnOnce("render player " + LegacyForgeClientEvents.class.getName(), "An old player render listener failed: " + failure);
                return false;
            }
        }

        private static float frameTick() {
            try {
                return Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
            } catch (RuntimeException | LinkageError none) {
                return 0f;
            }
        }
    }
}
