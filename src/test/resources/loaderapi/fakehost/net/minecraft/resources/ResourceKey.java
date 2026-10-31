package net.minecraft.resources;

import net.minecraft.core.Registry;

public final class ResourceKey<T> {
    private final String registry;
    private final Identifier id;
    private ResourceKey(String registry, Identifier id) { this.registry = registry; this.id = id; }

    public static <T> ResourceKey<T> create(ResourceKey<? extends Registry<T>> registry, Identifier id) {
        return new ResourceKey<>(registry.id.toString(), id);
    }

    public static <T> ResourceKey<Registry<T>> createRegistryKey(Identifier id) {
        return new ResourceKey<>("minecraft:root", id);
    }

    public Identifier identifier() { return id; }

    @Override public String toString() { return registry + " / " + id; }
}
