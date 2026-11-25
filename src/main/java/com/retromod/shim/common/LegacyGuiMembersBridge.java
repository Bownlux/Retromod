/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.function.Function;

/** Window, screen, list, option and tooltip members old GUI mods call, each rebuilt where the host removed it. */
public final class LegacyGuiMembersBridge {

    static final String ROOT = RebuiltApiClasses.ROOT + "minecraft/client/";
    static final String WINDOW = "com/mojang/blaze3d/platform/Window";
    static final String WINDOW_CALLS = ROOT + "LegacyWindowCalls";
    static final String MINECRAFT = "net/minecraft/client/Minecraft";
    static final String CLIENT_FLAGS = ROOT + "LegacyClientFlags";
    static final String SCREEN = "net/minecraft/client/gui/screens/Screen";
    static final String SCREEN_MEMBERS = ROOT + "gui/screens/LegacyScreenMembers";
    static final String WRAP_ERROR = "(Ljava/lang/Runnable;Ljava/lang/String;Ljava/lang/String;)V";
    static final String STYLE_CLICK = "(Lnet/minecraft/network/chat/Style;)Z";
    static final String SELECTION_LIST = "net/minecraft/client/gui/components/AbstractSelectionList";
    static final String RANGE_BASE = "net/minecraft/client/OptionInstance$IntRangeBase";
    static final String RANGES = ROOT + "LegacyOptionRanges";
    static final String OLD_XMAP = "(Ljava/util/function/IntFunction;Ljava/util/function/ToIntFunction;)"
            + "Lnet/minecraft/client/OptionInstance$SliderableValueSet;";
    static final String TOOLTIP_UTIL = "net/minecraft/client/gui/screens/inventory/tooltip/TooltipRenderUtil";
    static final String TOOLTIP_COMPONENT = "net/minecraft/client/gui/screens/inventory/tooltip/ClientTooltipComponent";
    static final String EXTRACT_CALLS = ROOT + "gui/LegacyGuiExtractCalls";
    static final String CONTAINER_SCREEN = "net/minecraft/client/gui/screens/inventory/AbstractContainerScreen";
    static final String SLOT_HIGHLIGHT = "(" + "Lnet/minecraft/client/gui/GuiGraphicsExtractor;" + "III)V";
    static final String OPTIONS = "net/minecraft/client/Options";
    static final String IMAGE_BUTTON = "net/minecraft/client/gui/components/ImageButton";
    static final String LEGACY_IMAGE_BUTTON = ROOT + "gui/components/LegacyImageButton";
    static final String ON_PRESS = "Lnet/minecraft/client/gui/components/Button$OnPress;";
    static final String TEXTURE = "IIIIIIILnet/minecraft/resources/Identifier;";
    /** The 1.20.1 texture constructors mods still call: without a hover offset, plain, with the texture size, and with a message too. */
    static final String[] OLD_IMAGE_BUTTONS = {"(IIIIIILnet/minecraft/resources/Identifier;" + ON_PRESS + ")V", "(" + TEXTURE + ON_PRESS + ")V", "(" + TEXTURE + "II" + ON_PRESS + ")V",
            "(" + TEXTURE + "II" + ON_PRESS + "Lnet/minecraft/network/chat/Component;)V"};
    static final String BOOLEAN_OPTIONS = ROOT + "LegacyBooleanOptions";
    static final String OPTION = "()Lnet/minecraft/client/OptionInstance;";
    static final String GUI = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;";
    static final String FONT = "Lnet/minecraft/client/gui/Font;";
    static final String OLD_BACKGROUND = "(" + GUI + "IIIII)V";
    static final String NEW_BACKGROUND = "(" + GUI + "IIIILnet/minecraft/resources/Identifier;)V";
    static final String OLD_IMAGE = "(" + FONT + "II" + GUI + ")V";
    static final String NEW_IMAGE = "(" + FONT + "IIII" + GUI + ")V";

    private LegacyGuiMembersBridge() {}

    /** Registers each bridge whose old member the host lacks; Mojang names, so Forge and NeoForge from 1.21.11 and every loader from 26.1. */
    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read);
    }

    static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        registerWindow(t, host.apply(WINDOW), host.apply(MINECRAFT));
        registerScreens(t, host);
        registerTooltips(t, host);
        registerImageButtons(t, host.apply(IMAGE_BUTTON));
        // 26.2 removed touchscreen mode; mods that read the option get one that is always off.
        if (lacks(host.apply(OPTIONS), "touchscreen", OPTION, "26.2")) {
            RebuiltApiClasses.registerTree(t, BOOLEAN_OPTIONS);
            t.registerMethodRedirect(OPTIONS, "touchscreen", OPTION, BOOLEAN_OPTIONS, "touchscreen", "(L" + OPTIONS + ";" + OPTION.substring(1), true);
        }
        // 1.21.11 gave the mapped range a discrete flag, which every vanilla option sets.
        if (lacks(host.apply(RANGE_BASE), "xmap", OLD_XMAP, "1.21.11")) {
            RebuiltApiClasses.registerTree(t, RANGES);
            String helperDesc = "(L" + RANGE_BASE + ";" + OLD_XMAP.substring(1);
            // Mods call it on the concrete range they built, which javac names as the owner.
            for (String owner : new String[]{RANGE_BASE, "net/minecraft/client/OptionInstance$IntRange",
                    "net/minecraft/client/OptionInstance$ClampingLazyMaxIntRange"}) {
                t.registerMethodRedirect(owner, "xmap", OLD_XMAP, RANGES, "xmap", helperDesc, true);
            }
        }
    }

    /** The frame rate limit moved to its tracker in 1.21.2, the GUI scale became whole in 1.21.6, and 26.2 dropped vsync and resizeDisplay. */
    static void registerWindow(RetromodTransformer t, ClassNode window, ClassNode minecraft) {
        if (lacks(window, "setGuiScale", "(D)V", "1.21.6") && (window == null || has(window, "setGuiScale", "(I)V"))) {
            // A fractional scale was never set by the game itself, so dropping the fraction keeps every scale it chose.
            t.registerConvertingRedirect(WINDOW, "setGuiScale", "(D)V", WINDOW, "setGuiScale", "(I)V", Opcodes.D2I, 0);
        }
        String windowArg = "(L" + WINDOW + ";";
        if (lacks(window, "getFramerateLimit", "()I", "1.21.2")) {
            RebuiltApiClasses.registerTree(t, WINDOW_CALLS);
            t.registerMethodRedirect(WINDOW, "getFramerateLimit", "()I", WINDOW_CALLS, "getFramerateLimit", windowArg + ")I", true);
            t.registerMethodRedirect(WINDOW, "setFramerateLimit", "(I)V", WINDOW_CALLS, "setFramerateLimit", windowArg + "I)V", true);
        }
        if (lacks(window, "updateVsync", "(Z)V", "26.2")) {
            RebuiltApiClasses.registerTree(t, WINDOW_CALLS);
            t.registerMethodRedirect(WINDOW, "updateVsync", "(Z)V", WINDOW_CALLS, "updateVsync", windowArg + "Z)V", true);
        }
        if (lacks(minecraft, "resizeDisplay", "()V", "26.1") && (minecraft == null || has(minecraft, "resizeGui", "()V"))) {
            // A rename, not a redirect, so a mixin selector naming it keeps its bare form.
            t.registerMethodRename(MINECRAFT, "resizeDisplay", "resizeGui");
        }
        // 26.2 dropped the static check; the improved transparency option is what it read.
        if (lacks(minecraft, "useShaderTransparency", "()Z", "26.2")) {
            RebuiltApiClasses.registerTree(t, CLIENT_FLAGS);
            t.registerMethodRedirect(MINECRAFT, "useShaderTransparency", "()Z", CLIENT_FLAGS, "useShaderTransparency", "()Z");
        }
    }

    /** Component clicks lost their screen method in 1.21.11, the error wrapper went in 1.21.2, and the list's top edge became its widget y. */
    static void registerScreens(RetromodTransformer t, Function<String, ClassNode> host) {
        if (lacks(host.apply(SCREEN), "handleComponentClicked", STYLE_CLICK, "1.21.11")) {
            RebuiltApiClasses.registerTree(t, SCREEN_MEMBERS);
            t.registerMethodRedirect(SCREEN, "handleComponentClicked", STYLE_CLICK, SCREEN_MEMBERS, "handleComponentClicked",
                    "(L" + SCREEN + ";" + STYLE_CLICK.substring(1), true);
        }
        // 1.21.2 dropped the static error wrapper.
        if (lacks(host.apply(SCREEN), "wrapScreenError", WRAP_ERROR, "1.21.2")) {
            RebuiltApiClasses.registerTree(t, SCREEN_MEMBERS);
            t.registerMethodRedirect(SCREEN, "wrapScreenError", WRAP_ERROR, SCREEN_MEMBERS, "wrapScreenError", WRAP_ERROR);
        }
        ClassNode list = host.apply(SELECTION_LIST);
        if (lacks(list, "getTop", "()I", "1.21.11")) {
            t.registerMethodRedirect(SELECTION_LIST, "getTop", "()I", SELECTION_LIST, "getY", "()I");
            t.registerInheritedMethodRedirect(SELECTION_LIST, "getTop", "()I", SELECTION_LIST, "getY", "()I");
        }
    }

    /** 1.21.2 gave tooltip components a font and full size and dropped the static slot highlight; the calls take their extract names from 26.1. */
    static void registerTooltips(RetromodTransformer t, Function<String, ClassNode> host) {
        ClassNode util = host.apply(TOOLTIP_UTIL);
        boolean extractHost = util != null ? has(util, "extractTooltipBackground", NEW_BACKGROUND) : targetAtLeast("26.1");
        if (!extractHost) return;
        RebuiltApiClasses.registerTree(t, EXTRACT_CALLS);
        t.registerMethodRedirect(TOOLTIP_UTIL, "extractTooltipBackground", OLD_BACKGROUND, EXTRACT_CALLS,
                "extractTooltipBackground", OLD_BACKGROUND);
        String component = "(L" + TOOLTIP_COMPONENT + ";";
        t.registerMethodRedirect(TOOLTIP_COMPONENT, "getHeight", "()I", EXTRACT_CALLS, "getHeight", component + ")I", true);
        t.registerMethodRedirect(TOOLTIP_COMPONENT, "extractImage", OLD_IMAGE, EXTRACT_CALLS, "extractImage",
                component + OLD_IMAGE.substring(1), true);
        // 1.21.2 replaced the static slot highlight with the screen's own back and front sprites.
        if (lacks(host.apply(CONTAINER_SCREEN), "extractSlotHighlight", SLOT_HIGHLIGHT, "26.1")) {
            t.registerMethodRedirect(CONTAINER_SCREEN, "extractSlotHighlight", SLOT_HIGHLIGHT, EXTRACT_CALLS, "extractSlotHighlight", SLOT_HIGHLIGHT);
        }
    }

    /** 1.20.2 made image buttons draw GUI sprites; a texture region button is rebuilt on the 26.1 extract names. */
    static void registerImageButtons(RetromodTransformer t, ClassNode imageButton) {
        boolean extractHost = imageButton != null
                ? has(imageButton, "extractContents", "(" + GUI + "IIF)V") && !has(imageButton, "<init>", OLD_IMAGE_BUTTONS[1])
                : targetAtLeast("26.1");
        if (!extractHost) return;
        RebuiltApiClasses.registerTree(t, LEGACY_IMAGE_BUTTON);
        for (String old : OLD_IMAGE_BUTTONS) {
            t.registerConstructorRedirect(IMAGE_BUTTON, old, LEGACY_IMAGE_BUTTON, "create", old.replace(")V", ")L" + IMAGE_BUTTON + ";"));
        }
        // A mod's own image button moves onto the rebuilt one, which also keeps the host's sprite constructors. A mixin
        // keeps its superclass, which Mixin checks against its target.
        t.registerSuperclassRebaseSkippingMixins(IMAGE_BUTTON, LEGACY_IMAGE_BUTTON);
    }

    private static boolean lacks(ClassNode owner, String name, String desc, String since) {
        return owner == null ? targetAtLeast(since) : !has(owner, name, desc);
    }

    private static boolean has(ClassNode owner, String name, String desc) {
        for (MethodNode method : owner.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return true;
        }
        return false;
    }

    private static boolean targetAtLeast(String version) {
        return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, version) >= 0;
    }
}
