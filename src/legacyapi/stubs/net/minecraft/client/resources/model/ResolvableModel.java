// Compile-only stub of a Forge 26.2 and 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.client.resources.model;

import net.minecraft.resources.Identifier;

public interface ResolvableModel {
    void resolveDependencies(Resolver resolver);

    interface Resolver {
        void markDependency(Identifier id);
    }
}
