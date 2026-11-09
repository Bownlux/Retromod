/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.commons.SimpleRemapper;

import java.util.Map;

/** Keeps pre-1.21.5 ticket types and region ticket calls linking on the 1.21.5 ticket system. */
public final class LegacyChunkTicketBridge {

    static final String CACHE = "net/minecraft/server/level/ServerChunkCache";
    static final String HEAD = "(Lnet/minecraft/server/level/TicketType;Lnet/minecraft/world/level/ChunkPos;I";
    static final String NEW = HEAD + ")V";
    static final String VANILLA = HEAD + "Ljava/lang/Object;)V";
    /** The Forge and NeoForge overload with a trailing forceTicks flag. */
    static final String FORCE_TICKS = HEAD + "Ljava/lang/Object;Z)V";

    static final String INTERMEDIARY_CACHE = "net/minecraft/class_3215";
    static final String INTERMEDIARY_HEAD = "(Lnet/minecraft/class_3230;Lnet/minecraft/class_1923;I";

    static final String TICKET_TYPE = "net/minecraft/server/level/TicketType";
    static final String INTERMEDIARY_TICKET_TYPE = "net/minecraft/class_3230";
    public static final String TYPES = RebuiltApiClasses.ROOT + "minecraft/server/level/LegacyTicketTypes";
    static final String INTERMEDIARY_TYPES = RebuiltApiClasses.ROOT + "intermediary/minecraft/server/level/LegacyTicketTypes";
    // The same helper in the intermediary names a Fabric host before 26.1 runs.
    static final SimpleRemapper INTERMEDIARY_NAMES = new SimpleRemapper(Map.of(
            TYPES, INTERMEDIARY_TYPES, TICKET_TYPE, INTERMEDIARY_TICKET_TYPE));

    private LegacyChunkTicketBridge() {}

    /** The Mojang-named calls: Forge and NeoForge mods, and Fabric mods after the 26.x remap. */
    public static void register(RetromodTransformer transformer) {
        // 1.21.5 keys a ticket by type and level only, so the value and the forceTicks flag are popped.
        for (String desc : new String[]{VANILLA, FORCE_TICKS}) {
            transformer.registerArgDropMethodRedirect(CACHE, "addRegionTicket", desc,
                    CACHE, "addTicketWithRadius", NEW);
            transformer.registerArgDropMethodRedirect(CACHE, "removeRegionTicket", desc,
                    CACHE, "removeTicketWithRadius", NEW);
        }
        // TicketType.create went when ticket types became registered records, so each old type becomes a new unregistered one.
        RebuiltApiClasses.registerTree(transformer, TYPES);
        redirectCreate(transformer, TICKET_TYPE, "create", "create", TYPES);
    }

    /** The same calls in the intermediary names a Fabric 1.21.5 to 1.21.11 host runs. */
    public static void registerIntermediary(RetromodTransformer transformer) {
        // method_17297 and method_17300 are gone from the 1.21.5 intermediary, which added these two.
        String oldDesc = INTERMEDIARY_HEAD + "Ljava/lang/Object;)V";
        String newDesc = INTERMEDIARY_HEAD + ")V";
        transformer.registerArgDropMethodRedirect(INTERMEDIARY_CACHE, "method_17297", oldDesc,
                INTERMEDIARY_CACHE, "method_66009", newDesc);
        transformer.registerArgDropMethodRedirect(INTERMEDIARY_CACHE, "method_17300", oldDesc,
                INTERMEDIARY_CACHE, "method_66010", newDesc);
        RebuiltApiClasses.registerTree(transformer, TYPES, INTERMEDIARY_NAMES);
        redirectCreate(transformer, INTERMEDIARY_TICKET_TYPE, "method_14291", "method_20628", INTERMEDIARY_TYPES);
    }

    private static void redirectCreate(RetromodTransformer transformer, String type, String create, String createTimed, String helper) {
        String desc = "(Ljava/lang/String;Ljava/util/Comparator;)L" + type + ";";
        String timedDesc = "(Ljava/lang/String;Ljava/util/Comparator;I)L" + type + ";";
        transformer.registerMethodRedirect(type, create, desc, helper, "create", desc);
        transformer.registerMethodRedirect(type, createTimed, timedDesc, helper, "create", timedDesc);
    }
}
