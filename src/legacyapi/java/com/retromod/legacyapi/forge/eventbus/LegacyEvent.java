/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.eventbus;

import com.retromod.shim.forge.embedded.LegacyEventPriority;
import java.lang.annotation.Annotation;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraftforge.common.util.HasResult;
import net.minecraftforge.common.util.Result;
import net.minecraftforge.eventbus.api.event.MutableEvent;

/** Forge 1.20.1's event base, which Forge 26.2 removed, rebuilt as an EventBus 7 event with the old cancel, result and phase rules. */
public class LegacyEvent extends MutableEvent implements HasResult {

    // Not InheritableEvent: each class rebased onto this base declares it, so the base never becomes a
    // parent bus, which a mod's bus group would refuse for not being a mod-bus event.

    /** The phase before a bus that tracks phases delivers the event; no old priority has these bits. */
    public static final byte NO_PHASE = Byte.MIN_VALUE;

    // Each mod embeds its own marker copy, so the marker is matched by the end of its name.
    private static final String RESULT_MARKER = "com.retromod.legacyapi.forge.eventbus.LegacyHasResult";
    private static final Map<Class<?>, Boolean> MARKED = new ConcurrentHashMap<>();

    // Not EventBus 7's Cancellable: a flag of its own keeps receiveCanceled, un-cancelling and post's answer.
    private boolean canceled;
    private Result result = Result.DEFAULT;
    private byte phase = NO_PHASE;

    public LegacyEvent() {}

    /** False here; Retromod adds an override answering true to each class marked {@code @Cancelable}. */
    public boolean isCancelable() {
        return false;
    }

    public boolean isCanceled() {
        return canceled;
    }

    /** Forge 1.20.1 refused to cancel an event that is not cancelable. */
    public void setCanceled(boolean cancel) {
        if (!isCancelable()) {
            throw new UnsupportedOperationException(getClass().getName() + " is not a cancelable event");
        }
        canceled = cancel;
    }

    /** Whether this class or one of its superclasses carries the old {@code @Event.HasResult} marker. */
    public boolean hasResult() {
        return MARKED.computeIfAbsent(getClass(), LegacyEvent::marked);
    }

    @Override
    public Result getResult() {
        return result;
    }

    /** Stored as given; Forge 1.20.1 did not check {@link #hasResult()} here either. */
    @Override
    public void setResult(Result value) {
        result = value;
    }

    /** The priority the game bus has reached with this event, or null before delivery and on a mod bus. */
    public LegacyEventPriority getPhase() {
        if (phase == NO_PHASE) return null;
        for (LegacyEventPriority priority : LegacyEventPriority.values()) {
            if (priority.bits() == phase) return priority;
        }
        return null;
    }

    /** Forge 1.20.1 refused a null phase and any move that is not to a lower priority. */
    public void setPhase(LegacyEventPriority value) {
        Objects.requireNonNull(value, "phase");
        byte next = value.bits();
        if (phase != NO_PHASE && next >= phase) {
            throw new IllegalArgumentException("Cannot move the phase of " + getClass().getName()
                    + " from " + getPhase() + " to " + value);
        }
        phase = next;
    }

    /** Whether a listener with {@code receiveCanceled} runs, after moving the phase to {@code phaseBits}. */
    // Every mod's dispatch calls this by name on any mod's copy, so its descriptor names only JDK types.
    public boolean retromod$deliver(boolean receiveCanceled, byte phaseBits) {
        // A mod bus passes NO_PHASE and leaves the phase alone; the game bus only moves it down.
        if (phaseBits != NO_PHASE && (phase == NO_PHASE || phaseBits < phase)) {
            phase = phaseBits;
        }
        return receiveCanceled || !isCancelable() || !isCanceled();
    }

    private static boolean marked(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Annotation annotation : current.getDeclaredAnnotations()) {
                if (annotation.annotationType().getName().endsWith(RESULT_MARKER)) return true;
            }
        }
        return false;
    }
}
