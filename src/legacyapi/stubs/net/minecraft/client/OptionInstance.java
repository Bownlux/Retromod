// Compile-only stub of a 1.21.11 to 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.client;

import com.mojang.serialization.Codec;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.network.chat.Component;

public final class OptionInstance<T> {
    // The 1.21.11 and 26.1 constructor, whose last argument was a consumer.
    public OptionInstance(String caption, TooltipSupplier<T> tooltip, CaptionBasedToString<T> toString, ValueSet<T> values,
            T initial, Consumer<T> onChange) { throw new UnsupportedOperationException(); }
    // The 26.2 and 26.3 constructor, whose last argument is a listener.
    public OptionInstance(String caption, TooltipSupplier<T> tooltip, CaptionBasedToString<T> toString, ValueSet<T> values,
            T initial, ValueUpdateListener<? super T> onChange) { throw new UnsupportedOperationException(); }
    public OptionInstance(String caption, TooltipSupplier<T> tooltip, CaptionBasedToString<T> toString, ValueSet<T> values,
            Codec<T> codec, T initial, ValueUpdateListener<? super T> onChange) { throw new UnsupportedOperationException(); }
    public static OptionInstance<Boolean> createBoolean(String caption, boolean initial, ValueUpdateListener<? super Boolean> onChange) { throw new UnsupportedOperationException(); }
    public static <T> TooltipSupplier<T> noTooltip() { throw new UnsupportedOperationException(); }
    public T get() { throw new UnsupportedOperationException(); }
    public void set(T value) { throw new UnsupportedOperationException(); }

    public interface ValueUpdateListener<T> {
        void valueChanged(T value);
    }

    public interface TooltipSupplier<T> {
    }

    public interface CaptionBasedToString<T> {
        Component toString(Component caption, T value);
    }

    public interface ValueSet<T> {
    }

    public interface SliderableValueSet<T> extends ValueSet<T> {
    }

    public interface IntRangeBase extends SliderableValueSet<Integer> {
        default <R> SliderableValueSet<R> xmap(java.util.function.IntFunction<? extends R> to,
                java.util.function.ToIntFunction<? super R> from, boolean discrete) { throw new UnsupportedOperationException(); }
    }

    public static final class Enum<T> implements ValueSet<T> {
        public Enum(List<T> values, Codec<T> codec) { throw new UnsupportedOperationException(); }
    }
}
