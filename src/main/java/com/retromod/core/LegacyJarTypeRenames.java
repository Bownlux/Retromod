/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.retromod.shim.common.LegacyEnchantmentBridge;
import com.retromod.shim.common.LegacyEquipmentBridge;
import com.retromod.shim.fabric.Pre1_20_5MojangArmorMaterialBridge;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/** The per-jar type renames the Forge runtime makes before its class pass, for the CLI and AOT paths. */
public final class LegacyJarTypeRenames {

    private final boolean armorMaterial;
    private final Map<String, String> equipment;
    private final boolean enchantment;

    private LegacyJarTypeRenames(boolean armorMaterial, Map<String, String> equipment, boolean enchantment) {
        this.armorMaterial = armorMaterial;
        this.equipment = equipment;
        this.enchantment = enchantment;
    }

    /** Decides once for the whole jar, so a field and the code reading it always agree. */
    public static LegacyJarTypeRenames forJar(RetromodTransformer transformer, Iterable<byte[]> classes) {
        // The order of ForgeModTransformer.transformClasses: each decision sees the earlier renames.
        List<byte[]> current = new ArrayList<>();
        classes.forEach(current::add);
        boolean armorMaterial = Pre1_20_5MojangArmorMaterialBridge.isLegacyJar(transformer, current);
        if (armorMaterial) current = map(current, Pre1_20_5MojangArmorMaterialBridge::renameLegacyInterface);
        // Also records the jar's classes for the equipment and feature bridges, as the runtime does.
        Map<String, String> equipment = LegacyEquipmentBridge.legacyRenames(transformer, current);
        if (!equipment.isEmpty()) current = map(current, bytes -> LegacyEquipmentBridge.rename(bytes, equipment));
        boolean enchantment = LegacyEnchantmentBridge.isLegacyJar(transformer, current);
        return new LegacyJarTypeRenames(armorMaterial, equipment, enchantment);
    }

    /** One class with every rename this jar takes, or the same array when none applies. */
    public byte[] apply(byte[] classBytes) {
        byte[] renamed = classBytes;
        if (armorMaterial) renamed = Pre1_20_5MojangArmorMaterialBridge.renameLegacyInterface(renamed);
        renamed = LegacyEquipmentBridge.rename(renamed, equipment);
        if (enchantment) renamed = LegacyEnchantmentBridge.rename(renamed);
        return renamed;
    }

    /** {@code provider} serving renamed bytes, so the class pass sees the hierarchy the runtime sees. */
    public Function<String, byte[]> over(Function<String, byte[]> provider) {
        if (!armorMaterial && equipment.isEmpty() && !enchantment) return provider;
        // Frame merges ask for the same class often, and the full precompile asks from many threads.
        Map<String, Optional<byte[]>> renamed = new ConcurrentHashMap<>();
        return name -> renamed.computeIfAbsent(name,
                key -> Optional.ofNullable(provider.apply(key)).map(this::apply)).orElse(null);
    }

    private static List<byte[]> map(List<byte[]> classes, UnaryOperator<byte[]> rename) {
        List<byte[]> renamed = new ArrayList<>(classes.size());
        for (byte[] classBytes : classes) renamed.add(rename.apply(classBytes));
        return renamed;
    }
}
