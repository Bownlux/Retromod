/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.fmlhost;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.BiConsumer;
import java.util.function.Function;

/** Stands in for Forge 26.2's static ModLoader: it records each call a test can then read back. */
public final class ModLoader {

    /** Each call as "name argument", in order. */
    public static final List<String> CALLS = new ArrayList<>();
    /** Each event posted, in order. */
    public static final List<Object> POSTED = new ArrayList<>();
    /** The names of the states FML has completed. */
    public static final Set<String> COMPLETED = new CopyOnWriteArraySet<>();
    private static final List<ModLoadingWarning> WARNINGS = new ArrayList<>();

    private ModLoader() {}

    public static void addWarning(ModLoadingWarning warning) {
        CALLS.add("addWarning");
        WARNINGS.add(warning);
    }

    public static List<ModLoadingWarning> getWarnings() {
        CALLS.add("getWarnings");
        return List.copyOf(WARNINGS);
    }

    public static boolean hasCompletedState(IModLoadingState state) {
        CALLS.add("hasCompletedState " + state.name());
        return COMPLETED.contains(state.name());
    }

    public static <T extends IModBusEvent> void runEventGenerator(Function<Object, T> generator) {
        CALLS.add("runEventGenerator");
        POSTED.add(generator.apply(null));
    }

    public static <T extends IModBusEvent> void postEvent(T event) {
        CALLS.add("postEvent");
        POSTED.add(event);
    }

    public static <T extends IModBusEvent> T postEventWithReturn(T event) {
        CALLS.add("postEventWithReturn");
        POSTED.add(event);
        return event;
    }

    public static <T extends IModBusEvent> void postEventWrapContainerInModOrder(T event) {
        CALLS.add("postEventWrapContainerInModOrder");
        POSTED.add(event);
    }

    public static <T extends IModBusEvent> void postEventWithWrapInModOrder(T event, BiConsumer<Object, T> pre,
            BiConsumer<Object, T> post) {
        CALLS.add("postEventWithWrapInModOrder");
        POSTED.add(event);
    }
}
