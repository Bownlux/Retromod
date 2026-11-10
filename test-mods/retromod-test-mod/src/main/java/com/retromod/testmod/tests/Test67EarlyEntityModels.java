/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.retromod.testmod.SimpleTest;
import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.EntityModelLoader;

/** Minecraft's entity models asked for during client init, which on 26.x runs before the game has a model manager. */
public final class Test67EarlyEntityModels implements Test {

    // What the game answered during client init; 1.20.1 answers null there, before its models exist.
    private static EntityModelLoader kept;

    @Override
    public String description() {
        return "Minecraft's entity models asked for during client init, as an early item renderer does";
    }

    @Override
    public TestResult run() {
        try {
            kept = MinecraftClient.getInstance().getEntityModelLoader();
            return TestResult.success();
        } catch (Throwable t) {
            return TestResult.fail("the getter threw " + t);
        }
    }

    /** The world-join half: the models kept from client init bake the player layer once the game has loaded. */
    public static Test bakesInWorld() {
        return new SimpleTest("the entity models kept from client init bake a vanilla layer in a world", () -> {
            // A 26.x host is Mojang-named at run time, and there the kept models must not be null.
            boolean modernHost = hostHas("net.minecraft.client.model.geom.EntityModelSet");
            if (kept == null) return modernHost ? TestResult.fail("the getter answered null") : TestResult.success();
            try {
                return kept.getModelPart(EntityModelLayers.PLAYER).hasChild("head")
                        ? TestResult.success() : TestResult.fail("the player layer has no head");
            } catch (Throwable t) {
                return TestResult.fail("baking threw " + t);
            }
        });
    }

    private static boolean hostHas(String name) {
        try {
            Class.forName(name, false, Test67EarlyEntityModels.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
