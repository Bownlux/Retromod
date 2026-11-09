// Compile-only stub of a Forge 26.2 host type for src/legacyapi; never packaged.
package net.minecraftforge.eventbus.api.bus;

import java.lang.invoke.MethodHandles;
import java.util.Collection;
import net.minecraftforge.eventbus.api.listener.EventListener;

public interface BusGroup {
    BusGroup DEFAULT = null;

    static BusGroup create(String name) { throw new UnsupportedOperationException(); }

    static BusGroup create(String name, Class<?> baseType) { throw new UnsupportedOperationException(); }

    String name();

    void startup();

    void shutdown();

    void dispose();

    void trim();

    Collection<EventListener> register(MethodHandles.Lookup lookup, Class<?> listenerClass);

    Collection<EventListener> register(MethodHandles.Lookup lookup, Object listener);

    void unregister(Collection<EventListener> listeners);
}
