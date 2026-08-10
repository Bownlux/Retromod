/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.fabric.embedded;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bridges the old ResourceManagerHelper API onto the ResourceLoader API (Fabric API for 1.21.9):
 * {@code registerReloadListener(listener)} maps to {@code registerReloader(id, listener)}.
 */
public class ResourceManagerHelperShim {

    private static final Map<Object, ResourceManagerHelperShim> INSTANCES = new ConcurrentHashMap<>();
    private static final AtomicInteger COUNTER = new AtomicInteger(0);

    private final Object resourceType;
    private Object realLoader;

    private ResourceManagerHelperShim(Object resourceType) {
        this.resourceType = resourceType;
        initRealLoader();
    }
    
    private void initRealLoader() {
        try {
            Class<?> loaderClass = Class.forName("net.fabricmc.fabric.api.resource.ResourceLoader");
            Method getMethod = loaderClass.getMethod("get", resourceType.getClass());
            realLoader = getMethod.invoke(null, resourceType);
        } catch (ClassNotFoundException e) {
            // pre-1.21.9: fall back to the old helper
            try {
                Class<?> helperClass = Class.forName("net.fabricmc.fabric.api.resource.ResourceManagerHelper");
                Method getMethod = helperClass.getMethod("get", resourceType.getClass());
                realLoader = getMethod.invoke(null, resourceType);
            } catch (Exception ex) {
                throw new RuntimeException("Retromod: Could not find resource loader API", ex);
            }
        } catch (Exception e) {
            throw new RuntimeException("Retromod: Failed to get resource loader", e);
        }
    }
    
    public static ResourceManagerHelperShim get(Object resourceType) {
        return INSTANCES.computeIfAbsent(resourceType, ResourceManagerHelperShim::new);
    }

    /** Old-API entry point; bridges to registerReloader(id, listener). */
    public void registerReloadListener(Object listener) {
        try {
            Object id = getListenerId(listener);
            if (id == null) {
                id = createIdentifier("retromod", "compat_listener_" + COUNTER.incrementAndGet());
            }

            // new API: registerReloader(Identifier, ResourceReloader)
            try {
                Method registerMethod = realLoader.getClass().getMethod(
                    "registerReloader",
                    id.getClass(),
                    Class.forName("net.minecraft.resource.ResourceReloader")
                );
                registerMethod.invoke(realLoader, id, listener);
                return;
            } catch (NoSuchMethodException e) {
                // fall through to old API
            }

            // old API: registerReloadListener(IdentifiableResourceReloadListener)
            try {
                Method registerMethod = findMethodByName(realLoader.getClass(), "registerReloadListener");
                if (registerMethod != null) {
                    registerMethod.invoke(realLoader, listener);
                    return;
                }
            } catch (Exception ignored) {}

            throw new RuntimeException("Could not register reload listener - no compatible API found");

        } catch (Exception e) {
            throw new RuntimeException("Retromod: Failed to register reload listener", e);
        }
    }

    private Object getListenerId(Object listener) {
        try {
            Class<?> identifiableClass = Class.forName(
                "net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener"
            );
            if (identifiableClass.isInstance(listener)) {
                Method getIdMethod = identifiableClass.getMethod("getFabricId");
                return getIdMethod.invoke(listener);
            }
        } catch (ClassNotFoundException e) {
            // interface absent in this version
        } catch (Exception e) {
            // listener has no ID
        }
        return null;
    }
    
    private Object createIdentifier(String namespace, String path) {
        try {
            return IdentifierShim.of(namespace, path);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create identifier: " + namespace + ":" + path, e);
        }
    }
    
    private Method findMethodByName(Class<?> clazz, String name) {
        for (Method m : clazz.getMethods()) {
            if (m.getName().equals(name)) {
                return m;
            }
        }
        return null;
    }

    /** New API only; the old API has no ordering, so this is a no-op there. */
    public void addReloaderOrdering(Object first, Object second) {
        try {
            Method orderMethod = realLoader.getClass().getMethod(
                "addReloaderOrdering",
                first.getClass(),
                second.getClass()
            );
            orderMethod.invoke(realLoader, first, second);
        } catch (NoSuchMethodException e) {
            // old API: no ordering support
        } catch (Exception e) {
            throw new RuntimeException("Retromod: Failed to add reloader ordering", e);
        }
    }
}
