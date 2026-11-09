/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.tree.ClassNode;

import java.util.List;
import java.util.function.Function;

/** Points {@code Entity.getTeam()}, which returns PlayerTeam from 1.20.3 instead of Team, at the new return type. */
public final class LegacyEntityTeamBridge {

    static final String ENTITY = "net/minecraft/world/entity/Entity";
    static final String OLD_DESC = "()Lnet/minecraft/world/scores/Team;";
    static final String NEW_DESC = "()Lnet/minecraft/world/scores/PlayerTeam;";
    // The classes mods most often call it on; others reach it through the hierarchy the transformer proves.
    static final List<String> OWNERS = List.of(ENTITY, "net/minecraft/world/entity/LivingEntity",
            "net/minecraft/world/entity/Mob", "net/minecraft/world/entity/TamableAnimal",
            "net/minecraft/world/entity/player/Player", "net/minecraft/client/player/AbstractClientPlayer",
            "net/minecraft/client/player/LocalPlayer", "net/minecraft/server/level/ServerPlayer");
    // A Fabric 1.20.3 to 1.21.11 host: Entity is class_1297, Team class_270 and PlayerTeam class_268, under one id.
    static final String INTERMEDIARY_ENTITY = "net/minecraft/class_1297";
    static final String INTERMEDIARY_OLD_DESC = "()Lnet/minecraft/class_270;";
    static final String INTERMEDIARY_NEW_DESC = "()Lnet/minecraft/class_268;";

    private LegacyEntityTeamBridge() {}

    /** Mojang names: Forge and NeoForge hosts that run them, and Fabric from 26.1. */
    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read, ENTITY, "getTeam", OLD_DESC, NEW_DESC, OWNERS);
    }

    /** Intermediary names, for a Fabric host from 1.20.3 to 1.21.11. */
    public static void registerIntermediary(RetromodTransformer t) {
        register(t, ClassResourceInspector::read, INTERMEDIARY_ENTITY, "method_5781", INTERMEDIARY_OLD_DESC, INTERMEDIARY_NEW_DESC,
                List.of(INTERMEDIARY_ENTITY));
    }

    static void register(RetromodTransformer t, Function<String, ClassNode> host, String entity, String name, String oldDesc,
            String newDesc, List<String> owners) {
        // A host that still returns Team keeps the call; the registering shims start at 1.20.3, so an unread host takes it.
        ClassNode hostEntity = host.apply(entity);
        if (hostEntity != null && hostEntity.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(oldDesc))) return;
        // One hierarchy-proved redirect, since several would make a mod entity's own call ambiguous.
        t.registerInheritedMethodRedirect(entity, name, oldDesc, entity, name, newDesc);
        for (String owner : owners) {
            if (!owner.equals(entity)) t.registerMethodRedirect(owner, name, oldDesc, owner, name, newDesc);
        }
    }
}
