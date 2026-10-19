/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common.trimstubs;

import java.io.BufferedReader;
import java.io.StringReader;

/** Stands in for {@code Resource}: a client asset's contents. */
public final class Resource {
    private final String contents;

    public Resource(String contents) {
        this.contents = contents;
    }

    public BufferedReader openAsReader() {
        return new BufferedReader(new StringReader(contents));
    }
}
