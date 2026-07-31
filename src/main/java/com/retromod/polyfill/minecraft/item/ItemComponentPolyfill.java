/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.polyfill.minecraft.item;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.polyfill.PolyfillProvider;

/**
 * Polyfill for the 1.20.5+ item component system changes (NBT → DataComponents).
 *
 * In 1.20.5, Mojang replaced the NBT-based ItemStack tag system with a typed
 * DataComponent system. The old methods (getTag, setTag, hasTag, getOrCreateTag)
 * no longer exist on ItemStack. This polyfill redirects those calls to an
 * embedded bridge class that uses reflection to access either the new
 * DataComponents.CUSTOM_DATA component or the old NBT API, depending on
 * what's available at runtime.
 *
 * <p><b>Host-gated to 1.20.5+.</b> Below 1.20.5 the NBT methods still exist on
 * {@code ItemStack}, so redirecting them would replace a working call with the
 * component-based bridge before components exist. The from-owner is the live
 * Mojang {@code net/minecraft/world/item/ItemStack}, so on a pre-1.20.5 NeoForge
 * host (Mojang-named mods) the redirect WOULD fire; gate it.
 *
 * Method redirects:
 * - ItemStack.getTag() / getTagCompound() → ItemStackNbtBridge.getTag(itemStack)
 * - ItemStack.setTag(CompoundTag) → ItemStackNbtBridge.setTag(itemStack, tag)
 * - ItemStack.hasTag() → ItemStackNbtBridge.hasTag(itemStack)
 * - ItemStack.getOrCreateTag() → ItemStackNbtBridge.getOrCreateTag(itemStack)
 */
public class ItemComponentPolyfill implements PolyfillProvider {

    @Override
    public String getName() {
        return "Minecraft Item Component System (NBT → DataComponents)";
    }

    @Override
    public String getCategory() {
        return "minecraft_vanilla";
    }

    @Override
    public String[] getRemovedClasses() {
        // No classes were removed; the methods on ItemStack changed signature
        return new String[]{};
    }

    @Override
    public String[] getPolyfillClasses() {
        return new String[]{
            "com.retromod.polyfill.minecraft.item.embedded.ItemStackNbtBridge"
        };
    }

    @Override
    public void registerPolyfills(RetromodTransformer transformer) {
        // host >= 1.20.5  <=>  "1.20.5" is not strictly greater than the target.
        if (RetromodVersion.mcVersionExceeds("1.20.5", RetromodVersion.TARGET_MC_VERSION)) {
            return; // ItemStack.getTag()/setTag()/... still exist below 1.20.5.
        }

        // Register embedded shim classes so they get injected into transformed mod JARs
        for (String cls : getPolyfillClasses()) {
            transformer.registerEmbeddedShim(cls);
        }

        // ItemStack NBT method redirects (1.20.5+)

        // getTag() → ItemStackNbtBridge.getTag(itemStack)
        // Instance method on ItemStack becomes static call with itemStack as first arg
        transformer.registerMethodRedirect(
            "net/minecraft/world/item/ItemStack",
            "getTag",
            "()Lnet/minecraft/nbt/CompoundTag;",
            "com/retromod/polyfill/minecraft/item/embedded/ItemStackNbtBridge",
            "getTag",
            "(Ljava/lang/Object;)Ljava/lang/Object;"
        );

        // getTagCompound(), the older Forge name for the same method
        transformer.registerMethodRedirect(
            "net/minecraft/world/item/ItemStack",
            "getTagCompound",
            "()Lnet/minecraft/nbt/CompoundTag;",
            "com/retromod/polyfill/minecraft/item/embedded/ItemStackNbtBridge",
            "getTag",
            "(Ljava/lang/Object;)Ljava/lang/Object;"
        );

        // hasTag() → ItemStackNbtBridge.hasTag(itemStack)
        transformer.registerMethodRedirect(
            "net/minecraft/world/item/ItemStack",
            "hasTag",
            "()Z",
            "com/retromod/polyfill/minecraft/item/embedded/ItemStackNbtBridge",
            "hasTag",
            "(Ljava/lang/Object;)Z"
        );

        // setTag(CompoundTag) → ItemStackNbtBridge.setTag(itemStack, tag)
        transformer.registerMethodRedirect(
            "net/minecraft/world/item/ItemStack",
            "setTag",
            "(Lnet/minecraft/nbt/CompoundTag;)V",
            "com/retromod/polyfill/minecraft/item/embedded/ItemStackNbtBridge",
            "setTag",
            "(Ljava/lang/Object;Ljava/lang/Object;)V"
        );

        // getOrCreateTag() → ItemStackNbtBridge.getOrCreateTag(itemStack)
        transformer.registerMethodRedirect(
            "net/minecraft/world/item/ItemStack",
            "getOrCreateTag",
            "()Lnet/minecraft/nbt/CompoundTag;",
            "com/retromod/polyfill/minecraft/item/embedded/ItemStackNbtBridge",
            "getOrCreateTag",
            "(Ljava/lang/Object;)Ljava/lang/Object;"
        );
    }
}
