/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi.fabric.v26.screen;

import com.retromod.loaderapi.fabric.v26.HostLink;
import com.retromod.loaderapi.fabric.v26.screen.shared.LegacyGatherDebugTextLeft;
import com.retromod.loaderapi.fabric.v26.screen.shared.LegacyGatherDebugTextRight;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;

import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** The old {@code GatherDebugTextEvents} of 1.21.5 to 1.21.8, shown through one 26.x debug screen entry. */
public final class LegacyDebugText {

    /** The id of the debug screen entry, listed in the debug options screen. */
    static final String ENTRY_PATH = "legacy_debug_text";

    /** The old left column event. */
    public static final Event<LegacyGatherDebugTextLeft> LEFT = EventFactory.createArrayBacked(
            LegacyGatherDebugTextLeft.class, listeners -> lines -> {
                for (LegacyGatherDebugTextLeft listener : listeners) listener.gatherLegacyLeftDebugText(lines);
            });

    /** The old right column event. */
    public static final Event<LegacyGatherDebugTextRight> RIGHT = EventFactory.createArrayBacked(
            LegacyGatherDebugTextRight.class, listeners -> lines -> {
                for (LegacyGatherDebugTextRight listener : listeners) listener.gatherLegacyRightDebugText(lines);
            });

    static {
        try {
            installDebugEntry();
        } catch (RuntimeException e) {
            // The mod's class setup reads these events, so a host refusal must not fail it.
            HostGui.warnOnce("debug-entry", "Retromod could not add the debug screen entry for old "
                    + "debug text ({}). The lines are kept but not shown.", e.toString());
        }
    }

    private LegacyDebugText() {}

    /** The lines old mods add to the left column this frame. */
    static List<String> leftLines() {
        List<String> lines = new ArrayList<>();
        LEFT.invoker().gatherLegacyLeftDebugText(lines);
        return lines;
    }

    /** The lines old mods add to the right column this frame. */
    static List<String> rightLines() {
        List<String> lines = new ArrayList<>();
        RIGHT.invoker().gatherLegacyRightDebugText(lines);
        return lines;
    }

    private static void installDebugEntry() {
        // Fabric API 26.1 to 26.3 widen this vanilla method so mods can add debug screen entries.
        Optional<MethodHandle> register = HostLink.handle(HostGui.DEBUG_REGISTER);
        Optional<Class<?>> entryType = HostGui.type(HostGui.DEBUG_ENTRY);
        if (register.isEmpty() || entryType.isEmpty()) {
            HostGui.debugOnce("no-debug-entries", "Old debug text events are kept but not shown: this "
                    + "side cannot add a debug screen entry.");
            return;
        }
        Object left = HostGui.id("retromod", ENTRY_PATH + "_left");
        Object right = HostGui.id("retromod", ENTRY_PATH + "_right");
        Object entry = HostGui.proxy(entryType.get(), "legacy debug text", (proxy, method, args) -> {
            if (method.getName().equals("display") && args != null && args.length == 4) {
                // Each old column becomes one group, which the debug screen places in a column.
                addGroup(args[0], left, leftLines());
                addGroup(args[0], right, rightLines());
                return null;
            }
            return HostGui.defaultOf(proxy, method, args, "legacy debug text");
        });
        HostGui.call(register.get(), HostGui.id("retromod", ENTRY_PATH), entry);
        HostGui.infoOnce("debug-entry", "Old mods' debug text appears in the debug screen as "
                + "retromod:{} once it is turned on in the debug options screen.", ENTRY_PATH);
    }

    private static void addGroup(Object displayer, Object group, List<String> lines) {
        if (!lines.isEmpty()) HostGui.call(HostLink.require(HostGui.DEBUG_ADD_GROUP), displayer, group, lines);
    }
}
