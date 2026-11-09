/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import com.retromod.testlib.forge.RetromodTestLibForge;
import net.minecraftforge.eventbus.api.Cancelable;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.GenericEvent;

/** Events a mod defines on Forge 1.20.1's own event base, which Forge 26.2 removed with EventBus 6. */
final class LegacyEventBaseCase {

    // Kept out of the mod class, so a missing base fails only these checks. Nothing posts these events:
    // the cases build and use them directly, as code around a post does.
    @Cancelable
    static final class CancelableEvent extends Event {
    }

    @Event.HasResult
    static final class ResultEvent extends Event {
    }

    static final class PlainEvent extends Event {
    }

    static final class TypedEvent<T> extends GenericEvent<T> {
        TypedEvent(Class<T> type) {
            super(type);
        }
    }

    private LegacyEventBaseCase() {}

    /** NeoForge keeps an event base of its own, so these Forge cases have nothing to check there. */
    static boolean onNeoForge() {
        try {
            Class.forName("net.neoforged.bus.api.Event", false, LegacyEventBaseCase.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /** A @Cancelable event cancels, a plain one refuses as Forge 1.20.1 did, and a @HasResult event keeps its result. */
    static boolean cancelAndResult() {
        CancelableEvent cancelable = new CancelableEvent();
        cancelable.setCanceled(true);
        ResultEvent withResult = new ResultEvent();
        boolean startsAtDefault = withResult.getResult() == Event.Result.DEFAULT;
        withResult.setResult(Event.Result.DENY);
        PlainEvent plain = new PlainEvent();
        boolean refused;
        try {
            plain.setCanceled(true);
            refused = false;
        } catch (UnsupportedOperationException expected) {
            refused = true;
        }
        return cancelable.isCancelable() && cancelable.isCanceled() && withResult.hasResult() && startsAtDefault
                && withResult.getResult() == Event.Result.DENY && !plain.hasResult() && refused;
    }

    /** Code typed on Event, as Zeta, architectury and balm write it, sees a custom event and nothing else. */
    static boolean eventTypedCode() {
        Object custom = new CancelableEvent();
        Object text = "text";
        return custom instanceof Event && !(text instanceof Event)
                && Event.class.isAssignableFrom(CancelableEvent.class) && !Event.class.isAssignableFrom(String.class)
                && cancelThroughBase((Event) custom);
    }

    // tacz and Quark cancel through an Event-typed value.
    private static boolean cancelThroughBase(Event event) {
        event.setCanceled(true);
        return event.isCancelable() && event.isCanceled();
    }

    /** A switch over Event.Result takes the branch for its constant, though the host enum lists them the other way. */
    static boolean resultSwitch() {
        return branch(Event.Result.valueOf("ALLOW")).equals("allow") && branch(Event.Result.DENY).equals("deny")
                && branch(Event.Result.DEFAULT).equals("default") && Event.Result.values().length == 3;
    }

    private static String branch(Event.Result result) {
        switch (result) {
            case ALLOW:
                return "allow";
            case DENY:
                return "deny";
            default:
                return "default";
        }
    }

    /** A GenericEvent built with its type reports that type. */
    static boolean genericType() {
        return new TypedEvent<>(String.class).getGenericType() == String.class;
    }

    /** The result this mod sets on the library's event is the one the library reads, as alexsmobs does with citadel's. */
    static boolean crossModResult() {
        RetromodTestLibForge.LibResultEvent event = new RetromodTestLibForge.LibResultEvent();
        event.setResult(Event.Result.ALLOW);
        return event.hasResult() && RetromodTestLibForge.allowed(event);
    }
}
