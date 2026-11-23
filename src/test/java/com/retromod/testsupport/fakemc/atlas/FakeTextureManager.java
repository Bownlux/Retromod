/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.atlas;

import java.util.HashMap;
import java.util.Map;

/** Stands in for {@code TextureManager}: an unknown location loads a plain texture, as the real one does. */
public class FakeTextureManager {
    // Public, since the renamed stand-ins land in different packages.
    public final Map<FakeAtlasId, FakeTexture> byPath = new HashMap<>();

    public FakeTexture getTexture(FakeAtlasId location) {
        return byPath.computeIfAbsent(location, id -> new FakeTexture("loaded " + id));
    }
}
