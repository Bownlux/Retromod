/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

/** Whether a host's entity render states can be traced back to the entity they were made from. */
public final class RenderStateLookupHost {

    static final String STATE = "net/minecraft/client/renderer/entity/state/EntityRenderState";
    static final String RENDERER = "net/minecraft/client/renderer/entity/EntityRenderer";
    /** The offline floor for a fresh state per draw, inferred from the intermediary tables; no 1.21.9 jar was checked. */
    static final String FRESH_STATES = "1.21.9";

    private RenderStateLookupHost() {}

    /** True on a host whose states name their entity type and are made fresh for each draw; offline, from 1.21.9. */
    public static boolean supported() {
        ClassNode state = ClassResourceInspector.read(STATE);
        ClassNode renderer = ClassResourceInspector.read(RENDERER);
        if (state == null || renderer == null) {
            return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, FRESH_STATES) >= 0;
        }
        return supported(state, renderer);
    }

    /** The same answer from the host's EntityRenderState and EntityRenderer classes. */
    public static boolean supported(ClassNode state, ClassNode renderer) {
        // 1.21.2 to 1.21.4 states have no entity type to narrow the search by.
        if (!hasField(state, "entityType")) return false;
        // Before 1.21.9 each renderer refilled one state for every entity, so a state cannot stand for one entity.
        return !hasField(renderer, "reusedState");
    }

    private static boolean hasField(ClassNode node, String name) {
        for (FieldNode field : node.fields) {
            if (field.name.equals(name)) return true;
        }
        return false;
    }
}
