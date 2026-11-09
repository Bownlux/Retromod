/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.capabilities;

import com.retromod.shim.common.embedded.LegacyLog;
import java.lang.reflect.Method;
import net.minecraftforge.common.capabilities.AutoRegisterCapability;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.eventbus.api.event.MutableEvent;
import net.minecraftforge.fml.event.IModBusEvent;

/** Forge 1.20.1's {@code RegisterCapabilitiesEvent}, which Forge 26.2 replaced with {@code @AutoRegisterCapability}. */
public final class LegacyRegisterCapabilitiesEvent extends MutableEvent implements IModBusEvent {

    private static volatile Method registrar;
    private static volatile boolean unreachable;

    public LegacyRegisterCapabilitiesEvent() {}

    /** Marks the capability of {@code type} registered, as Forge 1.20.1 did, so its {@code isRegistered()} and listeners answer. */
    public <T> void register(Class<T> type) {
        // Forge 26.2 registers an @AutoRegisterCapability type itself a moment later, and a second registration throws.
        if (isAutoRegistered(type)) return;
        Method get = registrar();
        if (get == null) return;
        // Keyed by internal name, as CapabilityManager.get(CapabilityToken) and Forge's own scan key it.
        String name = type.getName().replace('.', '/');
        try {
            Capability<?> capability = (Capability<?>) get.invoke(null, name, null, false);
            // Forge 1.20.1 threw on a second registration; two mods sharing an API class register it once here.
            if (!capability.isRegistered()) get.invoke(null, name, null, true);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
            LegacyLog.warnOnce("register capability " + name, "Retromod could not mark the capability " + name
                    + " registered: " + cause + ". Capability lookups do not depend on it");
        }
    }

    private static boolean isAutoRegistered(Class<?> type) {
        try {
            return type.isAnnotationPresent(AutoRegisterCapability.class);
        } catch (LinkageError absent) {
            return false;
        }
    }

    // CapabilityManager.get(String, Identifier, boolean), which registers when asked to. Forge 26.2 keeps it
    // package-private in an automatic module, whose packages are open to reflection.
    private static Method registrar() {
        Method found = registrar;
        if (found != null || unreachable) return found;
        try {
            for (Method method : CapabilityManager.class.getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (method.getName().equals("get") && parameters.length == 3 && parameters[0] == String.class
                        && parameters[2] == boolean.class) {
                    method.setAccessible(true);
                    registrar = method;
                    return method;
                }
            }
            unreachable = true;
            LegacyLog.warnOnce("capability registrar", "Retromod found no registering CapabilityManager.get on this"
                    + " Forge, so capabilities from RegisterCapabilitiesEvent are not marked registered. Capability"
                    + " lookups do not depend on it");
        } catch (RuntimeException | LinkageError failure) {
            unreachable = true;
            LegacyLog.warnOnce("capability registrar", "Retromod could not reach CapabilityManager.get on this Forge,"
                    + " so capabilities from RegisterCapabilitiesEvent are not marked registered: " + failure
                    + ". Capability lookups do not depend on it");
        }
        return null;
    }
}
