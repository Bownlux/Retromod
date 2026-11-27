/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.client.event;

import com.retromod.shim.common.embedded.LegacyLog;
import java.util.function.Consumer;
import net.minecraft.server.packs.resources.ResourceProvider;
import net.neoforged.bus.api.Event;
import net.neoforged.fml.event.IModBusEvent;

/** Forge 1.20.1's and NeoForge 1.21.1's shader registration event, which 26.x removed; Retromod posts it once at client setup. */
public final class LegacyRegisterShadersEvent extends Event implements IModBusEvent {

    private final ResourceProvider resources;

    public LegacyRegisterShadersEvent(ResourceProvider resources) {
        this.resources = resources;
    }

    public ResourceProvider getResourceProvider() {
        return resources;
    }

    /**
     * {@code registerShader(shader, onLoaded)}. 26.x builds no old shader program, so the callback gets what the old
     * constructor gave, which is null on NeoForge.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void registerShader(LegacyRegisterShadersEvent event, Object shader, Consumer onLoaded) {
        if (onLoaded == null) return;
        try {
            onLoaded.accept(shader);
        } catch (RuntimeException failure) {
            LegacyLog.warn("An old shader callback failed, which leaves its shader unset as before: " + failure);
        }
    }
}
