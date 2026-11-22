// Compile-only stub of a 1.21.5 to 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.util.random;

import java.util.List;
import java.util.Optional;
import java.util.function.ToIntFunction;
import net.minecraft.util.RandomSource;

public class WeightedRandom {
    public static <T> int getTotalWeight(List<T> list, ToIntFunction<T> weight) { throw new UnsupportedOperationException(); }
    public static <T> Optional<T> getRandomItem(RandomSource random, List<T> list, int total, ToIntFunction<T> weight) { throw new UnsupportedOperationException(); }
    public static <T> Optional<T> getWeightedItem(List<T> list, int index, ToIntFunction<T> weight) { throw new UnsupportedOperationException(); }
    public static <T> Optional<T> getRandomItem(RandomSource random, List<T> list, ToIntFunction<T> weight) { throw new UnsupportedOperationException(); }
}
