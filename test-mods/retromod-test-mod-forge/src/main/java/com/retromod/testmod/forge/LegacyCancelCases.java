/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import java.util.ArrayList;
import java.util.List;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingChangeTargetEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.LoggerFactory;

/**
 * Old listeners that cancel a Forge event, which Forge 26.2 cancels only through a listener's
 * return value: lambdas, @SubscribeEvent methods on objects and subscriber classes, and a
 * receiveCanceled listener. The event is posted once the server starts, so the results are in the log.
 */
final class LegacyCancelCases {

    static final List<String> NOTES = new ArrayList<>();
    // The case the listeners act on, null between posts, so a real mob's target change is left alone.
    static String active;

    private LegacyCancelCases() {}

    static void register() {
        try {
            MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGH, LegacyCancelCases::high);
            MinecraftForge.EVENT_BUS.addListener(EventPriority.LOW, LegacyCancelCases::low);
            MinecraftForge.EVENT_BUS.addListener(LegacyCancelCases::check);
        } catch (Throwable t) {
            log("Old lambda listeners on a cancellable Forge event register (construction)",
                    "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        try {
            // EventBus 7 reads a public class with two listeners itself; Retromod hooks a class it cannot see into.
            MinecraftForge.EVENT_BUS.register(new ObjectHandlers());
            MinecraftForge.EVENT_BUS.register(new HiddenHandlers());
        } catch (Throwable t) {
            log("Old @SubscribeEvent objects on a cancellable Forge event register (construction)",
                    "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /** Whether one of the given cases is being posted; never true for a real mob's target change. */
    static boolean active(String... cases) {
        if (active == null) return false;
        for (String each : cases) {
            if (each.equals(active)) return true;
        }
        return false;
    }

    // Notes what it read on entry, then cancels, or cancels and takes it back, as the case asks.
    private static void high(LivingChangeTargetEvent event) {
        if (!active("cancel", "uncancel", "read")) return;
        NOTES.add("high read " + event.isCanceled());
        if (active("cancel", "uncancel")) event.setCanceled(true);
        if (active("uncancel")) event.setCanceled(false);
    }

    private static void low(LivingChangeTargetEvent event) {
        if (active("cancel", "uncancel", "read")) NOTES.add("low");
    }

    private static void check(ServerStartedEvent started) {
        log("(a) A static HIGH @SubscribeEvent handler of a subscriber class cancels a Forge event: post returns true, "
                + "isCanceled() is true, and LOW does not run (server started)", post("static", true, "static high"));
        log("(b) An old listener object registered with register() cancels a Forge event, and LOW does not run "
                + "(server started)", post("object", true, "object high"));
        log("(b) An old listener object of a class EventBus 7 cannot see into cancels a Forge event, and LOW does not "
                + "run (server started)", post("hidden", true, "hidden high"));
        log("(c) A lambda listener cancels a Forge event: post returns true, isCanceled() is true, and LOW does not run "
                + "(server started)", post("cancel", true, "high read false"));
        log("(d) A lambda listener that cancels and then un-cancels leaves the event running, and LOW runs "
                + "(server started)", post("uncancel", false, "high read false", "low"));
        log("(e) isCanceled() reads false when a listener starts (server started)",
                post("read", false, "high read false", "low"));
        log("(f) A receiveCanceled LOWEST handler runs once after a cancel and sees isCanceled() true (server started)",
                post("watched", true, "static high", "watch true"));
        log("(f) A receiveCanceled LOWEST handler runs once at its turn when nothing cancels (server started)",
                post("watched-pass", false, "static high", "static low", "watch false"));
        log("(g) The one static handler of a subscriber class cancels a Forge event, and LOW does not run "
                + "(server started)", post("lone", true, "lone high"));
    }

    // "success" when post() and isCanceled() both give the expected answer and the listeners ran in order.
    private static String post(String testCase, boolean cancelled, String... order) {
        try {
            NOTES.clear();
            LivingChangeTargetEvent event = new LivingChangeTargetEvent(null, null,
                    LivingChangeTargetEvent.LivingTargetType.MOB_TARGET);
            boolean posted;
            active = testCase;
            try {
                posted = MinecraftForge.EVENT_BUS.post(event);
            } finally {
                active = null;
            }
            if (posted != cancelled) return "fail: post returned " + posted + " after " + NOTES;
            if (event.isCanceled() != cancelled) return "fail: isCanceled() after the post returned " + !cancelled;
            return NOTES.equals(List.of(order)) ? "success" : "fail: ran " + NOTES + ", expected " + List.of(order);
        } catch (Throwable t) {
            return "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    private static void log(String what, String result) {
        LoggerFactory.getLogger("Retromod-Test-Forge").info("[Retromod-Test-Forge] {}: {}", what, result);
    }

    /**
     * Static handlers FML subscribes: HIGH cancels for (a) and (f), LOW notes every handler case it is
     * reached in, so a cancel that fails to stop it shows, and LOWEST takes cancelled events.
     */
    @Mod.EventBusSubscriber(modid = "retromod_test_mod_forge")
    public static final class StaticHandlers {

        private StaticHandlers() {}

        @SubscribeEvent(priority = EventPriority.HIGH)
        public static void high(LivingChangeTargetEvent event) {
            if (!active("static", "watched", "watched-pass")) return;
            NOTES.add("static high");
            if (!active("watched-pass")) event.setCanceled(true);
        }

        @SubscribeEvent(priority = EventPriority.LOW)
        public static void low(LivingChangeTargetEvent event) {
            if (active("static", "object", "hidden", "watched", "watched-pass", "lone")) NOTES.add("static low");
        }

        @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
        public static void watch(LivingChangeTargetEvent event) {
            if (active("watched", "watched-pass")) NOTES.add("watch " + event.isCanceled());
        }
    }

    /** The one static handler of its subscriber class, which Retromod hooks from a companion class. */
    @Mod.EventBusSubscriber(modid = "retromod_test_mod_forge")
    public static final class LoneHandler {

        private LoneHandler() {}

        @SubscribeEvent(priority = EventPriority.HIGH)
        public static void high(LivingChangeTargetEvent event) {
            if (!active("lone")) return;
            NOTES.add("lone high");
            event.setCanceled(true);
        }
    }

    /** A public listener object with two handlers, which EventBus 7 registers itself. */
    public static final class ObjectHandlers {

        @SubscribeEvent(priority = EventPriority.HIGH)
        public void high(LivingChangeTargetEvent event) {
            if (!active("object")) return;
            NOTES.add("object high");
            event.setCanceled(true);
        }

        @SubscribeEvent(priority = EventPriority.LOW)
        public void low(LivingChangeTargetEvent event) {
            if (active("object")) NOTES.add("object low");
        }
    }

    /** A listener object of a class that is not public, which Retromod hooks handler by handler. */
    static final class HiddenHandlers {

        @SubscribeEvent(priority = EventPriority.HIGH)
        public void high(LivingChangeTargetEvent event) {
            if (!active("hidden")) return;
            NOTES.add("hidden high");
            event.setCanceled(true);
        }

        @SubscribeEvent(priority = EventPriority.LOW)
        public void low(LivingChangeTargetEvent event) {
            if (active("hidden")) NOTES.add("hidden low");
        }
    }
}
