/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.common.embedded.LegacyToolRules;
import org.objectweb.asm.tree.ClassNode;

/** Keeps 1.21.1 tool rules, built from a block tag or list, and the three-argument tool component working on newer hosts. */
public final class LegacyToolRuleBridge {

    static final String SUPPORT = "com/retromod/shim/common/embedded/LegacyToolRules";
    static final String RULE = "net/minecraft/world/item/component/Tool$Rule";
    static final String TOOL = "net/minecraft/world/item/component/Tool";
    private static final String OBJECT = "Ljava/lang/Object;";

    private LegacyToolRuleBridge() {}

    /** Registers on a host whose rules take holder sets, which 1.21.2 introduced. */
    public static void register(RetromodTransformer t) {
        ClassNode rule = ClassResourceInspector.read(RULE);
        boolean holderSets = rule == null
                ? RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "1.21.2") >= 0
                : rule.methods.stream().noneMatch(m -> m.desc.startsWith("(Lnet/minecraft/tags/TagKey;"));
        if (!holderSets) return;
        SyntheticEmbedder.registerClassResource(t, SUPPORT, LegacyToolRules.class);
        String result = ")L" + RULE + ";";
        for (String blocks : new String[]{"Lnet/minecraft/tags/TagKey;", "Ljava/util/List;"}) {
            t.registerMethodRedirect(RULE, "minesAndDrops", "(" + blocks + "F" + result, SUPPORT, "minesAndDrops", "(" + OBJECT + "F)" + OBJECT);
            t.registerMethodRedirect(RULE, "deniesDrops", "(" + blocks + result, SUPPORT, "deniesDrops", "(" + OBJECT + ")" + OBJECT);
            t.registerMethodRedirect(RULE, "overrideSpeed", "(" + blocks + "F" + result, SUPPORT, "overrideSpeed", "(" + OBJECT + "F)" + OBJECT);
        }
        // 1.21.5 added a fourth argument, whether the tool breaks blocks in creative mode.
        ClassNode tool = ClassResourceInspector.read(TOOL);
        boolean threeArguments = tool != null && tool.methods.stream()
                .anyMatch(m -> m.name.equals("<init>") && m.desc.equals("(Ljava/util/List;FI)V"));
        if (!threeArguments) {
            t.registerConstructorRedirect(TOOL, "(Ljava/util/List;FI)V", SUPPORT, "tool", "(" + OBJECT + "FI)" + OBJECT);
        }
    }
}
