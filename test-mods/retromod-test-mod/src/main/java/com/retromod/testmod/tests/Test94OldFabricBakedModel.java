/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.renderer.v1.Renderer;
import net.fabricmc.fabric.api.renderer.v1.RendererAccess;
import net.fabricmc.fabric.api.renderer.v1.material.RenderMaterial;
import net.fabricmc.fabric.api.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.renderer.v1.model.FabricBakedModel;
import net.fabricmc.fabric.api.renderer.v1.render.RenderContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.render.model.Baker;
import net.minecraft.client.render.model.ModelBakeSettings;
import net.minecraft.client.render.model.UnbakedModel;
import net.minecraft.client.render.model.json.ModelOverrideList;
import net.minecraft.client.render.model.json.ModelTransformation;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.util.SpriteIdentifier;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.BlockRenderView;

/** A block and item drawn by an old FabricBakedModel from a 1.20.1 model resolver; Test95 checks the draw. */
public final class Test94OldFabricBakedModel implements Test {

    public static final Identifier ID = new Identifier("retromod_test_mod", "frapi_block");
    public static final Identifier BLOCK_MODEL = new Identifier("retromod_test_mod", "block/frapi_block");
    public static final Identifier ITEM_MODEL = new Identifier("retromod_test_mod", "item/frapi_block");
    public static Block block;
    /** Set the first time the old emitBlockQuads runs, which only a client drawing the block does. */
    public static volatile boolean emittedBlock;
    public static volatile boolean emittedItem;

    @Override
    public String description() {
        return "an old FabricBakedModel block and item from a 1.20.1 model resolver";
    }

    @Override
    public TestResult run() {
        block = Registry.register(Registries.BLOCK, ID, new Block(AbstractBlock.Settings.create().strength(1.0f)));
        Registry.register(Registries.ITEM, ID, new BlockItem(block, new Item.Settings()));
        if (FabricLoader.getInstance().getEnvironmentType() != EnvType.CLIENT) return TestResult.success();
        try {
            Client.register();
            return TestResult.success();
        } catch (Throwable t) {
            return TestResult.fail(t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /** Client classes, loaded only on a client. */
    static final class Client {
        static void register() {
            ModelLoadingPlugin.register(context -> context.resolveModel().register(resolver ->
                    resolver.id().equals(BLOCK_MODEL) || resolver.id().equals(ITEM_MODEL) ? new Unbaked() : null));
        }
    }

    /** The unbaked model: a diamond block sprite, baked into the emitting model. */
    static final class Unbaked implements UnbakedModel {
        @Override
        public Collection<Identifier> getModelDependencies() {
            return List.of();
        }

        @Override
        public void setParents(Function<Identifier, UnbakedModel> modelLoader) {
        }

        @Override
        public BakedModel bake(Baker baker, Function<SpriteIdentifier, Sprite> textureGetter, ModelBakeSettings settings, Identifier id) {
            Sprite sprite = textureGetter.apply(new SpriteIdentifier(PlayerScreenHandler.BLOCK_ATLAS_TEXTURE,
                    new Identifier("minecraft", "block/diamond_block")));
            return new Baked(sprite);
        }
    }

    /** Emits a cube with an emissive material through the old emitter; it has no plain quads. */
    static final class Baked implements BakedModel, FabricBakedModel {
        private final Sprite sprite;

        Baked(Sprite sprite) {
            this.sprite = sprite;
        }

        @Override
        public boolean isVanillaAdapter() {
            return false;
        }

        @Override
        public void emitBlockQuads(BlockRenderView view, BlockState state, BlockPos pos, Supplier<Random> random, RenderContext context) {
            emittedBlock = true;
            emitCube(context);
        }

        @Override
        public void emitItemQuads(ItemStack stack, Supplier<Random> random, RenderContext context) {
            emittedItem = true;
            emitCube(context);
        }

        private void emitCube(RenderContext context) {
            Renderer renderer = RendererAccess.INSTANCE.getRenderer();
            RenderMaterial glowing = renderer.materialFinder().emissive(true).find();
            QuadEmitter emitter = context.getEmitter();
            for (Direction side : Direction.values()) {
                if (context.isFaceCulled(side)) continue;
                emitter.square(side, 0f, 0f, 1f, 1f, 0f);
                emitter.spriteBake(sprite, MutableQuadView.BAKE_LOCK_UV);
                emitter.color(-1, -1, -1, -1);
                emitter.material(glowing);
                emitter.emit();
            }
        }

        @Override
        public List<BakedQuad> getQuads(BlockState state, Direction face, Random random) {
            return List.of();
        }

        @Override
        public boolean useAmbientOcclusion() {
            return true;
        }

        @Override
        public boolean hasDepth() {
            return true;
        }

        @Override
        public boolean isSideLit() {
            return true;
        }

        @Override
        public boolean isBuiltin() {
            return false;
        }

        @Override
        public Sprite getParticleSprite() {
            return sprite;
        }

        @Override
        public ModelTransformation getTransformation() {
            return ModelTransformation.NONE;
        }

        @Override
        public ModelOverrideList getOverrides() {
            return ModelOverrideList.EMPTY;
        }
    }
}
