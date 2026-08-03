/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Fabric runtime path only repairs classes it recognises as Mixins, which it learns from the
 * mod's Mixin configs. A config naming convention missed here means the mod silently gets no Mixin
 * repairs at all, so this covers the same names the rest of the pipeline accepts.
 */
class FabricMixinConfigDiscoveryTest {

    private static String config(String pkg, String mixinClass) {
        return "{\n  \"package\": \"" + pkg + "\",\n  \"mixins\": [\n    \"" + mixinClass
                + "\"\n  ]\n}\n";
    }

    @SuppressWarnings("unchecked")
    private static Set<String> discover(Path dir) throws Exception {
        Method method = FabricModTransformer.class.getDeclaredMethod("findMixinClasses", Path.class);
        method.setAccessible(true);
        return (Set<String>) method.invoke(new FabricModTransformer("26.1"), dir);
    }

    @Test
    @DisplayName("Every Mixin config naming convention is discovered, including modid.mixin.json")
    void findsMixinConfigsUnderEveryNamingConvention(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("standard.mixins.json"), config("com.a.mixin", "Standard"));
        // Singular: accepted everywhere else, and previously missed here.
        Files.writeString(dir.resolve("legacy.mixin.json"), config("com.b.mixin", "Legacy"));
        Files.writeString(dir.resolve("mixins.modmenu.json"), config("com.c.mixin", "Prefixed"));

        Set<String> found = discover(dir);

        assertTrue(found.contains("com/a/mixin/Standard"), "standard name: " + found);
        assertTrue(found.contains("com/b/mixin/Legacy"), "modid.mixin.json: " + found);
        assertTrue(found.contains("com/c/mixin/Prefixed"), "mixins.modid.json: " + found);
    }

    @Test
    @DisplayName("An unrelated JSON file contributes no Mixin classes")
    void ignoresUnrelatedJson(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("fabric.mod.json"),
                "{\"id\": \"example\", \"mixins\": [\"example.mixins.json\"]}");

        assertTrue(discover(dir).isEmpty(), "fabric.mod.json is not a Mixin config");
    }
}
