/*
 * Retromod Test Mod (NeoForge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.neoforge;

import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** 1.21.6 removed Component.Serializer, whose JSON text statics a 1.21.1 mod calls with registries. */
final class TextJsonCase {

    private TextJsonCase() {}

    /** Styled text written to JSON and read back, or the first value that came back wrong. */
    static String run(HolderLookup.Provider registries) {
        MutableComponent text = Component.Serializer.fromJson("{\"text\":\"hi\",\"color\":\"red\"}", registries);
        if (text == null || !"hi".equals(text.getString())) return "fail: read " + text;
        String json = Component.Serializer.toJson(text, registries);
        if (!json.contains("\"red\"")) return "fail: wrote " + json;
        MutableComponent back = Component.Serializer.fromJson(json, registries);
        return back != null && back.equals(text) ? "success" : "fail: read back " + back;
    }
}
