/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;
import java.lang.reflect.Method;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.feature.FeatureRenderer;
import net.minecraft.client.render.entity.feature.FeatureRendererContext;
import net.minecraft.client.render.entity.model.EntityModel;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;

/** An old feature renderer, block entity renderer and entity renderer each get a 26.x submit, and an item drawn outside a draw is quiet. */
public final class Test74OldRendererShapes implements Test {

    @Override
    public String description() {
        return "old feature, block entity and entity renderers take 26.x submits; getItemRenderer outside a draw";
    }

    @Override
    public TestResult run() {
        if (FabricLoader.getInstance().getEnvironmentType() != EnvType.CLIENT) return TestResult.success();
        try {
            for (Class<?> renderer : new Class<?>[]{OldFeature.class, OldBlockRenderer.class, OldEntityRenderer.class}) {
                if (!submits(renderer)) return TestResult.fail(renderer.getSimpleName() + " has no 26.x submit");
            }
            // The subclass's super.render, written as method_4199, must reach the base's old render, which draws an item
            // with no draw open and so draws nothing.
            Method render = null;
            for (Method method : BrightFeature.class.getMethods()) {
                if (method.getName().equals("render") && method.getParameterCount() == 10) render = method;
            }
            if (render == null) return TestResult.fail("the old render was not named render again");
            render.invoke(new BrightFeature(), new MatrixStack(), null, 15, null, 0f, 0f, 0f, 0f, 0f, 0f);
            return BrightFeature.drew ? TestResult.success() : TestResult.fail("the subclass's super.render did not run the base");
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            return TestResult.fail(cause.getClass().getSimpleName() + ": " + cause.getMessage());
        }
    }

    // A submit taking 26.x's SubmitNodeCollector that the class itself declares, which Retromod adds to an old renderer; the
    // host base's own submit is inherited either way.
    private static boolean submits(Class<?> renderer) {
        for (Method method : renderer.getDeclaredMethods()) {
            if (!method.getName().equals("submit")) continue;
            for (Class<?> parameter : method.getParameterTypes()) {
                if (parameter.getName().equals("net.minecraft.client.renderer.SubmitNodeCollector")) return true;
            }
        }
        return false;
    }

    /** A feature layer that draws an item with the old item renderer, as Sophisticated Backpacks' layer does. */
    static class OldFeature extends FeatureRenderer<LivingEntity, EntityModel<LivingEntity>> {
        static boolean drew;

        OldFeature() {
            super((FeatureRendererContext<LivingEntity, EntityModel<LivingEntity>>) null);
        }

        @Override
        public void render(MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light, LivingEntity entity, float limbAngle,
                float limbDistance, float tickDelta, float animationProgress, float headYaw, float headPitch) {
            // An empty stack, since this runs while mods initialize; the call still has to link.
            MinecraftClient.getInstance().getItemRenderer().renderItem(ItemStack.EMPTY, ModelTransformationMode.GROUND, light, 0,
                    matrices, vertexConsumers, null, 0);
            drew = true;
        }
    }

    /** A subclass that calls the base's render, which Fabric's map names submit on 26.x. */
    static final class BrightFeature extends OldFeature {
        @Override
        public void render(MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light, LivingEntity entity, float limbAngle,
                float limbDistance, float tickDelta, float animationProgress, float headYaw, float headPitch) {
            super.render(matrices, vertexConsumers, light, entity, limbAngle, limbDistance, tickDelta, animationProgress, headYaw, headPitch);
        }
    }

    /** A block entity renderer with the old render. */
    static final class OldBlockRenderer implements BlockEntityRenderer<BlockEntity> {
        @Override
        public void render(BlockEntity entity, float tickDelta, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light, int overlay) {
        }
    }

    /** An entity renderer directly under EntityRenderer, with the old render. */
    static final class OldEntityRenderer extends EntityRenderer<Entity> {
        OldEntityRenderer(EntityRendererFactory.Context context) {
            super(context);
        }

        @Override
        public void render(Entity entity, float yaw, float tickDelta, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light) {
            super.render(entity, yaw, tickDelta, matrices, vertexConsumers, light);
        }

        @Override
        public Identifier getTexture(Entity entity) {
            return null;
        }
    }
}
