/*
 * Retromod Test Mod (NeoForge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.neoforge;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.LoggerFactory;

/** A block and an item color through NeoForge 1.21.1's color events, which NeoForge 26.1 replaced with tint sources. Client only. */
final class ColorHandlersCase {

    private static final String EVENTS = "The old block color event reaches an addListener method reference, then the old item color event"
            + " a @SubscribeEvent method, which sees the block color (client start)";
    private static final String COLORS = "The registered block and item colors answer through BlockColors.getColor and"
            + " Minecraft.getItemColors(), the translucent item color with its alpha (first client tick)";
    // Opaque, as 1.21 mods answer, since 1.21's item renderer reads a tint's alpha.
    private static final int BLOCK_COLOR = 0xFF3366CC;
    // Deliberately translucent: a 1.21 mod's item tint keeps the alpha it answers.
    private static final int ITEM_COLOR = 0x80CC6633;

    private static volatile boolean blockPosted;
    private static volatile String itemResult;
    private static boolean reported;

    private ColorHandlersCase() {}

    static void register(IEventBus modBus) {
        try {
            // Sophisticated Backpacks' shape: a method reference whose parameter is the old event.
            modBus.addListener(ColorHandlersCase::onBlockColors);
            // Traveler's Backpack's shape: a class of @SubscribeEvent methods.
            modBus.register(ItemListeners.class);
            NeoForge.EVENT_BUS.addListener(ColorHandlersCase::onClientTick);
        } catch (Throwable t) {
            log(EVENTS, "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void onBlockColors(RegisterColorHandlersEvent.Block event) {
        event.register((state, level, pos, tintIndex) -> tintIndex == 0 ? BLOCK_COLOR : -1, CrossClassBlocks.CROSS_CLASS_BLOCK);
        blockPosted = true;
    }

    /** The item color listeners, subscribed as a class. */
    public static final class ItemListeners {
        @SubscribeEvent
        public static void onItemColors(RegisterColorHandlersEvent.Item event) {
            try {
                BlockState state = CrossClassBlocks.CROSS_CLASS_BLOCK.defaultBlockState();
                int seen = event.getBlockColors().getColor(state, null, null, 0);
                event.register((stack, tintIndex) -> tintIndex == 0 ? ITEM_COLOR : -1, CrossClassItems.CROSS_CLASS_ITEM);
                itemResult = !blockPosted ? "fail: the item event came before the block event"
                        : seen == BLOCK_COLOR ? "success" : "fail: the block color read " + Integer.toHexString(seen);
            } catch (Throwable t) {
                itemResult = "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage();
            }
        }
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        if (reported) return;
        reported = true;
        log(EVENTS, itemResult != null ? itemResult : blockPosted ? "fail: the item event was not posted" : "fail: neither event was posted");
        try {
            BlockState state = CrossClassBlocks.CROSS_CLASS_BLOCK.defaultBlockState();
            int block = Minecraft.getInstance().getBlockColors().getColor(state, null, null, 0);
            int item = Minecraft.getInstance().getItemColors().getColor(new ItemStack(CrossClassItems.CROSS_CLASS_ITEM), 0);
            log(COLORS, block == BLOCK_COLOR && item == ITEM_COLOR ? "success"
                    : "fail: block " + Integer.toHexString(block) + ", item " + Integer.toHexString(item));
        } catch (Throwable t) {
            log(COLORS, "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void log(String what, String result) {
        LoggerFactory.getLogger("Retromod-Test-NeoForge").info("[Retromod-Test-NeoForge] {}: {}", what, result);
    }
}
