/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.api.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.AuxiliaryVersionShim;
import com.retromod.shim.api.common.LibraryMoves;

/**
 * Curios API shim (accessory slots on Forge/NeoForge). Bridges the v1 to v5 breaks:
 * ICurio signatures, slot context, registration, capabilities.
 */
public class CuriosApiShim implements AuxiliaryVersionShim {

    // getCuriosHelper and SlotTypePreset are still in 9.5.1 and gone in 17.0.0; the release between that dropped
    // them was not checked, so with no Curios readable only 26.3 counts.
    static final LibraryMoves.Table CURIOS_HELPER_GONE = new LibraryMoves.Table("26.3", "26.3");
    
    @Override
    public String getShimName() {
        return "Curios API Compatibility";
    }
    
    @Override
    public String getSourceVersion() {
        return "1.0.0";
    }
    
    @Override
    public String getTargetVersion() {
        return "5.0.0";
    }
    
    @Override
    public String getModLoaderType() {
        return "forge"; // applies to neoforge too
    }

    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        transformer.registerClassRedirect(
            "top/theillusivec4/curios/api/CuriosApi",
            "top/theillusivec4/curios/api/CuriosApi"
        );

        // ICurio.onEquip(slot, entity) -> onEquip(SlotContext, previousStack)
        transformer.registerMethodRedirect(
            "top/theillusivec4/curios/api/type/capability/ICurio",
            "onEquip",
            "(Ljava/lang/String;Lnet/minecraft/world/entity/LivingEntity;)V",
            "com/retromod/shim/api/forge/embedded/CuriosShim",
            "onEquip",
            "(Ljava/lang/Object;Ljava/lang/String;Lnet/minecraft/world/entity/LivingEntity;)V"
        );

        transformer.registerMethodRedirect(
            "top/theillusivec4/curios/api/type/capability/ICurio",
            "onUnequip",
            "(Ljava/lang/String;Lnet/minecraft/world/entity/LivingEntity;)V",
            "com/retromod/shim/api/forge/embedded/CuriosShim",
            "onUnequip",
            "(Ljava/lang/Object;Ljava/lang/String;Lnet/minecraft/world/entity/LivingEntity;)V"
        );

        // ICurio.curioTick(slot, entity) -> curioTick(SlotContext)
        transformer.registerMethodRedirect(
            "top/theillusivec4/curios/api/type/capability/ICurio",
            "curioTick",
            "(Ljava/lang/String;Lnet/minecraft/world/entity/LivingEntity;)V",
            "com/retromod/shim/api/forge/embedded/CuriosShim",
            "curioTick",
            "(Ljava/lang/Object;Ljava/lang/String;Lnet/minecraft/world/entity/LivingEntity;)V"
        );

        transformer.registerClassRedirect(
            "top/theillusivec4/curios/api/type/capability/ICurio$DropRule",
            "top/theillusivec4/curios/api/type/capability/ICurio$DropRule"
        );

        LibraryMoves.registerMemberRedirect(transformer,
            "top/theillusivec4/curios/api/CuriosApi",
            "getCuriosHelper",
            "()Ltop/theillusivec4/curios/api/type/util/ICuriosHelper;",
            "com/retromod/shim/api/forge/embedded/CuriosShim",
            "getCuriosHelper",
            "()Ljava/lang/Object;",
            CURIOS_HELPER_GONE
        );
        
        // getCuriosHandler and getAttributeModifiers(String) stay unbridged: the old calls expect LazyOptional and a
        // Multimap the embedded helpers do not return, so a redirect would fail the whole class when it loads.

        LibraryMoves.registerMemberRedirect(transformer,
            "top/theillusivec4/curios/api/SlotTypePreset",
            "getIdentifier",
            "()Ljava/lang/String;",
            "com/retromod/shim/api/forge/embedded/CuriosShim",
            "getSlotIdentifier",
            "(Ljava/lang/Object;)Ljava/lang/String;",
            CURIOS_HELPER_GONE
        );

        transformer.registerMethodRedirect(
            "top/theillusivec4/curios/api/CuriosApi",
            "enqueueSlotType",
            "(Ljava/lang/String;Ltop/theillusivec4/curios/api/type/ISlotType;)V",
            "com/retromod/shim/api/forge/embedded/CuriosShim",
            "registerSlotType",
            "(Ljava/lang/String;Ljava/lang/Object;)V"
        );

        // ICurio.canRender(slot, entity) -> canRender(SlotContext)
        transformer.registerMethodRedirect(
            "top/theillusivec4/curios/api/type/capability/ICurio",
            "canRender",
            "(Ljava/lang/String;Lnet/minecraft/world/entity/LivingEntity;)Z",
            "com/retromod/shim/api/forge/embedded/CuriosShim",
            "canRender",
            "(Ljava/lang/Object;Ljava/lang/String;Lnet/minecraft/world/entity/LivingEntity;)Z"
        );

        transformer.registerMethodRedirect(
            "top/theillusivec4/curios/api/type/capability/ICurio",
            "render",
            "(Ljava/lang/String;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/LivingEntity;FFFFFF)V",
            "com/retromod/shim/api/forge/embedded/CuriosShim",
            "render",
            "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;ILnet/minecraft/world/entity/LivingEntity;FFFFFF)V"
        );
    }
    
    @Override
    public String[] getShimClasses() {
        return new String[] {
            "com.retromod.shim.api.forge.embedded.CuriosShim"
        };
    }
}
