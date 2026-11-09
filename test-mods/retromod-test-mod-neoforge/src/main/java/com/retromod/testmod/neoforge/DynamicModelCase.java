/*
 * Retromod Test Mod (NeoForge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.neoforge;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.ChunkRenderTypeSet;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.IDynamicBakedModel;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelProperty;
import org.slf4j.LoggerFactory;

/** A dynamic model, a wrapper and NeoForge's model data calls in a reload listener, as Sophisticated Backpacks builds its shapes. Client only. */
final class DynamicModelCase {

    private static final String LOADS = "A geometry loader listener registers, and a dynamic model class loads for a shape check (construction"
            + " and client reload)";
    private static final String CALLS = "Model data calls answer from a dynamic model, a wrapper and the block's own model (client reload)";
    private static final ModelProperty<String> COLOR = new ModelProperty<>();

    private DynamicModelCase() {}

    static void register(IEventBus modBus) {
        try {
            // Sophisticated Backpacks registers its backpack loader here; NeoForge 26.x posts no such event.
            modBus.addListener(DynamicModelCase::onGeometryLoaders);
            modBus.addListener(DynamicModelCase::onReloadListeners);
        } catch (Throwable t) {
            log(LOADS, "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    // Registers nothing: the listener only has to register, as NeoForge 26.x never posts the old event.
    private static void onGeometryLoaders(ModelEvent.RegisterGeometryLoaders event) {
    }

    private static void onReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) manager -> {
            BlockState stone = Blocks.STONE.defaultBlockState();
            BakedModel blockModel;
            try {
                blockModel = Minecraft.getInstance().getBlockRenderer().getBlockModel(stone);
                // The block's own model is no backpack, so the shapes fall back to the server's.
                log(LOADS, blockModel instanceof Pack ? "fail: the block model read as the dynamic model" : "success");
            } catch (Throwable t) {
                log(LOADS, "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage());
                return;
            }
            log(CALLS, calls(stone, blockModel));
        });
    }

    private static String calls(BlockState stone, BakedModel blockModel) {
        try {
            RandomSource random = RandomSource.create(42L);
            List<BakedQuad> stoneQuads = blockModel.getQuads(stone, Direction.UP, random, ModelData.EMPTY, null);
            if (stoneQuads.isEmpty()) return "skipped: block models were not loaded yet";
            BakedModel pack = new Pack(stoneQuads);
            ModelData blue = ModelData.builder().with(COLOR, "blue").build();
            List<BakedQuad> cloth = pack.getQuads(stone, null, random, blue, RenderType.cutout());
            ChunkRenderTypeSet layers = pack.getRenderTypes(stone, random, ModelData.EMPTY);
            ModelData adjusted = pack.getModelData(null, BlockPos.ZERO, stone, ModelData.EMPTY);
            if (cloth.size() != stoneQuads.size() || !layers.contains(RenderType.translucent()) || !"red".equals(adjusted.get(COLOR))) {
                return "fail: pack quads " + cloth.size() + ", layers " + layers.asList() + ", data " + adjusted.get(COLOR);
            }
            // The block's own model answers by its quads; a wrapper over it adds its own.
            ChunkRenderTypeSet stoneLayers = blockModel.getRenderTypes(stone, random, ModelData.EMPTY);
            BakedModel shining = new Shining(blockModel);
            List<BakedQuad> wrapped = shining.getQuads(stone, Direction.UP, random, ModelData.EMPTY, null);
            if (stoneLayers.isEmpty() || wrapped.size() != stoneQuads.size() + 1 || shining.getParticleIcon(ModelData.EMPTY) == null) {
                return "fail: stone layers " + stoneLayers.asList() + ", stone quads " + stoneQuads.size() + ", wrapped " + wrapped.size();
            }
            // Patchouli's book overrides call the list constructor with no overrides and keep the model they are given.
            if (new BookOverrides().resolve(pack, net.minecraft.world.item.ItemStack.EMPTY, null, null, 0) != pack) {
                return "fail: the overrides built from a list did not keep the model";
            }
            // On NeoForge 26.x the old model is also Minecraft's block model, which draws one part per layer it names.
            Method collect = hostCollectParts(pack);
            if (collect != null) {
                List<Object> parts = new ArrayList<>();
                collect.invoke(pack, RandomSource.create(42L), parts);
                if (parts.size() != 2) return "fail: Minecraft drew " + parts.size() + " parts of the dynamic model";
            }
            return "success";
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            return "fail: " + cause.getClass().getSimpleName() + ": " + cause.getMessage();
        }
    }

    // 26.x's collectParts(RandomSource, List); NeoForge 1.21.1 has none, where the old model draws itself.
    private static Method hostCollectParts(Object model) {
        for (Method method : model.getClass().getMethods()) {
            if (method.getName().equals("collectParts") && method.getParameterCount() == 2) return method;
        }
        return null;
    }

    private static void log(String what, String result) {
        LoggerFactory.getLogger("Retromod-Test-NeoForge").info("[Retromod-Test-NeoForge] {}: {}", what, result);
    }

    /** A backpack: the same quads in two layers, and red model data when the block entity left none. */
    static final class Pack implements IDynamicBakedModel {
        private final List<BakedQuad> quads;

        Pack(List<BakedQuad> quads) {
            this.quads = quads;
        }

        @Override
        public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource random, ModelData data, RenderType renderType) {
            return side == null ? quads : List.of();
        }

        @Override
        public ChunkRenderTypeSet getRenderTypes(BlockState state, RandomSource random, ModelData data) {
            return ChunkRenderTypeSet.of(RenderType.cutout(), RenderType.translucent());
        }

        @Override
        public ModelData getModelData(BlockAndTintGetter level, BlockPos pos, BlockState state, ModelData data) {
            return data.get(COLOR) != null ? data : data.derive().with(COLOR, "red").build();
        }

        @Override
        public boolean useAmbientOcclusion() {
            return true;
        }

        @Override
        public boolean isGui3d() {
            return true;
        }

        @Override
        public boolean usesBlockLight() {
            return true;
        }

        @Override
        public boolean isCustomRenderer() {
            return false;
        }

        @Override
        public TextureAtlasSprite getParticleIcon() {
            return Minecraft.getInstance().getBlockRenderer().getBlockModelShaper().getParticleIcon(Blocks.STONE.defaultBlockState());
        }

        @Override
        public ItemTransforms getTransforms() {
            return ItemTransforms.NO_TRANSFORMS;
        }

        @Override
        public ItemOverrides getOverrides() {
            return ItemOverrides.EMPTY;
        }
    }

    /** Overrides built from a model's override list, as Patchouli's book model builds them with none. */
    static final class BookOverrides extends ItemOverrides {
        BookOverrides() {
            super(null, null, List.of());
        }
    }

    /** A wrapper that adds the first quad of its wrapped model's top again, as Create's connected textures add quads. */
    static final class Shining extends BakedModelWrapper<BakedModel> {
        Shining(BakedModel wrapped) {
            super(wrapped);
        }

        @Override
        public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource random, ModelData data, RenderType renderType) {
            List<BakedQuad> quads = new ArrayList<>(super.getQuads(state, side, random, data, renderType));
            if (!quads.isEmpty()) quads.add(quads.get(0));
            return quads;
        }
    }
}
