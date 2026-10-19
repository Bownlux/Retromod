/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi.fabric.v26.render;

import com.retromod.loaderapi.fabric.v26.render.shared.LegacyColorProviderRegistry;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** The old {@code ColorProviderRegistry.BLOCK} and {@code ITEM}, kept here and fed to the 26.x host. */
public final class LegacyColorProviders {

    /** Old block colors, applied through the host's {@code BlockColorRegistry}. */
    public static final LegacyColorProviderRegistry BLOCK = new BlockProviders();

    /** Old item colors, applied as tint sources when the host bakes item models. */
    public static final LegacyColorProviderRegistry ITEM = new ItemProviders();

    private LegacyColorProviders() {}

    /** The old item color registered for {@code item}, or null. */
    static Object itemProvider(Object item) {
        return item == null ? null : ((ItemProviders) ITEM).providers.get(item);
    }

    /** Old block colors by block. A host tint source answers {@code get} for other blocks. */
    private static final class BlockProviders implements LegacyColorProviderRegistry {
        private final Map<Object, Object> providers = new ConcurrentHashMap<>();

        @Override
        public void register(Object provider, Object... blocks) {
            if (provider == null || blocks == null) return;
            for (Object block : blocks) {
                if (block != null) providers.put(block, provider);
            }
            HostTints.registerBlockColor(provider, blocks);
        }

        @Override
        public Object get(Object block) {
            if (block == null) return null;
            Object provider = providers.get(block);
            return provider != null ? provider : HostTints.hostBlockColor(block);
        }
    }

    /** Old item colors by item; an old call may name a block, which stands for its item. */
    private static final class ItemProviders implements LegacyColorProviderRegistry {
        private final Map<Object, Object> providers = new ConcurrentHashMap<>();

        @Override
        public void register(Object provider, Object... items) {
            if (provider == null || items == null) return;
            for (Object itemLike : items) {
                Object item = HostTints.asItem(itemLike);
                if (item != null) providers.put(item, provider);
            }
            HostTints.watchItemModels();
        }

        @Override
        public Object get(Object itemLike) {
            Object item = HostTints.asItem(itemLike);
            return item == null ? null : providers.get(item);
        }
    }
}
