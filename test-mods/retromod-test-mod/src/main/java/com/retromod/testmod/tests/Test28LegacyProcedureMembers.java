/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;
import net.minecraft.advancement.Advancement;
import net.minecraft.advancement.PlayerAdvancementTracker;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerAdvancementLoader;
import net.minecraft.server.command.CommandOutput;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec2f;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;

/**
 * #347: the members an MCreator food mod's procedures call that later hosts changed. The command
 * source it plays a sound through took an int permission level and both names, the inventory
 * count and clear, both item drops and the entity damage gained arguments, a property's values
 * became a list, the stack damage call was removed, the advancement became a holder, and the
 * player's server became private.
 *
 * <p>Calls on a null receiver prove linkage only: the bridge links, then fails on the missing
 * receiver. The command source, the property values and the empty stack's damage are checked for
 * their answers.
 */
public final class Test28LegacyProcedureMembers implements Test {

    @Override
    public String description() {
        return "#347 procedure members later hosts changed";
    }

    @Override
    public TestResult run() {
        String failure = commandSource();
        if (failure == null) failure = linkage("PlayerInventory.remove(predicate, max, craftSlots)",
                () -> ((PlayerInventory) null).remove(stack -> true, 0, null));
        if (failure == null) failure = linkage("PlayerEntity.dropItem(stack, retainOwnership)",
                () -> ((PlayerEntity) null).dropItem(ItemStack.EMPTY, false));
        if (failure == null) failure = linkage("PlayerEntity.dropItem(stack, throwRandomly, retainOwnership)",
                () -> ((PlayerEntity) null).dropItem(ItemStack.EMPTY, true, false));
        if (failure == null) failure = linkage("LivingEntity.damage(source, amount)",
                () -> ((LivingEntity) null).damage(null, 1f));
        if (failure == null && Properties.AGE_3.getValues().size() != 4) {
            failure = "IntProperty.getValues() did not list four ages";
        }
        if (failure == null && ItemStack.EMPTY.damage(1, Random.create(), null)) {
            failure = "an empty stack reported that it broke";
        }
        if (failure == null) failure = linkage("ServerAdvancementLoader.get(id)",
                () -> ((ServerAdvancementLoader) null).get(new Identifier("retromod", "tea")));
        if (failure == null) failure = linkage("PlayerAdvancementTracker.grantCriterion",
                () -> ((PlayerAdvancementTracker) null).grantCriterion((Advancement) null, "drank"));
        if (failure == null) failure = linkage("ServerPlayerEntity.server", () -> {
            MinecraftServer server = ((ServerPlayerEntity) null).server;
        });
        return failure == null ? TestResult.success() : TestResult.fail(failure);
    }

    /** The source a procedure builds to run a command. Without an entity it keeps its names. */
    private static String commandSource() {
        try {
            ServerCommandSource source = new ServerCommandSource(CommandOutput.DUMMY, Vec3d.ZERO, Vec2f.ZERO,
                    null, 4, "Retromod", Text.literal("Retromod"), null, null);
            if (!source.getName().equals("Retromod") || !source.getDisplayName().getString().equals("Retromod")) {
                return "the command source lost its names";
            }
            return source.hasPermissionLevel(2) ? null : "the command source lost its permission level";
        } catch (Throwable t) {
            return "the command source failed: " + t;
        }
    }

    /** Only a linkage error counts: anything thrown after the call links is the null receiver. */
    private static String linkage(String label, Runnable probe) {
        try {
            probe.run();
            return null;
        } catch (IncompatibleClassChangeError | NoClassDefFoundError | VerifyError e) {
            return label + " did not link: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        } catch (Throwable linkedThenRefused) {
            return null;
        }
    }
}
