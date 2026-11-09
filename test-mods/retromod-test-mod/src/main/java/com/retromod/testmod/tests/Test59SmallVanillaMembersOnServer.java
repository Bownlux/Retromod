/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;

/** The registry and entity members a 1.21.1 mod calls on a running server, which 1.21.2 and 1.21.9 changed. */
public final class Test59SmallVanillaMembersOnServer implements Test {

    private final MinecraftServer server;

    public Test59SmallVanillaMembersOnServer(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public String description() {
        return "getOptional(key) on the registry manager, and an entity's getServer()";
    }

    @Override
    public TestResult run() {
        // 1.21.2 renamed getOptional(key) to the lookup the registry manager already had.
        if (server.getRegistryManager().getOptional(RegistryKeys.BIOME).isEmpty()) {
            return TestResult.fail("the biome registry was not found");
        }
        // 1.21.9 removed Entity.getServer(); the entity's world still has the server.
        Entity item = new ItemEntity(server.getOverworld(), 0, 64, 0, ItemStack.EMPTY);
        if (item.getServer() != server) return TestResult.fail("the entity's server read as " + item.getServer());
        return TestResult.success();
    }
}
