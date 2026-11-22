/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.server.packs.repository;

import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackMetadataResources;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.repository.Pack;

/** {@code ResourcesSupplier.openPrimary}, which 26.3 split into {@code openMetadata} and {@code openResources}. */
public final class LegacyPackSuppliers {

    private LegacyPackSuppliers() {}

    /** The pack's own resources without overlays, which every 26.3 supplier also opens for its metadata. */
    public static PackResources openPrimary(Pack.ResourcesSupplier supplier, PackLocationInfo location) {
        PackMetadataResources opened = supplier.openMetadata(location);
        if (opened instanceof PackResources resources) return resources;
        // A supplier that opens only metadata has no single pack to hand out; openResources needs metadata first.
        close(opened);
        throw new UnsupportedOperationException("Retromod: an old mod opened the primary resources of pack "
                + location.id() + ", but its supplier " + supplier.getClass().getName()
                + " opens only metadata on this Minecraft version");
    }

    private static void close(PackMetadataResources opened) {
        try {
            opened.close();
        } catch (RuntimeException ignored) {
            // A metadata reader that fails to close holds only its file handle until the game exits.
        }
    }
}
