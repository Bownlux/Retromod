/*
 * Retromod Test Mod (NeoForge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.neoforge;

import com.google.gson.JsonParser;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import net.minecraft.client.renderer.item.ClampedItemPropertyFunction;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.client.renderer.item.ItemPropertyFunction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.LoggerFactory;

/** Item properties registered in client setup's queued work, as Farmer's Delight registers its skillet's. Client only. */
final class ItemPropertyCase {

    private static final String MODID = "retromod_test_mod_neoforge";
    private static final String REGISTER = "ItemProperties.register runs in client setup's queued work (client setup)";
    private static final String READ = "ItemProperties.getProperty answers the registered functions, the clamped one kept at 1"
            + " (first client tick after setup)";
    private static final String TYPE = "Retromod's item property type reads the old function, and the item's definition names it"
            + " (first client tick after setup)";
    private static final ResourceLocation CHARGE = ResourceLocation.fromNamespaceAndPath(MODID, "charge");
    private static final ResourceLocation CLAMPED = ResourceLocation.fromNamespaceAndPath(MODID, "clamped");

    private static volatile boolean registered;
    private static boolean checked;

    private ItemPropertyCase() {}

    static void register(IEventBus modBus) {
        try {
            modBus.addListener(ItemPropertyCase::onClientSetup);
            NeoForge.EVENT_BUS.addListener(ItemPropertyCase::onClientTick);
        } catch (Throwable t) {
            log(REGISTER, fail(t));
        }
    }

    // NeoForge 1.21.1 mods call both overloads: the plain function one, and vanilla's clamped one.
    private static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            try {
                ItemProperties.register(Items.STICK, CHARGE, (ItemPropertyFunction) (stack, level, entity, seed) -> 0.75F);
                ItemProperties.register(Items.STICK, CLAMPED, (ClampedItemPropertyFunction) (stack, level, entity, seed) -> 3F);
                registered = true;
                log(REGISTER, "success");
            } catch (Throwable t) {
                log(REGISTER, fail(t));
            }
        });
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        if (!registered || checked) return;
        checked = true;
        ItemStack stick = new ItemStack(Items.STICK);
        try {
            float charge = value(ItemProperties.getProperty(stick, CHARGE), stick);
            float clamped = value(ItemProperties.getProperty(stick, CLAMPED), stick);
            log(READ, charge == 0.75F && clamped == 1F ? "success" : "fail: answered " + charge + " and " + clamped);
        } catch (Throwable t) {
            log(READ, fail(t));
        }
        try {
            log(TYPE, typeReads(stick));
        } catch (Throwable t) {
            log(TYPE, fail(t));
        }
    }

    private static float value(ItemPropertyFunction function, ItemStack stack) {
        return function == null ? Float.NaN : function.call(stack, null, null, 0);
    }

    /** Decodes the property the item's definition names through the host's own codec and asks it for the stack. */
    private static String typeReads(ItemStack stack) throws Exception {
        ClassLoader loader = ItemPropertyCase.class.getClassLoader();
        Class<?> properties;
        try {
            properties = Class.forName("net.minecraft.client.renderer.item.properties.numeric.RangeSelectItemModelProperties", true, loader);
        } catch (ClassNotFoundException olderHost) {
            return "success (this host reads model overrides)";
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
        if (property.isEmpty()) return "fail: the host has no retromod:legacy_item_property type";
        Method get = null;
        for (Method method : Class.forName("net.minecraft.client.renderer.item.properties.numeric.RangeSelectItemModelProperty",
                true, loader).getMethods()) {
            if (method.getName().equals("get") && method.getParameterCount() == 4) get = method;
        }
        if (get == null) return "fail: the host's range property has no get";
        Object answered = get.invoke(property.get(), stack, null, null, 0);
        if (!Float.valueOf(0.75F).equals(answered)) return "fail: the type answered " + answered;
        String definition = resource("/assets/" + MODID + "/items/legacy_charged.json");
        if (definition == null || !definition.contains("retromod:legacy_item_property") || !definition.contains(CHARGE.toString())) {
            return "fail: the item's definition does not read the property: " + definition;
        }
        return "success";
    }

    private static String resource(String path) throws java.io.IOException {
        try (InputStream in = ItemPropertyCase.class.getResourceAsStream(path)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String fail(Throwable t) {
        return "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage();
    }

    private static void log(String what, String result) {
        LoggerFactory.getLogger("Retromod-Test-NeoForge").info("[Retromod-Test-NeoForge] {}: {}", what, result);
    }
}
