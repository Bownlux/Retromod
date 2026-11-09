/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodVersion;
import com.retromod.shim.common.embedded.LegacyEnchantmentRegistry;
import com.retromod.shim.common.embedded.LegacyPublicMethods;
import com.retromod.util.RetromodLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Records the registries of each data pack load, so old code that reads the enchantment registry while one runs finds them. */
public final class ReloadRegistryRecorder {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    // NeoForge renamed its reload event in 1.21.4; Forge kept its name.
    private static final String[] NEOFORGE_RELOAD = {
            "net.neoforged.neoforge.event.AddServerReloadListenersEvent", "net.neoforged.neoforge.event.AddReloadListenerEvent"};
    private static final String NEOFORGE_SERVER = "net.neoforged.neoforge.event.server.";
    private static final String FORGE_RELOAD = "net.minecraftforge.event.AddReloadListenerEvent";
    private static final String FORGE_SERVER = "net.minecraftforge.event.server.";

    private ReloadRegistryRecorder() {}

    /** Listens on a NeoForge or Forge game bus from 1.21, where the enchantment registry comes from data packs. */
    public static void listen() {
        if (RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "1.21") < 0) return;
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        ClassLoader loader = context != null ? context : ReloadRegistryRecorder.class.getClassLoader();
        try {
            if (!listenNeoForge(loader) && !listenForge(loader)) {
                LOGGER.debug(RetromodLog.DETAIL, "No NeoForge or Forge reload event found, so data pack loads are not recorded");
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LOGGER.warn("Retromod could not listen for data pack loads, so old mods that read the enchantment "
                    + "registry while one runs find it empty: {}", e.toString());
        }
    }

    /** Puts the event's registries in the slot every mod's copy of the enchantment view reads. */
    static void record(Object event) {
        Object registries = registriesOf(event);
        if (registries != null) slot().set(new WeakReference<>(registries));
    }

    /** Empties the slot once a server runs or stops, so the view never answers from a world that is gone. */
    static void clear(Object event) {
        slot().set(null);
    }

    /** What the event hands out its registries through, on any NeoForge or Forge from 1.21; null when it names none. */
    static Object registriesOf(Object event) {
        // NeoForge 1.21 to 26.2 and Forge 1.21.x, then Forge 26.x.
        Object registries = call(event, "getRegistryAccess");
        if (registries == null) registries = call(event, "getRegistries");
        // NeoForge 26.3 keeps them only on the server resources being loaded.
        if (registries == null) registries = call(call(call(event, "getServerResources"), "fullRegistries"), "lookup");
        return registries;
    }

    @SuppressWarnings("unchecked")
    static AtomicReference<Object> slot() {
        Object slot = System.getProperties().computeIfAbsent(LegacyEnchantmentRegistry.RELOAD_SLOT,
                key -> new AtomicReference<Object>());
        // Something else under the key leaves this runtime a slot of its own, which no view reads.
        return slot instanceof AtomicReference<?> reference ? (AtomicReference<Object>) reference : new AtomicReference<>();
    }

    private static boolean listenNeoForge(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> reload = firstClass(loader, NEOFORGE_RELOAD);
        if (reload == null) return false;
        Object bus = Class.forName("net.neoforged.neoforge.common.NeoForge", true, loader).getField("EVENT_BUS").get(null);
        Class<?> priority = Class.forName("net.neoforged.bus.api.EventPriority", false, loader);
        Method addListener = Class.forName("net.neoforged.bus.api.IEventBus", false, loader)
                .getMethod("addListener", priority, Class.class, Consumer.class);
        // Highest, so the registries are in place before any listener the event reaches reads them.
        Object highest = priority.getField("HIGHEST").get(null);
        addListener.invoke(bus, highest, reload, (Consumer<Object>) ReloadRegistryRecorder::record);
        for (String server : new String[] {"ServerStartedEvent", "ServerStoppedEvent"}) {
            addListener.invoke(bus, highest, Class.forName(NEOFORGE_SERVER + server, false, loader),
                    (Consumer<Object>) ReloadRegistryRecorder::clear);
        }
        return true;
    }

    private static boolean listenForge(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> reload = firstClass(loader, FORGE_RELOAD);
        if (reload == null) return false;
        addForgeListener(loader, reload, ReloadRegistryRecorder::record);
        for (String server : new String[] {"ServerStartedEvent", "ServerStoppedEvent"}) {
            addForgeListener(loader, Class.forName(FORGE_SERVER + server, false, loader), ReloadRegistryRecorder::clear);
        }
        return true;
    }

    // EventBus 7 (Forge 26.x and some 1.21.x) gives each event a bus of its own; EventBus 6 has one game bus.
    private static void addForgeListener(ClassLoader loader, Class<?> event, Consumer<Object> listener)
            throws ReflectiveOperationException {
        Object bus;
        try {
            bus = event.getField("BUS").get(null);
        } catch (NoSuchFieldException eventBus6) {
            bus = null;
        }
        if (bus != null) {
            Class<?> priority = Class.forName("net.minecraftforge.eventbus.api.listener.Priority", false, loader);
            Class.forName("net.minecraftforge.eventbus.api.bus.EventBus", false, loader)
                    .getMethod("addListener", byte.class, Consumer.class)
                    .invoke(bus, priority.getField("HIGHEST").get(null), listener);
            return;
        }
        Object gameBus = Class.forName("net.minecraftforge.common.MinecraftForge", true, loader).getField("EVENT_BUS").get(null);
        Class<?> priority = Class.forName("net.minecraftforge.eventbus.api.EventPriority", false, loader);
        Class.forName("net.minecraftforge.eventbus.api.IEventBus", false, loader)
                .getMethod("addListener", priority, boolean.class, Class.class, Consumer.class)
                .invoke(gameBus, priority.getField("HIGHEST").get(null), false, event, listener);
    }

    private static Class<?> firstClass(ClassLoader loader, String... names) {
        for (String name : names) {
            try {
                return Class.forName(name, false, loader);
            } catch (ClassNotFoundException | LinkageError absent) {
                // the next name, or another loader
            }
        }
        return null;
    }

    private static Object call(Object target, String name) {
        if (target == null) return null;
        try {
            Method method = LegacyPublicMethods.method(target.getClass(), name);
            return method.invoke(target);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError absent) {
            return null;
        }
    }
}
