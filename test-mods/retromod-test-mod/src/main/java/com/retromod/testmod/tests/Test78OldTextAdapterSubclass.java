/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonElement;
import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;
import java.lang.reflect.Type;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;

/** A text adapter that extends Text.Serializer, as CreativeCore's does, and toJsonTree, which 1.20.5 made private. */
public final class Test78OldTextAdapterSubclass implements Test {

    @Override
    public String description() {
        return "A Text.Serializer subclass reads through its own override, and toJsonTree writes plain text as an object";
    }

    @Override
    public TestResult run() {
        Gson gson = new GsonBuilder().registerTypeHierarchyAdapter(Text.class, new Marked()).create();
        Text text = gson.fromJson("{\"text\":\"sub\"}", Text.class);
        if (text == null || !"sub!".equals(text.getString())) return TestResult.fail("the subclass read " + text);
        // Before 1.20.3 plain text was written as an object with a text key.
        JsonElement tree = Text.Serializer.toJsonTree(Text.literal("plain"));
        boolean object = tree != null && tree.isJsonObject() && tree.getAsJsonObject().has("text");
        if (!object || !"plain".equals(tree.getAsJsonObject().get("text").getAsString())) {
            return TestResult.fail("toJsonTree wrote " + tree);
        }
        return TestResult.success();
    }

    /** Marks every text it reads, so the test sees its override ran. */
    static final class Marked extends Text.Serializer {
        @Override
        public MutableText deserialize(JsonElement json, Type type, JsonDeserializationContext context) {
            return super.deserialize(json, type, context).append("!");
        }
    }
}
