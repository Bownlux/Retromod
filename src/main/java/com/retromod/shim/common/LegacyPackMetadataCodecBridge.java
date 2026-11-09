/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.tree.ClassNode;

import java.util.List;
import java.util.function.Function;

/** Answers reads of 1.21.1's public {@code PackMetadataSection.CODEC} with a rebuilt codec, on hosts that removed it. */
public final class LegacyPackMetadataCodecBridge {

    static final String METADATA = "net/minecraft/server/packs/metadata/pack/PackMetadataSection";
    static final String FIELD = "CODEC";
    static final String CODEC = "Lcom/mojang/serialization/Codec;";
    static final String REBUILT = RebuiltApiClasses.ROOT + "minecraft/server/packs/metadata/LegacyPackMetadataCodec";
    /** The first host checked without the field; earlier hosts are read, or keep it offline. */
    static final String FIRST_CHECKED_HOST = "1.21.11";

    private LegacyPackMetadataCodecBridge() {}

    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read);
    }

    static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        if (!hostLacksCodec(host)) return;
        RebuiltApiClasses.registerTree(t, REBUILT);
        // The intermediary remap names the old field by its new private name, FALLBACK_CODEC, which no mod can read.
        for (String name : List.of(FIELD, "FALLBACK_CODEC")) {
            t.registerFieldRedirect(METADATA, name, CODEC, REBUILT, "codec", "()" + CODEC);
        }
    }

    /** Whether the host's pack metadata has no public {@code CODEC}; offline or unreadable, a target from 1.21.11. */
    static boolean hostLacksCodec(Function<String, ClassNode> host) {
        ClassNode metadata = host.apply(METADATA);
        if (metadata == null) {
            return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, FIRST_CHECKED_HOST) >= 0;
        }
        return metadata.fields.stream().noneMatch(field -> field.name.equals(FIELD));
    }
}
