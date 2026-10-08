/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;

/**
 * Follows vanilla renames from Minecraft 1.19 that 1.18.2 mods still use.
 *
 * <p>1.19 renamed several {@code GameEvent} constants when it generalized them for the warden, and
 * folded {@code ClientboundAddMobPacket} into {@code ClientboundAddEntityPacket}. A mob that sends
 * its own spawn packet or overrides {@code recreateFromPacket} named the deleted class, so its
 * override was never called and its spawn packet failed to link. The old names do not exist on a
 * 1.19 or newer host, so no current mod is affected.
 */
public final class LegacyMinecraft119Bridge {

    private static final String GAME_EVENT = "net/minecraft/world/level/gameevent/GameEvent";

    /** 1.18.2 game event constant to its 1.19 name. */
    static final String[][] GAME_EVENT_RENAMES = {
        {"ENTITY_DAMAGED", "ENTITY_DAMAGE"},
        {"ENTITY_KILLED", "ENTITY_DIE"},
        {"MOB_INTERACT", "ENTITY_INTERACT"},
        {"RAVAGER_ROAR", "ENTITY_ROAR"},
        {"WOLF_SHAKING", "ENTITY_SHAKE"},
        {"DRINKING_FINISH", "DRINK"},
        {"ELYTRA_FREE_FALL", "ELYTRA_GLIDE"},
    };

    private LegacyMinecraft119Bridge() {
    }

    public static void register(RetromodTransformer transformer) {
        String desc = "L" + GAME_EVENT + ";";
        for (String[] rename : GAME_EVENT_RENAMES) {
            transformer.registerFieldRedirect(GAME_EVENT, rename[0], desc, GAME_EVENT, rename[1], desc);
        }
        LegacyVillagerBridge.register(transformer);
        transformer.registerClassRedirect("net/minecraft/network/protocol/game/ClientboundAddMobPacket",
                "net/minecraft/network/protocol/game/ClientboundAddEntityPacket");
    }
}
