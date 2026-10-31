package net.minecraft.core.registries;

import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;

public final class Registries {
    public static final ResourceKey<Registry<Object>> CONTEXT_INT_PROVIDER =
            ResourceKey.createRegistryKey(Identifier.fromNamespaceAndPath("minecraft", "context_int_provider"));
    public static final ResourceKey<Registry<Object>> CONTEXT_FLOAT_PROVIDER =
            ResourceKey.createRegistryKey(Identifier.fromNamespaceAndPath("minecraft", "context_float_provider"));
    private Registries() {}
}
