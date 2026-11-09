/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.renderer.item;

import com.mojang.serialization.MapCodec;
import com.retromod.shim.common.embedded.LegacyLog;
import java.util.function.ToDoubleFunction;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.item.properties.numeric.RangeSelectItemModelProperty;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/** The range property {@code retromod:legacy_item_property}: the value an old mod's ItemProperties function gives a stack. */
public final class LegacyItemProperty implements RangeSelectItemModelProperty {

    /** {@code {"id": <old property id>}}, next to the range dispatch's own fields. */
    public static final MapCodec<LegacyItemProperty> MAP_CODEC =
            Identifier.CODEC.fieldOf("id").xmap(LegacyItemProperty::new, LegacyItemProperty::id);

    private final Identifier id;
    private final String key;

    public LegacyItemProperty(Identifier id) {
        this.id = id;
        this.key = id.toString();
    }

    public Identifier id() {
        return id;
    }

    @Override
    public float get(ItemStack stack, ClientLevel level, ItemOwner owner, int seed) {
        ToDoubleFunction<Object[]> function = LegacyItemPropertyStore.find(stack.getItem(), key);
        // Minecraft 1.20.1 read a property no function answered as negative infinity, so no override on it held.
        if (function == null) return Float.NEGATIVE_INFINITY;
        // The old functions took the living entity holding the stack, which 26.x passes as an item owner.
        LivingEntity entity = owner == null ? null : owner.asLivingEntity();
        try {
            return (float) function.applyAsDouble(new Object[]{stack, level, entity, seed});
        } catch (RuntimeException | LinkageError failure) {
            LegacyLog.warnOnce("item property " + key, "The old item property " + key + " threw " + failure
                    + ", so Retromod shows its items as if no override on it held");
            return Float.NEGATIVE_INFINITY;
        }
    }

    @Override
    public MapCodec<LegacyItemProperty> type() {
        return MAP_CODEC;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof LegacyItemProperty property && property.key.equals(key);
    }

    @Override
    public int hashCode() {
        return key.hashCode();
    }

    @Override
    public String toString() {
        return "LegacyItemProperty[" + key + "]";
    }
}
