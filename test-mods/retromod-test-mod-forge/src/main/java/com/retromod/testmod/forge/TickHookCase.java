/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.LoggerFactory;

/** Checks a spark-style listener on the base TickEvent, and unregister, while a Forge server runs. */
final class TickHookCase {

    // What a server with a loaded world fires every tick, with or without players.
    private static final Set<String> EXPECTED = Set.of("SERVER START", "SERVER END", "LEVEL START", "LEVEL END");
    private static final int WAIT = 20;

    private TickHookCase() {}

    static void register() {
        MinecraftForge.EVENT_BUS.addListener(TickHookCase::start);
    }

    // Each server start in one session is judged on its own run. The checks need server ticks, so the
    // results are in the log rather than the summary.
    private static void start(ServerStartedEvent event) {
        try {
            new Run().begin();
        } catch (Throwable t) {
            log("an old listener on the base TickEvent reads type and phase, and unregister stops it",
                    "fail: registration threw " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /** One server's hook, typed counter and tick clock. */
    private static final class Run {
        // Client events reach the hook on the render thread, so the set is shared.
        private final Set<String> seen = ConcurrentHashMap.newKeySet();
        private final Hook hook = new Hook(this);
        private final Watch watch = new Watch(this);
        private final Consumer<TickEvent.ServerTickEvent> counter = this::count;
        private int ticks;
        private int hookClosedAt = -1;
        private int counterClosedAt = -1;
        private int hookCallsAfter;
        private int counterStarts;
        private int counterEnds;
        private int counterCallsAfter;

        void begin() {
            MinecraftForge.EVENT_BUS.register(hook);
            MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, TickEvent.ServerTickEvent.class, counter);
            MinecraftForge.EVENT_BUS.register(watch);
        }

        void hookTick(TickEvent event) {
            // Server ticks run on the server thread, the one that unregisters the hook.
            boolean server = event.type == TickEvent.Type.SERVER;
            if (server && hookClosedAt >= 0) {
                hookCallsAfter++;
                return;
            }
            seen.add(event.type.name() + " " + event.phase.name());
            // spark stops its hook from inside a tick, so the hook unregisters itself from its own listener.
            if (server && event.phase == TickEvent.Phase.END && seen.containsAll(EXPECTED)) {
                MinecraftForge.EVENT_BUS.unregister(hook);
                hookClosedAt = ticks;
            }
        }

        void count(TickEvent.ServerTickEvent event) {
            if (counterClosedAt >= 0 && ticks > counterClosedAt) {
                counterCallsAfter++;
            } else if (event.phase == TickEvent.Phase.START) {
                counterStarts++;
            } else {
                counterEnds++;
            }
        }

        void watchTick(TickEvent.ServerTickEvent event) {
            if (event.phase != TickEvent.Phase.START) return;
            ticks++;
            if (hookClosedAt >= 0 && counterClosedAt < 0 && ticks == hookClosedAt + WAIT) {
                boolean types = TickEvent.Type.values().length == 5 && TickEvent.Type.valueOf("RENDER") == TickEvent.Type.RENDER;
                log("an old listener on the base TickEvent reads type and phase, and unregister stops it",
                        !types ? "fail: TickEvent.Type has " + TickEvent.Type.values().length + " constants"
                                : hookCallsAfter > 0 ? "fail: " + hookCallsAfter + " server ticks reached it after unregister"
                                : "success");
                // The typed listener's two halves are removed by one unregister of the consumer.
                MinecraftForge.EVENT_BUS.unregister(counter);
                counterClosedAt = ticks;
            } else if (counterClosedAt >= 0 && ticks == counterClosedAt + WAIT) {
                log("a typed server tick listener hears both phases, and unregister removes both",
                        counterStarts == 0 || counterEnds == 0 ? "fail: heard " + counterStarts + " starts and " + counterEnds + " ends"
                                : counterCallsAfter > 0 ? "fail: " + counterCallsAfter + " calls after unregister"
                                : "success");
                MinecraftForge.EVENT_BUS.unregister(watch);
            }
        }
    }

    /** The hook object, as spark registers it: one listener on the base event. */
    public static final class Hook {
        private final Run run;

        Hook(Run run) {
            this.run = run;
        }

        @SubscribeEvent
        public void onTick(TickEvent event) {
            run.hookTick(event);
        }
    }

    /** Counts server ticks, so each check waits a fixed number of them. */
    public static final class Watch {
        private final Run run;

        Watch(Run run) {
            this.run = run;
        }

        @SubscribeEvent
        public void onServerTick(TickEvent.ServerTickEvent event) {
            run.watchTick(event);
        }
    }

    private static void log(String description, String result) {
        LoggerFactory.getLogger("Retromod-Test-Forge").info("[Retromod-Test-Forge] {} (server ticks): {}", description, result);
    }
}
