// Compile-only stub of a 26.x host type for src/legacyapi; never packaged.
package net.minecraft.world.level.storage;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;

public final class TagValueInput {
    private TagValueInput() {}
    public static ValueInput create(ProblemReporter reporter, HolderLookup.Provider registries, CompoundTag tag) { throw new UnsupportedOperationException(); }
}
