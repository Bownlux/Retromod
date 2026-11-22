/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common.embedded;

import java.lang.reflect.Method;

/** {@code RenderSystem.maxSupportedTextureSize()}, read from the GPU device, whose method name differs by version. */
public final class LegacyDeviceLimits {

    // What every OpenGL 3.3 driver must support, used only when the device cannot be asked.
    private static final int GUARANTEED_TEXTURE_SIZE = 1024;
    private static final String RENDER_SYSTEM = "com.mojang.blaze3d.systems.RenderSystem";
    private static volatile int cached;

    private LegacyDeviceLimits() {}

    /** The device's largest texture side: getMaxTextureSize() up to 26.1, getDeviceInfo().limits().maxTextureSize() from 26.2. */
    public static int maxSupportedTextureSize() {
        if (cached > 0) return cached;
        try {
            Class<?> renderSystem = Class.forName(RENDER_SYSTEM, false, LegacyDeviceLimits.class.getClassLoader());
            Object device = renderSystem.getMethod("getDevice").invoke(null);
            Method size = findPublic(device.getClass(), "getMaxTextureSize");
            if (size != null) return cached = (Integer) size.invoke(device);
            // 26.2 moved the limits into the device's info record.
            Method info = findPublic(device.getClass(), "getDeviceInfo");
            Object limits = info == null ? null : call(call(device, info), "limits");
            Method max = limits == null ? null : findPublic(limits.getClass(), "maxTextureSize");
            if (max != null) return cached = (Integer) max.invoke(limits);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            // Falls through to the guaranteed size below.
        }
        LegacyLog.warnOnce("max-texture-size", "An old mod asked for the largest texture size, and Retromod could not read"
                + " it from the GPU device, so it answered " + GUARANTEED_TEXTURE_SIZE + ".");
        return GUARANTEED_TEXTURE_SIZE;
    }

    private static Object call(Object target, Method method) throws ReflectiveOperationException {
        return target == null ? null : method.invoke(target);
    }

    private static Object call(Object target, String name) throws ReflectiveOperationException {
        Method method = target == null ? null : findPublic(target.getClass(), name);
        return method == null ? null : method.invoke(target);
    }

    // The device's own class may be package-private, so the method is looked up on its public interfaces too.
    private static Method findPublic(Class<?> type, String name) {
        // The interfaces of each class up the chain, since the device class may extend a backend base class.
        for (Class<?> owner = type; owner != null; owner = owner.getSuperclass()) {
            for (Class<?> each : owner.getInterfaces()) {
                try {
                    return each.getMethod(name);
                } catch (NoSuchMethodException next) {
                    // Try the next interface.
                }
            }
        }
        try {
            return type.getMethod(name);
        } catch (NoSuchMethodException none) {
            return null;
        }
    }
}
