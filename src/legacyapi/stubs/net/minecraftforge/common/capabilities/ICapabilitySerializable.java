// Compile-only stub of a Forge 26.2 host type for src/legacyapi; never packaged.
package net.minecraftforge.common.capabilities;

import net.minecraft.nbt.Tag;
import net.minecraftforge.common.util.INBTSerializable;

public interface ICapabilitySerializable<T extends Tag> extends ICapabilityProvider, INBTSerializable<T> {
}
