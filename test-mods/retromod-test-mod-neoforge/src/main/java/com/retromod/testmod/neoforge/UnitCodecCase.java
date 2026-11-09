/*
 * Retromod Test Mod (NeoForge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.neoforge;

import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;

/** DataFixerUpper 8's Codec.unit, which DFU 9 and later moved to MapCodec.unitCodec. */
final class UnitCodecCase {

    private UnitCodecCase() {}

    static boolean run() {
        Codec<String> fixed = Codec.unit("fixed");
        Codec<String> supplied = Codec.unit(() -> "supplied");
        return "fixed".equals(fixed.parse(JsonOps.INSTANCE, new JsonObject()).result().orElse(null))
                && "supplied".equals(supplied.parse(JsonOps.INSTANCE, new JsonObject()).result().orElse(null));
    }
}
