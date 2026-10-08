/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.neoforge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.VersionShim;

/** Enchantments went data-driven (rarity/level methods removed) and event APIs were restructured. */
public class NeoForge_1_20_6_to_1_21 implements VersionShim {

    @Override public String getShimName() { return "NeoForge 1.20.6 to 1.21"; }
    @Override public String getSourceVersion() { return "1.20.6"; }
    @Override public String getTargetVersion() { return "1.21"; }
    @Override public String getModLoaderType() { return "neoforge"; }

    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        // Vanilla item bases removed in 1.21, rebased only on hosts that lost them.
        com.retromod.shim.common.RemovedItemBaseBridge.registerRemovedIn(transformer, "1.21");
        com.retromod.shim.common.LegacyDyeColorBridge.register(transformer);
        // ResourceLocation(namespace, path) ctor went private in 1.21; point it at the static factory (#92)
        transformer.registerConstructorRedirect(
            "net/minecraft/resources/ResourceLocation",
            "(Ljava/lang/String;Ljava/lang/String;)V",
            "net/minecraft/resources/ResourceLocation", "fromNamespaceAndPath",
            "(Ljava/lang/String;Ljava/lang/String;)Lnet/minecraft/resources/ResourceLocation;"
        );

        // NOTE (#119): the Enchantment.getRarity/getMaxLevel/getMinLevel redirects here
        // targeted a "com/retromod/shim/neoforge/embedded/EnchantmentShim" that was never
        // written (phantom target). This shim (typed "neoforge") was ALSO firing on Fabric
        // hosts via the previously-unfiltered RetromodPreLaunch transform path: after the
        // intermediary→Mojang harvest a Fabric mod's Enchantment.getMaxLevel matched this
        // key and got rewritten to the nonexistent shim → NoClassDefFoundError the first
        // time Apollo's Enchantment Rebalance ran the anvil. Both faults are fixed: the
        // loader filter in RetromodPreLaunch stops the cross-loader bite, and these three
        // redirects are deleted because getMaxLevel()I / getMinLevel()I never left the API
        // (present 1.21 through 26.2, data-driven definition) and getRarity can't be bridged
        // by a call-site redirect alone (its Rarity return TYPE is gone too).
        // EventShim (EntityJoinLevelEvent.getLevel) was likewise a phantom target; removed.
    }

    @Override
    public String[] getShimClasses() {
        return new String[0];
    }
}
