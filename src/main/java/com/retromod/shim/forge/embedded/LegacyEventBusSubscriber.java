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
 * Replaces the 1.12.2 {@code @Mod.EventBusSubscriber} on a class whose handlers are all 1.12
 * shaped. The annotation kept its name through 1.20, so Forge would otherwise auto-register the
 * class and read its 1.12 {@code Side} values as {@code Dist} names. {@link LegacyForgeEvents}
 * finds these classes in the loader scan data and subscribes them itself.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface LegacyEventBusSubscriber {
    /** The 1.12 annotation named only {@code Side.CLIENT}; skipped on a dedicated server. */
    boolean clientOnly() default false;
}
