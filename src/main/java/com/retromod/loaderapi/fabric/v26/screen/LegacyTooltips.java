/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi.fabric.v26.screen;

import com.retromod.loaderapi.fabric.v26.HostLink;

import java.lang.invoke.MethodHandle;
import java.util.List;
import java.util.Optional;

/** Serves calls to the old three-argument {@code ItemTooltipCallback.getTooltip} through the host's four-argument one. */
public final class LegacyTooltips {

    private LegacyTooltips() {}

    /** {@code callback.getTooltip(stack, flag, lines)}, as a 1.16.5 to 1.20.4 mod calls it. */
    public static void getTooltip(Object callback, Object stack, Object flag, List<?> lines) {
        HostGui.call(HostLink.require(HostGui.TOOLTIP_FOUR_ARGS), callback, stack, context(), flag, lines);
    }

    /** The tooltip context of the client's world, or the empty one on a side without a world. */
    static Object context() {
        Optional<MethodHandle> instance = HostLink.handle(HostGui.MINECRAFT_INSTANCE);
        Optional<MethodHandle> level = HostLink.handle(HostGui.CLIENT_LEVEL);
        Optional<MethodHandle> of = HostLink.handle(HostGui.TOOLTIP_OF_LEVEL);
        if (instance.isPresent() && level.isPresent() && of.isPresent()) {
            Object client = HostGui.call(instance.get());
            Object world = client == null ? null : HostGui.call(level.get(), client);
            // Old callers had no context; a newer listener reads registries from the world's.
            if (world != null) return HostGui.call(of.get(), world);
        }
        return HostGui.call(HostLink.require(HostGui.TOOLTIP_EMPTY));
    }
}
