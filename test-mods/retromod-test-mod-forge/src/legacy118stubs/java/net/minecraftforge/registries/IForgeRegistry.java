package net.minecraftforge.registries;

/** Compile-only: the 1.18.2 registry a Register event handed out. */
public interface IForgeRegistry<V extends IForgeRegistryEntry<V>> {
    @SuppressWarnings("unchecked")
    void registerAll(V... values);
}
