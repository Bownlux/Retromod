/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.api.fabric;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.VersionShim;

/**
 * Bridges REI (Roughly Enough Items) v3 plugin/entry APIs up to v12: EntryStack
 * creation moved to EntryStacks, REIPlugin split into client/server, and the
 * display/registration packages were renamed.
 */
public class ReiApiShim implements VersionShim {
    
    @Override
    public String getShimName() {
        return "REI API Compatibility";
    }
    
    @Override
    public String getSourceVersion() {
        return "3.0.0";
    }
    
    @Override
    public String getTargetVersion() {
        return "12.0.0";
    }
    
    @Override
    public String getModLoaderType() {
        return "fabric";
    }
    
    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        transformer.registerClassRedirect(
            "me/shedaniel/rei/api/RecipeHelper",
            "me/shedaniel/rei/api/client/registry/display/DisplayRegistry"
        );

        transformer.registerClassRedirect(
            "me/shedaniel/rei/api/EntryRegistry",
            "me/shedaniel/rei/api/client/registry/entry/EntryRegistry"
        );

        // EntryStack.create(stack/fluid) -> EntryStacks.of(...)
        transformer.registerMethodRedirect(
            "me/shedaniel/rei/api/common/entry/EntryStack",
            "create",
            "(Lnet/minecraft/item/ItemStack;)Lme/shedaniel/rei/api/common/entry/EntryStack;",
            "me/shedaniel/rei/api/common/util/EntryStacks",
            "of",
            "(Lnet/minecraft/item/ItemStack;)Lme/shedaniel/rei/api/common/entry/EntryStack;"
        );

        transformer.registerMethodRedirect(
            "me/shedaniel/rei/api/common/entry/EntryStack",
            "create",
            "(Lnet/minecraft/fluid/Fluid;)Lme/shedaniel/rei/api/common/entry/EntryStack;",
            "me/shedaniel/rei/api/common/util/EntryStacks",
            "of",
            "(Lnet/minecraft/fluid/Fluid;)Lme/shedaniel/rei/api/common/entry/EntryStack;"
        );

        // REIPlugin/REIPluginV0 collapsed into the client plugin interface
        transformer.registerClassRedirect(
            "me/shedaniel/rei/api/REIPlugin",
            "me/shedaniel/rei/api/client/plugins/REIClientPlugin"
        );

        transformer.registerClassRedirect(
            "me/shedaniel/rei/api/REIPluginV0",
            "me/shedaniel/rei/api/client/plugins/REIClientPlugin"
        );

        // registerRecipeDisplays(RecipeHelper) -> registerDisplays(DisplayRegistry)
        transformer.registerMethodRedirect(
            "me/shedaniel/rei/api/client/plugins/REIClientPlugin",
            "registerRecipeDisplays",
            "(Lme/shedaniel/rei/api/RecipeHelper;)V",
            "me/shedaniel/rei/api/client/plugins/REIClientPlugin",
            "registerDisplays",
            "(Lme/shedaniel/rei/api/client/registry/display/DisplayRegistry;)V"
        );

        // same name, new EntryRegistry package
        transformer.registerMethodRedirect(
            "me/shedaniel/rei/api/client/plugins/REIClientPlugin",
            "registerEntries",
            "(Lme/shedaniel/rei/api/EntryRegistry;)V",
            "me/shedaniel/rei/api/client/plugins/REIClientPlugin",
            "registerEntries",
            "(Lme/shedaniel/rei/api/client/registry/entry/EntryRegistry;)V"
        );

        // BasicDisplay ctor and category/widget factories route through the embedded ReiShim
        transformer.registerConstructorRedirect(
            "me/shedaniel/rei/api/common/display/basic/BasicDisplay",
            "(Ljava/util/List;Ljava/util/List;)V",
            "com/retromod/shim/api/fabric/embedded/ReiShim",
            "createBasicDisplay",
            "(Ljava/util/List;Ljava/util/List;)Ljava/lang/Object;"
        );

        transformer.registerMethodRedirect(
            "me/shedaniel/rei/api/client/registry/category/CategoryRegistry",
            "add",
            "(Lme/shedaniel/rei/api/client/registry/display/DisplayCategory;)V",
            "com/retromod/shim/api/fabric/embedded/ReiShim",
            "addCategory",
            "(Ljava/lang/Object;Ljava/lang/Object;)V"
        );

        transformer.registerMethodRedirect(
            "me/shedaniel/rei/api/client/gui/Widgets",
            "createRecipeBase",
            "(Lme/shedaniel/math/Rectangle;)Lme/shedaniel/rei/api/client/gui/widgets/Widget;",
            "com/retromod/shim/api/fabric/embedded/ReiShim",
            "createRecipeBase",
            "(Ljava/lang/Object;)Ljava/lang/Object;"
        );

        transformer.registerMethodRedirect(
            "me/shedaniel/rei/api/client/gui/Widgets",
            "createSlot",
            "(Lme/shedaniel/math/Point;)Lme/shedaniel/rei/api/client/gui/widgets/Slot;",
            "com/retromod/shim/api/fabric/embedded/ReiShim",
            "createSlot",
            "(Ljava/lang/Object;)Ljava/lang/Object;"
        );
    }
    
    @Override
    public String[] getShimClasses() {
        return new String[] {
            "com.retromod.shim.api.fabric.embedded.ReiShim"
        };
    }
}
