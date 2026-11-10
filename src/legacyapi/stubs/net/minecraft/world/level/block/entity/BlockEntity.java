// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.level.block.entity;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.storage.ValueOutput;

public class BlockEntity {
    public final CompoundTag saveWithoutMetadata(HolderLookup.Provider registries) { throw new UnsupportedOperationException(); }
    public final CompoundTag saveWithFullMetadata(HolderLookup.Provider registries) { throw new UnsupportedOperationException(); }
    public void saveWithId(ValueOutput output) { throw new UnsupportedOperationException(); }
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) { throw new UnsupportedOperationException(); }
}
