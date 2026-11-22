/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.tree.ClassNode;

/** Keeps an old mod's reads and writes of {@code Villager.MEMORY_TYPES} and {@code SENSOR_TYPES} working on 26.1 and newer. */
public final class LegacyVillagerBrainBridge {

    static final String VILLAGER = "net/minecraft/world/entity/npc/villager/Villager";
    // The owner mods built before 1.21.11 name; the class move may not have run when the field is matched.
    static final String OLD_VILLAGER = "net/minecraft/world/entity/npc/Villager";
    static final String BRAIN = RebuiltApiClasses.ROOT + "minecraft/world/entity/LegacyVillagerBrain";
    static final String GETTER_DESC = "()Lcom/google/common/collect/ImmutableList;";
    static final String SETTER_DESC = "(Ljava/util/Collection;)V";
    private static final String FIRST_HOST_WITHOUT = "26.1";

    private LegacyVillagerBrainBridge() {}

    /** Registers the bridge where the host's villager keeps its memories in a brain provider instead of the old lists. */
    public static void register(RetromodTransformer t) {
        if (!hostDroppedLists()) return;
        RebuiltApiClasses.registerTree(t, BRAIN);
        // Moonlight adds a memory to MEMORY_TYPES for Supplementaries' scared villagers during common setup.
        for (String owner : new String[] {VILLAGER, OLD_VILLAGER}) {
            bridge(t, owner, "MEMORY_TYPES", "memoryTypes", "setMemoryTypes");
            bridge(t, owner, "SENSOR_TYPES", "sensorTypes", "setSensorTypes");
        }
    }

    private static void bridge(RetromodTransformer t, String owner, String field, String getter, String setter) {
        t.registerStaticFieldBridge(owner, field, BRAIN, getter, GETTER_DESC, setter, SETTER_DESC);
        t.limitStaticFieldBridgeToModsBefore(owner, field, FIRST_HOST_WITHOUT);
    }

    /** 26.1 removed the lists and gave Villager a BRAIN_PROVIDER; an unreadable host decides by version. */
    static boolean hostDroppedLists() {
        ClassNode villager = ClassResourceInspector.read(VILLAGER);
        if (villager == null) {
            return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, FIRST_HOST_WITHOUT) >= 0;
        }
        boolean lists = villager.fields.stream().anyMatch(f -> f.name.equals("MEMORY_TYPES"));
        boolean provider = villager.fields.stream().anyMatch(f -> f.name.equals("BRAIN_PROVIDER"));
        return !lists && provider;
    }
}
