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
 * Stand-in for the 1.12.2 {@code net.minecraftforge.fml.common.eventhandler.SubscribeEvent}.
 * The host loader must not see these handlers: their parameters are often 1.12 event types the
 * host bus rejects. {@link LegacyForgeEvents} reads this annotation and wires each handler itself.
 * The old {@code priority} element is not declared, so reflection skips it.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface LegacySubscribeEvent {
    boolean receiveCanceled() default false;
}
