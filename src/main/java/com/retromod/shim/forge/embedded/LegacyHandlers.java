/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.embedded;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The 1.12 handler methods of a class, recorded at transform time. {@code getDeclaredMethods}
 * resolves every parameter type in the class, and one handler for a removed client event would
 * fail the lookup of all the others. {@link LegacyForgeEvents} looks each handler up by name and
 * descriptor instead, so only that handler's own types load.
 *
 * <p>Each entry is {@code kind|s-or-i|name|descriptor|a|b}. Kind {@code E} is an
 * {@code @SubscribeEvent} handler ({@code a} is the registry path a {@code RegistryEvent.Register<T>}
 * handler fills, or empty; {@code b} is receiveCanceled), {@code L} an FML lifecycle handler,
 * {@code I} a {@code @Mod.Instance} field ({@code a} is its mod id) and {@code P} a
 * {@code @SidedProxy} field ({@code a} and {@code b} are the client and server classes). Fields
 * are recorded for the same reason: {@code getDeclaredFields} resolves every field type.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface LegacyHandlers {
    String[] value();
}
