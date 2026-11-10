/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A command context of the mod's own that keeps only some items, as Sophisticated Backpacks builds for its item
 * argument. Commands register while a world loads its data, so the result is in the log rather than the summary.
 */
final class CommandContextCase {

    private static final Logger LOG = LoggerFactory.getLogger("Retromod-Test-Forge");
    private static volatile String result = "fail: RegisterCommandsEvent did not run";

    private CommandContextCase() {}

    static void register() {
        MinecraftForge.EVENT_BUS.addListener(CommandContextCase::onRegisterCommands);
        MinecraftForge.EVENT_BUS.addListener(CommandContextCase::check);
    }

    // A throw here would stop the world from loading, so the case keeps it as its result.
    private static void onRegisterCommands(RegisterCommandsEvent event) {
        try {
            CommandBuildContext diamonds = onlyDiamonds(event.getBuildContext());
            ItemArgument argument = ItemArgument.item(diamonds);
            argument.parse(new StringReader("minecraft:diamond"));
            boolean stoneRefused;
            try {
                argument.parse(new StringReader("minecraft:stone"));
                stoneRefused = false;
            } catch (CommandSyntaxException expected) {
                stoneRefused = true;
            }
            long items = diamonds.holderLookup(Registries.ITEM).listElements().count();
            result = stoneRefused && items == 1 ? "success" : "fail: the context kept " + items + " items";
        } catch (Throwable t) {
            result = "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    static CommandBuildContext onlyDiamonds(CommandBuildContext delegate) {
        return new CommandBuildContext() {
            @Override
            public <T> HolderLookup<T> holderLookup(ResourceKey<? extends Registry<T>> key) {
                return delegate.holderLookup(key).filterElements(item -> item == Items.DIAMOND);
            }
        };
    }

    private static void check(ServerStartedEvent event) {
        LOG.info("[Retromod-Test-Forge] An old CommandBuildContext of the mod's own builds an item argument while "
                + "commands register (server started): {}", result);
    }
}
