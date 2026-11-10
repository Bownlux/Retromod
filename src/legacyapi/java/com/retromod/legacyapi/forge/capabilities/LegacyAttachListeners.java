/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.capabilities;

import com.retromod.legacyapi.forge.eventbus.LegacyListenerSets;
import com.retromod.shim.common.embedded.LegacyLog;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.function.Consumer;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.eventbus.api.bus.EventBus;

/** Old generic attach listeners and {@code getGenericType} reads on Forge 26.2, which fires one attach event per holder kind. */
public final class LegacyAttachListeners {

    private static final String ATTACH_EVENT = "net.minecraftforge.event.AttachCapabilitiesEvent";
    // Each kind Forge 26.2 fires and the holder class Forge 1.20.1 named for it, the only five it ever attached to.
    private static final String[][] KINDS = {
        {"Entities", "net.minecraft.world.entity.Entity"},
        {"ItemStacks", "net.minecraft.world.item.ItemStack"},
        {"BlockEntities", "net.minecraft.world.level.block.entity.BlockEntity"},
        {"Levels", "net.minecraft.world.level.Level"},
        {"LevelChunks", "net.minecraft.world.level.chunk.LevelChunk"},
    };

    private LegacyAttachListeners() {}

    /** Whether {@code type} is the host's attach event, split into one event per holder kind. */
    public static boolean isSplitAttachEvent(Class<?> type) {
        return type != null && type.getName().equals(ATTACH_EVENT) && kind(type, KINDS[0][0]) != null;
    }

    /** {@code bus.addGenericListener(holder, ...)} for the attach event: the listener joins that holder kind's event. */
    public static void add(BusGroup group, Class<?> attachEvent, Class<?> holder, byte priority, Consumer<Object> listener) {
        // Forge 1.20.1 posted attach events on the game bus only, so a listener added to a mod bus never ran.
        if (group != BusGroup.DEFAULT) return;
        Class<?> kind = kindFor(attachEvent, holder);
        if (kind == null) {
            String name = holder == null ? "no class" : holder.getName();
            LegacyLog.warnOnce("attach listener " + name, "The capability listener " + listener.getClass().getName()
                    + " asks for " + name + ", which Forge never attaches capabilities to, so Retromod did not add it");
            return;
        }
        EventBus<?> bus = busOf(kind);
        if (bus == null) return;
        // Remembered under the listener, so the old unregister(listener) removes it again.
        LegacyListenerSets.remember(group, listener, bus, addTo(bus, priority, listener));
    }

    /** {@code event.getGenericType()}: the holder class, such as Entity for an entity's attach event, or null. */
    public static Type getGenericType(Object event) {
        // The event's class, or the one of its superclasses that extends the attach event directly.
        for (Class<?> type = event.getClass(); type.getSuperclass() != null; type = type.getSuperclass()) {
            if (type.getSuperclass().getName().equals(ATTACH_EVENT)) return holderOf(type);
        }
        return null;
    }

    // The holder kind whose event Forge 1.20.1 would have filtered for this class, matched by identity as EventBus 6 did.
    private static Class<?> kindFor(Class<?> attachEvent, Class<?> holder) {
        if (holder == null) return null;
        for (String[] kind : KINDS) {
            if (!kind[1].equals(holder.getName())) continue;
            Class<?> type = kind(attachEvent, kind[0]);
            return type != null && holderOf(type) == holder ? type : null;
        }
        return null;
    }

    // The nested event class, loaded without running its initializer.
    private static Class<?> kind(Class<?> attachEvent, String name) {
        try {
            return Class.forName(attachEvent.getName() + "$" + name, false, attachEvent.getClassLoader());
        } catch (ClassNotFoundException | LinkageError absent) {
            return null;
        }
    }

    // The typed getObject() each kind declares beside its bridge names the holder.
    private static Class<?> holderOf(Class<?> kind) {
        try {
            for (Method method : kind.getDeclaredMethods()) {
                if (method.getName().equals("getObject") && method.getParameterCount() == 0 && !method.isBridge()) {
                    return method.getReturnType();
                }
            }
        } catch (LinkageError unreadable) {
            // A kind whose methods cannot be read matches no holder.
        }
        return null;
    }

    private static EventBus<?> busOf(Class<?> kind) {
        try {
            Field field = kind.getField("BUS");
            return field.get(null) instanceof EventBus<?> bus ? bus : null;
        } catch (ReflectiveOperationException | LinkageError unreadable) {
            LegacyLog.warnOnce("attach bus " + kind.getName(), "Retromod could not read the event bus of "
                    + kind.getName() + ", so old capability listeners for it were not added: " + unreadable);
            return null;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static net.minecraftforge.eventbus.api.listener.EventListener addTo(EventBus<?> bus, byte priority,
            Consumer<Object> listener) {
        return ((EventBus) bus).addListener(priority, (Consumer) listener);
    }
}
