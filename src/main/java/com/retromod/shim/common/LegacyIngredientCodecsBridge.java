/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.tree.ClassNode;

import java.util.function.Function;

/** Answers NeoForge 1.21.1's ingredient list codecs, which an old recipe serializer reads and the host lacks, from its ingredient codec. */
public final class LegacyIngredientCodecsBridge {

    static final String INGREDIENT = "net/minecraft/world/item/crafting/Ingredient";
    static final String REBUILT = RebuiltApiClasses.ROOT + "minecraft/world/item/crafting/LegacyIngredientCodecs";
    private static final String CODEC = "Lcom/mojang/serialization/Codec;";
    /** Each old field and the rebuilt getter that answers it. */
    static final String[][] FIELDS = {{"LIST_CODEC", "list"}, {"LIST_CODEC_NONEMPTY", "listNonEmpty"}};

    private LegacyIngredientCodecsBridge() {}

    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read);
    }

    /** Only on a host whose ingredient has its codec but neither old list field. */
    static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        ClassNode ingredient = host.apply(INGREDIENT);
        if (ingredient == null) return;
        boolean hasCodec = ingredient.fields.stream().anyMatch(f -> f.name.equals("CODEC") && f.desc.equals(CODEC));
        boolean hasOld = ingredient.fields.stream().anyMatch(f -> f.name.startsWith("LIST_CODEC"));
        if (!hasCodec || hasOld) return;
        RebuiltApiClasses.registerTree(t, REBUILT);
        for (String[] field : FIELDS) {
            t.registerStaticFieldBridge(INGREDIENT, field[0], REBUILT, field[1], "()" + CODEC, null, null);
        }
    }
}
