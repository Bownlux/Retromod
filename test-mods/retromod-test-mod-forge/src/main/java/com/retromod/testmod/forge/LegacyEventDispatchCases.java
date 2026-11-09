/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import com.retromod.testlib.forge.RetromodTestLibForge;
import java.util.ArrayList;
import java.util.List;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.Cancelable;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.LoggerFactory;

/** A mod's own events posted on Forge 1.20.1's buses, which Forge 26.2 replaced with EventBus 7. */
final class LegacyEventDispatchCases {

    private static final List<String> NOTES = new ArrayList<>();
    private static boolean registered;

    @Cancelable
    public static class CancelEvent extends Event {
    }

    @Cancelable
    public static class UncancelEvent extends Event {
    }

    public static class OrderEvent extends Event {
    }

    public static class OrderSubEvent extends OrderEvent {
    }

    @Cancelable
    public static class GateEvent extends Event {
    }

    /** Game-bus handlers, registered as an object the way most old mods register theirs. */
    public static final class Handlers {
        @SubscribeEvent(priority = EventPriority.HIGH)
        public void cancelHigh(CancelEvent event) {
            NOTES.add("cancel-high");
            event.setCanceled(true);
        }

        @SubscribeEvent(priority = EventPriority.LOW)
        public void cancelLow(CancelEvent event) {
            NOTES.add("cancel-low");
        }

        @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
        public void cancelLowest(CancelEvent event) {
            NOTES.add("cancel-lowest");
        }

        @SubscribeEvent(priority = EventPriority.HIGH)
        public void uncancelHigh(UncancelEvent event) {
            NOTES.add("uncancel-high");
            event.setCanceled(true);
        }

        @SubscribeEvent
        public void uncancelNormal(UncancelEvent event) {
            NOTES.add("uncancel-normal");
        }

        @SubscribeEvent(priority = EventPriority.LOW, receiveCanceled = true)
        public void uncancelLow(UncancelEvent event) {
            NOTES.add("uncancel-low");
            event.setCanceled(false);
        }

        @SubscribeEvent(priority = EventPriority.LOWEST)
        public void uncancelLowest(UncancelEvent event) {
            NOTES.add("uncancel-lowest");
        }

        @SubscribeEvent(priority = EventPriority.HIGHEST)
        public void orderParentHighest(OrderEvent event) {
            NOTES.add("parent-highest");
        }

        @SubscribeEvent(priority = EventPriority.LOW)
        public void orderSubLow(OrderSubEvent event) {
            NOTES.add("sub-low");
        }

        @SubscribeEvent
        public void orderParentNormal(OrderEvent event) {
            NOTES.add("parent-normal");
        }

        @SubscribeEvent
        public void orderSubNormal(OrderSubEvent event) {
            NOTES.add("sub-normal");
        }

        @SubscribeEvent(priority = EventPriority.HIGH)
        public void orderParentHigh(OrderEvent event) {
            NOTES.add("parent-high");
        }

        @SubscribeEvent
        public void gate(GateEvent event) {
            NOTES.add("gate");
            event.setCanceled(true);
        }
    }

    /** Handlers of the library's events, kept apart so a missing library fails only its own checks. */
    public static final class LibraryHandlers {
        @SubscribeEvent
        public void libraryCancel(RetromodTestLibForge.LibCancelableEvent event) {
            event.setCanceled(true);
        }

        @SubscribeEvent
        public void libraryResult(RetromodTestLibForge.LibResultEvent event) {
            event.setResult(Event.Result.ALLOW);
        }

        @SubscribeEvent
        public void genericString(RetromodTestLibForge.LibGenericEvent<String> event) {
            NOTES.add("generic-string");
        }

        @SubscribeEvent
        public void genericInteger(RetromodTestLibForge.LibGenericEvent<Integer> event) {
            NOTES.add("generic-integer");
        }
    }

    /** A handler on this mod's own bus for the library's mod-bus event. */
    public static final class ModBusHandlers {
        @SubscribeEvent
        public void libraryModBus(RetromodTestLibForge.LibModBusEvent event) {
            NOTES.add("mod-bus");
        }
    }

    /** Static handlers of a Forge event, registered by class as Iceberg and Highlighter register theirs. */
    public static final class StaticHandlers {
        private static int publicRuns;
        private static int hiddenRuns;

        @SubscribeEvent(priority = EventPriority.HIGHEST)
        public static void started(ServerStartedEvent event) {
            publicRuns++;
        }

        // Forge 1.20.1 took only the public static handlers of a class.
        @SubscribeEvent(priority = EventPriority.HIGHEST)
        static void hidden(ServerStartedEvent event) {
            hiddenRuns++;
        }
    }

    private LegacyEventDispatchCases() {}

    private static void register() {
        if (registered) return;
        registered = true;
        MinecraftForge.EVENT_BUS.register(new Handlers());
        MinecraftForge.EVENT_BUS.addListener(LegacyEventDispatchCases::checkGameBus);
        // Forge 1.20.1 kept one copy of a class's handlers however often the class was registered.
        MinecraftForge.EVENT_BUS.register(StaticHandlers.class);
        MinecraftForge.EVENT_BUS.register(StaticHandlers.class);
        try {
            MinecraftForge.EVENT_BUS.register(new LibraryHandlers());
            MinecraftForge.EVENT_BUS.addGenericListener(String.class, LegacyEventDispatchCases::genericLambda);
        } catch (Throwable missingLibrary) {
            log("The library's events take this mod's listeners", "registration threw " + missingLibrary);
        }
    }

    private static void genericLambda(RetromodTestLibForge.LibGenericEvent<String> event) {
        NOTES.add("generic-lambda");
    }

    /** During construction Forge 1.20.1's game bus was still shut down: a post ran nothing and returned false. */
    static boolean gameBusClosedDuringConstruction() {
        register();
        NOTES.clear();
        boolean cancelled = MinecraftForge.EVENT_BUS.post(new GateEvent());
        return !cancelled && NOTES.isEmpty();
    }

    /** A mod bus is live from the start, and takes a library's mod-bus event. */
    static boolean libraryEventOnModBus() {
        NOTES.clear();
        var modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.register(new ModBusHandlers());
        modBus.post(new RetromodTestLibForge.LibModBusEvent());
        return NOTES.equals(List.of("mod-bus"));
    }

    // Posted once the game bus runs; the server starts after the summary, so the results are in the log.
    private static void checkGameBus(ServerStartedEvent started) {
        log("A cancelled own event skips listeners without receiveCanceled, and post returns true",
                expect(postNoting(new CancelEvent()), true, "cancel-high", "cancel-lowest"));
        log("A later receiveCanceled listener un-cancels an own event, and post returns false",
                expect(postNoting(new UncancelEvent()), false, "uncancel-high", "uncancel-low", "uncancel-lowest"));
        log("Own event listeners run by priority, a subclass's before its parent's within one",
                expect(postNoting(new OrderSubEvent()), false,
                        "parent-highest", "parent-high", "sub-normal", "parent-normal", "sub-low"));
        log("The game bus runs a post after loading, which it held back during construction",
                expect(postNoting(new GateEvent()), true, "gate"));
        try {
            log("The library reads the result and the cancel this mod's listeners set on its events",
                    RetromodTestLibForge.postForResult() && RetromodTestLibForge.postCancelable() ? null : "not seen");
            NOTES.clear();
            RetromodTestLibForge.postGeneric(String.class);
            RetromodTestLibForge.postGeneric(Integer.class);
            log("A generic listener runs only for its type, from a handler and from addGenericListener",
                    NOTES.equals(List.of("generic-string", "generic-lambda", "generic-integer")) ? null : "ran " + NOTES);
        } catch (Throwable missingLibrary) {
            log("The library's events reach this mod's listeners", "threw " + missingLibrary);
        }
        log("A class registered twice runs its public static handler once and its package-private one never",
                StaticHandlers.publicRuns == 1 && StaticHandlers.hiddenRuns == 0 ? null
                        : "ran " + StaticHandlers.publicRuns + " and " + StaticHandlers.hiddenRuns + " times");
        Object hostEvent = started;
        log("Event-typed code takes a Forge event and an own event, and nothing else",
                hostEvent instanceof Event && postAny(new CancelEvent()) && !((Object) "text" instanceof Event)
                        ? null : "instanceof or post through Event failed");
    }

    // tacz and Bookshelf post through helpers typed on the base Event.
    private static boolean postAny(Event event) {
        return MinecraftForge.EVENT_BUS.post(event);
    }

    private static boolean postNoting(Event event) {
        NOTES.clear();
        return MinecraftForge.EVENT_BUS.post(event);
    }

    // null when the post returned the expected flag and the listeners ran in the expected order.
    private static String expect(boolean cancelled, boolean expectedCancel, String... order) {
        if (cancelled != expectedCancel) return "post returned " + cancelled;
        return NOTES.equals(List.of(order)) ? null : "ran " + NOTES + ", expected " + List.of(order);
    }

    private static void log(String description, String problem) {
        LoggerFactory.getLogger("Retromod-Test-Forge").info("[Retromod-Test-Forge] {} (server started): {}",
                description, problem == null ? "success" : "fail: " + problem);
    }
}
