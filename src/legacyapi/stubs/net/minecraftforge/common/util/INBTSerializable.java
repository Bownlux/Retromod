// Compile-only stub of a Forge 26.2 host type for src/legacyapi; never packaged.
package net.minecraftforge.common.util;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.Tag;

public interface INBTSerializable<T extends Tag> {
    T serializeNBT(HolderLookup.Provider registries);

    void deserializeNBT(HolderLookup.Provider registries, T nbt);
}
