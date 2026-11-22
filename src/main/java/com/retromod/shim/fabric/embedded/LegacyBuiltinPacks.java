/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric.embedded;

import java.lang.reflect.Method;

/** Fabric API's old built-in pack registration with a sub path, on the resource loader 26.x Fabric API has instead. */
public final class LegacyBuiltinPacks {

    public static final String INTERNAL_NAME = "com/retromod/shim/fabric/embedded/LegacyBuiltinPacks";
    private static final String LOADER = "net.fabricmc.fabric.impl.resource.ResourceLoaderImpl";
    private static final String OLD_HELPER = "net.fabricmc.fabric.api.resource.ResourceManagerHelper";
    // One warning is enough to point at the cause, however many packs a mod registers.
    private static volatile boolean warned;

    private LegacyBuiltinPacks() {}

    /** {@code ResourceManagerHelperImpl.registerBuiltinResourcePack(id, subPath, container, name, activation)}: whether the pack was registered. */
    public static boolean registerBuiltinResourcePack(Object id, String subPath, Object container, Object name, Object activation) {
        ClassLoader loader = LegacyBuiltinPacks.class.getClassLoader();
        String activationName = activation instanceof Enum<?> constant ? constant.name() : "NORMAL";
        try {
            // Every 26.x Fabric API keeps the same call on its v1 resource loader, with its own activation type.
            for (Method method : Class.forName(LOADER, false, loader).getMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (method.getName().equals("registerBuiltinPack") && parameters.length == 5 && parameters[4].isEnum()) {
                    return (boolean) method.invoke(null, id, subPath, container, name, constant(parameters[4], activationName));
                }
            }
            // Without it, the public form keeps the sub path but not the display name.
            for (Method method : Class.forName(OLD_HELPER, false, loader).getMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (method.getName().equals("registerBuiltinResourcePack") && parameters.length == 4 && parameters[3] == boolean.class) {
                    return (boolean) method.invoke(null, id, subPath, container, !activationName.equals("NORMAL"));
                }
            }
        } catch (ReflectiveOperationException | ClassCastException e) {
            // The old call reported a pack it could not register with false, so this does too.
            warn(id, e);
            return false;
        }
        warn(id, null);
        return false;
    }

    private static void warn(Object id, Throwable cause) {
        if (warned) return;
        warned = true;
        // Embedded copies reach no Retromod class, so the JDK's logger carries the message.
        System.getLogger("Retromod").log(System.Logger.Level.WARNING, "Retromod could not register the built-in pack " + id
                + " through this Fabric API's resource loader, so the mod runs without it. Update Fabric API, and if that does"
                + " not help, report this line with the Retromod, Fabric API and mod versions.", cause);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object constant(Class<?> type, String name) {
        return Enum.valueOf((Class<Enum>) type, name);
    }
}
