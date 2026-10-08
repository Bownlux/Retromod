/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.polyfill.fabric;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.polyfill.PolyfillProvider;

/**
 * Polyfill for Fabric registry and object builder API changes.
 *
 * The Fabric API has deprecated and removed several registry and builder classes:
 * - 1.20.5: FabricBlockSettings deprecated in favor of AbstractBlock.Settings
 * - 1.20.5: RegistryEntryRemovedCallback removed
 * - 1.20.5: FabricEntityTypeBuilder deprecated in favor of vanilla EntityType.Builder
 * - 1.21: FuelRegistry API changed (instance-based instead of static)
 *
 * Covered changes:
 * FabricRegistryBuilder is deliberately absent: every Fabric API through 26.x still ships it
 * with the same shape, so it needs no polyfill.
 * - RegistryEntryAddedCallback (API changed)
 * - RegistryEntryRemovedCallback (removed in 1.20.5)
 * - FabricBlockSettings (deprecated 1.20.5, use AbstractBlock.Settings)
 * - FabricEntityTypeBuilder (deprecated, use vanilla EntityType.Builder)
 * - FuelRegistry (API changed in 1.21)
 * - CompostingChanceRegistry (stays)
 * - FlammableBlockRegistry (stays)
 */
public class FabricRegistryPolyfill implements PolyfillProvider {

    @Override
    public String getName() {
        return "Fabric Registry API Changes";
    }

    @Override
    public String getCategory() {
        return "fabric_api";
    }

    @Override
    public String[] getRemovedClasses() {
        return new String[]{
            // Removed in 1.20.5
            "net/fabricmc/fabric/api/event/registry/RegistryEntryRemovedCallback",

            // Deprecated in 1.20.5, use AbstractBlock.Settings directly
            "net/fabricmc/fabric/api/object/builder/v1/block/FabricBlockSettings",

            // Deprecated, use vanilla EntityType.Builder
            "net/fabricmc/fabric/api/object/builder/v1/entity/FabricEntityTypeBuilder"
        };
    }

    @Override
    public String[] getPolyfillClasses() {
        // No embedded stubs needed. Class redirects handle these migrations.
        return new String[]{};
    }

    /**
     * Fabric API still ships these classes on many hosts, and a redirect would replace a working
     * reference. Fabric API 26.1 still ships FabricEntityTypeBuilder, whose factory methods do not
     * exist on EntityType.Builder. Offline transforms cannot see the host, so they keep the
     * redirect.
     */
    private static void redirectIfRemoved(RetromodTransformer transformer, String removed,
            String replacement) {
        if (ClassResourceInspector.exists(removed)) return;
        transformer.registerClassRedirect(removed, replacement);
    }

    @Override
    public void registerPolyfills(RetromodTransformer transformer) {
        // FabricBlockSettings -> AbstractBlock.Settings
        // FabricBlockSettings was a thin wrapper around vanilla settings that
        // added a few convenience methods. In 1.20.5+ it was deprecated and
        // mods should use AbstractBlock.Settings (or Block.Properties in Mojang
        // mappings) directly.

        redirectIfRemoved(transformer,
            "net/fabricmc/fabric/api/object/builder/v1/block/FabricBlockSettings",
            "net/minecraft/world/level/block/state/BlockBehaviour$Properties");

        // Common FabricBlockSettings static factory methods redirect
        if (!ClassResourceInspector.exists(
                "net/fabricmc/fabric/api/object/builder/v1/block/FabricBlockSettings")) {
            transformer.registerMethodRedirect(
                "net/fabricmc/fabric/api/object/builder/v1/block/FabricBlockSettings", "of",
                "()Lnet/fabricmc/fabric/api/object/builder/v1/block/FabricBlockSettings;",
                "net/minecraft/world/level/block/state/BlockBehaviour$Properties", "of",
                "()Lnet/minecraft/world/level/block/state/BlockBehaviour$Properties;");
        }

        transformer.registerMethodRedirect(
            "net/fabricmc/fabric/api/object/builder/v1/block/FabricBlockSettings", "copyOf",
            "(Lnet/minecraft/block/AbstractBlock;)Lnet/fabricmc/fabric/api/object/builder/v1/block/FabricBlockSettings;",
            "net/minecraft/world/level/block/state/BlockBehaviour$Properties", "ofFullCopy",
            "(Lnet/minecraft/world/level/block/state/BlockBehaviour;)Lnet/minecraft/world/level/block/state/BlockBehaviour$Properties;");

        // FabricEntityTypeBuilder -> EntityType.Builder
        // FabricEntityTypeBuilder was deprecated in favor of vanilla's
        // EntityType.Builder which now supports all the same features.

        redirectIfRemoved(transformer,
            "net/fabricmc/fabric/api/object/builder/v1/entity/FabricEntityTypeBuilder",
            "net/minecraft/world/entity/EntityType$Builder");

        // FabricRegistryBuilder is not redirected. Fabric API still ships it on every host, and
        // RegistrySetBuilder is a datagen builder with none of its methods, so the old redirect
        // turned every FabricRegistryBuilder.from(...) into NoSuchMethodError (#249). On
        // intermediary hosts it also pointed at a Mojang-named class that does not exist there.

        // RegistryEntryRemovedCallback (removed in 1.20.5)
        // No direct replacement; registry entries are no longer removable.
        // Redirect to RegistryEntryAddedCallback to prevent CNFE; runtime
        // behavior will differ but the mod won't crash at load time.

        redirectIfRemoved(transformer,
            "net/fabricmc/fabric/api/event/registry/RegistryEntryRemovedCallback",
            "net/fabricmc/fabric/api/event/registry/RegistryEntryAddedCallback");

        // RegistryEntryAddedCallback API changes
        // The event callback signature changed from (rawId, id, object) to
        // (registryEntry) in newer versions.

        transformer.registerMethodRedirect(
            "net/fabricmc/fabric/api/event/registry/RegistryEntryAddedCallback", "event",
            "(Lnet/minecraft/util/registry/Registry;)Lnet/fabricmc/fabric/api/event/Event;",
            "net/fabricmc/fabric/api/event/registry/RegistryEntryAddedCallback", "event",
            "(Lnet/minecraft/core/Registry;)Lnet/fabricmc/fabric/api/event/Event;");

        // FuelRegistry API changes in 1.21
        // Old: FuelRegistry.INSTANCE.add(item, burnTime)
        // New: FuelRegistry.INSTANCE.add(item, burnTime) (stays but types changed)
        // The class itself stays, but parameter types use Mojang mappings now.

        transformer.registerMethodRedirect(
            "net/fabricmc/fabric/api/registry/FuelRegistry", "add",
            "(Lnet/minecraft/item/ItemConvertible;I)V",
            "net/fabricmc/fabric/api/registry/FuelRegistry", "add",
            "(Lnet/minecraft/world/level/ItemLike;I)V");

        transformer.registerMethodRedirect(
            "net/fabricmc/fabric/api/registry/FuelRegistry", "add",
            "(Lnet/minecraft/tag/Tag;I)V",
            "net/fabricmc/fabric/api/registry/FuelRegistry", "add",
            "(Lnet/minecraft/tags/TagKey;I)V");
    }
}
