/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * #298: a key binding category given as a plain name. From 1.21.9 the Controls screen titles a
 * category by its id, so Retromod writes the old name into a language file in this jar.
 */
public final class Test21KeyCategoryName implements Test {

    static final String CATEGORY = "Retromod Test Keys";
    private static final String KEY = "key.category.retromod_compat.retromod_test_keys";

    @Override
    public String description() {
        return "#298 key binding category keeps its name";
    }

    @Override
    public TestResult run() {
        String host = FabricLoader.getInstance().getModContainer("minecraft")
                .map(container -> container.getMetadata().getVersion().getFriendlyString()).orElse("");
        boolean titledById = host.startsWith("26.") || host.matches("1\\.21\\.(9|1[0-9]).*");
        try (InputStream in = Test21KeyCategoryName.class.getResourceAsStream(
                "/assets/retromod_compat/lang/en_us.json")) {
            if (!titledById) return TestResult.success();
            if (in == null) return TestResult.fail("no generated language file on Minecraft " + host);
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return text.contains(KEY) && text.contains(CATEGORY)
                    ? TestResult.success()
                    : TestResult.fail("language file lacks " + KEY + ": " + text);
        } catch (Exception e) {
            return TestResult.fail(e.toString());
        }
    }

    /** Never called; the transform reads the category from this constructor call. */
    static Object categoryKey() {
        return new KeyBinding("key.retromod_test.toggle", InputUtil.Type.KEYSYM, 82, CATEGORY);
    }
}
