/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.capabilities;

import com.retromod.legacyapi.forge.common.util.LegacyNBTSerializable;
import net.minecraft.nbt.Tag;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;

/** Forge 1.20.1's {@code ICapabilitySerializable}: still Forge 26.2's, with the old save and load of {@link LegacyNBTSerializable}. */
public interface LegacyCapabilitySerializable<T extends Tag> extends ICapabilitySerializable<T>, LegacyNBTSerializable<T> {
}
