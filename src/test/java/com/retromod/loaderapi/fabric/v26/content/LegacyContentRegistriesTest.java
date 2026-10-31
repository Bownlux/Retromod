/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi.fabric.v26.content;

import com.retromod.loaderapi.fabric.v26.net.FakeHost;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The old content registries reach a fake 26.3 host's block transformers and item components. */
class LegacyContentRegistriesTest {

    private static final String HELPERS = "com.retromod.loaderapi.fabric.v26.content.LegacyContentRegistries";
    private static final String HOE = "com.retromod.loaderapi.fabric.v26.content.LegacyHoeActions";

    @Test
    @DisplayName("Stripping, tilling and flattening go to Fabric API's block transformer helper")
    void blockTransformsReachTheHelper() throws Throwable {
        try (FakeHost host = FakeHost.start()) {
            Object log = block(host, "mod_log");
            Object stripped = block(host, "stripped_mod_log");
            Object soil = block(host, "mod_soil");
            Object farmland = block(host, "mod_farmland");
            Object path = block(host, "mod_path");

            call(host, HELPERS, "registerStrippable", log, stripped);
            call(host, HELPERS, "registerFlattenable", soil, state(path));
            Predicate<Object> anyUse = context -> true;
            call(host, HELPERS, "registerTillableState", soil, anyUse, state(farmland));
            // HoeItem.changeIntoState carries its state, so the action form becomes a transform too.
            Object action = call(host, HOE, "changeIntoState", state(farmland));
            call(host, HELPERS, "registerTillableAction", path, anyUse, action);

            assertEquals(List.of("strip mod_log -> stripped_mod_log", "flatten mod_soil -> mod_path",
                    "till mod_soil -> mod_farmland", "till mod_path -> mod_farmland"), events(host));
        }
    }

    @Test
    @DisplayName("A tilling action the mod wrote itself cannot become a transform and changes nothing")
    void ownTillingActionIsNotRegistered() throws Throwable {
        try (FakeHost host = FakeHost.start()) {
            Consumer<Object> ownAction = context -> { };
            call(host, HELPERS, "registerTillableAction", block(host, "mod_soil"), (Predicate<Object>) c -> true, ownAction);

            assertEquals(List.of(), events(host));
        }
    }

    @Test
    @DisplayName("Compost chances, fuel times and villager food become item components when the host builds them")
    void compostAndFuelBecomeComponents() throws Throwable {
        try (FakeHost host = FakeHost.start()) {
            Object seeds = item(host, "mod_seeds");
            Object coal = item(host, "mod_coal");
            Object pie = item(host, "mod_pie");
            call(host, HELPERS, "addCompostable", seeds, 0.3f);
            call(host, HELPERS, "addCompostable", pie, 0.6f);
            call(host, HELPERS, "addFuel", coal, 2400);
            call(host, HELPERS, "addVillagerFood", pie, 3);

            Map<String, Map<String, String>> components = build(host, seeds, coal, pie);

            assertEquals("villager food 3", components.get("mod_pie").get("villager_food"));

            assertEquals("compostable minecraft:context_int_provider / minecraft:compostable/low",
                    components.get("mod_seeds").get("compostable"));
            assertEquals("compostable minecraft:context_int_provider / minecraft:compostable/medium",
                    components.get("mod_pie").get("compostable"), "0.6 takes the nearest tier, 65%");
            assertEquals("fuel constant 2400 at reference minecraft:context_float_provider / "
                    + "minecraft:cooking/speed_default", components.get("mod_coal").get("cooking_fuel"));
            assertEquals(2400, call(host, HELPERS, "fuelTime", coal));
            assertEquals(0.3f, call(host, HELPERS, "compostChance", seeds));
        }
    }

    @Test
    @DisplayName("A removed fuel loses its component, and a chance of 0 sets none")
    void removalsAndZeroChances() throws Throwable {
        try (FakeHost host = FakeHost.start()) {
            Object coal = item(host, "mod_coal");
            Object weed = item(host, "mod_weed");
            call(host, HELPERS, "addFuel", coal, 1600);
            call(host, HELPERS, "removeFuel", coal);
            call(host, HELPERS, "addCompostable", weed, 0.0f);

            Map<String, Map<String, String>> components = build(host, coal, weed);

            assertTrue(components.get("mod_coal").isEmpty(), components.toString());
            assertTrue(components.get("mod_weed").isEmpty(), components.toString());
            assertEquals(null, call(host, HELPERS, "fuelTime", coal));
        }
    }

    // ---- Fake host access --------------------------------------------------------------------

    private static Object block(FakeHost host, String name) throws Exception {
        return host.type("net.minecraft.world.level.block.Block").getConstructor(String.class).newInstance(name);
    }

    private static Object state(Object block) throws Exception {
        return block.getClass().getMethod("defaultBlockState").invoke(block);
    }

    private static Object item(FakeHost host, String name) throws Exception {
        return host.type("net.minecraft.world.item.Item").getConstructor(String.class).newInstance(name);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, String>> build(FakeHost host, Object... items) throws Throwable {
        return (Map<String, Map<String, String>>) call(host, "fixture.FakeComponents", "build", List.of(items));
    }

    @SuppressWarnings("unchecked")
    private static List<String> events(FakeHost host) throws Exception {
        return List.copyOf((List<String>) host.type("fixture.Recorder").getField("EVENTS").get(null));
    }

    /** Calls a public static method by name and argument count, in the fake host's loader. */
    private static Object call(FakeHost host, String className, String method, Object... args) throws Throwable {
        for (Method candidate : host.type(className).getMethods()) {
            if (candidate.getName().equals(method) && candidate.getParameterCount() == args.length) {
                try {
                    return candidate.invoke(null, args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            }
        }
        throw new NoSuchMethodException(className + "." + method + " with " + args.length + " arguments");
    }
}
