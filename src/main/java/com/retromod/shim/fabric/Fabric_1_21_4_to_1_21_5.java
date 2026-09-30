/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.fabric;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.VersionShim;

/** Shim for Fabric 1.21.4 to 1.21.5. */
public class Fabric_1_21_4_to_1_21_5 implements VersionShim {
    @Override public String getShimName() { return "Fabric 1.21.4 to 1.21.5"; }
    @Override public String getSourceVersion() { return "1.21.4"; }
    @Override public String getTargetVersion() { return "1.21.5"; }
    @Override public String getModLoaderType() { return "fabric"; }

    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        // The tooltip adapter matches Mojang names, which a Fabric mod only has after the 26.x remap.
        if (com.retromod.core.RetromodVersion.isUnobfuscatedTarget(
                com.retromod.core.RetromodVersion.TARGET_MC_VERSION)) {
            com.retromod.shim.common.LegacyTooltipSynthetic.register(transformer);
        }

        // The tool and armour classes are not redirected to Item. Redirecting them won the extends
        // slot ahead of the removed-base rebase, so a sword became "extends Item" with a super call
        // to an Item(Tier, int, float, Properties) constructor that does not exist. The rebase
        // registered from 26.1 handles SwordItem, PickaxeItem, DiggerItem and ArmorItem; AxeItem,
        // ShovelItem and HoeItem still exist until 26.3, whose shim rebases them.

        transformer.registerClassRedirect(
            "net/minecraft/world/level/saveddata/maps/ForcedChunksSavedData",
            "net/minecraft/world/level/saveddata/maps/TicketStorage"
        );

        // blockUsingShield became blockUsingItem (any blocking item, not just shields).
        transformer.registerMethodRedirect(
            "net/minecraft/world/entity/LivingEntity", "blockUsingShield",
            "(Lnet/minecraft/world/entity/LivingEntity;)V",
            "net/minecraft/world/entity/LivingEntity", "blockUsingItem",
            "(Lnet/minecraft/world/entity/LivingEntity;)V"
        );
    }
    
    @Override public String[] getShimClasses() { return new String[0]; }
}
