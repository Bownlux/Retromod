/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.color;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.retromod.shim.common.embedded.LegacyLog;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.ToIntBiFunction;
import net.minecraft.client.color.item.ItemTintSource;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/** One tint index of an item model, colored by the old item color registered for the item. */
public final class LegacyItemTint implements ItemTintSource {

    private final int index;
    private final MapCodec<LegacyItemTint> type;

    private LegacyItemTint(int index, MapCodec<LegacyItemTint> type) {
        this.index = index;
        this.type = type;
    }

    /** A new codec for {@code {"index": n}}; each namespace needs its own, since the host maps a codec to one id. */
    public static MapCodec<LegacyItemTint> newType() {
        AtomicReference<MapCodec<LegacyItemTint>> type = new AtomicReference<>();
        type.set(Codec.INT.fieldOf("index").xmap(index -> new LegacyItemTint(index, type.get()), LegacyItemTint::index));
        return type.get();
    }

    int index() {
        return index;
    }

    @Override
    public int calculate(ItemStack stack, ClientLevel level, LivingEntity entity) {
        ToIntBiFunction<Object, Integer> handler = ItemColors.handlerOf(stack);
        if (handler == null) return -1;
        try {
            // The table answers the color drawn: opaque for a mod built before 1.21, whose item renderer ignored alpha,
            // and the handler's own alpha after, as 1.21's renderer read it and 26.x's does.
            return handler.applyAsInt(stack, index);
        } catch (RuntimeException | LinkageError failure) {
            // Old handlers now run while the game draws, and one can reach a vanilla API a later Minecraft removed.
            LegacyLog.warnOnce("item color " + handler, "The old item color handler " + handler + " threw " + failure
                    + ", so Retromod draws its tint untinted");
            return -1;
        }
    }

    @Override
    public MapCodec<? extends ItemTintSource> type() {
        return type;
    }
}
