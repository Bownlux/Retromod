/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common.trimstubs;

import java.util.Optional;

/** Stands in for {@code ResourceProvider}, which looks a client asset up by its id. */
public interface ResourceProvider {
    Optional<Resource> getResource(Identifier id);
}
