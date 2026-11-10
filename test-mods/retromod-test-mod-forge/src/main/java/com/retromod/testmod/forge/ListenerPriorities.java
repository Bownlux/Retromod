/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import java.util.ArrayList;
import java.util.List;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.LoggerFactory;

/**
 * Old listener objects registered with {@code MinecraftForge.EVENT_BUS.register}: one with two prioritized
 * handlers and a static one its subscriber class hooks, and a package-private one with a single handler. On
 * Forge 26.2 the old enum priority used to stop registration, a lone listener ran twice, and registering the
 * object ran the static handler a second time. The server starts after the summary, so the result is in the
 * log rather than the summary.
 */
final class ListenerPriorities {

    private static final List<String> ORDER = new ArrayList<>();

    private ListenerPriorities() {}

    // Forge 1.20.1 subscribes the static handler through the class only, never through the object.
    @Mod.EventBusSubscriber(modid = "retromod_test_mod_forge")
    public static final class Ordered {
        @SubscribeEvent(priority = EventPriority.HIGHEST)
        public static void fromClass(ServerStartedEvent event) {
            ORDER.add("static");
        }

        @SubscribeEvent(priority = EventPriority.LOW)
        public void low(ServerStartedEvent event) {
            ORDER.add("low");
        }

        @SubscribeEvent(priority = EventPriority.HIGH)
        public void high(ServerStartedEvent event) {
            ORDER.add("high");
        }
    }

    static final class Lone {
        @SubscribeEvent
        public void normal(ServerStartedEvent event) {
            ORDER.add("lone");
        }
    }

    /** Registers both objects and a LOWEST check that logs what ran, in order. */
    static void register() {
        try {
            MinecraftForge.EVENT_BUS.register(new Ordered());
            MinecraftForge.EVENT_BUS.register(new Lone());
            MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, ListenerPriorities::check);
        } catch (Throwable t) {
            log("fail: registration threw " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void check(ServerStartedEvent event) {
        List<String> expected = List.of("static", "high", "lone", "low");
        log(ORDER.equals(expected) ? "success" : "fail: ran " + ORDER + ", expected " + expected);
        // Each server start in one session is judged on its own run.
        ORDER.clear();
    }

    private static void log(String result) {
        LoggerFactory.getLogger("Retromod-Test-Forge").info("[Retromod-Test-Forge] Old @SubscribeEvent priorities order "
                + "registered listeners, and a lone or static listener runs once (server started): {}", result);
    }
}
