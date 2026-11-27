/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.fabric.client.model;

import com.mojang.serialization.MapCodec;
import com.retromod.legacyapi.fabric.client.renderer.LegacyFabricModels;
import com.retromod.legacyapi.minecraft.client.renderer.LegacyBakedModelCalls;
import com.retromod.shim.common.embedded.LegacyLog;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.client.renderer.v1.render.FabricLayerRenderState;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.dispatch.BlockModelRotation;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.resources.model.ResolvableModel;
import net.minecraft.client.resources.model.UnbakedModel;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4fc;

/** An old mod's model drawn as a 26.x item model, through the item layer's quad emitter. */
final class LegacyItemModels {

    // Old model classes whose code failed while an item was drawn; their items draw the host's model from then on.
    private static final Set<Class<?>> FAILED = ConcurrentHashMap.newKeySet();

    private LegacyItemModels() {}

    /** The unbaked item model for an old model; the host's own item model stays as the fallback. */
    static final class Unbaked implements ItemModel.Unbaked {
        private final UnbakedModel old;
        private final Identifier id;
        private final LegacyModelClaims claims;
        private final ItemModel.Unbaked fallback;

        Unbaked(UnbakedModel old, Identifier id, LegacyModelClaims claims, ItemModel.Unbaked fallback) {
            this.old = old;
            this.id = id;
            this.claims = claims;
            this.fallback = fallback;
        }

        @Override
        public MapCodec<? extends ItemModel.Unbaked> type() {
            return fallback.type();
        }

        @Override
        public void resolveDependencies(ResolvableModel.Resolver resolver) {
            Set<Identifier> seen = new HashSet<>();
            if (id != null) seen.add(id);
            LegacyUnbakedModels.markDependencies(old, resolver, claims, seen);
            fallback.resolveDependencies(resolver);
        }

        @Override
        public ItemModel bake(ItemModel.BakingContext context, Matrix4fc transformation) {
            LegacyClaimingBaker baker = new LegacyClaimingBaker(context.blockModelBaker(), claims);
            BlockStateModel model = LegacyUnbakedModels.bakeOld(old, baker, LegacyUnbakedModels.sprites(baker, id),
                    BlockModelRotation.IDENTITY, id);
            // The host's own item model is baked too, for an old model whose code fails while it is drawn.
            ItemModel host = fallback.bake(context, transformation);
            return model != null ? new Baked(model, transformation, host) : host;
        }
    }

    /** Draws the old model into one item layer each frame, with the old model's display transforms, else the host's model. */
    static final class Baked implements ItemModel {
        private final BlockStateModel model;
        private final Matrix4fc transformation;
        private final ItemModel host;

        Baked(BlockStateModel model, Matrix4fc transformation, ItemModel host) {
            this.model = model;
            this.transformation = transformation;
            this.host = host;
        }

        @Override
        public void update(ItemStackRenderState renderState, ItemStack stack, ItemModelResolver resolver, ItemDisplayContext displayContext,
                ClientLevel level, ItemOwner owner, int seed) {
            if (!FAILED.contains(model.getClass())) {
                try {
                    // The old model's own answers come first, so one that fails leaves the render state untouched.
                    boolean animated = !LegacyFabricModels.isVanillaAdapter(model);
                    var itemTransform = LegacyBakedModelCalls.getTransforms(model).getTransform(displayContext);
                    boolean blockLight = LegacyBakedModelCalls.usesBlockLight(model);
                    renderState.appendModelIdentityElement(this);
                    // An old model that draws per stack cannot be cached by its identity alone.
                    if (animated) renderState.setAnimated();
                    ItemStackRenderState.LayerRenderState layer = renderState.newLayer();
                    layer.setItemTransform(itemTransform);
                    layer.setLocalTransform(transformation);
                    layer.setUsesBlockLight(blockLight);
                    layer.setParticleMaterial(model.particleMaterial());
                    QuadEmitter emitter = ((FabricLayerRenderState) (Object) layer).emitter();
                    LegacyFabricModels.emitItemModel(model, emitter, stack, displayContext, RandomSource.create(seed));
                    return;
                } catch (RuntimeException | LinkageError failure) {
                    failed(model.getClass(), failure);
                }
            }
            host.update(renderState, stack, resolver, displayContext, level, owner, seed);
        }
    }

    private static void failed(Class<?> type, Throwable failure) {
        if (FAILED.add(type)) {
            LegacyLog.warn("The old model " + type.getName() + " failed while drawing an item (" + failure + "), so Retromod draws"
                    + " that item with its own 26.x model from now on.");
        }
    }
}
