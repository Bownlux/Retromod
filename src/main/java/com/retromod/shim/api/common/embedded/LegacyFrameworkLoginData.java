/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.api.common.embedded;

/**
 * Stands in for Framework 0.8's {@code ILoginData}. Framework 0.13 replaced login data with
 * configuration messages, so {@link LegacyFrameworkSupport#registerLoginData} accepts the data
 * and does not send it.
 */
public interface LegacyFrameworkLoginData {
}
