/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.event;

import net.minecraft.client.DeltaTracker;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.LogicalSide;

/** Forge 1.20.1 tick event fields and methods, answered from the Pre and Post events Forge 26.2 fires. */
public final class TickEvents {

    private TickEvents() {}

    /** {@code event.phase}: a Pre event is the start of the tick, a Post event its end. */
    public static TickPhase phase(Object event) {
        boolean start = event instanceof TickEvent.ClientTickEvent.Pre || event instanceof TickEvent.ServerTickEvent.Pre
                || event instanceof TickEvent.LevelTickEvent.Pre || event instanceof TickEvent.PlayerTickEvent.Pre
                || event instanceof TickEvent.RenderTickEvent.Pre;
        return start ? TickPhase.START : TickPhase.END;
    }

    /** {@code event.type}: the old tick event a Pre or Post event stands for. */
    public static TickType type(Object event) {
        if (event instanceof TickEvent.LevelTickEvent) return TickType.LEVEL;
        if (event instanceof TickEvent.PlayerTickEvent) return TickType.PLAYER;
        if (event instanceof TickEvent.ServerTickEvent) return TickType.SERVER;
        if (event instanceof TickEvent.RenderTickEvent) return TickType.RENDER;
        // The client tick is the one left; its records carry no data.
        return TickType.CLIENT;
    }

    /** {@code event.side}: level and player ticks carry theirs, and the server tick runs on the server. */
    public static LogicalSide side(Object event) {
        if (event instanceof TickEvent.LevelTickEvent level) return level.side();
        if (event instanceof TickEvent.PlayerTickEvent player) return player.side();
        return event instanceof TickEvent.ServerTickEvent ? LogicalSide.SERVER : LogicalSide.CLIENT;
    }

    /** {@code LevelTickEvent.level}. */
    public static Level level(Object event) {
        return ((TickEvent.LevelTickEvent) event).level();
    }

    /** {@code PlayerTickEvent.player}. */
    public static Player player(Object event) {
        return event instanceof TickEvent.PlayerTickEvent.Pre pre ? pre.player() : ((TickEvent.PlayerTickEvent.Post) event).player();
    }

    /** {@code ServerTickEvent.getServer()}. */
    public static MinecraftServer server(Object event) {
        return ((TickEvent.ServerTickEvent) event).server();
    }

    /** {@code haveTime()} on a server or level tick, a default method on Forge 26.2's interfaces. */
    public static boolean haveTime(Object event) {
        return event instanceof TickEvent.ServerTickEvent server ? server.haveTime()
                : ((TickEvent.LevelTickEvent) event).haveTime();
    }

    /** {@code RenderTickEvent.renderTickTime}: the paused or running partial tick, as Forge 1.20.1 passed it. */
    public static float renderTickTime(Object event) {
        DeltaTracker timer = event instanceof TickEvent.RenderTickEvent.Pre pre ? pre.timer()
                : ((TickEvent.RenderTickEvent.Post) event).timer();
        // False matches what the 26.2 game and level renderers pass.
        return timer.getGameTimeDeltaPartialTick(false);
    }
}
