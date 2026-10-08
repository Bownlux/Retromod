/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric.embedded;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Turns the identifier of a 1.20.5 attribute modifier back into the UUID an older mod expects.
 *
 * <p>The bridge names a modifier built from a UUID {@code minecraft:<uuid>}, so reading the path
 * back gives the mod its own UUID, and a mod that compares a modifier with its UUID constant still
 * finds it. A modifier created under a real identifier gets a stable name-based UUID instead.
 * Copied into each mod that needs it, so it uses only the JDK.
 */
public final class LegacyAttributeIds {

    private LegacyAttributeIds() {}

    public static UUID uuidOf(String path, String fullId) {
        try {
            return UUID.fromString(path);
        } catch (IllegalArgumentException notAUuid) {
            return UUID.nameUUIDFromBytes(fullId.getBytes(StandardCharsets.UTF_8));
        }
    }
}
