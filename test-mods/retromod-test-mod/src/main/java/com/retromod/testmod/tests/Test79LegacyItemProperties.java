/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.google.gson.JsonParser;
import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import net.fabricmc.fabric.api.object.builder.v1.client.model.FabricModelPredicateProviderRegistry;
import net.minecraft.client.item.ModelPredicateProvider;
import net.minecraft.client.item.ModelPredicateProviderRegistry;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Identifier;

/**
 * Item properties registered through Fabric API's registry, read back through the vanilla one, and read by the item
 * property type Retromod adds on 26.x, which the item's translated definition names.
 */
public final class Test79LegacyItemProperties implements Test {

    private static final Identifier CHARGE = new Identifier("retromod_test_mod", "charge");
    private static final Identifier CLAMPED = new Identifier("retromod_test_mod", "clamped");

    @Override
    public String description() {
        return "Old item properties register, read back clamped, and reach Retromod's item property type and the item's definition";
    }

    @Override
    public TestResult run() {
        FabricModelPredicateProviderRegistry.register(Items.STICK, CHARGE, (stack, world, entity, seed) -> 0.75F);
        FabricModelPredicateProviderRegistry.register(Items.STICK, CLAMPED, (stack, world, entity, seed) -> 3F);
        ItemStack stick = new ItemStack(Items.STICK);
        float charge = value(ModelPredicateProviderRegistry.get(Items.STICK, CHARGE), stick);
        float clamped = value(ModelPredicateProviderRegistry.get(Items.STICK, CLAMPED), stick);
        if (charge != 0.75F || clamped != 1F) return TestResult.fail("get answered " + charge + " and " + clamped);
        try {
            String typed = typeReads(stick);
            return typed == null ? TestResult.success() : TestResult.fail(typed);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return TestResult.fail(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static float value(ModelPredicateProvider provider, ItemStack stack) {
        return provider == null ? Float.NaN : provider.call(stack, null, null, 0);
    }

    /** Null when the host's own codec decodes Retromod's property, it answers the stack, and the definition names it. */
    private static String typeReads(ItemStack stack) throws ReflectiveOperationException {
        ClassLoader loader = Test79LegacyItemProperties.class.getClassLoader();
        Class<?> properties;
        try {
            properties = Class.forName("net.minecraft.client.renderer.item.properties.numeric.RangeSelectItemModelProperties", true, loader);
        } catch (ClassNotFoundException olderHost) {
            // A host before 26.1, or one before 1.21.4 that still reads model overrides.
            return null;
        }
        // Every host call goes through reflection, so nothing here depends on how the host shapes DataFixerUpper.
        Object json = JsonParser.parseString("{\"property\":\"retromod:legacy_item_property\",\"id\":\"" + CHARGE + "\"}");
        Object mapCodec = properties.getField("MAP_CODEC").get(null);
        Object codec = Class.forName("com.mojang.serialization.MapCodec", true, loader).getMethod("codec").invoke(mapCodec);
        Class<?> ops = Class.forName("com.mojang.serialization.DynamicOps", true, loader);
        Object jsonOps = Class.forName("com.mojang.serialization.JsonOps", true, loader).getField("INSTANCE").get(null);
        Object result = Class.forName("com.mojang.serialization.Decoder", true, loader).getMethod("parse", ops, Object.class)
                .invoke(codec, jsonOps, json);
        Optional<?> property = (Optional<?>) Class.forName("com.mojang.serialization.DataResult", true, loader)
                .getMethod("result").invoke(result);
        if (property.isEmpty()) return "the host has no retromod:legacy_item_property type";
        Method get = null;
        for (Method method : Class.forName("net.minecraft.client.renderer.item.properties.numeric.RangeSelectItemModelProperty",
                true, loader).getMethods()) {
            if (method.getName().equals("get") && method.getParameterCount() == 4) get = method;
        }
        if (get == null) return "the host's range property has no get";
        Object answered = get.invoke(property.get(), stack, null, null, 0);
        if (!Float.valueOf(0.75F).equals(answered)) return "the type answered " + answered;
        String definition = read("/assets/retromod_test_mod/items/legacy_charged.json");
        if (definition == null || !definition.contains("retromod:legacy_item_property") || !definition.contains(CHARGE.toString())) {
            return "the item's definition does not read the property: " + definition;
        }
        return null;
    }

    private static String read(String path) {
        try (InputStream in = Test79LegacyItemProperties.class.getResourceAsStream(path)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }
}
