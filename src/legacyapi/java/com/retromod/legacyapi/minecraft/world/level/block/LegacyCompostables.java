/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.level.block;

import com.retromod.shim.common.embedded.LegacyLog;
import it.unimi.dsi.fastutil.objects.Object2FloatMap;
import it.unimi.dsi.fastutil.objects.Object2FloatOpenHashMap;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentInitializers;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.component.Compostable;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.storage.loot.providers.number.ints.ResolvableInt;

import java.util.HashSet;
import java.util.Set;
import java.util.function.BiFunction;

/** {@code ComposterBlock.COMPOSTABLES} from before 26.3, kept in step with the compostable item component that replaced it. */
public final class LegacyCompostables extends Object2FloatOpenHashMap<ItemLike> {

    private static final LegacyCompostables INSTANCE = new LegacyCompostables();
    // Vanilla's five composting chances, and the 26.3 presets that hold them.
    private static final float[] CHANCES = {0.3f, 0.5f, 0.65f, 0.85f, 1.0f};
    private static final String[] PRESETS = {"compostable/low", "compostable/low_medium", "compostable/medium",
            "compostable/medium_high", "compostable/always_add_one"};
    /** What a component that names no preset counts as. */
    static final float UNKNOWN_CHANCE = 0.5f;

    // Items this copy gave a component initializer, and items a mod took out of the table.
    private final Set<Item> initialized = new HashSet<>();
    private final Set<Item> removed = new HashSet<>();

    private LegacyCompostables() {}

    /** {@code ComposterBlock.COMPOSTABLES}. */
    public static Object2FloatMap<ItemLike> compostables() {
        return INSTANCE;
    }

    /** Records the chance; each component build then gives the item vanilla's nearest one. */
    @Override
    public float put(ItemLike item, float chance) {
        float old = getFloat(item);
        // A 0 is what an unbound item reads, so a mod copying it must not take the item out; removeFloat does that.
        if (chance <= 0f) return old;
        // Keyed by item, as vanilla's own add was, so a block and its item are one entry.
        Item key = item.asItem();
        synchronized (this) {
            super.put(key, chance);
            removed.remove(key);
        }
        initialize(key);
        return old;
    }

    @Override
    public float removeFloat(Object key) {
        float old = getFloat(key);
        if (!(key instanceof ItemLike item)) return old;
        Item removedItem = item.asItem();
        synchronized (this) {
            super.removeFloat(removedItem);
            removed.add(removedItem);
        }
        initialize(removedItem);
        return old;
    }

    @Override
    public float putIfAbsent(ItemLike item, float chance) {
        if (containsKey(item)) return getFloat(item);
        put(item, chance);
        return defaultReturnValue();
    }

    @Override
    public float addTo(ItemLike item, float increment) {
        return put(item, getFloat(item) + increment);
    }

    @Override
    public float merge(ItemLike item, float value,
            BiFunction<? super Float, ? super Float, ? extends Float> remapping) {
        Float merged = containsKey(item) ? remapping.apply(getFloat(item), value) : Float.valueOf(value);
        if (merged == null) {
            removeFloat(item);
            return defaultReturnValue();
        }
        put(item, merged);
        return merged;
    }

    @Override
    public float getFloat(Object key) {
        if (!(key instanceof ItemLike like)) return 0f;
        Item item = like.asItem();
        synchronized (this) {
            if (super.containsKey(item)) return super.getFloat(item);
            if (removed.contains(item)) return 0f;
        }
        return hostChance(item);
    }

    @Override
    public boolean containsKey(Object key) {
        return getFloat(key) > 0f;
    }

    @Override
    public float getOrDefault(Object key, float fallback) {
        float chance = getFloat(key);
        return chance > 0f ? chance : fallback;
    }

    /** The preset index nearest to {@code chance}. */
    static int nearestPreset(float chance) {
        int best = 0;
        for (int i = 1; i < CHANCES.length; i++) {
            if (Math.abs(chance - CHANCES[i]) < Math.abs(chance - CHANCES[best])) best = i;
        }
        return best;
    }

    /** The old chance a preset's path stands for, or the unknown chance. */
    static float chanceOfPreset(String path) {
        for (int i = 0; i < PRESETS.length; i++) {
            if (PRESETS[i].equals(path)) return CHANCES[i];
        }
        return UNKNOWN_CHANCE;
    }

    /** The item's chance from its component, or 0 while the game has not bound item components yet. */
    private static float hostChance(Item item) {
        if (item == null) return 0f;
        Holder.Reference<Item> holder = item.builtInRegistryHolder();
        if (!holder.areComponentsBound()) return 0f;
        Compostable compostable = holder.components().get(DataComponents.COMPOSTABLE);
        if (compostable == null) return 0f;
        // A component built from vanilla's presets names one; any other provider counts as an even chance.
        if (compostable.layers() instanceof ResolvableInt.Reference reference) {
            return chanceOfPreset(reference.key().identifier().getPath());
        }
        return UNKNOWN_CHANCE;
    }

    /**
     * Gives the item one component initializer, which the game runs on every component build (each world load,
     * reload, and client registry sync) and which reads this table as it is then.
     */
    private void initialize(Item item) {
        synchronized (this) {
            if (!initialized.add(item)) return;
        }
        ResourceKey<Item> key;
        try {
            key = item.builtInRegistryHolder().key();
        } catch (RuntimeException unregistered) {
            synchronized (this) {
                initialized.remove(item);
            }
            LegacyLog.warnOnce("compost:" + item, "An old mod set the composting chance of " + item + " before the item"
                    + " was registered, so that chance does not apply");
            return;
        }
        DataComponentInitializers initializers = BuiltInRegistries.DATA_COMPONENT_INITIALIZERS;
        // The game keeps its initializers in a plain list, and mods add to it from several setup threads. The game
        // reads that list without a lock, so a put made while a build runs can throw
        // ConcurrentModificationException in that build; puts are expected at setup.
        synchronized (initializers) {
            initializers.add(key, (builder, lookup, itemKey) -> apply(builder, item));
        }
    }

    private void apply(DataComponentMap.Builder builder, Item item) {
        float chance;
        synchronized (this) {
            if (removed.contains(item)) {
                chance = 0f;
            } else if (super.containsKey(item)) {
                chance = super.getFloat(item);
            } else {
                return;
            }
        }
        builder.set(DataComponents.COMPOSTABLE, chance > 0f ? new Compostable(ResourceKey.create(
                Registries.CONTEXT_INT_PROVIDER, Identifier.withDefaultNamespace(PRESETS[nearestPreset(chance)]))) : null);
    }
}
