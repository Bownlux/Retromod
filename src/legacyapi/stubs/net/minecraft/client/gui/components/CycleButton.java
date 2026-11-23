// Compile-only stub of a 1.21.11 to 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.client.gui.components;

import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.network.chat.Component;

public class CycleButton<T> {
    private CycleButton() {}

    public static class Builder<T> {
        public Builder<T> withValues(java.util.Collection<T> values) { throw new UnsupportedOperationException(); }
        public Builder(Function<T, Component> stringifier, Supplier<T> defaultValue) { throw new UnsupportedOperationException(); }
    }
}
