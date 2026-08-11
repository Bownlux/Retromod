/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.VersionShim;

/** Forge 1.19.3 to 1.19.4: DamageSource's string ctor became registry-driven DamageType, and GENERIC/FALL were removed. */
public class Forge_1_19_3_to_1_19_4 implements VersionShim {

    @Override public String getShimName() { return "Forge 1.19.3 to 1.19.4"; }
    @Override public String getSourceVersion() { return "1.19.3"; }
    @Override public String getTargetVersion() { return "1.19.4"; }
    @Override public String getModLoaderType() { return "forge"; }

    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        transformer.registerConstructorRedirect(
            "net/minecraft/world/damagesource/DamageSource",
            "(Ljava/lang/String;)V",
            "com/retromod/shim/forge/embedded/DamageSourceShim", "create",
            "(Ljava/lang/String;)Lnet/minecraft/world/damagesource/DamageSource;"
        );
        transformer.registerFieldRedirect(
            "net/minecraft/world/damagesource/DamageSource", "GENERIC",
            "com/retromod/shim/forge/embedded/DamageSourceShim", "GENERIC"
        );
        transformer.registerFieldRedirect(
            "net/minecraft/world/damagesource/DamageSource", "FALL",
            "com/retromod/shim/forge/embedded/DamageSourceShim", "FALL"
        );
    }

    @Override
    public String[] getShimClasses() {
        return new String[0];
    }
}
