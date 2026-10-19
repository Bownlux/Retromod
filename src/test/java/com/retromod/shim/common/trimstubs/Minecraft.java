/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common.trimstubs;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Stands in for the client: {@link #RESOURCES} holds the client assets a test puts there. */
public final class Minecraft {
    private static final Minecraft INSTANCE = new Minecraft();

    /** Asset contents by id. */
    public static final Map<Identifier, String> RESOURCES = new HashMap<>();

    private Minecraft() {}

    public static Minecraft getInstance() {
        return INSTANCE;
    }

    public ResourceManager getResourceManager() {
        return id -> Optional.ofNullable(RESOURCES.get(id)).map(Resource::new);
    }
}
