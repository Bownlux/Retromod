/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.fabric.client.rendering;

import com.retromod.shim.common.embedded.LegacyLog;
import java.io.IOException;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.resources.Identifier;

/**
 * Fabric API's {@code CoreShaderRegistrationCallback} of 1.20.1 and 1.21.1, which Fabric API for 1.21.2 removed. Retromod
 * fires it once the client has started; 26.x builds no old shader program, so each callback gets null.
 */
@FunctionalInterface
public interface LegacyCoreShaderRegistrationCallback {

    Event<LegacyCoreShaderRegistrationCallback> EVENT = Fire.create();

    void registerShaders(RegistrationContext context) throws IOException;

    @FunctionalInterface
    interface RegistrationContext {
        /** The old {@code register(id, vertexFormat, onLoaded)}; the format's type changed on 26.x, so it is taken as is. */
        void register(Identifier id, Object vertexFormat, Consumer<Object> onLoaded) throws IOException;
    }

    /** Builds the event and fires it once the client has started. */
    final class Fire {
        private Fire() {}

        static Event<LegacyCoreShaderRegistrationCallback> create() {
            Event<LegacyCoreShaderRegistrationCallback> event = EventFactory.createArrayBacked(LegacyCoreShaderRegistrationCallback.class,
                    callbacks -> context -> {
                        for (LegacyCoreShaderRegistrationCallback callback : callbacks) callback.registerShaders(context);
                    });
            try {
                ClientLifecycleEvents.CLIENT_STARTED.register(client -> fire(event));
            } catch (RuntimeException | LinkageError server) {
                // A dedicated server has no client events, and no shaders to register.
            }
            return event;
        }

        private static void fire(Event<LegacyCoreShaderRegistrationCallback> event) {
            try {
                event.invoker().registerShaders((id, format, onLoaded) -> {
                    if (onLoaded != null) onLoaded.accept(null);
                });
            } catch (IOException | RuntimeException | LinkageError failure) {
                LegacyLog.warn("An old shader registration callback failed, which leaves its shader unset as before: " + failure);
            }
        }
    }
}
