/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.fabric;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.VersionShim;

/** Fabric 1.19.3 to 1.19.4: DamageSource to DamageType registry rework, recipe and display-context renames. */
public class Fabric_1_19_3_to_1_19_4 implements VersionShim {

    @Override public String getShimName() { return "Fabric 1.19.3 to 1.19.4"; }
    @Override public String getSourceVersion() { return "1.19.3"; }
    @Override public String getTargetVersion() { return "1.19.4"; }
    @Override public String getModLoaderType() { return "fabric"; }

    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        // DamageSource reworked onto the DamageType registry
        transformer.registerConstructorRedirect(
            "net/minecraft/entity/damage/DamageSource",
            "(Ljava/lang/String;)V",
            "com/retromod/shim/fabric/embedded/DamageSourceShim", "create",
            "(Ljava/lang/String;)Lnet/minecraft/entity/damage/DamageSource;"
        );
        transformer.registerFieldRedirect(
            "net/minecraft/entity/damage/DamageSource", "GENERIC",
            "com/retromod/shim/fabric/embedded/DamageSourceShim", "GENERIC"
        );
        transformer.registerFieldRedirect(
            "net/minecraft/entity/damage/DamageSource", "FALL",
            "com/retromod/shim/fabric/embedded/DamageSourceShim", "FALL"
        );
        transformer.registerFieldRedirect(
            "net/minecraft/entity/damage/DamageSource", "DROWN",
            "com/retromod/shim/fabric/embedded/DamageSourceShim", "DROWN"
        );
        transformer.registerFieldRedirect(
            "net/minecraft/entity/damage/DamageSource", "IN_FIRE",
            "com/retromod/shim/fabric/embedded/DamageSourceShim", "IN_FIRE"
        );
        transformer.registerMethodRedirect(
            "net/minecraft/recipe/RecipeManager", "getFirstMatch",
            "(Lnet/minecraft/recipe/RecipeType;Lnet/minecraft/inventory/Inventory;Lnet/minecraft/world/World;)Ljava/util/Optional;",
            "com/retromod/shim/fabric/embedded/RecipeShim", "getFirstMatch",
            "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/Optional;"
        );

        transformer.registerClassRedirect(
            "net/minecraft/client/renderer/block/model/ItemTransforms$TransformType",
            "net/minecraft/world/item/ItemDisplayContext"
        );
        transformer.registerClassRedirect(
            "net/minecraft/world/item/Wearable",
            "net/minecraft/world/item/Equipable"
        );
        transformer.registerMethodRedirect(
            "net/minecraft/world/level/biome/Biome", "isHumid",
            "()Z",
            "net/minecraft/world/level/biome/Biome", "hasPrecipitation",
            "()Z"
        );
        transformer.registerMethodRedirect(
            "net/minecraft/client/gui/components/AbstractWidget", "renderButton",
            "(Lnet/minecraft/client/gui/GuiGraphics;IIF)V",
            "net/minecraft/client/gui/components/AbstractWidget", "renderWidget",
            "(Lnet/minecraft/client/gui/GuiGraphics;IIF)V"
        );
        transformer.registerMethodRedirect(
            "net/minecraft/world/entity/Entity", "rideableUnderWater",
            "()Z",
            "net/minecraft/world/entity/Entity", "dismountsUnderwater",
            "()Z"
        );
    }

    @Override
    public String[] getShimClasses() {
        return new String[0];
    }
}
