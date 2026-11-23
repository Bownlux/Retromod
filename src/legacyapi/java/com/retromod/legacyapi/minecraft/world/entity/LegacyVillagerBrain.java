/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.entity;

import com.google.common.collect.ImmutableList;
import net.minecraft.world.entity.npc.villager.Villager;

import java.lang.reflect.Field;
import java.util.Collection;

/** {@code Villager.MEMORY_TYPES} and {@code SENSOR_TYPES}, which 26.1 folded into the villager's brain provider. */
public final class LegacyVillagerBrain {

    private static final String PROVIDER = "BRAIN_PROVIDER";
    private static final String MEMORIES = "memoryTypes";
    private static final String SENSORS = "sensorTypes";

    private LegacyVillagerBrain() {}

    /** The memories mods added; on 26.1 and newer the host derives the rest from the brain's sensors and activities. */
    public static ImmutableList<Object> memoryTypes() {
        return read(MEMORIES);
    }

    /** Replaces them, as a mod that added a memory to the old list did; villagers made from then on have it. */
    public static void setMemoryTypes(Collection<?> types) {
        write(MEMORIES, types);
    }

    /** The sensors every new villager brain gets. */
    public static ImmutableList<Object> sensorTypes() {
        return read(SENSORS);
    }

    /** Replaces them, as a mod that added a sensor to the old list did. */
    public static void setSensorTypes(Collection<?> types) {
        write(SENSORS, types);
    }

    private static ImmutableList<Object> read(String name) {
        try {
            Object provider = provider();
            return ImmutableList.copyOf((Collection<?>) field(provider, name).get(provider));
        } catch (ReflectiveOperationException | RuntimeException failure) {
            throw new IllegalStateException("Retromod could not read the villager brain's " + name, failure);
        }
    }

    // Each write replaces the whole list; a mod's read and write are separate calls, as they were with the old field.
    private static synchronized void write(String name, Collection<?> types) {
        try {
            Object provider = provider();
            field(provider, name).set(provider, ImmutableList.copyOf(types));
        } catch (ReflectiveOperationException | RuntimeException failure) {
            throw new IllegalStateException("Retromod could not change the villager brain's " + name, failure);
        }
    }

    /** The one provider every villager's brain is made from. */
    private static Object provider() throws ReflectiveOperationException {
        Field field = Villager.class.getDeclaredField(PROVIDER);
        field.setAccessible(true);
        return field.get(null);
    }

    /** The provider's list field, which is final, so it is opened for the write. A future JDK may refuse that write (JEP 500). */
    private static Field field(Object provider, String name) throws NoSuchFieldException {
        Field field = provider.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
