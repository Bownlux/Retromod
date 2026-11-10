// Compile-only stub of a Forge 26.2 host type for src/legacyapi; never packaged.
package net.minecraftforge.fml;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

// Static on Forge 26.2.
public final class ModList {
    private ModList() {}

    public static <T> Optional<T> getModObjectById(String modId) { throw new UnsupportedOperationException(); }

    public static Optional<? extends ModContainer> getModContainerById(String modId) { throw new UnsupportedOperationException(); }

    public static Optional<? extends ModContainer> getModContainerByObject(Object mod) { throw new UnsupportedOperationException(); }

    public static boolean isLoaded(String modId) { throw new UnsupportedOperationException(); }

    public static int size() { throw new UnsupportedOperationException(); }

    public static void forEachModInOrder(Consumer<ModContainer> action) { throw new UnsupportedOperationException(); }

    public static List<ModContainer> getLoadedMods() { throw new UnsupportedOperationException(); }
}
