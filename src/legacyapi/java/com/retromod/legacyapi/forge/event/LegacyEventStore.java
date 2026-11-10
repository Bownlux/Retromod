/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.event;

import com.retromod.legacyapi.forge.LegacySharedSlots;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.Iterator;

/** Values Forge 1.20.1 kept on an event, held per thread beside a host event that no longer keeps them. */
public final class LegacyEventStore {

    /** The cancel flag, a {@code Boolean}. */
    public static final int CANCELED = 0;
    /** The event result, the host's {@code Result}. */
    public static final int RESULT = 1;

    // An entry is {weak event, cancel flag, result}; change PROTOCOL with the layout.
    private static final int PROTOCOL = 1;
    private static final int FIELDS = 2;
    // Only the newest events a thread touched are kept, so a post and the read after it always meet.
    private static final int SLOTS = 16;

    // Every mod's copy shares one store, so a value one mod's listener sets is read by the mod that posted.
    @SuppressWarnings("unchecked")
    private static final ThreadLocal<ArrayDeque<Object[]>> ENTRIES =
            LegacySharedSlots.get("forge.event.store", PROTOCOL, ThreadLocal.class, ThreadLocal::new);

    private LegacyEventStore() {}

    /** The value kept for {@code event}, or null. */
    public static Object get(Object event, int field) {
        Object[] entry = find(entries(), event);
        return entry == null ? null : entry[1 + field];
    }

    /** Keeps {@code value} for {@code event}, which becomes the newest entry. */
    public static void put(Object event, int field, Object value) {
        ArrayDeque<Object[]> entries = entries();
        Object[] entry = find(entries, event);
        if (entry == null) {
            entry = new Object[1 + FIELDS];
            entry[0] = new WeakReference<>(event);
        } else {
            entries.remove(entry);
        }
        entry[1 + field] = value;
        entries.addFirst(entry);
        while (entries.size() > SLOTS) entries.removeLast();
    }

    /** How many events this thread's store still holds. */
    public static int size() {
        ArrayDeque<Object[]> entries = entries();
        find(entries, null);
        return entries.size();
    }

    private static ArrayDeque<Object[]> entries() {
        ArrayDeque<Object[]> entries = ENTRIES.get();
        if (entries == null) {
            entries = new ArrayDeque<>();
            ENTRIES.set(entries);
        }
        return entries;
    }

    // Matched by identity, since equal records are still separate events; collected events drop out on the way.
    private static Object[] find(ArrayDeque<Object[]> entries, Object event) {
        Object[] found = null;
        for (Iterator<Object[]> each = entries.iterator(); each.hasNext(); ) {
            Object[] entry = each.next();
            Object held = ((WeakReference<?>) entry[0]).get();
            if (held == null) {
                each.remove();
            } else if (held == event && found == null) {
                found = entry;
            }
        }
        return found;
    }
}
