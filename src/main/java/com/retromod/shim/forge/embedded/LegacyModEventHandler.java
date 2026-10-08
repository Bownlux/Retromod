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
 * Stand-in for the 1.12.2 {@code @Mod.EventHandler} marker on FML lifecycle methods. Mapping it to
 * the host's {@code @EventBusSubscriber} made Forge register the {@code @Mod} class on its event
 * bus, which rejects the lifecycle methods. The lifecycle bridge finds handlers by parameter type.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface LegacyModEventHandler {
}
