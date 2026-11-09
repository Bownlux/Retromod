/*
 * Retromod Test Mod (NeoForge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.neoforge;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.Optional;
import java.util.stream.Stream;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Items;

/** A command context of the mod's own that keeps only diamonds, as Sophisticated Backpacks 1.21.1 builds for its item argument. */
final class CommandContextCase {

    private CommandContextCase() {}

    /** The first step that went wrong, or success. */
    static String run(MinecraftServer server) throws ReflectiveOperationException {
        CommandBuildContext diamonds = onlyDiamonds(server.registryAccess());
        ItemArgument argument = ItemArgument.item(diamonds);
        try {
            argument.parse(new StringReader("minecraft:diamond"));
        } catch (CommandSyntaxException e) {
            return "fail: diamond did not parse: " + e.getMessage();
        }
        try {
            argument.parse(new StringReader("minecraft:stone"));
            return "fail: stone parsed through a context that keeps only diamonds";
        } catch (CommandSyntaxException expected) {
            // The filter held.
        }
        if (diamonds.listRegistries().noneMatch(Registries.ITEM::equals)) return "fail: listRegistries did not list the item registry";
        // 26.x asks every context for its features, which a 1.21.1 context never declared.
        Object features = diamonds.getClass().getMethod("enabledFeatures").invoke(diamonds);
        return features != null ? "success" : "fail: the context has no features";
    }

    static CommandBuildContext onlyDiamonds(HolderLookup.Provider registries) {
        return new CommandBuildContext() {
            @Override
            public Stream<ResourceKey<? extends Registry<?>>> listRegistries() {
                return registries.listRegistries();
            }

            @Override
            public <T> Optional<HolderLookup.RegistryLookup<T>> lookup(ResourceKey<? extends Registry<? extends T>> key) {
                return registries.lookup(key).map(found -> found.filterElements(item -> item == Items.DIAMOND));
            }
        };
    }
}
