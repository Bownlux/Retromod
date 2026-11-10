/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.event;

import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.player.PlayerEvent;

/** Forge 1.20.1's {@code PlayerEvent} class, rebuilt for mod events that extend it, such as Iceberg's pickup event. */
public class LegacyPlayerEvent extends LegacyLivingEvent implements PlayerEvent {

    public LegacyPlayerEvent(Player player) {
        super(player);
    }

    @Override
    public Player getEntity() {
        return (Player) super.getEntity();
    }
}
