/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.api.common.embedded;

import com.retromod.shim.common.embedded.LegacyPublicMethods;
import java.lang.reflect.Method;

/** Architectury API compatibility shim. */
public class ArchitecturyShim {

    public static Object getRegistries(String modId) {
        try {
            Class<?> registriesClass = Class.forName("dev.architectury.registry.registries.Registries");
            Method get = registriesClass.getMethod("get", String.class);
            return get.invoke(null, modId);
        } catch (Exception e) {
            try {
                Class<?> registriesClass = Class.forName("me.shedaniel.architectury.registry.Registries");
                Method get = registriesClass.getMethod("get", String.class);
                return get.invoke(null, modId);
            } catch (Exception e2) {
                throw new RuntimeException("Cannot get Architectury registries for: " + modId, e2);
            }
        }
    }
    
    public static Object getDeferredRegister(Object registries, Object registry) {
        try {
            for (Method m : LegacyPublicMethods.methods(registries.getClass())) {
                if (m.getName().equals("get") && m.getParameterCount() == 1) {
                    return m.invoke(registries, registry);
                }
            }
        } catch (Exception e) {
            // try the static create path
        }
        try {
            Class<?> deferredClass = Class.forName("dev.architectury.registry.registries.DeferredRegister");
            Method create = deferredClass.getMethod("create", String.class, Class.forName("net.minecraft.core.Registry"));
            return create.invoke(null, "unknown", registry);
        } catch (Exception e) {
            ApiShimWarnings.warnOnce("Architectury", "getDeferredRegister", registries, "it returned null", e);
            return null;
        }
    }
    
    public static void registerReceiver(Object side, Object resourceLocation, Object receiver) {
        try {
            Class<?> networkManager = Class.forName("dev.architectury.networking.NetworkManager");
            for (Method m : networkManager.getMethods()) {
                if (m.getName().equals("registerReceiver")) {
                    m.invoke(null, side, resourceLocation, receiver);
                    return;
                }
            }
        } catch (Exception e) {
            try {
                Class<?> networkManager = Class.forName("me.shedaniel.architectury.networking.NetworkManager");
                Method method = networkManager.getMethod("registerReceiver",
                    Class.forName("me.shedaniel.architectury.networking.NetworkManager$Side"),
                    Class.forName("net.minecraft.resources.ResourceLocation"),
                    Class.forName("me.shedaniel.architectury.networking.NetworkManager$NetworkReceiver"));
                method.invoke(null, side, resourceLocation, receiver);
            } catch (Exception e2) {
                // Name both failures: the current API's is usually the real one, the old fallback's is a missing class.
                ApiShimWarnings.warnOnce("Architectury", "registerReceiver", receiver, "the receiver was not "
                        + "registered (the older me.shedaniel API failed too: " + ApiShimWarnings.describe(e2) + ")", e);
            }
        }
    }
    
    public static Object registerMenu(Object resourceLocation, Object factory) {
        try {
            Class<?> menuRegistry = Class.forName("dev.architectury.registry.menu.MenuRegistry");
            for (Method m : menuRegistry.getMethods()) {
                if (m.getName().equals("register") || m.getName().equals("registerSimple")) {
                    if (m.getParameterCount() == 2) {
                        return m.invoke(null, resourceLocation, factory);
                    }
                }
            }
        } catch (Exception e) {
            try {
                Class<?> menuRegistry = Class.forName("me.shedaniel.architectury.registry.MenuRegistry");
                Method method = menuRegistry.getMethod("register",
                    Class.forName("net.minecraft.resources.ResourceLocation"),
                    Class.forName("me.shedaniel.architectury.registry.MenuRegistry$SimpleMenuTypeFactory"));
                return method.invoke(null, resourceLocation, factory);
            } catch (Exception e2) {
                throw new RuntimeException("Cannot register menu", e2);
            }
        }
        return null;
    }
    
    public static String getPlatform() {
        try {
            Class<?> platform = Class.forName("dev.architectury.platform.Platform");
            Method isFabric = platform.getMethod("isFabric");
            if ((Boolean) isFabric.invoke(null)) return "fabric";
            Method isForge = platform.getMethod("isForge");
            if ((Boolean) isForge.invoke(null)) return "forge";
            Method isNeoForge = platform.getMethod("isNeoForge");
            if ((Boolean) isNeoForge.invoke(null)) return "neoforge";
        } catch (Exception e) {
            try {
                Class<?> platform = Class.forName("me.shedaniel.architectury.platform.Platform");
                Method isFabric = platform.getMethod("isFabric");
                if ((Boolean) isFabric.invoke(null)) return "fabric";
                return "forge";
            } catch (Exception e2) {
            }
        }
        return "unknown";
    }
}
