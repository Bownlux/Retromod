package net.minecraftforge.registries;

/** Compile-only: Forge 1.18.2's entry base, which kept the entry's registry name. */
public abstract class ForgeRegistryEntry<V extends IForgeRegistryEntry<V>> implements IForgeRegistryEntry<V> {
    @SuppressWarnings("unchecked")
    public final V setRegistryName(String name) {
        return (V) this;
    }
}
