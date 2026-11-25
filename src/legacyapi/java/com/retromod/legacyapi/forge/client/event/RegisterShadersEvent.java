/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.client.event;

import com.retromod.shim.common.embedded.LegacyLog;
import java.util.function.Consumer;
import net.minecraft.server.packs.resources.ResourceProvider;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.eventbus.api.bus.EventBus;
import net.minecraftforge.eventbus.api.event.MutableEvent;
import net.minecraftforge.fml.event.IModBusEvent;

/** Forge 1.20.1's mod-bus shader registration event, which Forge 26.2 removed; Retromod posts it once at client setup. */
public final class RegisterShadersEvent extends MutableEvent implements IModBusEvent {

    private final ResourceProvider resources;

    public RegisterShadersEvent(ResourceProvider resources) {
        this.resources = resources;
    }

    public ResourceProvider getResourceProvider() {
        return resources;
    }

    /** {@code registerShader(shader, onLoaded)}: 26.2 builds no old shader program, so the callback gets the inert stand-in it was given. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void registerShader(RegisterShadersEvent event, Object shader, Consumer onLoaded) {
        if (onLoaded == null) return;
        try {
            onLoaded.accept(shader);
        } catch (RuntimeException failure) {
            LegacyLog.warn("An old shader callback failed, which leaves its shader unset as before: " + failure);
        }
    }

    /** Posts this copy's event to one mod's bus group; false when nothing there listens. */
    static boolean postTo(BusGroup group, ResourceProvider resources) {
        EventBus<RegisterShadersEvent> bus = IModBusEvent.getBus(group, RegisterShadersEvent.class);
        if (!bus.hasListeners()) return false;
        bus.post(new RegisterShadersEvent(resources));
        return true;
    }
}
