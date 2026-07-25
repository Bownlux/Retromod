/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.polyfill.minecraft.vanilla;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.polyfill.PolyfillProvider;

/**
 * Polyfill for vanilla Minecraft classes removed across major versions, so older mods
 * referencing them do not crash with ClassNotFoundException at startup.
 */
public class MinecraftVanillaPolyfill implements PolyfillProvider {

    @Override
    public String getName() {
        return "Minecraft Vanilla Removed APIs";
    }

    @Override
    public String getCategory() {
        return "minecraft_vanilla";
    }

    @Override
    public String[] getRemovedClasses() {
        return new String[]{
            "net/minecraft/text/LiteralText",
            "net/minecraft/text/TranslatableText",
            "net/minecraft/block/Material",
            "net/minecraft/block/MaterialColor",
            "net/minecraft/world/gen/feature/StructureFeature",
            "net/minecraft/util/LazyLoadedValue"
        };
    }

    @Override
    public String[] getPolyfillClasses() {
        return new String[]{
            "com/retromod/polyfill/minecraft/embedded/LazyLoadedValue"
        };
    }

    @Override
    public void registerPolyfills(RetromodTransformer transformer) {
        // LiteralText/TranslatableText, Material/MaterialColor and StructureFeature are
        // handled by the shim chain (calls rewritten to the Mojang names), no runtime stub.

        // LazyLoadedValue was removed in 26.1. Mojang replaced its USAGES with a plain
        // java.util.function.Supplier (e.g. InputConstants.Key.displayName is now
        // Supplier<Component>), so redirect the removed TYPE to Supplier and rewrite the
        // constructor `new LazyLoadedValue(supplier)` to the embedded polyfill's static factory
        // `LazyLoadedValue.of(Supplier):Supplier` (a memoizing wrapper that IS-A Supplier). This is
        // what lets a mixin @Accessor bind to the now-Supplier field: keeping the type as the
        // polyfill CLASS left the accessor descriptor as (LazyLoadedValue), which no longer matches
        // the Supplier field, so Mixin silently skipped it and the mod died AbstractMethodError
        // (Jade's KeyAccess/InputConstants$Key.setDisplayName, found in-game on 26.2 Fabric). The
        // old type's only API was get(), which is exactly Supplier.get(), so nothing is lost.
        //
        // Register both the Mojang name and the intermediary class_3528: the ASM remapper is
        // single-pass, so class_3528 -> LazyLoadedValue would not chain on to Supplier. The
        // constructor redirect is keyed on both raw owners too (CtorRedirectPrePass matches the raw
        // pre-remap owner as a fallback), so a Fabric mod's `new class_3528(...)` is caught before
        // the class redirect would (wrongly) turn it into `new Supplier`.
        //
        // Gated to 26.1+: below 26.1 net/minecraft/util/LazyLoadedValue still exists, and an
        // un-gated redirect would hijack the live class on a pre-26.1 NeoForge host (#17).
        if (!RetromodVersion.mcVersionExceeds("26.1", RetromodVersion.TARGET_MC_VERSION)) {
            for (String removed : new String[]{
                    "net/minecraft/util/LazyLoadedValue", "net/minecraft/class_3528"}) {
                transformer.registerClassRedirect(removed, "java/util/function/Supplier");
                transformer.registerConstructorRedirect(
                        removed, "(Ljava/util/function/Supplier;)V",
                        "com/retromod/polyfill/minecraft/embedded/LazyLoadedValue", "of",
                        "(Ljava/util/function/Supplier;)Ljava/util/function/Supplier;");
            }
        }
    }
}
