/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.Opcodes;

/** GUI drawing and key polling calls that 1.21.4-era mods make and 1.21.6 or 26.1 changed shape. */
public final class LegacyGuiDrawBridge {

    private static final String GUI_GRAPHICS = "net/minecraft/client/gui/GuiGraphics";
    private static final String EXTRACTOR = "net/minecraft/client/gui/GuiGraphicsExtractor";
    private static final String GUI_CALLS = "com/retromod/polyfill/minecraft/LegacyGuiCalls";
    private static final String RESOURCE_LOCATION = "Lnet/minecraft/resources/ResourceLocation;";
    private static final String IDENTIFIER = "Lnet/minecraft/resources/Identifier;";

    private LegacyGuiDrawBridge() {}

    /** The 26.1 shapes, where GuiGraphics is GuiGraphicsExtractor and drawString is text. */
    public static void register(RetromodTransformer t) {
        registerVoidText(t, EXTRACTOR, "text", "drawString", "text");
        registerFactorySpriteBlit(t, EXTRACTOR, IDENTIFIER);
        registerWindowHandleKeyPoll(t);
    }

    /** Sends the window-handle key poll 1.21.9 replaced to the polyfill. The 1.21.9 shims call this too. */
    public static void registerWindowHandleKeyPoll(RetromodTransformer t) {
        // LegacyGuiCalls asks the host which isKeyDown it has: (Window, int) from 1.21.9, (int) from 26.3.
        Common_1_21_11_to_26_1_ClassMoves.ensureSyntheticRegistered(t, GUI_CALLS);
        t.registerMethodRedirect("com/mojang/blaze3d/platform/InputConstants", "isKeyDown", "(JI)Z",
                GUI_CALLS, "isKeyDown", "(JI)Z");
    }

    /** The same text and sprite changes on 1.21.6 to 1.21.11, under their old class and method names. */
    public static void register1216(RetromodTransformer t) {
        // 26.1 renamed both again and register() covers those hosts, so the two never compete.
        if (RetromodVersion.isUnobfuscatedTarget(RetromodVersion.TARGET_MC_VERSION)) return;
        registerVoidText(t, GUI_GRAPHICS, "drawString", "drawString");
        // 1.21.11 renamed ResourceLocation to Identifier, and a call names the one its host has.
        registerFactorySpriteBlit(t, GUI_GRAPHICS, RESOURCE_LOCATION);
        registerFactorySpriteBlit(t, GUI_GRAPHICS, IDENTIFIER);
    }

    /** drawString stopped returning the end x in 1.21.6. */
    private static void registerVoidText(RetromodTransformer t, String owner, String newName, String... oldNames) {
        String font = "Lnet/minecraft/client/gui/Font;";
        for (String text : new String[]{"Ljava/lang/String;", "Lnet/minecraft/network/chat/Component;",
                "Lnet/minecraft/util/FormattedCharSequence;"}) {
            for (String tail : new String[]{"III", "IIIZ"}) {
                String args = "(" + font + text + tail + ")";
                // The caller still pops an int, so 0 takes the end x's place. Without it the screen
                // died on its first frame (#296); callers in the corpus discard the value.
                for (String oldName : oldNames) {
                    t.registerConvertingRedirect(owner, oldName, args + "I",
                            owner, newName, args + "V", 0, Opcodes.ICONST_0);
                }
            }
        }
    }

    /** blitSprite with a render-type factory, which 1.21.6 replaced with a render pipeline. */
    private static void registerFactorySpriteBlit(RetromodTransformer t, String owner, String id) {
        Common_1_21_11_to_26_1_ClassMoves.ensureSyntheticRegistered(t, GUI_CALLS);
        // The receiver becomes the first argument (auto-devirtualized redirect).
        t.registerMethodRedirect(owner, "blitSprite",
                "(Ljava/util/function/Function;" + id + "IIII)V",
                GUI_CALLS, "blitSprite",
                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;IIII)V");
    }
}
