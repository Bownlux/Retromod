/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;
import net.minecraft.server.MinecraftServer;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Optional;

/**
 * Test53's trade and brewing recipe reached the host. Read by reflection with 26.x names. Data
 * written at one start loads at the next, so the first start checks the files and later ones the game.
 */
public final class Test54TradesAndBrewingOnHost implements Test {

    private static final String TRADE = "retromod_legacy:legacy/minecraft/farmer/level_1/0";
    private static final String BREWING = "retromod_legacy:legacy_brewing/0";

    private final MinecraftServer server;

    public Test54TradesAndBrewingOnHost(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public String description() {
        return "Test53's trade and brewing recipe on the host";
    }

    @Override
    public TestResult run() {
        try {
            ClassLoader loader = server.getClass().getClassLoader();
            Class<?> levelResource = Class.forName("net.minecraft.world.level.storage.LevelResource", false, loader);
            Path datapacks = (Path) call(server, "getWorldPath", levelResource.getField("DATAPACK_DIR").get(null));
            Collection<?> selected = (Collection<?>) call(call(server, "getPackRepository"), "getSelectedIds");

            if (!Files.exists(datapacks.resolve("retromod-legacy-trades/data/minecraft/tags/villager_trade/farmer/level_1.json"))) {
                return TestResult.fail("no farmer trade was written to " + datapacks);
            }
            if (selected.contains("file/retromod-legacy-trades") && !hasTrade(loader)) {
                return TestResult.fail("the trade pack is on but " + TRADE + " did not load");
            }
            return checkBrewing(loader, datapacks, selected);
        } catch (ReflectiveOperationException e) {
            return TestResult.fail("could not read the host: " + e);
        }
    }

    /** 26.1 and 26.2 brew in code; 26.3 reads the recipe Retromod wrote. */
    private TestResult checkBrewing(ClassLoader loader, Path datapacks, Collection<?> selected)
            throws ReflectiveOperationException {
        if (method(server.getClass(), "potionBrewing", 0) != null) {
            Object brewing = call(server, "potionBrewing");
            Class<?> items = Class.forName("net.minecraft.world.item.Items", false, loader);
            Object awkward = Class.forName("net.minecraft.world.item.alchemy.Potions", false, loader).getField("AWKWARD").get(null);
            Class<?> contents = Class.forName("net.minecraft.world.item.alchemy.PotionContents", false, loader);
            Object potion = method(contents, "createItemStack", 2).invoke(null, items.getField("POTION").get(null), awkward);
            Object bone = stack(loader, items.getField("BONE").get(null));
            boolean mixes = (Boolean) method(brewing.getClass(), "hasMix", 2).invoke(brewing, potion, bone);
            return mixes ? TestResult.success() : TestResult.fail("awkward potion and bone do not brew");
        }
        if (!Files.exists(datapacks.resolve("retromod-legacy-brewing/data/retromod_legacy/recipe/legacy_brewing/0.json"))) {
            return TestResult.fail("no brewing recipe was written to " + datapacks);
        }
        if (selected.contains("file/retromod-legacy-brewing") && !hasRecipe(loader)) {
            return TestResult.fail("the brewing pack is on but " + BREWING + " did not load");
        }
        return TestResult.success();
    }

    private boolean hasTrade(ClassLoader loader) throws ReflectiveOperationException {
        Object registries = Class.forName("net.minecraft.core.registries.Registries", false, loader)
                .getField("VILLAGER_TRADE").get(null);
        // Through the interface, because the registry access's own class need not be public.
        Class<?> access = Class.forName("net.minecraft.core.RegistryAccess", false, loader);
        Object trades = access.getMethod("lookupOrThrow", Class.forName("net.minecraft.resources.ResourceKey", false, loader))
                .invoke(call(server, "registryAccess"), registries);
        Class<?> identifier = Class.forName("net.minecraft.resources.Identifier", false, loader);
        Object id = identifier.getMethod("parse", String.class).invoke(null, TRADE);
        return (Boolean) Class.forName("net.minecraft.core.Registry", false, loader)
                .getMethod("containsKey", identifier).invoke(trades, id);
    }

    private boolean hasRecipe(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> keys = Class.forName("net.minecraft.resources.ResourceKey", false, loader);
        Class<?> identifier = Class.forName("net.minecraft.resources.Identifier", false, loader);
        Object recipes = Class.forName("net.minecraft.core.registries.Registries", false, loader).getField("RECIPE").get(null);
        Object key = keys.getMethod("create", keys, identifier).invoke(null, recipes,
                identifier.getMethod("parse", String.class).invoke(null, BREWING));
        Object manager = call(server, "getRecipeManager");
        return ((Optional<?>) method(manager.getClass(), "byKey", 1).invoke(manager, key)).isPresent();
    }

    private static Object stack(ClassLoader loader, Object item) throws ReflectiveOperationException {
        Class<?> itemLike = Class.forName("net.minecraft.world.level.ItemLike", false, loader);
        return Class.forName("net.minecraft.world.item.ItemStack", false, loader).getConstructor(itemLike).newInstance(item);
    }

    private static Object call(Object target, String name, Object... args) throws ReflectiveOperationException {
        Method method = method(target.getClass(), name, args.length);
        if (method == null) throw new NoSuchMethodException(target.getClass().getName() + "." + name);
        return method.invoke(target, args);
    }

    private static Method method(Class<?> type, String name, int arity) {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == arity) return method;
        }
        return null;
    }
}
