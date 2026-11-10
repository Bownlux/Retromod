/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;

/** JSON text the 1.20.1 way: Text.Serializer's statics, which 1.20.5 made take registries, and its Gson adapters. */
public final class Test77OldTextJson implements Test {

    @Override
    public String description() {
        return "Text.Serializer reads and writes styled JSON text, and the Text and Style Gson adapters work";
    }

    @Override
    public TestResult run() {
        MutableText text = Text.Serializer.fromJson("{\"text\":\"hi\",\"color\":\"red\"}");
        if (text == null || !"hi".equals(text.getString())) return TestResult.fail("read " + text);
        String json = Text.Serializer.toJson(text);
        if (!json.contains("\"red\"")) return TestResult.fail("wrote " + json);
        // Config libraries register both serializers on a Gson of their own.
        Gson gson = new GsonBuilder().registerTypeHierarchyAdapter(Text.class, new Text.Serializer())
                .registerTypeHierarchyAdapter(Style.class, new Style.Serializer()).create();
        Text viaGson = gson.fromJson(json, Text.class);
        if (viaGson == null || !"hi".equals(viaGson.getString())) return TestResult.fail("the adapter read " + viaGson);
        Style style = gson.fromJson("{\"color\":\"gold\",\"bold\":true}", Style.class);
        if (style == null || !style.isBold()) return TestResult.fail("the style adapter read " + style);
        String styleJson = gson.toJson(style, Style.class);
        return styleJson.contains("\"gold\"") ? TestResult.success() : TestResult.fail("the style adapter wrote " + styleJson);
    }
}
