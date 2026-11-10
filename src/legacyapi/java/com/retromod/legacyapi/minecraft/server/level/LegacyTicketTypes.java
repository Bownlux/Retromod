/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.server.level;

import net.minecraft.server.level.TicketType;

import java.lang.reflect.Constructor;
import java.util.Comparator;

/** {@code TicketType.create}, which 1.21.5 removed, rebuilt as a new unregistered ticket type that loads and ticks like the old one. */
public final class LegacyTicketTypes {

    // The flag values from 1.21.9 on (checked on 1.21.11 and 26.3), used by value because a Fabric host before 26.1 renames the fields.
    private static final int LOADING = 2;
    private static final int SIMULATION = 4;
    private static final int CAN_EXPIRE_IF_UNLOADED = 16;
    // 1.21.5 to 1.21.8 take a TicketUse instead of flags, whose third constant is LOADING_AND_SIMULATION.
    private static final int LOADING_AND_SIMULATION = 2;

    private LegacyTicketTypes() {}

    /** {@code TicketType.create(name, comparator)}: tickets that keep their chunks loaded and ticking until they are removed. */
    public static TicketType create(String name, Comparator<?> comparator) {
        return create(name, comparator, 0);
    }

    /** {@code TicketType.create(name, comparator, lifespan)}: the same, but each ticket lapses {@code lifespan} ticks after it is added. */
    public static TicketType create(String name, Comparator<?> comparator, int lifespan) {
        // Tickets match by type identity, so every call gets its own instance, as before.
        try {
            for (Constructor<?> constructor : TicketType.class.getConstructors()) {
                Class<?>[] parameters = constructor.getParameterTypes();
                if (parameters.length == 2 && parameters[0] == long.class && parameters[1] == int.class) {
                    // Old tickets were never saved and never kept an empty dimension running.
                    // A timed one lapsed on schedule even while its chunk was still loading.
                    int flags = LOADING | SIMULATION | (lifespan != 0 ? CAN_EXPIRE_IF_UNLOADED : 0);
                    return (TicketType) constructor.newInstance((long) lifespan, flags);
                }
                if (parameters.length == 3 && parameters[0] == long.class && parameters[1] == boolean.class && parameters[2].isEnum()) {
                    return (TicketType) constructor.newInstance((long) lifespan, false, parameters[2].getEnumConstants()[LOADING_AND_SIMULATION]);
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not create the chunk ticket type " + name + " on this Minecraft version", e);
        }
        throw new IllegalStateException("Retromod found no chunk ticket type constructor it knows for " + name
                + " on this Minecraft version. Report this line with the Retromod and Minecraft versions.");
    }
}
