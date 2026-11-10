/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.util.McReflect;

/** Bridges 1.21.1 GUI drawing, list entries, buttons and key checks to the 26.x shapes. */
public final class LegacyGuiExtractBridge {

    static final String DRAWS = RebuiltApiClasses.ROOT + "minecraft/client/gui/LegacyGuiDraws";
    public static final String FLAG = "legacy-gui-extract";

    private static final String GUI = "net/minecraft/client/gui/GuiGraphicsExtractor";
    private static final String GUI_ARG = "L" + GUI + ";";
    private static final String RENDER_TYPE_OWNER = "net/minecraft/client/renderer/rendertype/RenderType";
    private static final String RENDER_TYPE = "L" + RENDER_TYPE_OWNER + ";";
    private static final String FONT = "Lnet/minecraft/client/gui/Font;";
    private static final String LIST = "Ljava/util/List;";
    private static final String OPTIONAL = "Ljava/util/Optional;";
    private static final String STACK = "Lnet/minecraft/world/item/ItemStack;";
    private static final String COMPONENT = "Lnet/minecraft/network/chat/Component;";
    private static final String IDENTIFIER = "Lnet/minecraft/resources/Identifier;";
    private static final String SPRITE = "Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;";
    private static final String POSITIONER = "Lnet/minecraft/client/gui/screens/inventory/tooltip/ClientTooltipPositioner;";
    private static final String KEY_MAPPING = "net/minecraft/client/KeyMapping";
    private static final String KEYS = "com/retromod/polyfill/minecraft/RetroKeyMapping";

    private LegacyGuiExtractBridge() {}

    public static void register(RetromodTransformer t) {
        RebuiltApiClasses.registerTree(t, DRAWS);
        registerDraws(t);
        registerTooltips(t);
        registerKeyChecks(t);
        // The pose stack, text selections and widget overrides are adapted after the remap, on every path.
        t.enablePostProcess(FLAG);
    }

    /** Fills, lines, text, sprites and items whose 1.21.1 shapes 26.x dropped or changed. */
    private static void registerDraws(RetromodTransformer t) {
        draw(t, "fill", "(" + RENDER_TYPE + "IIIII)V");
        draw(t, "fill", "(" + RENDER_TYPE + "IIIIII)V");
        draw(t, "fill", "(IIIIII)V");
        draw(t, "fillGradient", "(IIIIIII)V");
        draw(t, "fillGradient", "(" + RENDER_TYPE + "IIIIIII)V");
        // 26.x calls the lines horizontalLine and verticalLine, with the same argument order.
        for (String line : new String[]{"hLine", "vLine"}) {
            draw(t, line, "(IIII)V");
            draw(t, line, "(" + RENDER_TYPE + "IIII)V");
        }
        // drawString at a fractional position has no 26.x form; the heuristic name text is matched too.
        for (String text : new String[]{"Ljava/lang/String;", "Lnet/minecraft/util/FormattedCharSequence;"}) {
            String desc = "(" + FONT + text + "FFIZ)I";
            draw(t, "drawString", desc);
            t.registerMethodRedirect(GUI, "text", desc, DRAWS, "drawString", withGraphics(desc));
        }
        draw(t, "drawWordWrap", "(" + FONT + "Lnet/minecraft/network/chat/FormattedText;IIII)V");
        draw(t, "drawManaged", "(Ljava/lang/Runnable;)V");
        draw(t, "renderItem", "(" + STACK + "IIII)V");
        t.registerMethodRedirect(GUI, "extractItem", "(" + STACK + "IIII)V", DRAWS, "renderItem",
                withGraphics("(" + STACK + "IIII)V"));
        draw(t, "blitSprite", "(" + IDENTIFIER + "IIIII)V");
        draw(t, "blitSprite", "(" + IDENTIFIER + "IIIIIIII)V");
        draw(t, "blitSprite", "(" + IDENTIFIER + "IIIIIIIII)V");
        draw(t, "blit", "(IIIII" + SPRITE + ")V");
        draw(t, "blit", "(IIIII" + SPRITE + "FFFF)V");
        // 26.x has no GUI render types. The helpers hand back nothing, which only the fills above accept.
        for (String type : new String[]{"gui", "guiOverlay", "guiGhostRecipeOverlay"}) {
            t.registerMethodRedirect(RENDER_TYPE_OWNER, type, "()" + RENDER_TYPE, DRAWS, "guiRenderType", "()" + RENDER_TYPE);
        }
        t.registerMethodRedirect(RENDER_TYPE_OWNER, "guiTextHighlight", "()" + RENDER_TYPE,
                DRAWS, "guiTextHighlightRenderType", "()" + RENDER_TYPE);
    }

    /** The tooltip calls: renamed in 1.21.6, and the positioner form gained a flag. */
    private static void registerTooltips(RetromodTransformer t) {
        rename(t, "renderTooltip", "(" + FONT + STACK + "II)V", "setTooltipForNextFrame");
        rename(t, "renderTooltip", "(" + FONT + LIST + OPTIONAL + "II)V", "setTooltipForNextFrame");
        rename(t, "renderTooltip", "(" + FONT + LIST + "II)V", "setTooltipForNextFrame");
        rename(t, "renderTooltip", "(" + FONT + COMPONENT + "II)V", "setTooltipForNextFrame");
        rename(t, "renderComponentTooltip", "(" + FONT + LIST + "II)V", "setComponentTooltipForNextFrame");
        // A Fabric mod's id already reads setTooltipForNextFrame, with the old arguments.
        String positioned = "(" + FONT + LIST + POSITIONER + "II)V";
        for (String name : new String[]{"renderTooltip", "setTooltipForNextFrame"}) {
            t.registerMethodRedirect(GUI, name, positioned, DRAWS, "renderTooltip", withGraphics(positioned));
        }
        // Forge and NeoForge both patch the stack-aware tooltip back in under the new name.
        if (McReflect.isNeoForge() || McReflect.isForgeTarget() || McReflect.isForge()) {
            rename(t, "renderTooltip", "(" + FONT + LIST + OPTIONAL + STACK + "II)V", "setTooltipForNextFrame");
        }
        // NeoForge renamed its other two; Forge kept their old names.
        if (McReflect.isNeoForge()) {
            rename(t, "renderComponentTooltip", "(" + FONT + LIST + "II" + STACK + ")V", "setComponentTooltipForNextFrame");
            rename(t, "renderComponentTooltipFromElements", "(" + FONT + LIST + "II" + STACK + ")V",
                    "setComponentTooltipFromElementsForNextFrame");
        }
    }

    /** {@code KeyMapping} checks that took raw key and button codes before 1.21.9. */
    private static void registerKeyChecks(RetromodTransformer t) {
        Common_1_21_11_to_26_1_ClassMoves.ensureSyntheticRegistered(t, KEYS);
        t.registerMethodRedirect(KEY_MAPPING, "matches", "(II)Z", KEYS, "matches", "(Ljava/lang/Object;II)Z");
        t.registerMethodRedirect(KEY_MAPPING, "matchesMouse", "(I)Z", KEYS, "matchesMouse", "(Ljava/lang/Object;I)Z");
        t.registerMethodRedirect(KEY_MAPPING, "getCategory", "()Ljava/lang/String;",
                KEYS, "categoryName", "(Ljava/lang/Object;)Ljava/lang/String;");
    }

    private static void draw(RetromodTransformer t, String name, String desc) {
        t.registerMethodRedirect(GUI, name, desc, DRAWS, name, withGraphics(desc));
    }

    private static void rename(RetromodTransformer t, String name, String desc, String newName) {
        t.registerMethodRedirect(GUI, name, desc, GUI, newName, desc);
    }

    /** The helper's descriptor: the old call's, with the graphics it was called on first. */
    private static String withGraphics(String desc) {
        return "(" + GUI_ARG + desc.substring(1);
    }
}
