/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.gui.stubbus;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Stands in for an EventBus 7 event, whose static BUS is an instance of a hidden class. */
public final class StaticBusEvent {

    /** The bus API type, like EventBus 7's exported EventBus interface. */
    public interface Bus {
        Object addListener(Consumer<?> listener);
    }

    public static final Bus BUS = new RecordingBus();

    private StaticBusEvent() {}

    /** Every listener added to {@link #BUS} so far. */
    public static List<Consumer<?>> listeners() {
        return ((RecordingBus) BUS).listeners;
    }

    // Like EventBus 7's EventBusImpl, this class cannot be reached from another package.
    private static final class RecordingBus implements Bus {
        private final List<Consumer<?>> listeners = new ArrayList<>();

        @Override
        public Object addListener(Consumer<?> listener) {
            listeners.add(listener);
            return listener;
        }
    }

    /** An EventBus 6 event: no static BUS, listeners go through MinecraftForge.EVENT_BUS. */
    public static final class EventBus6Event {
    }

    /** A class whose BUS is an instance field, which is not an EventBus 7 event bus. */
    public static final class InstanceBusEvent {
        public final Bus BUS = new RecordingBus();
    }
}
