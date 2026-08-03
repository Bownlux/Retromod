/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 * 
 * Cloth Config API Compatibility Shim
 */
package com.retromod.shim.api.fabric;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.VersionShim;

/** Cloth Config API shim (v4.x through v11.x): bridges Builder/entry-builder API changes. */
public class ClothConfigApiShim implements VersionShim {
    
    @Override
    public String getShimName() {
        return "Cloth Config API Compatibility";
    }
    
    @Override
    public String getSourceVersion() {
        return "4.0.0";
    }
    
    @Override
    public String getTargetVersion() {
        return "11.0.0";
    }
    
    @Override
    public String getModLoaderType() {
        return "fabric";
    }
    
    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        registerAutoConfigClientMove(transformer);

        // old package was me.shedaniel.clothconfig, now clothconfig2
        transformer.registerClassRedirect(
            "me/shedaniel/clothconfig/api/ConfigBuilder",
            "me/shedaniel/clothconfig2/api/ConfigBuilder"
        );

        transformer.registerClassRedirect(
            "me/shedaniel/clothconfig/api/ConfigCategory",
            "me/shedaniel/clothconfig2/api/ConfigCategory"
        );

        transformer.registerClassRedirect(
            "me/shedaniel/clothconfig/api/ConfigEntryBuilder",
            "me/shedaniel/clothconfig2/api/ConfigEntryBuilder"
        );

        // v6.x (MC 1.17-1.19) -> v11.x
        transformer.registerMethodRedirect(
            "me/shedaniel/clothconfig2/api/ConfigBuilder",
            "getOrCreateCategory",
            "(Lnet/minecraft/text/Text;)Lme/shedaniel/clothconfig2/api/ConfigCategory;",
            "com/retromod/shim/api/fabric/embedded/ClothConfigShim",
            "getOrCreateCategoryCompat",
            "(Ljava/lang/Object;Lnet/minecraft/text/Text;)Ljava/lang/Object;"
        );

        transformer.registerClassRedirect(
            "me/shedaniel/clothconfig2/gui/entries/SubCategoryListEntry$Builder",
            "me/shedaniel/clothconfig2/impl/builders/SubCategoryBuilder"
        );

        // setTooltipSupplier was removed in later versions
        transformer.registerMethodRedirect(
            "me/shedaniel/clothconfig2/api/AbstractConfigListEntry",
            "setTooltipSupplier",
            "(Ljava/util/function/Supplier;)V",
            "com/retromod/shim/api/fabric/embedded/ClothConfigShim",
            "setTooltipSupplierCompat",
            "(Ljava/lang/Object;Ljava/util/function/Supplier;)V"
        );

        transformer.registerMethodRedirect(
            "me/shedaniel/clothconfig2/api/AbstractConfigListEntry",
            "setErrorSupplier",
            "(Ljava/util/function/Supplier;)V",
            "com/retromod/shim/api/fabric/embedded/ClothConfigShim",
            "setErrorSupplierCompat",
            "(Ljava/lang/Object;Ljava/util/function/Supplier;)V"
        );

        transformer.registerMethodRedirect(
            "me/shedaniel/clothconfig2/api/ConfigBuilder",
            "setSavingRunnable",
            "(Ljava/lang/Runnable;)V",
            "com/retromod/shim/api/fabric/embedded/ClothConfigShim",
            "setSavingRunnableCompat",
            "(Ljava/lang/Object;Ljava/lang/Runnable;)V"
        );

        transformer.registerMethodRedirect(
            "me/shedaniel/clothconfig2/api/ConfigBuilder",
            "create",
            "()Lme/shedaniel/clothconfig2/api/ConfigBuilder;",
            "com/retromod/shim/api/fabric/embedded/ClothConfigShim",
            "createBuilder",
            "()Ljava/lang/Object;"
        );

        // startStrField was renamed to startTextField
        transformer.registerMethodRedirect(
            "me/shedaniel/clothconfig2/api/ConfigEntryBuilder",
            "startStrField",
            "(Lnet/minecraft/text/Text;Ljava/lang/String;)Lme/shedaniel/clothconfig2/impl/builders/StringFieldBuilder;",
            "me/shedaniel/clothconfig2/api/ConfigEntryBuilder",
            "startTextField",
            "(Lnet/minecraft/text/Text;Ljava/lang/String;)Lme/shedaniel/clothconfig2/impl/builders/StringFieldBuilder;"
        );

        transformer.registerMethodRedirect(
            "me/shedaniel/clothconfig2/api/ConfigEntryBuilder",
            "startBooleanToggle",
            "(Lnet/minecraft/text/Text;Z)Lme/shedaniel/clothconfig2/impl/builders/BooleanToggleBuilder;",
            "com/retromod/shim/api/fabric/embedded/ClothConfigShim",
            "startBooleanToggle",
            "(Ljava/lang/Object;Lnet/minecraft/text/Text;Z)Ljava/lang/Object;"
        );

        // setTooltip went from Optional to varargs
        transformer.registerMethodRedirect(
            "me/shedaniel/clothconfig2/api/AbstractConfigListEntry",
            "setTooltip",
            "(Ljava/util/Optional;)V",
            "com/retromod/shim/api/fabric/embedded/ClothConfigShim",
            "setTooltipCompat",
            "(Ljava/lang/Object;Ljava/util/Optional;)V"
        );

        transformer.registerMethodRedirect(
            "me/shedaniel/clothconfig2/api/ConfigEntryBuilder",
            "startDropdownMenu",
            "(Lnet/minecraft/text/Text;Ljava/lang/Object;)Lme/shedaniel/clothconfig2/impl/builders/DropdownMenuBuilder;",
            "com/retromod/shim/api/fabric/embedded/ClothConfigShim",
            "startDropdownMenu",
            "(Ljava/lang/Object;Lnet/minecraft/text/Text;Ljava/lang/Object;)Ljava/lang/Object;"
        );

        transformer.registerMethodRedirect(
            "me/shedaniel/clothconfig2/api/ConfigEntryBuilder",
            "startColorField",
            "(Lnet/minecraft/text/Text;I)Lme/shedaniel/clothconfig2/impl/builders/ColorFieldBuilder;",
            "com/retromod/shim/api/fabric/embedded/ClothConfigShim",
            "startColorField",
            "(Ljava/lang/Object;Lnet/minecraft/text/Text;I)Ljava/lang/Object;"
        );

        // very old mods constructed LiteralText/TranslatableText directly
        transformer.registerClassRedirect(
            "net/minecraft/text/LiteralText",
            "com/retromod/shim/api/fabric/embedded/TextShim$LiteralTextShim"
        );
        
        transformer.registerClassRedirect(
            "net/minecraft/text/TranslatableText",
            "com/retromod/shim/api/fabric/embedded/TextShim$TranslatableTextShim"
        );
    }
    
    /**
     * Cloth Config's 26.1 build moved the two client-only helpers off {@code AutoConfig} onto a
     * new {@code AutoConfigClient}, keeping their signatures. A mod built against an older Cloth
     * still calls the old owner, so opening its config screen through Mod Menu dies with
     * {@code NoSuchMethodError: AutoConfig.getConfigScreen} while the rest of the mod runs (#181,
     * Double Hotbar). Verified by comparing cloth-config 20.0.149 with 26.1.154: the pair is
     * present on {@code AutoConfig} in the first and only on {@code AutoConfigClient} in the
     * second, and {@code register} and {@code getConfigHolder} did not move.
     *
     * <p>Gated to 26.1 and newer. On an older host the old owner still has these methods, and
     * pointing them at a class Cloth does not ship yet would break a mod that works today.
     */
    private void registerAutoConfigClientMove(RetromodTransformer transformer) {
        if (!com.retromod.core.RetromodVersion.isUnobfuscatedTarget(
                com.retromod.core.RetromodVersion.TARGET_MC_VERSION)) {
            return;
        }

        final String autoConfig = "me/shedaniel/autoconfig/AutoConfig";
        final String autoConfigClient = "me/shedaniel/autoconfig/AutoConfigClient";

        // Registered under both spellings of Screen, because a redirect may be matched either
        // before or after the intermediary names are rewritten.
        for (String screen : new String[]{
                "Lnet/minecraft/class_437;", "Lnet/minecraft/client/gui/screens/Screen;"}) {
            String desc = "(Ljava/lang/Class;" + screen + ")Ljava/util/function/Supplier;";
            transformer.registerMethodRedirect(
                autoConfig, "getConfigScreen", desc,
                autoConfigClient, "getConfigScreen", desc);
        }

        String guiRegistry = "(Ljava/lang/Class;)Lme/shedaniel/autoconfig/gui/registry/GuiRegistry;";
        transformer.registerMethodRedirect(
            autoConfig, "getGuiRegistry", guiRegistry,
            autoConfigClient, "getGuiRegistry", guiRegistry);
    }

    @Override
    public String[] getShimClasses() {
        return new String[] {
            "com.retromod.shim.api.fabric.embedded.ClothConfigShim",
            "com.retromod.shim.api.fabric.embedded.TextShim"
        };
    }
}
