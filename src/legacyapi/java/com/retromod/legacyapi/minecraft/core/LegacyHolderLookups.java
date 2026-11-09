/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.core;

import com.mojang.serialization.Lifecycle;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.flag.FeatureFlags;

import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;

/** Old registry lookup calls, and what old command contexts answer, where the context became a registry provider. */
public final class LegacyHolderLookups {

    private LegacyHolderLookups() {}

    /** {@code CommandBuildContext.holderLookup(key)} before 1.20.5: the provider's lookup, which throws for a missing registry as the old call did. */
    public static <T> HolderLookup<T> holderLookup(HolderLookup.Provider context, ResourceKey<? extends Registry<? extends T>> key) {
        return context.lookupOrThrow(key);
    }

    /** {@code HolderLookup.filterElements(filter)} before 1.20.5, which the host keeps on registry lookups only. */
    public static <T> HolderLookup<T> filterElements(HolderLookup<T> lookup, Predicate<T> filter) {
        if (lookup instanceof HolderLookup.RegistryLookup<T> registry) return registry.filterElements(filter);
        // A lookup of the mod's own keeps its tags, as the old filter did, and loses the elements the filter refuses.
        return new Filtered<>(lookup, filter);
    }

    /** What an old context's own {@code holderLookup(key)} found, as the provider lookup the host asks for. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Optional<HolderLookup.RegistryLookup<?>> lookup(HolderLookup<?> found, ResourceKey<?> key) {
        if (found == null) return Optional.empty();
        if (found instanceof HolderLookup.RegistryLookup<?> registry) return Optional.of(registry);
        return Optional.of(new Keyed(key, found));
    }

    /** The registry keys an old context lists: none, since it could only answer a lookup by key. */
    public static Stream<ResourceKey<? extends Registry<?>>> registryKeys() {
        return Stream.empty();
    }

    /** The features an old context enables: all of them, since its lookups already left out what it hides. */
    public static FeatureFlagSet enabledFeatures() {
        return FeatureFlags.REGISTRY.allFlags();
    }

    /** {@code HolderLookup.Provider.asGetterLookup()} before 1.21.2, when every provider became a getter provider itself. */
    public static HolderGetter.Provider asGetterLookup(HolderLookup.Provider provider) {
        return provider;
    }

    private static final class Filtered<T> implements HolderLookup<T> {
        private final HolderLookup<T> parent;
        private final Predicate<T> filter;

        Filtered(HolderLookup<T> parent, Predicate<T> filter) {
            this.parent = parent;
            this.filter = filter;
        }

        @Override
        public Stream<Holder.Reference<T>> listElements() {
            return parent.listElements().filter(this::accepts);
        }

        @Override
        public Stream<HolderSet.Named<T>> listTags() {
            return parent.listTags();
        }

        @Override
        public Optional<Holder.Reference<T>> get(ResourceKey<T> key) {
            return parent.get(key).filter(this::accepts);
        }

        @Override
        public Optional<HolderSet.Named<T>> get(TagKey<T> tag) {
            return parent.get(tag);
        }

        private boolean accepts(Holder.Reference<T> holder) {
            return filter.test(holder.value());
        }
    }

    // A lookup of the mod's own answered under the registry key the host asked for.
    private static final class Keyed<T> implements HolderLookup.RegistryLookup<T> {
        private final ResourceKey<? extends Registry<? extends T>> key;
        private final HolderLookup<T> parent;

        Keyed(ResourceKey<? extends Registry<? extends T>> key, HolderLookup<T> parent) {
            this.key = key;
            this.parent = parent;
        }

        @Override
        public ResourceKey<? extends Registry<? extends T>> key() {
            return key;
        }

        @Override
        public Lifecycle registryLifecycle() {
            return Lifecycle.stable();
        }

        @Override
        public Stream<Holder.Reference<T>> listElements() {
            return parent.listElements();
        }

        @Override
        public Stream<HolderSet.Named<T>> listTags() {
            return parent.listTags();
        }

        @Override
        public Optional<Holder.Reference<T>> get(ResourceKey<T> key) {
            return parent.get(key);
        }

        @Override
        public Optional<HolderSet.Named<T>> get(TagKey<T> tag) {
            return parent.get(tag);
        }
    }
}
