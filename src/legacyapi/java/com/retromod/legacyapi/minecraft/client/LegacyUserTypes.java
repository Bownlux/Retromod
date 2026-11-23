/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client;

import net.minecraft.client.User;

/** The old User.Type, which 1.21.11 removed when every account became a Microsoft account. */
public enum LegacyUserTypes {
    LEGACY("legacy"), MOJANG("mojang"), MSA("msa");

    private final String name;

    LegacyUserTypes(String name) {
        this.name = name;
    }

    /** {@code user.getType()}: a Microsoft account when the login carries an Xbox user id, else an offline one, as the old default was. */
    public static LegacyUserTypes getType(User user) {
        try {
            Object xuid = user.getClass().getMethod("getXuid").invoke(user);
            return xuid instanceof java.util.Optional<?> id && id.isPresent() ? MSA : LEGACY;
        } catch (ReflectiveOperationException | RuntimeException noXuid) {
            return MSA;
        }
    }

    /** {@code User.Type.byName(name)}. */
    public static LegacyUserTypes byName(String name) {
        for (LegacyUserTypes type : values()) {
            if (type.name.equalsIgnoreCase(name)) return type;
        }
        return null;
    }

    public String getName() {
        return name;
    }
}
