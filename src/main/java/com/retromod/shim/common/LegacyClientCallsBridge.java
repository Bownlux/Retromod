/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/** Bridges small client calls that 26.x removed or reshaped: render getters, render targets, link screens, cycle buttons and chat characters. */
public final class LegacyClientCallsBridge {

    static final String RENDER_CALLS = "com/retromod/shim/common/embedded/LegacyRenderCalls";
    static final String CYCLE_BUTTONS = RebuiltApiClasses.ROOT + "minecraft/client/gui/LegacyCycleButtons";
    static final String LINK_SCREENS = RebuiltApiClasses.ROOT + "minecraft/client/gui/LegacyLinkScreens";
    static final String NAMED_TARGETS = RebuiltApiClasses.ROOT + "minecraft/client/renderer/LegacyNamedTextureTargets";
    static final String FORMAT_TARGETS = RebuiltApiClasses.ROOT + "minecraft/client/renderer/LegacyTextureTargets";
    static final String COLOR_FORMAT_TARGETS = RebuiltApiClasses.ROOT + "minecraft/client/renderer/LegacyColorFormatTextureTargets";

    static final String RENDER_SYSTEM = "com/mojang/blaze3d/systems/RenderSystem";
    static final String RENDER_TARGET = "com/mojang/blaze3d/pipeline/RenderTarget";
    static final String TEXTURE_TARGET = "com/mojang/blaze3d/pipeline/TextureTarget";
    static final String CONFIRM_LINK = "net/minecraft/client/gui/screens/ConfirmLinkScreen";
    static final String CYCLE_BUTTON = "net/minecraft/client/gui/components/CycleButton";
    static final String CYCLE_BUILDER = CYCLE_BUTTON + "$Builder";
    static final String STRING_UTIL = "net/minecraft/util/StringUtil";
    static final String BUILT_IN_REGISTRIES = "net/minecraft/core/registries/BuiltInRegistries";

    static final String BOOLEAN_CONSUMER = "Lit/unimi/dsi/fastutil/booleans/BooleanConsumer;";
    static final String COMPONENT = "Lnet/minecraft/network/chat/Component;";
    static final String OLD_TARGET_CTOR = "(IIZZ)V";
    static final String NAMED_TARGET_CTOR = "(Ljava/lang/String;IIZ)V";
    static final String FORMAT_TARGET_CTOR =
            "(Ljava/lang/String;IILcom/mojang/renderpearl/api/GpuFormat;Lcom/mojang/renderpearl/api/GpuFormat;)V";
    static final String COLOR_FORMAT_TARGET_CTOR = "(Ljava/lang/String;IIZLcom/mojang/blaze3d/GpuFormat;)V";

    /** Which TextureTarget constructor the host offers for the old four-argument one. */
    enum TargetShape { NAMED, COLOR_FORMAT, FORMATS, NONE }

    private LegacyClientCallsBridge() {}

    /** Registers every bridge for a 26.x host, each checked against the host's own classes when they can be read. */
    public static void register(RetromodTransformer t) {
        boolean gameReadable = ClassResourceInspector.read(BUILT_IN_REGISTRIES) != null;
        registerChatCharacter(t);
        registerRenderGetters(t);
        registerTextureTarget(t, textureTargetShape(ClassResourceInspector.read(TEXTURE_TARGET), gameReadable));
        if (cycleBuilderMissing(ClassResourceInspector.read(CYCLE_BUTTON), gameReadable)) registerCycleButton(t);
        if (linkScreenTakesUri(ClassResourceInspector.read(CONFIRM_LINK), gameReadable)) registerConfirmLink(t);
    }

    /** StringUtil.isAllowedChatCharacter(char) became (int); a char is already an int on the stack. */
    static void registerChatCharacter(RetromodTransformer t) {
        t.registerMethodRedirect(STRING_UTIL, "isAllowedChatCharacter", "(C)Z", STRING_UTIL, "isAllowedChatCharacter", "(I)Z");
    }

    /** RenderSystem getters with no single value left, and RenderTarget's GL id and old resize. */
    static void registerRenderGetters(RetromodTransformer t) {
        t.registerMethodRedirect(RENDER_SYSTEM, "getShaderFogStart", "()F", RENDER_CALLS, "shaderFogStart", "()F");
        t.registerMethodRedirect(RENDER_SYSTEM, "getShaderFogEnd", "()F", RENDER_CALLS, "shaderFogEnd", "()F");
        t.registerMethodRedirect(RENDER_SYSTEM, "getShaderColor", "()[F", RENDER_CALLS, "shaderColor", "()[F");
        t.registerMethodRedirect(RENDER_SYSTEM, "getShaderTexture", "(I)I", RENDER_CALLS, "shaderTexture", "(I)I");
        t.registerMethodRedirect(RENDER_SYSTEM, "runAsFancy", "(Ljava/lang/Runnable;)V", RENDER_CALLS, "runAsFancy",
                "(Ljava/lang/Runnable;)V");
        // Mods call these through RenderTarget and through the TextureTarget they hold.
        for (String owner : new String[]{RENDER_TARGET, TEXTURE_TARGET}) {
            t.registerMethodRedirect(owner, "getColorTextureId", "()I", RENDER_CALLS, "colorTextureId",
                    "(Ljava/lang/Object;)I", true);
            t.registerMethodRedirect(owner, "getDepthTextureId", "()I", RENDER_CALLS, "depthTextureId",
                    "(Ljava/lang/Object;)I", true);
            t.registerMethodRedirect(owner, "resize", "(IIZ)V", RENDER_CALLS, "resize", "(Ljava/lang/Object;IIZ)V", true);
        }
    }

    /** new TextureTarget(width, height, useDepth, clearError), built with the constructor the host has. */
    static void registerTextureTarget(RetromodTransformer t, TargetShape shape) {
        String helper = switch (shape) {
            case NAMED -> NAMED_TARGETS;
            case COLOR_FORMAT -> COLOR_FORMAT_TARGETS;
            case FORMATS -> FORMAT_TARGETS;
            case NONE -> null;
        };
        if (helper == null) return;
        RebuiltApiClasses.registerTree(t, helper);
        t.registerConstructorRedirect(TEXTURE_TARGET, OLD_TARGET_CTOR, helper, "create", "(IIZZ)L" + TEXTURE_TARGET + ";");
    }

    /** CycleButton.builder(stringifier) and Builder.withInitialValue(value), on the builder that takes its value up front. */
    static void registerCycleButton(RetromodTransformer t) {
        RebuiltApiClasses.registerTree(t, CYCLE_BUTTONS);
        String builder = "L" + CYCLE_BUILDER + ";";
        t.registerMethodRedirect(CYCLE_BUTTON, "builder", "(Ljava/util/function/Function;)" + builder,
                CYCLE_BUTTONS, "builder", "(Ljava/util/function/Function;)" + builder);
        t.registerMethodRedirect(CYCLE_BUILDER, "withInitialValue", "(Ljava/lang/Object;)" + builder,
                CYCLE_BUTTONS, "withInitialValue", "(" + builder + "Ljava/lang/Object;)" + builder, true);
    }

    /** new ConfirmLinkScreen(callback, url, trusted), its titled form, and the static link helpers, with the link parsed into a URI. */
    static void registerConfirmLink(RetromodTransformer t) {
        RebuiltApiClasses.registerTree(t, LINK_SCREENS);
        String screen = "L" + CONFIRM_LINK + ";";
        t.registerConstructorRedirect(CONFIRM_LINK, "(" + BOOLEAN_CONSUMER + "Ljava/lang/String;Z)V",
                LINK_SCREENS, "confirmLinkScreen", "(" + BOOLEAN_CONSUMER + "Ljava/lang/String;Z)" + screen);
        t.registerConstructorRedirect(CONFIRM_LINK, "(" + BOOLEAN_CONSUMER + COMPONENT + "Ljava/lang/String;Z)V",
                LINK_SCREENS, "confirmLinkScreen", "(" + BOOLEAN_CONSUMER + COMPONENT + "Ljava/lang/String;Z)" + screen);
        String lastScreen = "Lnet/minecraft/client/gui/screens/Screen;Ljava/lang/String;";
        String onPress = "Lnet/minecraft/client/gui/components/Button$OnPress;";
        for (String trusted : new String[]{"", "Z"}) {
            t.registerMethodRedirect(CONFIRM_LINK, "confirmLink", "(" + lastScreen + trusted + ")" + onPress,
                    LINK_SCREENS, "confirmLink", "(" + lastScreen + trusted + ")" + onPress);
            t.registerMethodRedirect(CONFIRM_LINK, "confirmLinkNow", "(" + lastScreen + trusted + ")V",
                    LINK_SCREENS, "confirmLinkNow", "(" + lastScreen + trusted + ")V");
        }
    }

    /** The host's TextureTarget shape; offline, where no game class is readable, the version decides. */
    static TargetShape textureTargetShape(ClassNode target, boolean gameReadable) {
        if (target != null) {
            if (hasMethod(target, "<init>", OLD_TARGET_CTOR)) return TargetShape.NONE;
            if (hasMethod(target, "<init>", NAMED_TARGET_CTOR)) return TargetShape.NAMED;
            if (hasMethod(target, "<init>", COLOR_FORMAT_TARGET_CTOR)) return TargetShape.COLOR_FORMAT;
            if (hasMethod(target, "<init>", FORMAT_TARGET_CTOR)) return TargetShape.FORMATS;
            return TargetShape.NONE;
        }
        if (gameReadable) return TargetShape.NONE;
        if (targetAtLeast("26.3")) return TargetShape.FORMATS;
        return targetAtLeast("26.2") ? TargetShape.COLOR_FORMAT : TargetShape.NAMED;
    }

    /** Whether the host lacks builder(Function) but has the Builder constructor the helper calls. */
    static boolean cycleBuilderMissing(ClassNode cycleButton, boolean gameReadable) {
        if (cycleButton != null) {
            if (hasMethod(cycleButton, "builder", "(Ljava/util/function/Function;)L" + CYCLE_BUILDER + ";")) return false;
            ClassNode builder = ClassResourceInspector.read(CYCLE_BUILDER);
            return builder != null && hasMethod(builder, "<init>", "(Ljava/util/function/Function;Ljava/util/function/Supplier;)V");
        }
        // Offline every 26.x host qualifies: 26.1 to 26.3 all lack builder(Function).
        return !gameReadable;
    }

    /** Whether ConfirmLinkScreen takes the link as a URI and no longer as a String (26.3). */
    static boolean linkScreenTakesUri(ClassNode screen, boolean gameReadable) {
        if (screen != null) {
            return !hasMethod(screen, "<init>", "(" + BOOLEAN_CONSUMER + "Ljava/lang/String;Z)V")
                    && hasMethod(screen, "<init>", "(" + BOOLEAN_CONSUMER + "Ljava/net/URI;Z)V")
                    && hasMethod(screen, "<init>", "(" + BOOLEAN_CONSUMER + COMPONENT + "Ljava/net/URI;Z)V");
        }
        return !gameReadable && targetAtLeast("26.3");
    }

    private static boolean targetAtLeast(String version) {
        return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, version) >= 0;
    }

    private static boolean hasMethod(ClassNode owner, String name, String desc) {
        for (MethodNode method : owner.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return true;
        }
        return false;
    }
}
