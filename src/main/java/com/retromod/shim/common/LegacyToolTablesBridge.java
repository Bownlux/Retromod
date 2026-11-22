/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.tree.ClassNode;

import java.util.List;
import java.util.function.Function;

/** Points old reads and writes of the axe, shovel and hoe tables 26.3 removed at rebuilt tables that feed its block transformers. */
public final class LegacyToolTablesBridge {

    static final String TABLES = RebuiltApiClasses.ROOT + "minecraft/world/item/LegacyToolTables";
    static final String AXE = "net/minecraft/world/item/AxeItem";

    private static final String MAP_GETTER = "()Ljava/util/Map;";
    private static final String MAP_SETTER = "(Ljava/util/Map;)V";
    private static final String STATE = "Lnet/minecraft/world/level/block/state/BlockState;";
    private static final String CONSUMER = "Ljava/util/function/Consumer;";

    private LegacyToolTablesBridge() {}

    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read);
    }

    static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        if (!hostRemovedThem(host.apply(AXE))) return;
        RebuiltApiClasses.registerTree(t, TABLES);
        // Mods name the removed classes, which Retromod also moves onto generated tool bases, so both names are covered.
        for (String tool : List.of("AxeItem", "ShovelItem", "HoeItem")) {
            for (String owner : List.of("net/minecraft/world/item/" + tool, "com/retromod/generated/Legacy" + tool)) {
                registerTool(t, tool, owner);
            }
        }
    }

    private static void registerTool(RetromodTransformer t, String tool, String owner) {
        switch (tool) {
            case "AxeItem" -> {
                t.registerStaticFieldBridge(owner, "STRIPPABLES", TABLES, "strippables", MAP_GETTER, "setStrippables", MAP_SETTER);
                t.registerMethodRedirect(owner, "getAxeStrippingState", "(" + STATE + ")" + STATE,
                        TABLES, "getAxeStrippingState", "(" + STATE + ")" + STATE);
            }
            case "ShovelItem" -> {
                t.registerStaticFieldBridge(owner, "FLATTENABLES", TABLES, "flattenables", MAP_GETTER, "setFlattenables", MAP_SETTER);
                t.registerMethodRedirect(owner, "getShovelPathingState", "(" + STATE + ")" + STATE,
                        TABLES, "getShovelPathingState", "(" + STATE + ")" + STATE);
            }
            default -> {
                t.registerStaticFieldBridge(owner, "TILLABLES", TABLES, "tillables", MAP_GETTER, "setTillables", MAP_SETTER);
                String context = "Lnet/minecraft/world/item/context/UseOnContext;";
                t.registerMethodRedirect(owner, "onlyIfAirAbove", "(" + context + ")Z", TABLES, "onlyIfAirAbove", "(" + context + ")Z");
                t.registerMethodRedirect(owner, "changeIntoState", "(" + STATE + ")" + CONSUMER,
                        TABLES, "changeIntoState", "(" + STATE + ")" + CONSUMER);
                String drop = "(" + STATE + "Lnet/minecraft/world/level/ItemLike;)" + CONSUMER;
                t.registerMethodRedirect(owner, "changeIntoStateAndDropItem", drop, TABLES, "changeIntoStateAndDropItem", drop);
            }
        }
    }

    /** Whether the host has no AxeItem, as from 26.3; offline or unreadable, a target from 26.3. */
    static boolean hostRemovedThem(ClassNode axe) {
        if (axe != null) return false;
        return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "26.3") >= 0;
    }
}
