/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.atlas;

/** Stands in for {@code Minecraft}: the blocks atlas under its texture location and under its atlas id, as on 26.x. */
public class FakeAtlasClient {
    private static final FakeAtlasClient INSTANCE = new FakeAtlasClient();

    private final FakeTextureManager textures = new FakeTextureManager();
    private final FakeAtlasManager atlases = new FakeAtlasManager();

    private FakeAtlasClient() {
        FakeTextureAtlas blocks = new FakeTextureAtlas("blocks atlas");
        FakeTextureAtlas chests = new FakeTextureAtlas("chests atlas");
        textures.byPath.put(FakeAtlasId.parse("minecraft:textures/atlas/blocks.png"), blocks);
        textures.byPath.put(FakeAtlasId.parse("minecraft:textures/atlas/chest.png"), chests);
        atlases.byId.put(FakeAtlasId.parse("minecraft:blocks"), blocks);
        atlases.byId.put(FakeAtlasId.parse("minecraft:chests"), chests);
    }

    public static FakeAtlasClient getInstance() {
        return INSTANCE;
    }

    public FakeTextureManager getTextureManager() {
        return textures;
    }

    public FakeAtlasManager getAtlasManager() {
        return atlases;
    }
}
