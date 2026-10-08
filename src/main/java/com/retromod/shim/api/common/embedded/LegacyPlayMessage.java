/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.api.common.embedded;

/**
 * Stands in for Framework 0.8's {@code PlayMessage} base class. A message registered through the
 * old builder call is wrapped in a stream codec by {@link LegacyFrameworkSupport}.
 */
public abstract class LegacyPlayMessage implements LegacyFrameworkMessage {
    public LegacyPlayMessage() {
    }
}
