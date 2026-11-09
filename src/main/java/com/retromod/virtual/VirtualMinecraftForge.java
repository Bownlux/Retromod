/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.virtual;

import com.retromod.shim.common.embedded.LegacyPublicMethods;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.*;

/**
 * Virtual replacement for MinecraftForge class.
 * Emulates the old Forge event bus system.
 */
public class VirtualMinecraftForge {
    
    public static final VirtualEventBus EVENT_BUS = new VirtualEventBus();
    public static final VirtualEventBus TERRAIN_GEN_BUS = new VirtualEventBus();
    public static final VirtualEventBus ORE_GEN_BUS = new VirtualEventBus();
    
    /**
     * Virtual event bus implementation.
     */
    public static class VirtualEventBus {
        private final Map<Class<?>, List<EventHandler>> handlers = new HashMap<>();
        
        public void register(Object target) {
            for (Method method : target.getClass().getMethods()) {
                if (method.isAnnotationPresent(VirtualSubscribeEvent.class)) {
                    Class<?>[] params = method.getParameterTypes();
                    if (params.length == 1) {
                        Class<?> eventType = params[0];
                        // The annotation is read on the class's own method; the call goes through a public declaration.
                        handlers.computeIfAbsent(eventType, k -> new ArrayList<>())
                                .add(new EventHandler(target, LegacyPublicMethods.invocable(target.getClass(), method)));
                    }
                }
            }
        }
        
        public void unregister(Object target) {
            for (List<EventHandler> list : handlers.values()) {
                list.removeIf(h -> h.target == target);
            }
        }
        
        public boolean post(Object event) {
            List<EventHandler> eventHandlers = handlers.get(event.getClass());
            if (eventHandlers != null) {
                for (EventHandler handler : eventHandlers) {
                    try {
                        handler.method.invoke(handler.target, event);
                    } catch (Exception e) {
                        System.err.println("Retromod: Error in event handler: " + e.getMessage());
                    }
                }
            }
            
            try {
                Method isCanceled = LegacyPublicMethods.method(event.getClass(), "isCanceled");
                return (boolean) isCanceled.invoke(event);
            } catch (Exception e) {
                return false;
            }
        }
        
        public <T> void addListener(Consumer<T> consumer) {
            // Modern-style listener registration
        }
        
        private record EventHandler(Object target, Method method) {}
    }
}
