/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.core;

/**
 * A compatibility shim outside the Minecraft transition graph.
 *
 * <p>The source and target returned by an auxiliary shim describe a library API version. They
 * must not be compared with the running Minecraft version or used as graph edges.
 */
public interface AuxiliaryVersionShim extends VersionShim {
}
