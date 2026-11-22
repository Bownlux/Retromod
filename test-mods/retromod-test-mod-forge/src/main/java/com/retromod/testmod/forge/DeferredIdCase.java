/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegisterEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Moonlight's block sets build a block or item first and register it afterwards under an id made from a name,
 * which 1.21.2 and newer refuse with "Item id not set". Each object here is built before its id exists and then
 * handed to the register event's helper.
 */
public final class DeferredIdCase {

    private static final Logger LOG = LoggerFactory.getLogger("Retromod-Test-Forge");
    private static final String NAMESPACE = "retromod_test_mod_forge";

    private DeferredIdCase() {}

    public static void register(RegisterEvent event) {
        event.register(ForgeRegistries.Keys.BLOCKS, helper -> {
            String result;
            try {
                Block block = buildBlock();
                helper.register(id("deferred_block"), block);
                result = block.getDescriptionId().equals("block.retromod_test_mod_forge.deferred_block")
                        ? "success" : "fail: the block reads " + block.getDescriptionId();
            } catch (Throwable t) {
                result = "fail: " + t;
            }
            LOG.info("[Retromod-Test-Forge] a block built before its id is known takes the id it is registered under: {}", result);
        });
        event.register(ForgeRegistries.Keys.ITEMS, helper -> {
            String result;
            try {
                Item item = buildItem();
                helper.register(id("deferred_item"), item);
                result = item.getDescriptionId().equals("item.retromod_test_mod_forge.deferred_item")
                        ? "success" : "fail: the item reads " + item.getDescriptionId();
            } catch (Throwable t) {
                result = "fail: " + t;
            }
            LOG.info("[Retromod-Test-Forge] an item built before its id is known takes the id it is registered under: {}", result);
        });
    }

    // Built in their own methods, apart from any registration, as Supplementaries builds its sign post items.
    private static Block buildBlock() {
        return new Block(BlockBehaviour.Properties.of().strength(1.0F));
    }

    private static Item buildItem() {
        return new Item(new Item.Properties().stacksTo(16));
    }

    /** The id is made after the object, from a name, as Moonlight's {@code res(name)} helpers do. */
    private static ResourceLocation id(String path) {
        return new ResourceLocation(NAMESPACE, path);
    }
}
