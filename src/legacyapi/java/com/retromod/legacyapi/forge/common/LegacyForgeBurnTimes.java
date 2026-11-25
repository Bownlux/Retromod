/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.common;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.entity.FuelValues;
import net.minecraftforge.server.ServerLifecycleHooks;

/** Forge 1.20.1's {@code ForgeHooks.getBurnTime(stack, type)}, answered by the world's fuel values on Forge 26.2. */
public final class LegacyForgeBurnTimes {

    private LegacyForgeBurnTimes() {}

    /** The item's own burn time, else the fuel table's, after Forge's burn time event; 0 with no world loaded. */
    public static int getBurnTime(ItemStack stack, RecipeType<?> type) {
        if (stack.isEmpty()) return 0;
        // Forge patches burnDuration(stack, type) to do what the old hook did, so the answer matches the furnace's.
        FuelValues values = ServerFuel.find();
        if (values == null) values = ClientFuel.find();
        return values == null ? 0 : values.burnDuration(stack, type);
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
