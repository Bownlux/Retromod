/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.VersionShim;

/** Forge 1.19.4 to 1.20: Material removed, MaterialColor renamed to NoteBlockInstrument, sign API changed. */
public class Forge_1_19_4_to_1_20 implements VersionShim {

    @Override public String getShimName() { return "Forge 1.19.4 to 1.20"; }
    @Override public String getSourceVersion() { return "1.19.4"; }
    @Override public String getTargetVersion() { return "1.20"; }
    @Override public String getModLoaderType() { return "forge"; }

    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        transformer.registerMethodRename(
            "net/minecraft/world/level/block/state/BlockBehaviour$Properties",
            "noDrops", "noLootTable"
        );
        transformer.registerMethodRedirect(
            "net/minecraft/world/level/block/state/BlockBehaviour$Properties", "of",
            "(Lnet/minecraft/world/level/material/Material;)Lnet/minecraft/world/level/block/state/BlockBehaviour$Properties;",
            "net/minecraft/world/level/block/state/BlockBehaviour$Properties", "of",
            "()Lnet/minecraft/world/level/block/state/BlockBehaviour$Properties;"
        );
        transformer.registerClassRedirect(
            "net/minecraft/world/level/material/Material",
            "com/retromod/shim/forge/embedded/MaterialShim"
        );
        transformer.registerClassRedirect(
            "net/minecraft/world/level/material/MaterialColor",
            "net/minecraft/world/level/block/state/properties/NoteBlockInstrument"
        );
        transformer.registerMethodRedirect(
            "net/minecraft/world/level/block/entity/SignBlockEntity", "getMessage",
            "(I)Lnet/minecraft/network/chat/Component;",
            "com/retromod/shim/forge/embedded/SignShim", "getMessage",
            "(Ljava/lang/Object;I)Ljava/lang/Object;"
        );
    }

    @Override
    public String[] getShimClasses() {
        return new String[0];
    }
}
