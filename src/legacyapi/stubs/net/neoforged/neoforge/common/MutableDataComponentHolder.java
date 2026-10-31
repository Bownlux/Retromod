// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.neoforged.neoforge.common;

import net.minecraft.core.component.DataComponentHolder;
import net.minecraft.core.component.DataComponentType;

public interface MutableDataComponentHolder extends DataComponentHolder {
    <T> T set(DataComponentType<T> type, T value);
    <T> T remove(DataComponentType<? extends T> type);
}
