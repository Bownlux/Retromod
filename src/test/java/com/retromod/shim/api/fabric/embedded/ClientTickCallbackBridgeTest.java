/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.api.fabric.embedded;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;

class ClientTickCallbackBridgeTest {

    @Test
    void registersThroughPublicEventInterfaceWhenImplementationIsPrivate() throws Exception {
        HiddenEvent event = new HiddenEvent();
        Object listener = new Object();

        ClientTickCallbackBridge.registerListener(PublicEvent.class, event, listener);

        assertSame(listener, event.listener);
    }

    public interface PublicEvent {
        void register(Object listener);
    }

    private static final class HiddenEvent implements PublicEvent {
        private Object listener;

        @Override
        public void register(Object listener) {
            this.listener = listener;
        }
    }
}
