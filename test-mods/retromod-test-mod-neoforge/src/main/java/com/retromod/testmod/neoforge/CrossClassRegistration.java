/*
 * Retromod Test Mod (NeoForge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.neoforge;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.RegisterEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The loader half: the only class that names the ids of {@link CrossClassBlocks} and
 * {@link CrossClassItems}, from {@code RegisterEvent} consumers. On 26.x the block class died with
 * {@code Block id not set} when the consumer first read the field. The block consumer reads the
 * item first, so the item's statement is what initializes the block class. Each result is logged
 * once its object is registered.
 */
public final class CrossClassRegistration {

    private static final Logger LOG = LoggerFactory.getLogger("Retromod-Test-NeoForge");

    private CrossClassRegistration() {}

    public static void register(RegisterEvent event) {
        event.register(Registries.BLOCK, helper -> {
            String result;
            try {
                Item first = CrossClassItems.CROSS_CLASS_ITEM;
                helper.register(ResourceLocation.fromNamespaceAndPath("retromod_test_mod_neoforge", "cross_class_block"),
                        CrossClassBlocks.CROSS_CLASS_BLOCK);
                String key = CrossClassBlocks.CROSS_CLASS_BLOCK.getDescriptionId();
                result = first == null ? "fail: the item was not built"
                        : key.equals("block.retromod_test_mod_neoforge.cross_class_block")
                        ? "success" : "fail: built under another id: " + key;
            } catch (Throwable t) {
                result = "fail: " + describe(t);
            }
            LOG.info("[Retromod-Test-NeoForge] a block built in one class and registered by another gets its id (register event): {}",
                    result);
        });
        event.register(Registries.ITEM, helper -> {
            String result;
            try {
                helper.register(ResourceLocation.fromNamespaceAndPath("retromod_test_mod_neoforge", "cross_class_item"),
                        CrossClassItems.CROSS_CLASS_ITEM);
                // Before 1.21.2 a block item names itself after its block. From 26.x on it takes
                // its name from its own id, which must not be any other item's.
                String key = CrossClassItems.CROSS_CLASS_ITEM.getDescriptionId();
                result = !key.startsWith("item.") || key.equals("item.retromod_test_mod_neoforge.cross_class_item")
                        ? "success" : "fail: built under another id: " + key;
            } catch (Throwable t) {
                result = "fail: " + describe(t);
            }
            LOG.info("[Retromod-Test-NeoForge] an item built around another class's block gets its own id (register event): {}",
                    result);
        });
    }

    /** The failure and its causes, so a class initializer's real error reaches the log. */
    private static String describe(Throwable t) {
        StringBuilder text = new StringBuilder();
        for (Throwable cause = t; cause != null; cause = cause.getCause()) {
            if (text.length() > 0) text.append(" <- ");
            text.append(cause.getClass().getSimpleName()).append(": ").append(cause.getMessage());
            if (cause.getCause() == null || cause.getCause() == cause) {
                StackTraceElement[] frames = cause.getStackTrace();
                for (int i = 0; i < Math.min(6, frames.length); i++) text.append(" at ").append(frames[i]);
                break;
            }
        }
        return text.toString();
    }
}
