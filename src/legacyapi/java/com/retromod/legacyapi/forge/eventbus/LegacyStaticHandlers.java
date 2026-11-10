/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.eventbus;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.eventbus.api.bus.EventBus;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.IModBusEvent;
import net.minecraftforge.fml.loading.FMLEnvironment;

/** Puts an old mod's static EventBus 7 handler on each bus once, as Forge 1.20.1 took a class once per bus. */
public final class LegacyStaticHandlers {

    // Buses are compared by identity: EventBus 7's are records whose hash follows their listener lists.
    private static final Map<Method, Set<Object>> HOOKED = new HashMap<>();

    private LegacyStaticHandlers() {}

    /** Whether the static handler still has to go onto the bus: false when FML subscribes it there or it is already there. */
    public static boolean claim(BusGroup receiving, Method handler, Object bus) {
        // FML subscribes a kept @Mod.EventBusSubscriber class after the mod's constructor, so its turn is foreseen.
        if (bus == fmlBus(receiving, handler)) return false;
        synchronized (HOOKED) {
            return HOOKED.computeIfAbsent(handler, any -> Collections.newSetFromMap(new IdentityHashMap<>())).add(bus);
        }
    }

    /** The static one-parameter handler a subscriber's companion names, by its event's name or by its own name alone. */
    public static Method find(Class<?> owner, String handler, String event) throws NoSuchMethodException {
        try {
            return owner.getDeclaredMethod(handler, Class.forName(event, false, owner.getClassLoader()));
        } catch (ClassNotFoundException moved) {
            // The embed moves an event Retromod built into the mod, and a companion names it in a string the embed leaves.
            for (Method method : owner.getDeclaredMethods()) {
                if (method.getName().equals(handler) && Modifier.isStatic(method.getModifiers())
                        && method.getParameterCount() == 1) {
                    return method;
                }
            }
            throw new NoSuchMethodException(owner.getName() + "." + handler + " taking " + event);
        }
    }

    /** Lets the static handler onto the bus again, after {@code unregister} took it off there. */
    public static void release(Method handler, Object bus) {
        synchronized (HOOKED) {
            Set<Object> buses = HOOKED.get(handler);
            if (buses != null) buses.remove(bus);
        }
    }

    // The bus FML puts the handler on when it subscribes the handler's own class, or null when it leaves the class alone.
    private static Object fmlBus(BusGroup receiving, Method handler) {
        try {
            Mod.EventBusSubscriber subscriber = handler.getDeclaringClass().getAnnotation(Mod.EventBusSubscriber.class);
            if (subscriber == null || !Arrays.asList(subscriber.value()).contains(FMLEnvironment.dist)) return null;
            Class<?> type = handler.getParameterTypes()[0];
            BusGroup group = fmlGroup(subscriber, type, receiving);
            return group == null ? null : create(group, type);
        } catch (RuntimeException | LinkageError unreadable) {
            // A handler FML's rules cannot be read for is hooked, since a missing listener is worse than a repeated one.
            return null;
        }
    }

    // The group FML 26.2 and 26.3 use: the game group, or for a mod-bus event the subscribing mod's own group.
    private static BusGroup fmlGroup(Mod.EventBusSubscriber subscriber, Class<?> type, BusGroup receiving) {
        String modId = subscriber.modid();
        Optional<? extends ModContainer> named = modId.isEmpty() ? Optional.empty() : ModList.getModContainerById(modId);
        // FML subscribes a class only for the mod its id names.
        if (!modId.isEmpty() && named.isEmpty()) return null;
        // An old mod that left the bus out gets FML's default, BOTH, which picks the group by the event.
        boolean modBus = switch (subscriber.bus().name()) {
            case "FORGE" -> false;
            case "MOD" -> true;
            default -> IModBusEvent.class.isAssignableFrom(type);
        };
        if (!modBus) return BusGroup.DEFAULT;
        if (named.isPresent()) return named.get().getModBusGroup();
        // Without an id FML uses the group of the mod that holds the class, which is the mod's own bus.
        return receiving == BusGroup.DEFAULT ? null : receiving;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object create(BusGroup group, Class<?> type) {
        return EventBus.create(group, (Class) type);
    }
}
