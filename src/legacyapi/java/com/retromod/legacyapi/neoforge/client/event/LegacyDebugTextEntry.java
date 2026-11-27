/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.client.event;

import com.retromod.shim.common.embedded.LegacyLog;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.neoforge.client.event.RegisterDebugEntriesEvent;
import net.neoforged.neoforge.common.NeoForge;

/** A debug screen entry per old mod that posts this copy's old debug text event and shows its two columns. */
final class LegacyDebugTextEntry implements DebugScreenEntry {

    private static boolean listening;

    private final Identifier left;
    private final Identifier right;
    private volatile boolean failed;

    private LegacyDebugTextEntry(String modId) {
        this.left = Identifier.fromNamespaceAndPath("retromod", "legacy_debug_text/" + modId + "/left");
        this.right = Identifier.fromNamespaceAndPath("retromod", "legacy_debug_text/" + modId + "/right");
    }

    /** Registers this copy's entry on the constructing mod's bus, once; the player turns it on in the debug options. */
    static void listen() {
        // Construction is the only time FML names an active mod.
        ModContainer container = ModLoadingContext.get().getActiveContainer();
        IEventBus bus = container == null ? null : container.getEventBus();
        if (bus == null) return;
        synchronized (LegacyDebugTextEntry.class) {
            if (listening) return;
            listening = true;
        }
        String modId = container.getModId();
        bus.addListener(RegisterDebugEntriesEvent.class, event -> event.register(
                Identifier.fromNamespaceAndPath("retromod", "legacy_debug_text/" + modId), new LegacyDebugTextEntry(modId)));
    }

    @Override
    public void display(DebugScreenDisplayer displayer, Level level, LevelChunk clientChunk, LevelChunk serverChunk) {
        // After one failure the event is not posted again, since a broken listener would fail on every frame.
        if (failed) return;
        LegacyDebugTextEvent event = new LegacyDebugTextEvent();
        try {
            NeoForge.EVENT_BUS.post(event);
        } catch (RuntimeException | LinkageError failure) {
            failed = true;
            LegacyLog.warnOnce("debug text " + left, "An old debug screen text listener failed, so its mod's debug text is "
                    + "left out until the game restarts: " + failure);
            return;
        }
        if (!event.getLeft().isEmpty()) displayer.addToGroup(left, event.getLeft());
        if (!event.getRight().isEmpty()) displayer.addToGroup(right, event.getRight());
    }
}
