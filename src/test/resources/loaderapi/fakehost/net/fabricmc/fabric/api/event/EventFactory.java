package net.fabricmc.fabric.api.event;

import java.util.function.Function;

public final class EventFactory {
    private EventFactory() {}

    public static <T> Event<T> createArrayBacked(Class<? super T> type, Function<T[], T> invokerFactory) {
        return new Event<>(type, invokerFactory);
    }
}
