/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.level.levelgen.structure;

import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;

/** {@code StructurePlaceSettings.setKeepLiquids}, which later versions replaced with liquid settings. */
public final class LegacyStructureSettings {

    private LegacyStructureSettings() {}

    /** {@code settings.setKeepLiquids(keep)}: keeping liquids is what applying waterlogging means now. */
    public static StructurePlaceSettings setKeepLiquids(StructurePlaceSettings settings, boolean keep) {
        return settings.setLiquidSettings(keep ? LiquidSettings.APPLY_WATERLOGGING : LiquidSettings.IGNORE_WATERLOGGING);
    }
}
