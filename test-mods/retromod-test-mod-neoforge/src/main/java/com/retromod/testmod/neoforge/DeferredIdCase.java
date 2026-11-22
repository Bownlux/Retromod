/*
 * Retromod Test Mod (NeoForge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.neoforge;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.neoforge.registries.RegisterEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Moonlight's block sets build a block or item first and register it afterwards, from a {@code RegisterEvent},
 * under an id made from a name, which 1.21.2 and newer refuse with "Item id not set". Each object here is built
 * that way, through {@code Registry.register} and through the event's register helper.
 */
public final class DeferredIdCase {

    private static final Logger LOG = LoggerFactory.getLogger("Retromod-Test-NeoForge");
    private static final String NAMESPACE = "retromod_test_mod_neoforge";

    // Kept for the server-started half.
    private static Item item;
    private static Item helperItem;

    private DeferredIdCase() {}

    public static void register(RegisterEvent event) {
        event.register(Registries.BLOCK, helper -> {
            String result;
            try {
                Block block = new Block(BlockBehaviour.Properties.of().strength(1.0F));
                Registry.register(event.getRegistry(Registries.BLOCK), id("deferred_block"), block);
                result = block.getDescriptionId().equals("block.retromod_test_mod_neoforge.deferred_block")
                        && block.getLootTable().location().equals(id("blocks/deferred_block"))
                        ? "success" : "fail: the block reads " + block.getDescriptionId() + " and " + block.getLootTable();
            } catch (Throwable t) {
                result = "fail: " + t;
            }
            LOG.info("[Retromod-Test-NeoForge] a block built before its id is known takes the id it is registered under: {}",
                    result);
        });
        event.register(Registries.ITEM, helper -> {
            String result;
            try {
                Item built = new Item(new Item.Properties().stacksTo(16));
                Item builtForHelper = new Item(new Item.Properties());
                Registry.register(event.getRegistry(Registries.ITEM), id("deferred_item"), built);
                helper.register(id("deferred_helper_item"), builtForHelper);
                item = built;
                helperItem = builtForHelper;
                result = built.getDescriptionId().equals("item.retromod_test_mod_neoforge.deferred_item")
                        && builtForHelper.getDescriptionId().equals("item.retromod_test_mod_neoforge.deferred_helper_item")
                        ? "success" : "fail: the items read " + built.getDescriptionId() + " and " + builtForHelper.getDescriptionId();
            } catch (Throwable t) {
                result = "fail: " + t;
            }
            LOG.info("[Retromod-Test-NeoForge] items built before their ids are known take the ids they are registered under: {}",
                    result);
        });
    }

    /** The server-started half: the items' stacks read the name and stack size of their real ids. */
    static String checkOnServer() {
        if (item == null || helperItem == null) return "fail: the registration half did not run";
        ItemStack stack = new ItemStack(item);
        if (stack.getMaxStackSize() != 16) return "fail: the stack size is " + stack.getMaxStackSize();
        String name = stack.getHoverName().getString();
        if (!name.equals("item.retromod_test_mod_neoforge.deferred_item")) return "fail: the stack is named " + name;
        String helperName = new ItemStack(helperItem).getHoverName().getString();
        return helperName.equals("item.retromod_test_mod_neoforge.deferred_helper_item")
                ? "success" : "fail: the helper item's stack is named " + helperName;
    }

    /** The id is made after the object, from a name, as Moonlight's {@code res(name)} helpers do. */
    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(NAMESPACE, path);
    }
}
