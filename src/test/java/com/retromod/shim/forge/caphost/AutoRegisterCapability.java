/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.caphost;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** Stands in for Forge's AutoRegisterCapability, kept at run time. */
@Retention(RetentionPolicy.RUNTIME)
public @interface AutoRegisterCapability {
}
