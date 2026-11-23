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

import java.util.function.Function;

/** The old graphics mode option and option constructors, rebuilt on the graphics preset and value listeners. */
public final class LegacyOptionsBridge {

    static final String OPTIONS = "net/minecraft/client/Options";
    static final String OPTION = "net/minecraft/client/OptionInstance";
    static final String GRAPHICS_STATUS = "net/minecraft/client/GraphicsStatus";
    static final String GRAPHICS_PRESET = "net/minecraft/client/GraphicsPreset";
    static final String GRAPHICS_OPTION = RebuiltApiClasses.ROOT + "minecraft/client/LegacyGraphicsModeOption";
    static final String GRAPHICS_OPTION_262 = RebuiltApiClasses.ROOT + "minecraft/client/LegacyGraphicsModeOption262";
    static final String OPTION_INSTANCES = RebuiltApiClasses.ROOT + "minecraft/client/LegacyOptionInstances";
    static final String GRAPHICS_MODE = "()L" + OPTION + ";";
    static final String OPTION_HEAD = "(Ljava/lang/String;L" + OPTION + "$TooltipSupplier;L" + OPTION + "$CaptionBasedToString;L"
            + OPTION + "$ValueSet;";
    static final String SIX_ARGS = OPTION_HEAD + "Ljava/lang/Object;Ljava/util/function/Consumer;)";
    static final String SEVEN_ARGS = OPTION_HEAD + "Lcom/mojang/serialization/Codec;Ljava/lang/Object;Ljava/util/function/Consumer;)";

    private LegacyOptionsBridge() {}

    /** Registers each bridge whose old member the host lacks. */
    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read);
    }

    static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        ClassNode option = host.apply(OPTION);
        // 26.2 made option constructors take a value listener in place of a consumer.
        boolean listenerOptions = option == null ? targetAtLeast("26.2") : !declares(option, "<init>", SIX_ARGS + "V");
        if (listenerOptions) {
            RebuiltApiClasses.registerTree(t, OPTION_INSTANCES);
            t.registerConstructorRedirect(OPTION, SIX_ARGS + "V", OPTION_INSTANCES, "create", SIX_ARGS + "L" + OPTION + ";");
            t.registerConstructorRedirect(OPTION, SEVEN_ARGS + "V", OPTION_INSTANCES, "create", SEVEN_ARGS + "L" + OPTION + ";");
        }
        // The fast, fancy and fabulous mode became the graphics preset, which adds a custom setting.
        if (host.apply(GRAPHICS_STATUS) == null) {
            t.registerClassRedirect(GRAPHICS_STATUS, GRAPHICS_PRESET);
            // The old numbering has no counterpart on the preset; both names are keyed, as the class move may come first.
            String presets = RebuiltApiClasses.ROOT + "minecraft/client/LegacyGraphicsPresets";
            RebuiltApiClasses.registerTree(t, presets);
            for (String owner : new String[] {GRAPHICS_STATUS, GRAPHICS_PRESET}) {
                t.registerMethodRedirect(owner, "getId", "()I", presets, "getId", "(L" + GRAPHICS_PRESET + ";)I", true);
                t.registerMethodRedirect(owner, "byId", "(I)L" + owner + ";", presets, "byId", "(I)L" + GRAPHICS_PRESET + ";");
            }
        }
        ClassNode options = host.apply(OPTIONS);
        if (options == null || !declares(options, "graphicsMode", GRAPHICS_MODE)) {
            String helper = listenerOptions ? GRAPHICS_OPTION_262 : GRAPHICS_OPTION;
            RebuiltApiClasses.registerTree(t, helper);
            t.registerMethodRedirect(OPTIONS, "graphicsMode", GRAPHICS_MODE, helper, "graphicsMode", "(L" + OPTIONS + ";)L" + OPTION + ";", true);
        }
    }

    static boolean declares(ClassNode owner, String name, String desc) {
        for (MethodNode method : owner.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return true;
        }
        return false;
    }

    private static boolean targetAtLeast(String version) {
        return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, version) >= 0;
    }
}
