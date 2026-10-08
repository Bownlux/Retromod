/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package net.minecraft.world.item.alchemy;

import java.util.List;
import java.util.Optional;

/**
 * Test double with the 1.21.1 shape of Minecraft's {@code PotionContents} record. The runtime
 * bridge finds the record by this exact name, so the double has to live in Minecraft's package.
 */
public record PotionContents(Optional<String> potion, Optional<Integer> customColor, List<Object> customEffects) {}
