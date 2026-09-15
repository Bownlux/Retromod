/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.VersionShim;

/** Forge 1.19.2 to 1.19.3: creative-tab rework, registry events, PoseStack to GuiGraphics. */
public class Forge_1_19_2_to_1_19_3 implements VersionShim {

    @Override public String getShimName() { return "Forge 1.19.2 to 1.19.3"; }
    @Override public String getSourceVersion() { return "1.19.2"; }
    @Override public String getTargetVersion() { return "1.19.3"; }
    @Override public String getModLoaderType() { return "forge"; }

    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        // A 1.19.2 mod makes a creative tab by subclassing CreativeModeTab and calling its String
        // constructor, which 1.19.3 deleted in favour of a builder. The subclass loads and then
        // dies on its own super(...) call (#264). Rebase it onto a generated bridge.
        CreativeModeTabBridge.register(transformer);

        // No class-redirect for Registry: it still exists as a type, so renaming it would break
        // every Registry<T> use. The moved statics (BLOCK, ITEM, ...) need FieldRedirects instead.
        transformer.registerMethodRedirect(
            "net/minecraft/world/item/CreativeModeTab", "builder",
            "(Lnet/minecraft/world/item/CreativeModeTab$Row;I)Lnet/minecraft/world/item/CreativeModeTab$Builder;",
            "com/retromod/shim/forge/embedded/CreativeTabShim", "builder",
            "(Ljava/lang/Object;I)Ljava/lang/Object;"
        );
        transformer.registerClassRedirect(
            "net/minecraftforge/event/RegistryEvent",
            "net/minecraftforge/registries/RegisterEvent"
        );
        transformer.registerMethodRedirect(
            "net/minecraftforge/client/event/RenderGuiOverlayEvent", "getMatrixStack",
            "()Lcom/mojang/blaze3d/vertex/PoseStack;",
            "com/retromod/shim/forge/embedded/RenderShim", "getGuiGraphics",
            "(Ljava/lang/Object;)Ljava/lang/Object;"
        );

        // Widget renamed to Renderable
        transformer.registerClassRedirect(
            "net/minecraft/client/gui/components/Widget",
            "net/minecraft/client/gui/components/Renderable"
        );

        // Loot providers renamed to the sub-provider pattern
        transformer.registerClassRedirect(
            "net/minecraft/data/loot/BlockLoot",
            "net/minecraft/data/loot/BlockLootSubProvider"
        );
        transformer.registerClassRedirect(
            "net/minecraft/data/loot/EntityLoot",
            "net/minecraft/data/loot/EntityLootSubProvider"
        );

        // ForgeRegistries field renames
        transformer.registerFieldRedirect(
            "net/minecraftforge/registries/ForgeRegistries", "CONTAINERS",
            "Lnet/minecraftforge/registries/IForgeRegistry;",
            "net/minecraftforge/registries/ForgeRegistries", "MENU_TYPES",
            "Lnet/minecraftforge/registries/IForgeRegistry;"
        );
        transformer.registerFieldRedirect(
            "net/minecraftforge/registries/ForgeRegistries", "BLOCK_ENTITIES",
            "Lnet/minecraftforge/registries/IForgeRegistry;",
            "net/minecraftforge/registries/ForgeRegistries", "BLOCK_ENTITY_TYPES",
            "Lnet/minecraftforge/registries/IForgeRegistry;"
        );
        transformer.registerFieldRedirect(
            "net/minecraftforge/registries/ForgeRegistries", "ENTITIES",
            "Lnet/minecraftforge/registries/IForgeRegistry;",
            "net/minecraftforge/registries/ForgeRegistries", "ENTITY_TYPES",
            "Lnet/minecraftforge/registries/IForgeRegistry;"
        );
    }

    @Override
    public String[] getShimClasses() {
        return new String[0];
    }
}
