/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.gui;

import com.retromod.gui.stubbus.StaticBusEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Forge on EventBus 7 posts ScreenEvent.Init.Post only through the event's own static BUS. */
class TitleScreenButtonInjectorTest {

    @Test
    @DisplayName("An EventBus 7 event gets the listener through its static BUS")
    void subscribesThroughTheEventsStaticBus() throws Exception {
        int before = StaticBusEvent.listeners().size();
        Consumer<Object> listener = event -> { };

        // The bus object is a hidden class, as EventBus 7's internal EventBusImpl is to Retromod.
        assertTrue(TitleScreenButtonInjector.addToStaticBus(StaticBusEvent.class, listener),
                "a static BUS is the EventBus 7 shape");
        assertEquals(before + 1, StaticBusEvent.listeners().size());
        assertSame(listener, StaticBusEvent.listeners().get(before),
                "the bus must receive the listener Retromod passed in");
    }

    @Test
    @DisplayName("An EventBus 6 event without a static BUS stays on the EVENT_BUS path")
    void leavesEventBus6EventsToTheOldPath() throws Exception {
        int before = StaticBusEvent.listeners().size();

        assertFalse(TitleScreenButtonInjector.addToStaticBus(
                StaticBusEvent.EventBus6Event.class, event -> { }));
        assertEquals(before, StaticBusEvent.listeners().size(), "nothing may be subscribed");
    }

    @Test
    @DisplayName("An instance field named BUS is not an event bus")
    void ignoresAnInstanceFieldNamedBus() throws Exception {
        assertFalse(TitleScreenButtonInjector.addToStaticBus(
                StaticBusEvent.InstanceBusEvent.class, event -> { }));
    }

    @Test
    @DisplayName("register() reports false when Minecraft's GUI classes are missing")
    void registerReportsWhenTheButtonIsUnavailable() {
        // The unit test JVM has no Minecraft classes. RetromodClient logs the button only on true.
        assertFalse(TitleScreenButtonInjector.register());
    }
}
