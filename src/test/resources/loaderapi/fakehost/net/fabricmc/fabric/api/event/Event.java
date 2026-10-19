package net.fabricmc.fabric.api.event;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public class Event<T> {
    public final List<T> listeners = new ArrayList<>();
    private final Class<? super T> type;
    private final Function<T[], T> invokerFactory;

    public Event() { this(null, null); }

    Event(Class<? super T> type, Function<T[], T> invokerFactory) {
        this.type = type;
        this.invokerFactory = invokerFactory;
    }

    public void register(T listener) { listeners.add(listener); }

    /** As in Fabric API: an array-backed event checks each listener's type when it is stored. */
    @SuppressWarnings("unchecked")
    public T invoker() {
        T[] array = (T[]) Array.newInstance(type, listeners.size());
        for (int i = 0; i < array.length; i++) array[i] = listeners.get(i);
        return invokerFactory.apply(array);
    }
}
