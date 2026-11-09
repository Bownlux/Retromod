// Compile-only stub of a Forge 26.2 host type for src/legacyapi; never packaged.
package net.minecraftforge.fml;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;
import net.minecraftforge.fml.event.IModBusEvent;

// Static on Forge 26.2; Forge 1.20.1's ModLoader.get() is gone.
public final class ModLoader {
    private ModLoader() {}

    public static boolean isLoadingStateValid() { throw new UnsupportedOperationException(); }

    public static boolean hasCompletedState(IModLoadingState state) { throw new UnsupportedOperationException(); }

    public static <T extends IModBusEvent> void runEventGenerator(Function<ModContainer, T> generator) { throw new UnsupportedOperationException(); }

    public static <T extends IModBusEvent> void postEvent(T event) { throw new UnsupportedOperationException(); }

    public static <T extends IModBusEvent> T postEventWithReturn(T event) { throw new UnsupportedOperationException(); }

    public static <T extends IModBusEvent> void postEventWrapContainerInModOrder(T event) { throw new UnsupportedOperationException(); }

    public static <T extends IModBusEvent> void postEventWithWrapInModOrder(T event, BiConsumer<ModContainer, T> pre, BiConsumer<ModContainer, T> post) { throw new UnsupportedOperationException(); }

    public static List<ModLoadingWarning> getWarnings() { throw new UnsupportedOperationException(); }

    public static boolean isDataGenRunning() { throw new UnsupportedOperationException(); }
}
