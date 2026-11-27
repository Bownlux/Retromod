// Compile-only stub of a Fabric API 26.1 to 26.3 host type for src/legacyapi; never packaged.
package net.fabricmc.fabric.api.event;

import java.util.function.Function;

public final class EventFactory {
    private EventFactory() {}
    public static <T> Event<T> createArrayBacked(Class<? super T> type, Function<T[], T> invokerFactory) { throw new UnsupportedOperationException(); }
}
