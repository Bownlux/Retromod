/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.tree.ClassNode;

import java.util.function.Function;

/** Answers 1.21.4's {@code DataComponents.HIDE_ADDITIONAL_TOOLTIP}, which 1.21.5 removed, with a stand-in that is never stored. */
public final class LegacyHiddenTooltipBridge {

    static final String COMPONENTS = "net/minecraft/core/component/DataComponents";
    static final String FIELD = "HIDE_ADDITIONAL_TOOLTIP";
    static final String TYPE = "Lnet/minecraft/core/component/DataComponentType;";
    static final String BUILDER = "net/minecraft/core/component/DataComponentMap$Builder";
    static final String STACK = "net/minecraft/world/item/ItemStack";
    static final String PROPERTIES = "net/minecraft/world/item/Item$Properties";
    static final String PATCH_BUILDER = "net/minecraft/core/component/DataComponentPatch$Builder";
    static final String REBUILT = RebuiltApiClasses.ROOT + "minecraft/core/component/LegacyHiddenTooltip";
    static final String BUILDER_SET = "(" + TYPE + "Ljava/lang/Object;)L" + BUILDER + ";";
    static final String STACK_SET = "(" + TYPE + "Ljava/lang/Object;)Ljava/lang/Object;";
    static final String PROPERTIES_COMPONENT = "(" + TYPE + "Ljava/lang/Object;)L" + PROPERTIES + ";";
    static final String PATCH_SET = "(" + TYPE + "Ljava/lang/Object;)L" + PATCH_BUILDER + ";";
    /** The release that removed the component. */
    static final String VERSION = "1.21.5";

    private LegacyHiddenTooltipBridge() {}

    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read);
    }

    static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        if (!hostRemovedIt(host)) return;
        RebuiltApiClasses.registerTree(t, REBUILT);
        t.registerFieldRedirect(COMPONENTS, FIELD, TYPE, REBUILT, "type", "()" + TYPE);
        // The ways old code sets it go through helpers that drop the stand-in, which the host could not save.
        t.registerMethodRedirect(BUILDER, "set", BUILDER_SET, REBUILT, "set", "(L" + BUILDER + ";" + TYPE + "Ljava/lang/Object;)L" + BUILDER + ";", true);
        t.limitRedirectToModsBefore(BUILDER, "set", BUILDER_SET, VERSION);
        t.registerMethodRedirect(STACK, "set", STACK_SET, REBUILT, "set", "(L" + STACK + ";" + TYPE + "Ljava/lang/Object;)Ljava/lang/Object;", true);
        t.limitRedirectToModsBefore(STACK, "set", STACK_SET, VERSION);
        // An item's default components and a component patch would keep it too, where the host later asks for its codec.
        t.registerMethodRedirect(PROPERTIES, "component", PROPERTIES_COMPONENT, REBUILT, "component",
                "(L" + PROPERTIES + ";" + TYPE + "Ljava/lang/Object;)L" + PROPERTIES + ";", true);
        t.limitRedirectToModsBefore(PROPERTIES, "component", PROPERTIES_COMPONENT, VERSION);
        t.registerMethodRedirect(PATCH_BUILDER, "set", PATCH_SET, REBUILT, "set",
                "(L" + PATCH_BUILDER + ";" + TYPE + "Ljava/lang/Object;)L" + PATCH_BUILDER + ";", true);
        t.limitRedirectToModsBefore(PATCH_BUILDER, "set", PATCH_SET, VERSION);
    }

    /** Whether the host's components lack the field; offline or unreadable, a target from 1.21.5. */
    static boolean hostRemovedIt(Function<String, ClassNode> host) {
        ClassNode components = host.apply(COMPONENTS);
        if (components == null) return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, VERSION) >= 0;
        return components.fields.stream().noneMatch(field -> field.name.equals(FIELD));
    }
}
