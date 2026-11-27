/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;
import java.lang.reflect.Method;
import java.util.function.Predicate;
import net.minecraft.client.MinecraftClient;

/** At world join on a client: the host holds the old model for the block, and its draw reaches the old emitBlockQuads. */
public final class Test95FabricBakedModelOnHost implements Test {

    @Override
    public String description() {
        return "26.x draws the old FabricBakedModel block through its emitBlockQuads";
    }

    @Override
    public TestResult run() {
        if (Test94OldFabricBakedModel.block == null) return TestResult.fail("Test94 did not register its block");
        // The 26.x members are reached by reflection, because this mod compiles against 1.20.1.
        try {
            Object state = Test94OldFabricBakedModel.block.getDefaultState();
            Object models = MinecraftClient.getInstance().getClass().getMethod("getModelManager").invoke(MinecraftClient.getInstance());
            Object set = models.getClass().getMethod("getBlockStateModelSet").invoke(models);
            Object model = find(set.getClass(), "get", 1).invoke(set, state);
            if (!model.getClass().getName().endsWith("Test94OldFabricBakedModel$Baked")) {
                return TestResult.fail("the block's 26.x model is " + model.getClass().getName());
            }
            // Draw it into a host mesh, as the chunk builder would.
            Class<?> rendererType = Class.forName("net.fabricmc.fabric.api.client.renderer.v1.Renderer");
            Object mesh = rendererType.getMethod("mutableMesh").invoke(rendererType.getMethod("get").invoke(null));
            Object emitter = mesh.getClass().getMethod("emitter").invoke(mesh);
            Class<?> getter = Class.forName("net.minecraft.client.renderer.block.BlockAndTintGetter");
            Class<?> pos = Class.forName("net.minecraft.core.BlockPos");
            Class<?> random = Class.forName("net.minecraft.util.RandomSource");
            Object empty = getter.getField("EMPTY").get(null);
            Object origin = pos.getField("ZERO").get(null);
            Object seeded = random.getMethod("create", long.class).invoke(null, 42L);
            Predicate<Object> noCulling = side -> false;
            find(model.getClass(), "emitQuads", 6).invoke(model, emitter, empty, origin, state, seeded, noCulling);
            int quads = (Integer) mesh.getClass().getMethod("size").invoke(mesh);
            if (!Test94OldFabricBakedModel.emittedBlock || quads != 6) {
                return TestResult.fail("emitBlockQuads ran " + Test94OldFabricBakedModel.emittedBlock + ", the mesh has " + quads + " quads");
            }
            return TestResult.success();
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            return TestResult.fail(cause.getClass().getSimpleName() + ": " + cause.getMessage());
        }
    }

    private static Method find(Class<?> owner, String name, int parameters) throws NoSuchMethodException {
        for (Method method : owner.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == parameters) return method;
        }
        throw new NoSuchMethodException(owner.getName() + "." + name);
    }
}
