/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.renderer;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;

/** Objects one frame submits, handed out again once the game has drawn that frame. */
final class LegacyFramePool<T> {

    // Past this many in one frame, extra objects are made fresh and left to the garbage collector.
    private static final int MAX_POOLED = 1024;

    private final Supplier<T> create;
    private final List<T> entries = new ArrayList<>();
    private long frame = Long.MIN_VALUE;
    private int used;

    LegacyFramePool(Supplier<T> create) {
        this.create = create;
    }

    /** An object no submit of the frame being built still holds. */
    synchronized T next() {
        // Minecraft writes the last frame's duration once a frame is drawn and never during one, so a new value means
        // every earlier submit has been drawn. An unchanged value only keeps the pool from reusing anything yet.
        long now = Minecraft.getInstance().getFrameTimeNs();
        if (now != frame) {
            frame = now;
            used = 0;
        }
        if (used < entries.size()) return entries.get(used++);
        T fresh = create.get();
        if (entries.size() < MAX_POOLED) {
            entries.add(fresh);
            used++;
        }
        return fresh;
    }
}
