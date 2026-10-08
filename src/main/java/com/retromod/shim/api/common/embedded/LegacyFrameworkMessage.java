/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.api.common.embedded;

/**
 * Stands in for Framework 0.8's {@code IMessage}, which Framework 0.13 removed when messages became
 * plain objects with a stream codec.
 *
 * <p>It declares no methods. The old interface's {@code encode}, {@code decode}, and
 * {@code handle} are generic, so each message keeps erased bridge methods that
 * {@link LegacyFrameworkSupport} finds by name. Declaring them here would name Minecraft's
 * {@code FriendlyByteBuf}, which Retromod does not compile against.
 */
public interface LegacyFrameworkMessage {
}
