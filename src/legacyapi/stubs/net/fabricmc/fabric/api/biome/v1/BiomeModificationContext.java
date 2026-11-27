// Compile-only stub of a Fabric API 26.1 to 26.3 host type for src/legacyapi; never packaged.
package net.fabricmc.fabric.api.biome.v1;

import net.minecraft.resources.ResourceKey;

public interface BiomeModificationContext {
    interface GenerationSettingsContext {
        void addCarver(ResourceKey<?> carver);
        boolean removeCarver(ResourceKey<?> carver);
    }
}
