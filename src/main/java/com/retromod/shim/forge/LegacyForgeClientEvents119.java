/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;

/**
 * Follows Forge 1.19's renames of the client events whose shape did not change.
 *
 * <p>A handler parameter that names a deleted event class makes Forge reject every automatic
 * subscriber in that class, so one stale camera or key handler silently switched off a mod's other
 * client handlers too. The camera, fog color, key and screen opening events kept their contract
 * under new names. The camera's partial tick getter and the opened screen's setter were renamed. Events whose registration moved to a different bus, such as overlays, key
 * mappings and particle providers, are not renamed here.
 */
final class LegacyForgeClientEvents119 {

    private static final String CLIENT_EVENT = "net/minecraftforge/client/event/";
    private static final String SCREEN = "Lnet/minecraft/client/gui/screens/Screen;";

    private LegacyForgeClientEvents119() {
    }

    static void register(RetromodTransformer transformer) {
        String[][] moves = {
            {"EntityViewRenderEvent", "ViewportEvent"},
            {"EntityViewRenderEvent$CameraSetup", "ViewportEvent$ComputeCameraAngles"},
            {"EntityViewRenderEvent$FogColors", "ViewportEvent$ComputeFogColor"},
            {"InputEvent$KeyInputEvent", "InputEvent$Key"},
            {"ScreenOpenEvent", "ScreenEvent$Opening"},
        };
        for (String[] move : moves) {
            transformer.registerClassRedirect(CLIENT_EVENT + move[0], CLIENT_EVENT + move[1]);
        }
        for (String viewport : new String[]{"EntityViewRenderEvent", "EntityViewRenderEvent$CameraSetup",
                "EntityViewRenderEvent$FogColors"}) {
            transformer.registerMethodRedirect(CLIENT_EVENT + viewport, "getPartialTicks", "()D",
                    CLIENT_EVENT + "ViewportEvent", "getPartialTick", "()D");
        }
        // getScreen is left alone: Opening inherits it from ScreenEvent, and a redirect would also
        // rewrite a newer mod's own Opening.getScreen() call, since the class move makes the shapes equal.
        transformer.registerMethodRedirect(CLIENT_EVENT + "ScreenOpenEvent", "setScreen", "(" + SCREEN + ")V",
                CLIENT_EVENT + "ScreenEvent$Opening", "setNewScreen", "(" + SCREEN + ")V");
    }
}
