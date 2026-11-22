/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.retromod.testmod.SimpleTest;
import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;

/**
 * Moonlight's block sets build an item first and only then make the id they register it under, which
 * 1.21.2 and newer refuse with "Item id not set". The block, the plain item, and the mod's own item
 * subclass here are built the same way and must end up named after the ids they were registered under.
 */
public final class Test87DeferredRegistryIds implements Test {

    private static final String NAMESPACE = "retromod_test_mod";

    // Kept from common init for the server-started half.
    private static Block block;
    private static Item item;
    private static Item ownItem;

    /** The mod's own item class, whose constructor hands the properties to Minecraft's. */
    public static final class OwnItem extends Item {
        public OwnItem(Settings settings) {
            super(settings.maxCount(16));
        }
    }

    @Override
    public String description() {
        return "a block and items built before their registry id is known take the id they are registered under";
    }

    @Override
    public TestResult run() {
        Block builtBlock;
        Item builtItem;
        Item builtOwnItem;
        try {
            // Everything is built before any id exists, as Supplementaries' sign post items are.
            builtBlock = new Block(AbstractBlock.Settings.create().strength(1.0F));
            builtItem = new Item(new Item.Settings());
            builtOwnItem = new OwnItem(new Item.Settings());
        } catch (Throwable t) {
            return TestResult.fail("building without an id threw " + t);
        }
        Registry.register(Registries.BLOCK, id("deferred_block"), builtBlock);
        Registry.register(Registries.ITEM, id("deferred_item"), builtItem);
        Registry.register(Registries.ITEM, id("deferred_own_item"), builtOwnItem);
        block = builtBlock;
        item = builtItem;
        ownItem = builtOwnItem;

        if (!block.getTranslationKey().equals("block.retromod_test_mod.deferred_block")) {
            return TestResult.fail("the block's translation key is " + block.getTranslationKey());
        }
        if (!id("blocks/deferred_block").equals(block.getLootTableId())) {
            return TestResult.fail("the block's loot table is " + block.getLootTableId());
        }
        if (!item.getTranslationKey().equals("item.retromod_test_mod.deferred_item")) {
            return TestResult.fail("the item's translation key is " + item.getTranslationKey());
        }
        return ownItem.getTranslationKey().equals("item.retromod_test_mod.deferred_own_item")
                ? TestResult.success()
                : TestResult.fail("the own item's translation key is " + ownItem.getTranslationKey());
    }

    /** The id is made after the object, from a name, as Moonlight's {@code res(name)} helpers do. */
    private static Identifier id(String path) {
        return new Identifier(NAMESPACE, path);
    }

    /** The server-started half: the items' stacks read the name and properties of their real ids once components bind. */
    public static Test checkOnServer(MinecraftServer server) {
        return new SimpleTest("items built before their id was known have their components under the real id", () -> {
            if (item == null || ownItem == null) return TestResult.fail("the init half did not run");
            ItemStack stack = new ItemStack(item);
            String name = stack.getName().getString();
            if (!name.equals("item.retromod_test_mod.deferred_item")) return TestResult.fail("the stack is named " + name);
            ItemStack own = new ItemStack(ownItem);
            if (own.getMaxCount() != 16) return TestResult.fail("the own item's stack size is " + own.getMaxCount());
            String ownName = own.getName().getString();
            return ownName.equals("item.retromod_test_mod.deferred_own_item")
                    ? TestResult.success() : TestResult.fail("the own item's stack is named " + ownName);
        });
    }
}
