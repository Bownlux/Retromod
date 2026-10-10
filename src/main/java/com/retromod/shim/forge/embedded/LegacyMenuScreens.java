/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.embedded;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Forge's public {@code MenuScreens.register} on NeoForge, embedded per mod.
 *
 * <p>Forge's access transformer made {@code MenuScreens.register} public, so Forge mods register
 * their menu screens with it from client setup. NeoForge leaves it private and expects
 * {@code RegisterMenuScreensEvent}, so the call failed with {@code IllegalAccessError} and the
 * client stopped while loading. The same private method is called here instead, which keeps the
 * mod's own timing.
 */
public final class LegacyMenuScreens {

    private static volatile Method register;

    private LegacyMenuScreens() {}

    public static void register(Object menuType, Object screenConstructor) {
        try {
            registerMethod().invoke(null, menuType, screenConstructor);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException(cause);
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException("Retromod could not register the menu screen for "
                    + menuType + ". The mod's screen for this menu will not open.", e);
        }
    }

    private static Method registerMethod() throws ReflectiveOperationException {
        Method method = register;
        if (method != null) return method;
        Class<?> screens = Class.forName("net.minecraft.client.gui.screens.MenuScreens");
        for (Method candidate : screens.getDeclaredMethods()) {
            if (candidate.getName().equals("register") && candidate.getParameterCount() == 2) {
                candidate.setAccessible(true);
                register = candidate;
                return candidate;
            }
        }
        throw new NoSuchMethodException("MenuScreens.register(MenuType, ScreenConstructor)");
    }
}
