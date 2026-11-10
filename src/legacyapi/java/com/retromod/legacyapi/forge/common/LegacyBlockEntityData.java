/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.common;

import com.retromod.legacyapi.forge.LegacySharedSlots;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.AbstractMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Forge 1.20.1's persistent data of a block entity, which Forge 26.2 removed, kept beside each block entity. */
public final class LegacyBlockEntityData {

    // Forge 1.20.1 saved the data under this key, so a world saved there keeps it.
    static final String KEY = "ForgeData";
    // Every translated jar's copy shares one map, so a mod reads the data another mod's block entity loaded.
    static final String SLOT = "block-entity-data";
    static final int PROTOCOL = 1;

    // Copies share the map through the JDK's Map interface, so the first copy's implementation serves every jar.
    @SuppressWarnings("unchecked")
    private static final Map<Object, CompoundTag> DATA = LegacySharedSlots.get(SLOT, PROTOCOL, Map.class, IdentityWeakMap<CompoundTag>::new);

    private LegacyBlockEntityData() {}

    /** {@code blockEntity.getPersistentData()}: the same compound on every call, empty until something is put in it. */
    public static CompoundTag getPersistentData(Object blockEntity) {
        return DATA.computeIfAbsent(blockEntity, unused -> new CompoundTag());
    }

    /** Saves a copy under {@code ForgeData} once the block entity has persistent data, as Forge 1.20.1 did. */
    public static void save(Object blockEntity, ValueOutput output) {
        CompoundTag data = DATA.get(blockEntity);
        if (data != null) output.store(KEY, CompoundTag.CODEC, data.copy());
    }

    /** Reads {@code ForgeData} back; without the key the block entity keeps the data it has, as before. */
    public static void load(Object blockEntity, ValueInput input) {
        input.read(KEY, CompoundTag.CODEC).ifPresent(data -> DATA.put(blockEntity, data));
    }

    /**
     * Data per block entity, keyed by identity and held weakly, so it goes away with its block entity. It names no game
     * class, so a copy from any jar can use another copy's map.
     */
    static final class IdentityWeakMap<V> extends AbstractMap<Object, V> {
        private final Map<Key, V> entries = new HashMap<>();
        private final ReferenceQueue<Object> cleared = new ReferenceQueue<>();

        // A mod block entity may override equals, so two of them must never share one compound.
        private static final class Key extends WeakReference<Object> {
            private final int hash;

            Key(Object referent, ReferenceQueue<Object> queue) {
                super(referent, queue);
                hash = System.identityHashCode(referent);
            }

            @Override
            public boolean equals(Object other) {
                if (other == this) return true;
                Object referent = get();
                return other instanceof Key key && referent != null && referent == key.get();
            }

            @Override
            public int hashCode() {
                return hash;
            }
        }

        @Override
        public synchronized V get(Object blockEntity) {
            expunge();
            return entries.get(new Key(blockEntity, null));
        }

        @Override
        public synchronized V put(Object blockEntity, V data) {
            expunge();
            return entries.put(new Key(blockEntity, cleared), data);
        }

        @Override
        public synchronized V computeIfAbsent(Object blockEntity, Function<? super Object, ? extends V> create) {
            V data = get(blockEntity);
            if (data == null) {
                data = create.apply(blockEntity);
                put(blockEntity, data);
            }
            return data;
        }

        @Override
        public synchronized Set<Entry<Object, V>> entrySet() {
            expunge();
            Set<Entry<Object, V>> live = new HashSet<>();
            entries.forEach((key, data) -> {
                Object blockEntity = key.get();
                if (blockEntity != null) live.add(new SimpleImmutableEntry<>(blockEntity, data));
            });
            return live;
        }

        // A collected block entity's key drops out of the map.
        private void expunge() {
            for (Object key; (key = cleared.poll()) != null; ) entries.remove(key);
        }
    }
}
