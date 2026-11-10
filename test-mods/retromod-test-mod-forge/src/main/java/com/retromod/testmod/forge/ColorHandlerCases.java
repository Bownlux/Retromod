/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.client.event.RegisterColorHandlersEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import org.slf4j.LoggerFactory;

/** Old block and item color handlers, which Forge 26.2 and Minecraft 26.1 removed, logging whether the game reports them. Client only. */
public final class ColorHandlerCases {

    // Cobblestone's models have no tinted face, so these colors change nothing on screen.
    private static final int BLOCK_COLOR = 0x3366CC;
    private static final int ITEM_COLOR = 0xCC6633;

    private static boolean blockRan;
    private static boolean itemRan;
    private static int blockSeenByItem;
    private static boolean reported;

    private ColorHandlerCases() {}

    static void register(IEventBus modBus) {
        try {
            modBus.addListener(ColorHandlerCases::onBlockColors);
            modBus.addListener(ColorHandlerCases::onItemColors);
            MinecraftForge.EVENT_BUS.addListener(ColorHandlerCases::onClientTick);
        } catch (Throwable t) {
            log("Old color handlers register (construction)", "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void onBlockColors(RegisterColorHandlersEvent.Block event) {
        blockRan = true;
        event.register((state, level, pos, tint) -> tint == 0 ? BLOCK_COLOR : -1, Blocks.COBBLESTONE);
    }

    // Forge 1.20.1 posted the item event after the block one, so a block color registered there is visible here.
    private static void onItemColors(RegisterColorHandlersEvent.Item event) {
        itemRan = true;
        blockSeenByItem = event.getBlockColors().getColor(Blocks.COBBLESTONE.defaultBlockState(), null, null, 0);
        event.register((stack, tint) -> tint == 0 ? ITEM_COLOR : -1, Items.COBBLESTONE);
    }

    // The first client tick comes after both color events, which fire while the game starts.
    private static void onClientTick(TickEvent.ClientTickEvent event) {
        if (reported || event.phase != TickEvent.Phase.END) return;
        reported = true;
        try {
            String order = !blockRan ? "fail: the block color handler did not run"
                    : !itemRan ? "fail: the item color handler did not run"
                    : (blockSeenByItem & 0xFFFFFF) != BLOCK_COLOR ? "fail: the item handler saw " + Integer.toHexString(blockSeenByItem)
                    : "success";
            log("An old RegisterColorHandlersEvent Block handler runs before the Item one, which sees its color", order);

            Minecraft minecraft = Minecraft.getInstance();
            int block = minecraft.getBlockColors().getColor(Blocks.COBBLESTONE.defaultBlockState(), null, null, 0);
            int past = minecraft.getBlockColors().getColor(Blocks.COBBLESTONE.defaultBlockState(), null, null, 1);
            log("BlockColors.getColor answers the old block color, and -1 where it leaves a tint index alone",
                    (block & 0xFFFFFF) == BLOCK_COLOR && past == -1 ? "success"
                            : "fail: " + Integer.toHexString(block) + " and " + past);

            int item = minecraft.getItemColors().getColor(new ItemStack(Items.COBBLESTONE), 0);
            log("Minecraft.getItemColors() answers the old item color",
                    (item & 0xFFFFFF) == ITEM_COLOR ? "success" : "fail: " + Integer.toHexString(item));
        } catch (Throwable t) {
            log("Old color handlers read back", "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void log(String what, String result) {
        LoggerFactory.getLogger("Retromod-Test-Forge").info("[Retromod-Test-Forge] {}: {}", what, result);
    }
}
