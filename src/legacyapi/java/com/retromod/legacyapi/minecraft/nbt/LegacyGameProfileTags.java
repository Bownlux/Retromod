/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.nbt;

import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.Map;
import java.util.UUID;

/** {@code NbtUtils.readGameProfile} and {@code writeGameProfile}, which were gone by 1.21, in the tag format old saves hold. */
public final class LegacyGameProfileTags {

    private LegacyGameProfileTags() {}

    /** {@code NbtUtils.readGameProfile(tag)} from 1.20.1: a {@code Name}, an {@code Id} and {@code Properties}. */
    public static GameProfile readGameProfile(CompoundTag tag) {
        String name = tag.getString("Name").orElse("");
        UUID id = tag.get("Id") instanceof IntArrayTag array && array.getAsIntArray().length == 4
                ? UUIDUtil.uuidFromIntArray(array.getAsIntArray()) : new UUID(0L, 0L);
        // A profile with neither a name nor an id read as null before.
        if (name.isEmpty() && !tag.contains("Id")) return null;
        Multimap<String, Property> properties = LinkedHashMultimap.create();
        CompoundTag saved = tag.getCompound("Properties").orElse(null);
        if (saved != null) {
            for (String key : saved.keySet()) {
                for (Tag entry : saved.getListOrEmpty(key)) {
                    if (!(entry instanceof CompoundTag value)) continue;
                    String text = value.getString("Value").orElse("");
                    String signature = value.getString("Signature").orElse(null);
                    properties.put(key, signature == null ? new Property(key, text) : new Property(key, text, signature));
                }
            }
        }
        return new GameProfile(id, name, new PropertyMap(properties));
    }

    /** {@code NbtUtils.writeGameProfile(tag, profile)} from 1.20.1: writes into {@code tag} and returns it. */
    public static CompoundTag writeGameProfile(CompoundTag tag, GameProfile profile) {
        if (profile.name() != null && !profile.name().isBlank()) tag.putString("Name", profile.name());
        if (profile.id() != null) tag.put("Id", new IntArrayTag(UUIDUtil.uuidToIntArray(profile.id())));
        PropertyMap properties = profile.properties();
        if (properties != null && !properties.isEmpty()) {
            CompoundTag saved = new CompoundTag();
            for (Map.Entry<String, Property> entry : properties.entries()) {
                CompoundTag value = new CompoundTag();
                value.putString("Value", entry.getValue().value());
                if (entry.getValue().signature() != null) value.putString("Signature", entry.getValue().signature());
                ListTag list = saved.get(entry.getKey()) instanceof ListTag existing ? existing : new ListTag();
                list.add(value);
                saved.put(entry.getKey(), list);
            }
            tag.put("Properties", saved);
        }
        return tag;
    }
}
