// Compile-only stub of a 1.21.5 to 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.util.random;

import java.util.List;
import java.util.Optional;
import net.minecraft.util.RandomSource;

public final class WeightedList<E> {
    private WeightedList() {}
    public static <E> Builder<E> builder() { throw new UnsupportedOperationException(); }
    public List<Weighted<E>> unwrap() { throw new UnsupportedOperationException(); }
    public Optional<E> getRandom(RandomSource random) { throw new UnsupportedOperationException(); }

    public static class Builder<E> {
        public Builder<E> add(E value, int weight) { throw new UnsupportedOperationException(); }
        public WeightedList<E> build() { throw new UnsupportedOperationException(); }
    }
}
