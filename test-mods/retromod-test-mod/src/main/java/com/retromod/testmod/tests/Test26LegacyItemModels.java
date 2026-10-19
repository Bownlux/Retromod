/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Old item models get the client item definitions 1.21.4 reads in their place.
 *
 * <p>This mod ships, as every mod before 1.21.4 did, only {@code models/item/} files: a spawn egg on
 * vanilla's egg template and an item whose custom model data override picks a variant. Without a
 * definition from Retromod the egg would show the missing model, or the gray template on 1.21.4,
 * and the variant would never show. A host before 1.21.4 reads the old models and needs no
 * definition. A dedicated server ships no vanilla assets, so there only the shape of a written
 * definition is checked.
 */
public class Test26LegacyItemModels implements Test {

    private static final String ITEMS = "/assets/retromod_test_mod/items/";

    @Override
    public String description() {
        return "Legacy item models get 1.21.4 item definitions";
    }

    @Override
    public TestResult run() {
        String egg = read(ITEMS + "legacy_spawn_egg.json");
        String variant = read(ITEMS + "legacy_variant.json");
        Boolean hostReadsDefinitions = hostReadsItemDefinitions();
        if ((egg == null) != (variant == null)) {
            return TestResult.fail("only one definition was written: egg " + (egg != null)
                    + ", variant " + (variant != null));
        }
        if (egg == null) {
            return hostReadsDefinitions == Boolean.TRUE
                    ? TestResult.fail("this host reads items/ definitions, and none was written")
                    : TestResult.success();
        }
        if (hostReadsDefinitions == Boolean.FALSE) {
            return TestResult.fail("definitions were written for a host that still reads model overrides");
        }
        if (!egg.contains("\"tints\"")) {
            return TestResult.fail("the egg definition carries no colors: " + egg);
        }
        if (!variant.contains("minecraft:custom_model_data") || !variant.contains("item/legacy_variant_one")) {
            return TestResult.fail("the custom model data override was not translated: " + variant);
        }
        return TestResult.success();
    }

    /**
     * Whether the host reads {@code items/} definitions, from the vanilla assets on the class path:
     * a client from 1.21.4 ships {@code items/stick.json}. Null when no vanilla assets are visible.
     */
    private static Boolean hostReadsItemDefinitions() {
        ClassLoader loader = Test26LegacyItemModels.class.getClassLoader();
        if (loader.getResource("assets/minecraft/lang/en_us.json") == null) return null;
        return loader.getResource("assets/minecraft/items/stick.json") != null;
    }

    private static String read(String path) {
        try (InputStream in = Test26LegacyItemModels.class.getResourceAsStream(path)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }
}
