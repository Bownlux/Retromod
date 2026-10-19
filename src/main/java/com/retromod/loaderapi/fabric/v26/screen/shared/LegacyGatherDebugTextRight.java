/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi.fabric.v26.screen.shared;

import java.util.List;

/** The old Fabric API {@code GatherDebugTextEvents.Right} of 1.21.5 to 1.21.8. */
@FunctionalInterface
public interface LegacyGatherDebugTextRight {
    // Named for this type alone, so one class can implement it beside other shared types.
    void gatherLegacyRightDebugText(List<String> lines);
}
