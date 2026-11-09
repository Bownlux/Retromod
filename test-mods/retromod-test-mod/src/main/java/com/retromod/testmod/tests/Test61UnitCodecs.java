/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;

/** DataFixerUpper 6's Codec.unit, which DFU 9 and later moved to MapCodec.unitCodec. */
public final class Test61UnitCodecs implements Test {

    @Override
    public String description() {
        return "Codec.unit(value) and Codec.unit(supplier) parse to their values";
    }

    @Override
    public TestResult run() {
        Codec<String> fixed = Codec.unit("fixed");
        Codec<String> supplied = Codec.unit(() -> "supplied");
        Object first = fixed.parse(JsonOps.INSTANCE, new JsonObject()).result().orElse(null);
        Object second = supplied.parse(JsonOps.INSTANCE, new JsonObject()).result().orElse(null);
        if (!"fixed".equals(first) || !"supplied".equals(second)) {
            return TestResult.fail("the unit codecs read " + first + " and " + second);
        }
        return TestResult.success();
    }
}
