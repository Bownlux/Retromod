/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.eventbus;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** An old {@code @SubscribeEvent} on a listener of a legacy event, kept with its old values for Retromod's own dispatch. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface LegacySubscribe {

    /** The old priority's constant name, such as {@code HIGH}. */
    String priority() default "NORMAL";

    boolean receiveCanceled() default false;
}
