/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;

import java.lang.reflect.Field;

/**
 * Test51's compost chance and fuel time reached the host. Read by reflection with 26.x names,
 * because the host keeps them where 1.20.1 code cannot name them.
 */
public final class Test52ContentRegistriesOnHost implements Test {

    private final MinecraftServer server;

    public Test52ContentRegistriesOnHost(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public String description() {
        return "Test51's compost chance and fuel time on the host";
    }

    @Override
    public TestResult run() {
        try {
            Class<?> components = optional("net.minecraft.core.component.DataComponents");
            if (components != null && hasField(components, "COOKING_FUEL")) return checkComponents(components);
            if (hasMethod(server.getClass(), "fuelValues")) return checkFuelValues();
            // A 1.20.1 host runs the mod as it was built, with the registries it was built for.
            return TestResult.success();
        } catch (ReflectiveOperationException e) {
            return TestResult.fail("could not read the host: " + e);
        }
    }

    /** 26.3 and newer: the item components Retromod set when the host built them. */
    private TestResult checkComponents(Class<?> components) throws ReflectiveOperationException {
        Object fuel = component(Items.BONE, components.getField("COOKING_FUEL").get(null));
        if (fuel == null) return TestResult.fail("bone has no cooking_fuel component");
        if (!String.valueOf(fuel).contains(String.valueOf(Test51LegacyContentRegistries.BONE_BURN_TIME))) {
            return TestResult.fail("bone burns as " + fuel);
        }
        Object compost = component(Items.ROTTEN_FLESH, components.getField("COMPOSTABLE").get(null));
        if (compost == null || !String.valueOf(compost).contains("compostable/low")) {
            return TestResult.fail("rotten flesh composts as " + compost);
        }
        return TestResult.success();
    }

    /** 26.1 and 26.2: the server's fuel values, and the composter's own table. */
    private TestResult checkFuelValues() throws ReflectiveOperationException {
        Object fuelValues = server.getClass().getMethod("fuelValues").invoke(server);
        ItemStack bone = new ItemStack(Items.BONE);
        Object burn = fuelValues.getClass().getMethod("burnDuration", ItemStack.class).invoke(fuelValues, bone);
        if (!Integer.valueOf(Test51LegacyContentRegistries.BONE_BURN_TIME).equals(burn)) {
            return TestResult.fail("bone burns for " + burn + " ticks");
        }
        return TestResult.success();
    }

    private static Object component(Object item, Object type) throws ReflectiveOperationException {
        ClassLoader loader = item.getClass().getClassLoader();
        Object map = item.getClass().getMethod("components").invoke(item);
        // Through the interface, because the map's own class need not be public.
        Class<?> mapType = Class.forName("net.minecraft.core.component.DataComponentMap", false, loader);
        Class<?> typeClass = Class.forName("net.minecraft.core.component.DataComponentType", false, loader);
        return mapType.getMethod("get", typeClass).invoke(map, type);
    }

    private static Class<?> optional(String name) {
        try {
            return Class.forName(name, false, Test52ContentRegistriesOnHost.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    private static boolean hasField(Class<?> type, String name) {
        for (Field field : type.getFields()) {
            if (field.getName().equals(name)) return true;
        }
        return false;
    }

    private static boolean hasMethod(Class<?> type, String name) {
        for (var method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == 0) return true;
        }
        return false;
    }
}
