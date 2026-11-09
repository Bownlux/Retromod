// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.world.level.storage;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;

public abstract class TagValueOutput implements ValueOutput {
    public static TagValueOutput createWithContext(ProblemReporter reporter, HolderLookup.Provider registries) { throw new UnsupportedOperationException(); }
    public CompoundTag buildResult() { throw new UnsupportedOperationException(); }
}
