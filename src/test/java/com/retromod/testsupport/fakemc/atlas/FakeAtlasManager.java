/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.atlas;

import java.util.HashMap;
import java.util.Map;

/** Stands in for {@code AtlasManager}: atlases keyed by atlas id, never by texture. */
public class FakeAtlasManager {
    // Public, since the renamed stand-ins land in different packages.
    public final Map<FakeAtlasId, FakeTextureAtlas> byId = new HashMap<>();

    public FakeTextureAtlas getAtlasOrThrow(FakeAtlasId id) {
        FakeTextureAtlas atlas = byId.get(id);
        if (atlas == null) throw new IllegalArgumentException("Invalid atlas id: " + id);
        return atlas;
    }
}
