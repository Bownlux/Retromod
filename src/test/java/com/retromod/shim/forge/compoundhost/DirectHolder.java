/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.compoundhost;

/** A holder the stand-ins hand out, equal when it holds the same item. */
public record DirectHolder<T>(T value) implements Holder<T> {}
