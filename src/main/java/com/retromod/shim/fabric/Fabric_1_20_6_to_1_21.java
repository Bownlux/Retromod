/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.fabric;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.VersionShim;

/** Fabric 1.20.6 -> 1.21: data-driven enchantments and EnchantmentHelper signature changes. */
public class Fabric_1_20_6_to_1_21 implements VersionShim {

    @Override public String getShimName() { return "Fabric 1.20.6 to 1.21"; }
    @Override public String getSourceVersion() { return "1.20.6"; }
    @Override public String getTargetVersion() { return "1.21"; }
    @Override public String getModLoaderType() { return "fabric"; }

    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        // NOTE: this shim previously redirected Enchantment.getRarity/getMaxLevel/getMinLevel
        // and EnchantmentHelper.getLevel to a "com/retromod/shim/fabric/embedded/EnchantmentShim"
        // that was never written (a phantom target). Those redirects were removed (#119):
        //   - getMaxLevel()I and getMinLevel()I never left the API (present 1.21 through 26.2,
        //     backed by the data-driven definition), so redirecting them is wrong regardless.
        //   - the keys are Yarn class names (net/minecraft/enchantment/Enchantment), which never
        //     appear in distributed intermediary Fabric mods nor the Mojang runtime (pitfall 17),
        //     so they were dead on Fabric anyway; the crash came from the NeoForge sibling firing
        //     on Fabric (now gated by the loader filter in RetromodPreLaunch).
        //   - getRarity was genuinely removed, but a call-site method-redirect alone can't bridge
        //     it (the Enchantment$Rarity TYPE is gone too, so downstream CHECKCAST/field stores
        //     would NoClassDefFoundError); a proper synthetic-Rarity bridge is deferred.

        // 1.21 removed the 2-arg ResourceLocation ctor; route to the static factory.
        transformer.registerConstructorRedirect(
            "net/minecraft/resources/ResourceLocation",
            "(Ljava/lang/String;Ljava/lang/String;)V",
            "net/minecraft/resources/ResourceLocation", "fromNamespaceAndPath",
            "(Ljava/lang/String;Ljava/lang/String;)Lnet/minecraft/resources/ResourceLocation;"
        );

        // Intermediary-keyed copy for Fabric mods on a pre-26.1 host (remap off, 2-arg ctor still
        // private, #36). class_2960 = ResourceLocation, method_60655 = the factory.
        transformer.registerConstructorRedirect(
            "net/minecraft/class_2960",
            "(Ljava/lang/String;Ljava/lang/String;)V",
            "net/minecraft/class_2960", "method_60655",
            "(Ljava/lang/String;Ljava/lang/String;)Lnet/minecraft/class_2960;"
        );

        // Entity.changeDimension -> teleportTo (renamed in 1.21)
        transformer.registerMethodRedirect(
            "net/minecraft/world/entity/Entity", "changeDimension",
            "(Lnet/minecraft/server/level/ServerLevel;)Lnet/minecraft/world/entity/Entity;",
            "net/minecraft/world/entity/Entity", "teleportTo",
            "(Lnet/minecraft/server/level/ServerLevel;)Lnet/minecraft/world/entity/Entity;"
        );
    }

    @Override
    public String[] getShimClasses() {
        return new String[0];
    }
}
