/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.Opcodes;

/**
 * GUI drawing and key polling calls that 1.21.4-era mods make and 26.1 changed shape.
 *
 * <p>{@code GuiGraphics.drawString} became {@code GuiGraphicsExtractor.text}, which returns
 * nothing where the old method returned the end x. The rename alone left an {@code int} return
 * the host does not have, so the screen died on its first frame (#296, Phantom Shapes). The call
 * now returns 0 in its place; callers in the corpus discard it. The sprite blit that took a
 * render-type factory and the window-handle key poll go to
 * {@link com.retromod.polyfill.minecraft.LegacyGuiCalls}.
 */
public final class LegacyGuiDrawBridge {

    private static final String EXTRACTOR = "net/minecraft/client/gui/GuiGraphicsExtractor";
    private static final String GUI_CALLS = "com/retromod/polyfill/minecraft/LegacyGuiCalls";

    private LegacyGuiDrawBridge() {}

    public static void register(RetromodTransformer t) {
        String font = "Lnet/minecraft/client/gui/Font;";
        for (String text : new String[]{"Ljava/lang/String;", "Lnet/minecraft/network/chat/Component;",
                "Lnet/minecraft/util/FormattedCharSequence;"}) {
            for (String tail : new String[]{"III", "IIIZ"}) {
                String args = "(" + font + text + tail + ")";
                for (String oldName : new String[]{"drawString", "text"}) {
                    t.registerConvertingRedirect(EXTRACTOR, oldName, args + "I",
                            EXTRACTOR, "text", args + "V", 0, Opcodes.ICONST_0);
                }
            }
        }

        Common_1_21_11_to_26_1_ClassMoves.ensureSyntheticRegistered(t, GUI_CALLS);
        // The receiver becomes the first argument (auto-devirtualized redirect).
        t.registerMethodRedirect(EXTRACTOR, "blitSprite",
                "(Ljava/util/function/Function;Lnet/minecraft/resources/Identifier;IIII)V",
                GUI_CALLS, "blitSprite",
                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;IIII)V");
        t.registerMethodRedirect("com/mojang/blaze3d/platform/InputConstants", "isKeyDown", "(JI)Z",
                GUI_CALLS, "isKeyDown", "(JI)Z");
    }
}
