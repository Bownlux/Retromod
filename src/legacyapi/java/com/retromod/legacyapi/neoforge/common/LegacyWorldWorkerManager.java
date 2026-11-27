/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.common;

import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** The world worker manager Forge 1.20.1 and NeoForge 1.21.1 had, which spreads a long job over server ticks, rebuilt for NeoForge 1.21.2 and newer. */
public final class LegacyWorldWorkerManager {

    /** A job that does a little more of its work on each call. */
    public interface IWorker {
        boolean hasWork();

        /** Does one step; false lets the next worker have the rest of this tick. */
        boolean doWork();
    }

    // The part of a tick the workers may fill, as the old manager allowed: a 50 ms tick measured from its start,
    // and at least 10 ms when the tick itself ran late.
    private static final long TICK_NANOS = 50_000_000L;
    private static final long FLOOR_NANOS = 10_000_000L;

    private static final Deque<IWorker> WORKERS = new ArrayDeque<>();
    private static boolean listening;
    private static volatile boolean tickStarted;
    private static volatile long tickStart;

    private LegacyWorldWorkerManager() {}

    /** Queues a worker; it runs at the end of each server tick until it has no work left. */
    public static synchronized void addWorker(IWorker worker) {
        WORKERS.addLast(worker);
        listen();
    }

    /** Drops every queued worker, as a stopping server does. */
    public static synchronized void clear() {
        WORKERS.clear();
    }

    /** The old tick hook: the start of a tick records the time, the end runs the workers in what is left of it. */
    public static void tick(boolean start) {
        if (start) {
            tickStart = System.nanoTime();
            tickStarted = true;
            return;
        }
        // A worker added during a tick waits for the first tick whose start this copy saw.
        if (!tickStarted) return;
        long end = System.nanoTime() + Math.max(TICK_NANOS - (System.nanoTime() - tickStart), FLOOR_NANOS);
        // Each worker gets one turn per tick, in queue order; one that asks to wait hands the tick to the next.
        for (IWorker worker : queued()) {
            while (System.nanoTime() < end) {
                boolean again = worker.doWork();
                if (!worker.hasWork()) {
                    remove(worker);
                    break;
                }
                if (!again) break;
            }
            if (System.nanoTime() >= end) return;
        }
    }

    // A copy, so a worker added during the walk waits for the next tick.
    private static synchronized List<IWorker> queued() {
        return new ArrayList<>(WORKERS);
    }

    // By identity, so a worker's own equals never takes another worker out.
    private static synchronized void remove(IWorker worker) {
        WORKERS.removeIf(queued -> queued == worker);
    }

    // The host posts no worker ticks any more, so this copy listens to the server's own tick events, once.
    private static void listen() {
        if (listening) return;
        listening = true;
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Pre.class, event -> tick(true));
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, event -> tick(false));
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent.class, event -> clear());
    }
}
