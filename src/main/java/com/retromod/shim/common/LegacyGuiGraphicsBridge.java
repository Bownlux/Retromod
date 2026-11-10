/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;

/** Points the 1.21.1 GuiGraphics calls that 1.21.2 and 1.21.6 removed at {@code LegacyGuiGraphics}. */
public final class LegacyGuiGraphicsBridge {

    static final String HELPER = "com/retromod/polyfill/minecraft/LegacyGuiGraphics";
    private static final String GUI_GRAPHICS = "net/minecraft/client/gui/GuiGraphics";
    private static final String EXTRACTOR = "net/minecraft/client/gui/GuiGraphicsExtractor";
    private static final String OBJECT = "Ljava/lang/Object;";
    private static final String BUFFER_SOURCE = "Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;";

    private LegacyGuiGraphicsBridge() {}

    /** setColor, the texture and sprite blits, and bufferSource, which 1.21.2 removed or reshaped. */
    public static void register1212(RetromodTransformer t) {
        Common_1_21_11_to_26_1_ClassMoves.ensureSyntheticRegistered(t, HELPER);
        // The receiver becomes the helper's first argument. Each key names a method no later host
        // has, so a newer mod's own draw calls never match one.
        t.registerMethodRedirect(GUI_GRAPHICS, "setColor", "(FFFF)V",
                HELPER, "setColor", "(" + OBJECT + "FFFF)V");
        // 1.21.11 renamed ResourceLocation to Identifier, and a call names the one its host has.
        for (String id : new String[]{"Lnet/minecraft/resources/ResourceLocation;",
                "Lnet/minecraft/resources/Identifier;"}) {
            redirect(t, "blit", id, "IIFFIIII", "blit");
            redirect(t, "blit", id, "IIIIII", "blitTile");
            redirect(t, "blit", id, "IIIFFIIII", "blitAtDepth");
            redirect(t, "blit", id, "IIIIFFIIII", "blitScaled");
            redirect(t, "blitSprite", id, "IIII", "blitSprite");
        }
        // 1.21.6 queues GUI drawing for the end of the frame and has no buffer source to hand out.
        if (RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "1.21.6") < 0) {
            t.registerMethodRedirect(GUI_GRAPHICS, "bufferSource", "()" + BUFFER_SOURCE,
                    HELPER, "bufferSource", "(" + OBJECT + ")" + OBJECT);
        }
    }

    /** flush, which 1.21.6 removed: nothing is drawn before the end of the frame any more. */
    public static void register1216(RetromodTransformer t) {
        // A dropped call matches its owner exactly, and 26.1 renamed the class.
        String owner = RetromodVersion.isUnobfuscatedTarget(RetromodVersion.TARGET_MC_VERSION)
                ? EXTRACTOR : GUI_GRAPHICS;
        t.registerRemovedMethodNeutralize(owner, "flush", "()V");
    }

    private static void redirect(RetromodTransformer t, String name, String id, String ints, String helperName) {
        t.registerMethodRedirect(GUI_GRAPHICS, name, "(" + id + ints + ")V",
                HELPER, helperName, "(" + OBJECT + OBJECT + ints + ")V");
    }
}
