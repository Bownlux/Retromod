// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.neoforged.neoforge.common.util;

import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

public interface ValueIOSerializable {
    void serialize(ValueOutput output);
    void deserialize(ValueInput input);
}
