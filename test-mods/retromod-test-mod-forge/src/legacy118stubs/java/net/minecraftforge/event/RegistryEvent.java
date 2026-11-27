package net.minecraftforge.event;

/** Compile-only: Forge 1.18.2's registry event, which Forge 1.19 replaced with RegisterEvent. */
public class RegistryEvent {
    public static class Register<T extends net.minecraftforge.registries.IForgeRegistryEntry<T>> extends RegistryEvent {
        public net.minecraftforge.registries.IForgeRegistry<T> getRegistry() {
            return null;
        }
    }
}
