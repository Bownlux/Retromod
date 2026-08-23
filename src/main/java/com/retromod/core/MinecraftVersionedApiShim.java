/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.core;

/**
 * An API repair outside the transition graph whose versions are Minecraft host versions.
 *
 * <p>These providers remain auxiliary because they do not form Minecraft graph edges. Retromod
 * still compares their target with the host before registration.
 */
public interface MinecraftVersionedApiShim extends AuxiliaryVersionShim {
}
