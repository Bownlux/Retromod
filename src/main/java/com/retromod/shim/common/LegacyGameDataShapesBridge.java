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

/** Block entity component input, the block state loot condition and the show-item hover value, on their current shapes. */
public final class LegacyGameDataShapesBridge {

    static final String COMPONENT_INPUT = "net/minecraft/world/level/block/entity/BlockEntity$DataComponentInput";
    static final String COMPONENT_GETTER = "net/minecraft/core/component/DataComponentGetter";
    static final String STATE_CONDITION = "net/minecraft/world/level/storage/loot/predicates/LootItemBlockStatePropertyCondition";
    static final String CONDITION_BUILDER = RebuiltApiClasses.ROOT + "minecraft/world/level/storage/loot/LegacyBlockStateConditionBuilder";
    static final String ITEM_STACK_INFO = "net/minecraft/network/chat/HoverEvent$ItemStackInfo";
    static final String LEGACY_ITEM_STACK_INFO = RebuiltApiClasses.ROOT + "minecraft/network/chat/LegacyItemStackInfo";

    private LegacyGameDataShapesBridge() {}

    /** Registers each bridge whose old type the host lacks. */
    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read);
    }

    static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        // A block entity's implicit components arrive through the component getter, which has the same reads.
        if (host.apply(COMPONENT_INPUT) == null) t.registerClassRedirect(COMPONENT_INPUT, COMPONENT_GETTER);
        // 26.3 folded the block state condition into the block-matching one.
        if (lacksClass(host, STATE_CONDITION, "26.3")) {
            RebuiltApiClasses.registerTree(t, CONDITION_BUILDER);
            t.registerClassRedirect(STATE_CONDITION + "$Builder", CONDITION_BUILDER);
            t.registerMethodRedirect(STATE_CONDITION, "hasBlockStateProperties",
                    "(Lnet/minecraft/world/level/block/Block;)L" + STATE_CONDITION + "$Builder;", CONDITION_BUILDER,
                    "hasBlockStateProperties", "(Lnet/minecraft/world/level/block/Block;)L" + CONDITION_BUILDER + ";");
        }
        // The hover event constructor bridge turns a rebuilt item info into the show-item record.
        if (host.apply(ITEM_STACK_INFO) == null) {
            RebuiltApiClasses.registerTree(t, LEGACY_ITEM_STACK_INFO);
            t.registerClassRedirect(ITEM_STACK_INFO, LEGACY_ITEM_STACK_INFO);
        }
    }

    /** Whether the host lacks the class; when nothing is readable, whether the target is {@code since} or newer. */
    private static boolean lacksClass(Function<String, ClassNode> host, String name, String since) {
        if (host.apply(name) != null) return false;
        // A readable host is told apart from an unreadable one by a class every host from 1.21.11 has.
        if (host.apply("net/minecraft/world/level/storage/loot/LootContext") != null) return true;
        return targetAtLeast(since);
    }

    private static boolean targetAtLeast(String version) {
        return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, version) >= 0;
    }
}
