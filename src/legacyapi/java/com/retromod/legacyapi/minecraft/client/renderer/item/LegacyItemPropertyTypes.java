/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.renderer.item;

import com.mojang.serialization.MapCodec;
import com.retromod.shim.common.embedded.LegacyLog;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.item.properties.numeric.RangeSelectItemModelProperties;
import net.minecraft.client.renderer.item.properties.numeric.RangeSelectItemModelProperty;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemStack;

/** Adds {@code retromod:legacy_item_property} to Minecraft's range property types; Retromod's runtime calls it on a client. */
public final class LegacyItemPropertyTypes {

    public static final String NAMESPACE = "retromod";
    public static final String PATH = "legacy_item_property";

    private static final AtomicBoolean ADDED = new AtomicBoolean();

    private LegacyItemPropertyTypes() {}

    /** Adds the type once, before the first resource reload reads item definitions; answers whether it is there. */
    public static boolean install() {
        if (ADDED.get()) return true;
        try {
            // A host whose property reads another shape would call a method this class lacks while the game draws.
            Method get = RangeSelectItemModelProperty.class.getMethod("get", ItemStack.class, ClientLevel.class,
                    ItemOwner.class, int.class);
            if (get.getReturnType() != float.class) throw new NoSuchMethodException("get returns " + get.getReturnType());
            ExtraCodecs.LateBoundIdMapper<Identifier, MapCodec<? extends RangeSelectItemModelProperty>> ids = propertyTypes();
            // Mods may construct in parallel, and the host keeps one id table.
            synchronized (ids) {
                if (ADDED.get()) return true;
                ids.put(Identifier.fromNamespaceAndPath(NAMESPACE, PATH), LegacyItemProperty.MAP_CODEC);
                ADDED.set(true);
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            // Retromod's item definitions name the type, so the game reads those items without it.
            LegacyLog.warn("Retromod did not add the item property type " + NAMESPACE + ":" + PATH
                    + ", which its item definitions for old model overrides name: " + failure);
            return false;
        }
    }

    // Minecraft keeps its range property type ids in a private static field. Its jar has no module descriptor, so its
    // packages are open to reflection; the field is found by its type, so a rename does not lose it.
    @SuppressWarnings("unchecked")
    private static ExtraCodecs.LateBoundIdMapper<Identifier, MapCodec<? extends RangeSelectItemModelProperty>> propertyTypes()
            throws ReflectiveOperationException {
        for (Field field : RangeSelectItemModelProperties.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == ExtraCodecs.LateBoundIdMapper.class) {
                field.setAccessible(true);
                return (ExtraCodecs.LateBoundIdMapper<Identifier, MapCodec<? extends RangeSelectItemModelProperty>>) field.get(null);
            }
        }
        throw new NoSuchFieldException("RangeSelectItemModelProperties keeps no property type ids");
    }
}
