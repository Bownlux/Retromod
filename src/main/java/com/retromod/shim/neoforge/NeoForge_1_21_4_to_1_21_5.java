/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.neoforge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.VersionShim;

/** 1.21.5 folded the tool/armor Item subclasses into Item (data components). */
public class NeoForge_1_21_4_to_1_21_5 implements VersionShim {
    @Override public String getShimName() { return "NeoForge 1.21.4 to 1.21.5"; }
    @Override public String getSourceVersion() { return "1.21.4"; }
    @Override public String getTargetVersion() { return "1.21.5"; }
    @Override public String getModLoaderType() { return "neoforge"; }

    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        // Vanilla item bases removed in 1.21.5, rebased only on hosts that lost them.
        com.retromod.shim.common.RemovedItemBaseBridge.registerRemovedIn(transformer, "1.21.5");
        // appendHoverText moved to TooltipDisplay + Consumer; keep old tooltip overrides called.
        com.retromod.shim.common.LegacyTooltipSynthetic.register(transformer);

        // The tool and armour classes are not redirected to Item. Redirecting them won the extends
        // slot ahead of the removed-base rebase, so a sword became "extends Item" with a super call
        // to an Item(Tier, int, float, Properties) constructor that does not exist. The rebase
        // registered above handles SwordItem, PickaxeItem, DiggerItem and ArmorItem; AxeItem,
        // ShovelItem and HoeItem still exist until 26.3, whose shim rebases them.
    }

    @Override public String[] getShimClasses() { return new String[0]; }
}
