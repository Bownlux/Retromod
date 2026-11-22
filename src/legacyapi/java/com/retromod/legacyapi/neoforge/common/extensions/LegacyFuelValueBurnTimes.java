/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.common.extensions;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.entity.FuelValues;
import net.neoforged.neoforge.common.extensions.IItemStackExtension;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/** NeoForge 21.1's {@code getBurnTime} reads on NeoForge 21.2 to 26.2, which take the world's fuel values too. */
public final class LegacyFuelValueBurnTimes {

    private LegacyFuelValueBurnTimes() {}

    /** {@code ItemStack.getBurnTime(RecipeType)}: 0 for an empty stack, else the item's burn time. */
    public static int getBurnTime(ItemStack stack, RecipeType<?> type) {
        if (stack.isEmpty()) return 0;
        return Math.max(0, getBurnTime(stack.getItem(), stack, type));
    }

    /** {@code Item.getBurnTime(ItemStack, RecipeType)}: a mod item's own answer, else the host's for this world. */
    public static int getBurnTime(Item item, ItemStack stack, RecipeType<?> type) {
        return LegacyBurnTimeOverrides.burnTime(item, stack, type, held -> hostBurnTime(held, type));
    }

    /** A mod override's {@code super.getBurnTime(stack, type)}, which reads what the item would without the override. */
    public static int superGetBurnTime(Item item, ItemStack stack, RecipeType<?> type) {
        return hostBurnTime(stack, type);
    }

    /** The host's burn time, which reads the data map and fires the burn time event, or 0 with no world loaded. */
    static int hostBurnTime(ItemStack stack, RecipeType<?> type) {
        FuelValues values = ServerFuel.find();
        if (values == null) values = ClientFuel.find();
        return values == null ? 0 : ((IItemStackExtension) (Object) stack).getBurnTime(type, values);
    }

    // The running server's fuel values, which a single player game has too.
    private static final class ServerFuel {
        static FuelValues find() {
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            return server == null ? null : server.fuelValues();
        }
    }

    // The client world's fuel values, in a class of its own: a dedicated server has no client classes.
    private static final class ClientFuel {
        static FuelValues find() {
            try {
                Minecraft minecraft = Minecraft.getInstance();
                ClientLevel level = minecraft == null ? null : minecraft.level;
                return level == null ? null : level.fuelValues();
            } catch (LinkageError | RuntimeException noClient) {
                return null;
            }
        }
    }
}
