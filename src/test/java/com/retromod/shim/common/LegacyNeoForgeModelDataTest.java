/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.neoforge.LegacyNeoForgeBakedModelBridge;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.tree.ClassNode;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.retromod.shim.common.BridgeHarness.*;
import static org.junit.jupiter.api.Assertions.*;

/** NeoForge 1.21.1 dynamic models, a wrapper and model data calls, transformed for NeoForge 26.x and run over a stand-in host. */
class LegacyNeoForgeModelDataTest {

    private static final String CHUNK = "testhost/Chunk";
    private static final String MOD = "mod/Models";

    @TempDir
    static Path dir;

    private static Map<String, byte[]> mod;
    private static Map<String, byte[]> host;

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @BeforeAll
    static void compileShapes() throws Exception {
        mod = only(compile(dir.resolve("old"), oldSources()), "mod/");
        host = compile(dir.resolve("host"), hostSources());
    }

    @BeforeEach
    void neoForge() {
        McReflect.setForceForge(false);
        McReflect.setForceNeoForge(true);
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("Sophisticated Backpacks' shape check loads its dynamic model class and falls back for Minecraft's model")
    void shapeCheckLoads() throws Throwable {
        Run game = new Run(host, transform("26.3", bridge(), mod));
        assertEquals("default", game.call(MOD, "shape", game.call(CHUNK, "stone")), "a host model is not the backpack model");
        assertEquals("backpack", game.call(MOD, "shape", game.call(MOD, "pack")));
    }

    @Test
    @DisplayName("Model data calls answer from an old model's own members and from Minecraft's models by their quads")
    void modelDataCalls() throws Throwable {
        Run game = new Run(host, transform("26.3", bridge(), mod));
        assertEquals("quads [cloth plain], layers [cutout, translucent], particle pack_particle, data red, occlusion DEFAULT",
                game.call(MOD, "ask", game.call(MOD, "pack")), "the backpack's own members");
        assertEquals("quads [stone_cutout], layers [solid, cutout], particle stone_particle, data none, occlusion DEFAULT",
                game.call(MOD, "ask", game.call(CHUNK, "stone")), "Minecraft's model: quads of the cutout layer, layers from its quads");
        assertEquals("quads [stone_cutout, gem], layers [solid, cutout], particle stone_particle, data none, occlusion DEFAULT",
                game.call(MOD, "ask", game.call(MOD, "tinted", game.call(CHUNK, "stone"))),
                "a wrapper's change, the rest from Minecraft's; the old wrapper asked the wrapped model about occlusion per layer");
    }

    @Test
    @DisplayName("Minecraft draws an old dynamic model per layer with the block entity's model data")
    void hostDrawsOldModel() throws Throwable {
        Run game = new Run(host, transform("26.3", bridge(), mod));
        assertEquals("[cutout ao: cloth blue, translucent ao: glass blue] particle pack_particle flags 1",
                game.call(CHUNK, "draw", game.call(MOD, "pack")), "one part per layer the model names, with the level's data adjusted by the model");
        assertEquals("[solid ao: stone_solid, cutout ao: stone_cutout gem] particle stone_particle flags 0",
                game.call(CHUNK, "draw", game.call(MOD, "tinted", game.call(CHUNK, "stone"))), "a wrapper over Minecraft's model");
    }

    @Test
    @DisplayName("An old model that throws while Minecraft draws it draws nothing, once warned, and no throw reaches the chunk")
    void brokenModelDrawsNothing() throws Throwable {
        Run game = new Run(host, transform("26.3", bridge(), mod));
        Object broken = game.call(MOD, "broken");
        assertEquals("[] particle none flags 0", game.call(CHUNK, "draw", broken));
        assertEquals("[] particle none flags 0", game.call(CHUNK, "draw", broken), "it stays off");
    }

    @Test
    @DisplayName("An old model whose overrides call each default through super reaches the default, not its own override again")
    void superCallsReachDefaults() throws Throwable {
        Run game = new Run(host, transform("26.3", bridge(), mod));
        assertEquals("quads [plain, extra], same true, item types [translucent item], passes 1, occlusion DEFAULT, data true,"
                + " particle super_particle, layers [solid], forge occlusion true true", game.call(MOD, "superAsk", game.call(MOD, "supers")),
                "as CreativeCore's model ends its applyTransform in BakedModel.super.applyTransform");
    }

    @Test
    @DisplayName("Asked without a block in place, an old model gets empty data and stays on for the blocks it draws later")
    void levelLessCallsKeepModelOn() throws Throwable {
        Run game = new Run(host, transform("26.3", bridge(), mod));
        Object strict = game.call(MOD, "strict");
        assertEquals("[cutout ao: cloth plain, translucent ao: glass plain] particle pack_particle flags 1", game.call(CHUNK, "plain", strict),
                "the model's own getModelData, which reads the level as Sophisticated Backpacks' does, is not asked");
        assertEquals("[cutout ao: cloth blue, translucent ao: glass blue] particle pack_particle flags 1", game.call(CHUNK, "draw", strict));
    }

    // The item draw bridge, which makes old BakedModel the block model, and this bridge, over a NeoForge 26.3 client.
    private static Consumer<RetromodTransformer> bridge() {
        Set<String> present = Set.of("net/neoforged/neoforge/common/NeoForge", "net/neoforged/neoforge/model/data/ModelData",
                HostBuffers.CLIENT_ANCHOR);
        Function<String, ClassNode> reader = name -> present.contains(name) ? new ClassNode() : null;
        return t -> {
            RenderBufferSynthetics.register(t);
            LegacyItemDrawBridge.register(t, reader);
            // NeoForge's own moves, such as its TriState to Minecraft's, which the 1.21.2 shim registers.
            com.retromod.shim.neoforge.NeoForgeMovedClasses.register(t);
            LegacyNeoForgeBakedModelBridge.register(t, reader);
        };
    }

    /** The classes both views share, in 26.x's names. */
    private static Map<String, String> shared() {
        Map<String, String> sources = new HashMap<>();
        sources.put("net/minecraft/core/Direction.java", "package net.minecraft.core; public enum Direction { DOWN, UP, NORTH, SOUTH, WEST, EAST }");
        sources.put("net/minecraft/core/BlockPos.java", """
                package net.minecraft.core;
                public class BlockPos { public final int x; public BlockPos(int x) { this.x = x; } }""");
        sources.put("net/minecraft/util/RandomSource.java", """
                package net.minecraft.util;
                public interface RandomSource {
                    void setSeed(long seed);
                    long nextLong();
                    static RandomSource create(long seed) {
                        return new RandomSource() {
                            long value = seed;
                            public void setSeed(long next) { value = next; }
                            public long nextLong() { return value++; }
                        };
                    }
                }""");
        sources.put("net/minecraft/world/level/block/Block.java", "package net.minecraft.world.level.block; public class Block {}");
        sources.put("net/minecraft/world/level/block/state/BlockState.java", """
                package net.minecraft.world.level.block.state;
                public class BlockState {
                    final net.minecraft.world.level.block.Block block = new net.minecraft.world.level.block.Block();
                    public net.minecraft.world.level.block.Block getBlock() { return block; }
                }""");
        sources.put("net/minecraft/client/renderer/chunk/ChunkSectionLayer.java",
                "package net.minecraft.client.renderer.chunk; public enum ChunkSectionLayer { SOLID, CUTOUT, TRANSLUCENT }");
        sources.put("net/minecraft/client/renderer/rendertype/RenderType.java", """
                package net.minecraft.client.renderer.rendertype;
                public class RenderType {
                    final String name;
                    public RenderType(String name) { this.name = name; }
                    @Override public String toString() { return name; }
                }""");
        sources.put("net/minecraft/client/renderer/rendertype/RenderTypes.java", """
                package net.minecraft.client.renderer.rendertype;
                public class RenderTypes {
                    static final RenderType SOLID = new RenderType("solid"), CUTOUT = new RenderType("cutout"),
                            TRANSLUCENT = new RenderType("translucent");
                    public static RenderType solidMovingBlock() { return SOLID; }
                    public static RenderType cutoutMovingBlock() { return CUTOUT; }
                    public static RenderType translucentMovingBlock() { return TRANSLUCENT; }
                }""");
        sources.put("net/minecraft/client/renderer/texture/TextureAtlasSprite.java", """
                package net.minecraft.client.renderer.texture;
                public class TextureAtlasSprite {
                    final String name;
                    public TextureAtlasSprite(String name) { this.name = name; }
                    @Override public String toString() { return name; }
                }""");
        sources.put("net/minecraft/client/resources/model/geometry/BakedQuad.java", """
                package net.minecraft.client.resources.model.geometry;
                import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
                public class BakedQuad {
                    public final String name;
                    private final MaterialInfo info;
                    public BakedQuad(String name, ChunkSectionLayer layer) { this.name = name; this.info = new MaterialInfo(layer); }
                    public MaterialInfo materialInfo() { return info; }
                    @Override public String toString() { return name; }
                    public static final class MaterialInfo {
                        private final ChunkSectionLayer layer;
                        MaterialInfo(ChunkSectionLayer layer) { this.layer = layer; }
                        public ChunkSectionLayer layer() { return layer; }
                        public boolean isTinted() { return false; }
                        public net.minecraft.client.renderer.rendertype.RenderType itemRenderType() { return null; }
                    }
                }""");
        sources.put("net/minecraft/client/resources/model/cuboid/ItemTransforms.java", """
                package net.minecraft.client.resources.model.cuboid;
                public class ItemTransforms {
                    public static final ItemTransforms NO_TRANSFORMS = new ItemTransforms();
                    public ItemTransform getTransform(net.minecraft.world.item.ItemDisplayContext context) { return new ItemTransform(); }
                }""");
        sources.put("net/minecraft/client/resources/model/cuboid/ItemTransform.java", """
                package net.minecraft.client.resources.model.cuboid;
                public class ItemTransform { public void apply(boolean leftHand, com.mojang.blaze3d.vertex.PoseStack.Pose pose) {} }""");
        sources.put("net/minecraft/world/item/ItemStack.java", "package net.minecraft.world.item; public class ItemStack {}");
        sources.put("net/minecraft/world/item/ItemDisplayContext.java", "package net.minecraft.world.item; public enum ItemDisplayContext { GUI }");
        sources.put("com/mojang/blaze3d/vertex/PoseStack.java", """
                package com.mojang.blaze3d.vertex;
                public class PoseStack {
                    public Pose last() { return new Pose(); }
                    public void translate(float x, float y, float z) {}
                    public static final class Pose {}
                }""");
        sources.put("net/neoforged/neoforge/model/data/ModelProperty.java", """
                package net.neoforged.neoforge.model.data;
                public class ModelProperty<T> {}""");
        sources.put("net/neoforged/neoforge/model/data/ModelData.java", """
                package net.neoforged.neoforge.model.data;
                public final class ModelData {
                    public static final ModelData EMPTY = new ModelData(java.util.Map.of());
                    private final java.util.Map<ModelProperty<?>, Object> values;
                    private ModelData(java.util.Map<ModelProperty<?>, Object> values) { this.values = values; }
                    @SuppressWarnings("unchecked") public <T> T get(ModelProperty<T> property) { return (T) values.get(property); }
                    public static <T> ModelData of(ModelProperty<T> property, T value) { return new ModelData(java.util.Map.of(property, value)); }
                }""");
        return sources;
    }

    /** The NeoForge 1.21.1 view an old mod was compiled against, with the mod itself. */
    private static Map<String, String> oldSources() {
        Map<String, String> old = new HashMap<>(shared());
        old.put("net/minecraft/client/renderer/block/BlockAndTintGetter.java",
                "package net.minecraft.client.renderer.block; public interface BlockAndTintGetter {}");
        old.put("net/minecraft/client/renderer/block/model/ItemOverrides.java", """
                package net.minecraft.client.renderer.block.model;
                public class ItemOverrides { public static final ItemOverrides EMPTY = new ItemOverrides(); }""");
        old.put("net/neoforged/neoforge/client/ChunkRenderTypeSet.java", """
                package net.neoforged.neoforge.client;
                public class ChunkRenderTypeSet implements Iterable<net.minecraft.client.renderer.rendertype.RenderType> {
                    public static ChunkRenderTypeSet of(net.minecraft.client.renderer.rendertype.RenderType... types) { return null; }
                    public boolean contains(net.minecraft.client.renderer.rendertype.RenderType type) { return false; }
                    public java.util.Iterator<net.minecraft.client.renderer.rendertype.RenderType> iterator() { return null; }
                }""");
        old.put("net/neoforged/neoforge/common/util/TriState.java",
                "package net.neoforged.neoforge.common.util; public enum TriState { TRUE, FALSE, DEFAULT }");
        old.put("net/minecraft/client/resources/model/BakedModel.java", """
                package net.minecraft.client.resources.model;
                import java.util.List;
                import net.minecraft.client.renderer.rendertype.RenderType;
                import net.minecraft.client.resources.model.geometry.BakedQuad;
                import net.minecraft.core.*;
                import net.minecraft.util.RandomSource;
                import net.minecraft.world.level.block.state.BlockState;
                import net.neoforged.neoforge.model.data.ModelData;
                public interface BakedModel {
                    List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource random);
                    boolean useAmbientOcclusion();
                    boolean isGui3d();
                    boolean usesBlockLight();
                    boolean isCustomRenderer();
                    net.minecraft.client.renderer.texture.TextureAtlasSprite getParticleIcon();
                    net.minecraft.client.resources.model.cuboid.ItemTransforms getTransforms();
                    net.minecraft.client.renderer.block.model.ItemOverrides getOverrides();
                    // NeoForge 1.21.1's IBakedModelExtension, which BakedModel extends.
                    default List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource random, ModelData data, RenderType renderType) {
                        return getQuads(state, side, random);
                    }
                    default net.neoforged.neoforge.common.util.TriState useAmbientOcclusion(BlockState state, ModelData data, RenderType type) {
                        return null;
                    }
                    default ModelData getModelData(net.minecraft.client.renderer.block.BlockAndTintGetter level, BlockPos pos, BlockState state,
                            ModelData data) { return data; }
                    default net.minecraft.client.renderer.texture.TextureAtlasSprite getParticleIcon(ModelData data) { return null; }
                    default net.neoforged.neoforge.client.ChunkRenderTypeSet getRenderTypes(BlockState state, RandomSource random, ModelData data) {
                        return null;
                    }
                    default BakedModel applyTransform(net.minecraft.world.item.ItemDisplayContext context, com.mojang.blaze3d.vertex.PoseStack pose,
                            boolean leftHand) { return this; }
                    default List<RenderType> getRenderTypes(net.minecraft.world.item.ItemStack stack, boolean fabulous) { return List.of(); }
                    default List<BakedModel> getRenderPasses(net.minecraft.world.item.ItemStack stack, boolean fabulous) { return List.of(this); }
                    // Forge 1.20.1's occlusion questions, which a Forge mod on NeoForge still overrides.
                    default boolean useAmbientOcclusion(BlockState state) { return useAmbientOcclusion(); }
                    default boolean useAmbientOcclusion(BlockState state, RenderType type) { return useAmbientOcclusion(state); }
                }""");
        old.put("net/neoforged/neoforge/client/model/IDynamicBakedModel.java", """
                package net.neoforged.neoforge.client.model;
                import java.util.List;
                import net.minecraft.client.resources.model.geometry.BakedQuad;
                public interface IDynamicBakedModel extends net.minecraft.client.resources.model.BakedModel {
                    default List<BakedQuad> getQuads(net.minecraft.world.level.block.state.BlockState state, net.minecraft.core.Direction side,
                            net.minecraft.util.RandomSource random) { return null; }
                    List<BakedQuad> getQuads(net.minecraft.world.level.block.state.BlockState state, net.minecraft.core.Direction side,
                            net.minecraft.util.RandomSource random, net.neoforged.neoforge.model.data.ModelData data,
                            net.minecraft.client.renderer.rendertype.RenderType renderType);
                }""");
        old.put("net/neoforged/neoforge/client/model/BakedModelWrapper.java", """
                package net.neoforged.neoforge.client.model;
                import java.util.List;
                import net.minecraft.client.resources.model.BakedModel;
                import net.minecraft.client.resources.model.geometry.BakedQuad;
                public abstract class BakedModelWrapper<T extends BakedModel> implements BakedModel {
                    protected final T originalModel;
                    public BakedModelWrapper(T originalModel) { this.originalModel = originalModel; }
                    public List<BakedQuad> getQuads(net.minecraft.world.level.block.state.BlockState state, net.minecraft.core.Direction side,
                            net.minecraft.util.RandomSource random) { return null; }
                    public List<BakedQuad> getQuads(net.minecraft.world.level.block.state.BlockState state, net.minecraft.core.Direction side,
                            net.minecraft.util.RandomSource random, net.neoforged.neoforge.model.data.ModelData data,
                            net.minecraft.client.renderer.rendertype.RenderType renderType) { return null; }
                    public boolean useAmbientOcclusion() { return true; }
                    public boolean isGui3d() { return true; }
                    public boolean usesBlockLight() { return true; }
                    public boolean isCustomRenderer() { return false; }
                    public net.minecraft.client.renderer.texture.TextureAtlasSprite getParticleIcon() { return null; }
                    public net.minecraft.client.resources.model.cuboid.ItemTransforms getTransforms() { return null; }
                    public net.minecraft.client.renderer.block.model.ItemOverrides getOverrides() { return null; }
                }""");
        // As Sophisticated Backpacks' backpack model: cloth in the cutout layer, glass in the translucent one, colored by model data.
        old.put("mod/Backpack.java", """
                package mod;
                import java.util.List;
                import net.minecraft.client.renderer.rendertype.*;
                import net.minecraft.client.resources.model.geometry.BakedQuad;
                import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
                import net.minecraft.core.*;
                import net.minecraft.util.RandomSource;
                import net.minecraft.world.level.block.state.BlockState;
                import net.neoforged.neoforge.client.ChunkRenderTypeSet;
                import net.neoforged.neoforge.model.data.*;
                public class Backpack implements net.neoforged.neoforge.client.model.IDynamicBakedModel {
                    static final ModelProperty<String> COLOR = new ModelProperty<>();
                    public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource random, ModelData data, RenderType type) {
                        if (side != null) return List.of();
                        String color = data.get(COLOR) == null ? "plain" : data.get(COLOR);
                        if (type == RenderTypes.translucentMovingBlock()) return List.of(new BakedQuad("glass " + color, ChunkSectionLayer.TRANSLUCENT));
                        return List.of(new BakedQuad("cloth " + color, ChunkSectionLayer.CUTOUT));
                    }
                    public ChunkRenderTypeSet getRenderTypes(BlockState state, RandomSource random, ModelData data) {
                        return ChunkRenderTypeSet.of(RenderTypes.cutoutMovingBlock(), RenderTypes.translucentMovingBlock());
                    }
                    // The block entity's data says blue; asked with none, the backpack is red.
                    public ModelData getModelData(net.minecraft.client.renderer.block.BlockAndTintGetter level, BlockPos pos, BlockState state,
                            ModelData data) {
                        return data.get(COLOR) != null ? data : ModelData.of(COLOR, "red");
                    }
                    public boolean useAmbientOcclusion() { return true; }
                    public boolean isGui3d() { return true; }
                    public boolean usesBlockLight() { return true; }
                    public boolean isCustomRenderer() { return false; }
                    public net.minecraft.client.renderer.texture.TextureAtlasSprite getParticleIcon() {
                        return new net.minecraft.client.renderer.texture.TextureAtlasSprite("pack_particle");
                    }
                    public net.minecraft.client.resources.model.cuboid.ItemTransforms getTransforms() { return null; }
                    public net.minecraft.client.renderer.block.model.ItemOverrides getOverrides() {
                        return net.minecraft.client.renderer.block.model.ItemOverrides.EMPTY;
                    }
                }""");
        // As Create's wrappers: one more quad over the wrapped model's, and no ambient occlusion.
        old.put("mod/Tinted.java", """
                package mod;
                import java.util.*;
                import net.minecraft.client.resources.model.BakedModel;
                import net.minecraft.client.resources.model.geometry.BakedQuad;
                public class Tinted extends net.neoforged.neoforge.client.model.BakedModelWrapper<BakedModel> {
                    public Tinted(BakedModel wrapped) { super(wrapped); }
                    @Override
                    public List<BakedQuad> getQuads(net.minecraft.world.level.block.state.BlockState state, net.minecraft.core.Direction side,
                            net.minecraft.util.RandomSource random, net.neoforged.neoforge.model.data.ModelData data,
                            net.minecraft.client.renderer.rendertype.RenderType type) {
                        List<BakedQuad> quads = new ArrayList<>(super.getQuads(state, side, random, data, type));
                        if (side == null && type == net.minecraft.client.renderer.rendertype.RenderTypes.cutoutMovingBlock()) {
                            quads.add(new BakedQuad("gem", net.minecraft.client.renderer.chunk.ChunkSectionLayer.CUTOUT));
                        }
                        return quads;
                    }
                    @Override public boolean useAmbientOcclusion() { return false; }
                    public BakedModel wrapped() { return originalModel; }
                }""");
        old.put("mod/Broken.java", """
                package mod;
                public class Broken extends Backpack {
                    @Override
                    public java.util.List<net.minecraft.client.resources.model.geometry.BakedQuad> getQuads(net.minecraft.world.level.block.state.BlockState s,
                            net.minecraft.core.Direction side, net.minecraft.util.RandomSource r, net.neoforged.neoforge.model.data.ModelData d,
                            net.minecraft.client.renderer.rendertype.RenderType t) { throw new IllegalStateException("no backpack"); }
                }""");
        // As CreativeCore's model: each override ends in the old interface's default, through super.
        old.put("mod/Supers.java", """
                package mod;
                import java.util.*;
                import net.minecraft.client.renderer.rendertype.RenderType;
                import net.minecraft.client.resources.model.BakedModel;
                import net.minecraft.client.resources.model.geometry.BakedQuad;
                import net.minecraft.core.*;
                import net.minecraft.util.RandomSource;
                import net.minecraft.world.level.block.state.BlockState;
                import net.neoforged.neoforge.model.data.ModelData;
                public class Supers implements BakedModel {
                    public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource random) {
                        return List.of(new BakedQuad("plain", net.minecraft.client.renderer.chunk.ChunkSectionLayer.SOLID));
                    }
                    @Override public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource random, ModelData data, RenderType type) {
                        List<BakedQuad> quads = new ArrayList<>(BakedModel.super.getQuads(state, side, random, data, type));
                        quads.add(new BakedQuad("extra", net.minecraft.client.renderer.chunk.ChunkSectionLayer.SOLID));
                        return quads;
                    }
                    @Override public BakedModel applyTransform(net.minecraft.world.item.ItemDisplayContext context,
                            com.mojang.blaze3d.vertex.PoseStack pose, boolean leftHand) {
                        return BakedModel.super.applyTransform(context, pose, leftHand);
                    }
                    @Override public List<RenderType> getRenderTypes(net.minecraft.world.item.ItemStack stack, boolean fabulous) {
                        return BakedModel.super.getRenderTypes(stack, fabulous);
                    }
                    @Override public List<BakedModel> getRenderPasses(net.minecraft.world.item.ItemStack stack, boolean fabulous) {
                        return BakedModel.super.getRenderPasses(stack, fabulous);
                    }
                    @Override public net.neoforged.neoforge.common.util.TriState useAmbientOcclusion(BlockState state, ModelData data, RenderType type) {
                        return BakedModel.super.useAmbientOcclusion(state, data, type);
                    }
                    @Override public ModelData getModelData(net.minecraft.client.renderer.block.BlockAndTintGetter level, BlockPos pos, BlockState state,
                            ModelData data) { return BakedModel.super.getModelData(level, pos, state, data); }
                    @Override public net.minecraft.client.renderer.texture.TextureAtlasSprite getParticleIcon(ModelData data) {
                        return BakedModel.super.getParticleIcon(data);
                    }
                    @Override public net.neoforged.neoforge.client.ChunkRenderTypeSet getRenderTypes(BlockState state, RandomSource random, ModelData data) {
                        return BakedModel.super.getRenderTypes(state, random, data);
                    }
                    @Override public boolean useAmbientOcclusion(BlockState state) { return BakedModel.super.useAmbientOcclusion(state); }
                    @Override public boolean useAmbientOcclusion(BlockState state, RenderType type) { return BakedModel.super.useAmbientOcclusion(state, type); }
                    public boolean useAmbientOcclusion() { return true; }
                    public boolean isGui3d() { return true; }
                    public boolean usesBlockLight() { return true; }
                    public boolean isCustomRenderer() { return false; }
                    public net.minecraft.client.renderer.texture.TextureAtlasSprite getParticleIcon() {
                        return new net.minecraft.client.renderer.texture.TextureAtlasSprite("super_particle");
                    }
                    public net.minecraft.client.resources.model.cuboid.ItemTransforms getTransforms() {
                        return net.minecraft.client.resources.model.cuboid.ItemTransforms.NO_TRANSFORMS;
                    }
                    public net.minecraft.client.renderer.block.model.ItemOverrides getOverrides() {
                        return net.minecraft.client.renderer.block.model.ItemOverrides.EMPTY;
                    }
                }""");
        // As Sophisticated Backpacks' getModelData: it reads the block entity from the level, so a call without one throws.
        old.put("mod/Strict.java", """
                package mod;
                public class Strict extends Backpack {
                    @Override
                    public net.neoforged.neoforge.model.data.ModelData getModelData(net.minecraft.client.renderer.block.BlockAndTintGetter level,
                            net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState state,
                            net.neoforged.neoforge.model.data.ModelData data) {
                        level.toString();
                        return super.getModelData(level, pos, state, data);
                    }
                }""");
        // The calls a mod makes on whatever model it holds, and Sophisticated Backpacks' shape check.
        old.put("mod/Models.java", """
                package mod;
                import net.minecraft.client.renderer.rendertype.RenderTypes;
                import net.minecraft.client.resources.model.BakedModel;
                import net.minecraft.world.level.block.state.BlockState;
                import net.neoforged.neoforge.model.data.ModelData;
                public class Models {
                    public static Object pack() { return new Backpack(); }
                    public static Object broken() { return new Broken(); }
                    public static Object supers() { return new Supers(); }
                    public static Object strict() { return new Strict(); }
                    public static String superAsk(Object held) {
                        BakedModel model = (BakedModel) held;
                        BlockState state = new BlockState();
                        var random = net.minecraft.util.RandomSource.create(42L);
                        StringBuilder layers = new StringBuilder();
                        for (var type : model.getRenderTypes(state, random, ModelData.EMPTY)) layers.append(layers.length() == 0 ? "" : ", ").append(type);
                        var stack = new net.minecraft.world.item.ItemStack();
                        var cutout = RenderTypes.cutoutMovingBlock();
                        return "quads " + model.getQuads(state, null, random, ModelData.EMPTY, cutout)
                                + ", same " + (model.applyTransform(net.minecraft.world.item.ItemDisplayContext.GUI,
                                        new com.mojang.blaze3d.vertex.PoseStack(), false) == model)
                                + ", item types " + model.getRenderTypes(stack, true) + ", passes " + model.getRenderPasses(stack, true).size()
                                + ", occlusion " + model.useAmbientOcclusion(state, ModelData.EMPTY, cutout)
                                + ", data " + (model.getModelData(null, null, state, ModelData.EMPTY) == ModelData.EMPTY)
                                + ", particle " + model.getParticleIcon(ModelData.EMPTY) + ", layers [" + layers + "]"
                                + ", forge occlusion " + model.useAmbientOcclusion(state) + " " + model.useAmbientOcclusion(state, cutout);
                    }
                    public static Object tinted(Object wrapped) { return new Tinted((BakedModel) wrapped); }
                    public static String shape(Object held) {
                        BakedModel model = (BakedModel) held;
                        return model instanceof Backpack ? "backpack" : "default";
                    }
                    public static String ask(Object held) {
                        BakedModel model = (BakedModel) held;
                        BlockState state = new BlockState();
                        var random = net.minecraft.util.RandomSource.create(42L);
                        StringBuilder layers = new StringBuilder();
                        var set = model.getRenderTypes(state, random, ModelData.EMPTY);
                        for (var type : set) layers.append(layers.length() == 0 ? "" : ", ").append(type);
                        ModelData data = model.getModelData(null, null, state, ModelData.EMPTY);
                        return "quads " + model.getQuads(state, null, random, ModelData.EMPTY, RenderTypes.cutoutMovingBlock())
                                + ", layers [" + layers + "], particle " + model.getParticleIcon(ModelData.EMPTY)
                                + ", data " + (data.get(Backpack.COLOR) == null ? "none" : data.get(Backpack.COLOR))
                                + ", occlusion " + model.useAmbientOcclusion(state, ModelData.EMPTY, RenderTypes.cutoutMovingBlock());
                    }
                }""");
        return old;
    }

    /** NeoForge 26.3's client, as far as the rebuilt models reach, with a chunk that draws a block model. */
    private static Map<String, String> hostSources() {
        Map<String, String> sources = new HashMap<>(shared());
        // The item side the shared model helpers name as they load.
        sources.put("net/minecraft/client/renderer/Sheets.java", """
                package net.minecraft.client.renderer;
                public class Sheets {
                    static final net.minecraft.client.renderer.rendertype.RenderType TRANSLUCENT
                            = new net.minecraft.client.renderer.rendertype.RenderType("translucent item");
                    public static net.minecraft.client.renderer.rendertype.RenderType translucentItemSheet() { return TRANSLUCENT; }
                }""");
        sources.put("com/mojang/blaze3d/vertex/QuadInstance.java", "package com.mojang.blaze3d.vertex; public class QuadInstance {}");
        sources.put("net/minecraft/world/level/Level.java", "package net.minecraft.world.level; public class Level {}");
        sources.put("net/minecraft/world/entity/LivingEntity.java", "package net.minecraft.world.entity; public class LivingEntity {}");
        sources.put("net/minecraft/client/multiplayer/ClientLevel.java", "package net.minecraft.client.multiplayer; public class ClientLevel {}");
        sources.put("net/minecraft/client/resources/model/ModelBaker.java", "package net.minecraft.client.resources.model; public interface ModelBaker {}");
        sources.put("net/minecraft/client/resources/model/UnbakedModel.java",
                "package net.minecraft.client.resources.model; public interface UnbakedModel {}");
        sources.put("net/minecraft/client/renderer/block/model/BlockModel.java",
                "package net.minecraft.client.renderer.block.model; public interface BlockModel {}");
        sources.put("net/minecraft/client/renderer/block/ModelBlockRenderer.java",
                "package net.minecraft.client.renderer.block; public class ModelBlockRenderer {}");
        sources.put("com/mojang/blaze3d/vertex/VertexConsumer.java", "package com.mojang.blaze3d.vertex; public interface VertexConsumer {}");
        sources.put("net/minecraft/util/TriState.java", "package net.minecraft.util; public enum TriState { TRUE, FALSE, DEFAULT }");
        sources.put("net/neoforged/neoforge/common/extensions/IBlockGetterExtension.java", """
                package net.neoforged.neoforge.common.extensions;
                public interface IBlockGetterExtension {
                    default net.neoforged.neoforge.model.data.ModelData getModelData(net.minecraft.core.BlockPos pos) {
                        return net.neoforged.neoforge.model.data.ModelData.EMPTY;
                    }
                }""");
        sources.put("net/minecraft/client/renderer/block/BlockAndTintGetter.java", """
                package net.minecraft.client.renderer.block;
                public interface BlockAndTintGetter extends net.neoforged.neoforge.common.extensions.IBlockGetterExtension {}""");
        sources.put("net/minecraft/client/resources/model/sprite/Material.java", """
                package net.minecraft.client.resources.model.sprite;
                public class Material {
                    public static final class Baked {
                        private final net.minecraft.client.renderer.texture.TextureAtlasSprite sprite;
                        public Baked(net.minecraft.client.renderer.texture.TextureAtlasSprite sprite, boolean forceTranslucent) { this.sprite = sprite; }
                        public net.minecraft.client.renderer.texture.TextureAtlasSprite sprite() { return sprite; }
                    }
                }""");
        sources.put("net/minecraft/client/renderer/block/dispatch/BlockStateModelPart.java", """
                package net.minecraft.client.renderer.block.dispatch;
                public interface BlockStateModelPart {
                    java.util.List<net.minecraft.client.resources.model.geometry.BakedQuad> getQuads(net.minecraft.core.Direction side);
                    boolean useAmbientOcclusion();
                }""");
        sources.put("net/neoforged/neoforge/client/extensions/BlockStateModelExtension.java", """
                package net.neoforged.neoforge.client.extensions;
                import net.minecraft.client.renderer.block.dispatch.*;
                public interface BlockStateModelExtension {
                    // As NeoForge 26.x's: a model that reads no level data draws its plain parts.
                    default void collectParts(net.minecraft.client.renderer.block.BlockAndTintGetter level, net.minecraft.core.BlockPos pos,
                            net.minecraft.world.level.block.state.BlockState state, net.minecraft.util.RandomSource random,
                            java.util.List<BlockStateModelPart> parts) {
                        ((BlockStateModel) this).collectParts(random, parts);
                    }
                    default net.minecraft.client.resources.model.sprite.Material.Baked particleMaterial(
                            net.minecraft.client.renderer.block.BlockAndTintGetter level, net.minecraft.core.BlockPos pos,
                            net.minecraft.world.level.block.state.BlockState state) {
                        return ((BlockStateModel) this).particleMaterial();
                    }
                    default int materialFlags(net.minecraft.client.renderer.block.BlockAndTintGetter level, net.minecraft.core.BlockPos pos,
                            net.minecraft.world.level.block.state.BlockState state) {
                        return ((BlockStateModel) this).materialFlags();
                    }
                }""");
        sources.put("net/minecraft/client/renderer/block/dispatch/BlockStateModel.java", """
                package net.minecraft.client.renderer.block.dispatch;
                public interface BlockStateModel extends net.neoforged.neoforge.client.extensions.BlockStateModelExtension {
                    void collectParts(net.minecraft.util.RandomSource random, java.util.List<BlockStateModelPart> parts);
                    net.minecraft.client.resources.model.sprite.Material.Baked particleMaterial();
                    int materialFlags();
                }""");
        sources.put("net/minecraft/client/resources/model/geometry/QuadCollection.java", """
                package net.minecraft.client.resources.model.geometry;
                import java.util.*;
                public class QuadCollection {
                    final Map<net.minecraft.core.Direction, List<BakedQuad>> culled;
                    final List<BakedQuad> unculled;
                    QuadCollection(Map<net.minecraft.core.Direction, List<BakedQuad>> culled, List<BakedQuad> unculled) {
                        this.culled = culled; this.unculled = unculled;
                    }
                    public List<BakedQuad> getQuads(net.minecraft.core.Direction side) {
                        return side == null ? unculled : culled.getOrDefault(side, List.of());
                    }
                    public static class Builder {
                        final Map<net.minecraft.core.Direction, List<BakedQuad>> culled = new HashMap<>();
                        final List<BakedQuad> unculled = new ArrayList<>();
                        public Builder addCulledFace(net.minecraft.core.Direction side, BakedQuad quad) {
                            culled.computeIfAbsent(side, s -> new ArrayList<>()).add(quad); return this;
                        }
                        public Builder addUnculledFace(BakedQuad quad) { unculled.add(quad); return this; }
                        public QuadCollection build() { return new QuadCollection(culled, unculled); }
                    }
                }""");
        sources.put("net/minecraft/client/resources/model/SimpleModelWrapper.java", """
                package net.minecraft.client.resources.model;
                import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
                public final class SimpleModelWrapper implements BlockStateModelPart {
                    final net.minecraft.client.resources.model.geometry.QuadCollection quads;
                    final boolean occlusion;
                    public SimpleModelWrapper(net.minecraft.client.resources.model.geometry.QuadCollection quads, boolean occlusion,
                            net.minecraft.client.resources.model.sprite.Material.Baked particle) { this.quads = quads; this.occlusion = occlusion; }
                    public java.util.List<net.minecraft.client.resources.model.geometry.BakedQuad> getQuads(net.minecraft.core.Direction side) {
                        return quads.getQuads(side);
                    }
                    public boolean useAmbientOcclusion() { return occlusion; }
                }""");
        // Stone: a solid quad on top and a cutout quad unculled, as Minecraft bakes a block with two textures.
        sources.put("testhost/StoneModel.java", """
                package testhost;
                import net.minecraft.client.renderer.block.dispatch.*;
                import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
                import net.minecraft.client.resources.model.geometry.BakedQuad;
                public class StoneModel implements BlockStateModel {
                    public void collectParts(net.minecraft.util.RandomSource random, java.util.List<BlockStateModelPart> parts) {
                        parts.add(new BlockStateModelPart() {
                            public java.util.List<BakedQuad> getQuads(net.minecraft.core.Direction side) {
                                if (side == net.minecraft.core.Direction.UP) return java.util.List.of(new BakedQuad("stone_solid", ChunkSectionLayer.SOLID));
                                return side == null ? java.util.List.of(new BakedQuad("stone_cutout", ChunkSectionLayer.CUTOUT)) : java.util.List.of();
                            }
                            public boolean useAmbientOcclusion() { return true; }
                        });
                    }
                    public net.minecraft.client.resources.model.sprite.Material.Baked particleMaterial() {
                        return new net.minecraft.client.resources.model.sprite.Material.Baked(
                                new net.minecraft.client.renderer.texture.TextureAtlasSprite("stone_particle"), false);
                    }
                    public int materialFlags() { return 0; }
                }""");
        // NeoForge's chunk compile: each part's unculled quads, with the level's model data for the block entity there.
        sources.put("testhost/Chunk.java", """
                package testhost;
                import java.util.*;
                import net.minecraft.client.renderer.block.dispatch.*;
                public class Chunk {
                    public static Object stone() { return new StoneModel(); }
                    public static String draw(Object held) {
                        BlockStateModel model = (BlockStateModel) held;
                        net.minecraft.client.renderer.block.BlockAndTintGetter level = new net.minecraft.client.renderer.block.BlockAndTintGetter() {
                            public net.neoforged.neoforge.model.data.ModelData getModelData(net.minecraft.core.BlockPos pos) {
                                return net.neoforged.neoforge.model.data.ModelData.of(colorOf(model), "blue");
                            }
                        };
                        List<BlockStateModelPart> parts = new ArrayList<>();
                        var state = new net.minecraft.world.level.block.state.BlockState();
                        var pos = new net.minecraft.core.BlockPos(1);
                        model.collectParts(level, pos, state, net.minecraft.util.RandomSource.create(7L), parts);
                        return describe(parts, model.particleMaterial(level, pos, state), model.materialFlags(level, pos, state));
                    }
                    // Minecraft's level-less questions, as item and particle code asks them.
                    public static String plain(Object held) {
                        BlockStateModel model = (BlockStateModel) held;
                        List<BlockStateModelPart> parts = new ArrayList<>();
                        model.collectParts(net.minecraft.util.RandomSource.create(7L), parts);
                        return describe(parts, model.particleMaterial(), model.materialFlags());
                    }
                    static String describe(List<BlockStateModelPart> parts, net.minecraft.client.resources.model.sprite.Material.Baked particle,
                            int flags) {
                        List<String> drawn = new ArrayList<>();
                        for (BlockStateModelPart part : parts) {
                            List<net.minecraft.client.resources.model.geometry.BakedQuad> all = new ArrayList<>();
                            for (var side : net.minecraft.core.Direction.values()) all.addAll(part.getQuads(side));
                            all.addAll(part.getQuads(null));
                            List<String> quads = new ArrayList<>();
                            for (var quad : all) quads.add(quad.name);
                            String layer = all.isEmpty() ? "?" : all.get(0).materialInfo().layer().name().toLowerCase();
                            drawn.add(layer + (part.useAmbientOcclusion() ? " ao: " : ": ") + String.join(" ", quads));
                        }
                        return drawn + " particle " + (particle == null ? "none" : particle.sprite()) + " flags " + flags;
                    }
                    // The model's own data key, as a block entity of the mod would fill it.
                    @SuppressWarnings("unchecked")
                    static net.neoforged.neoforge.model.data.ModelProperty<String> colorOf(Object model) {
                        try {
                            Class<?> type = model.getClass();
                            while (type != null && !type.getName().equals("mod.Backpack")) type = type.getSuperclass();
                            if (type == null) return new net.neoforged.neoforge.model.data.ModelProperty<>();
                            var field = type.getDeclaredField("COLOR");
                            field.setAccessible(true);
                            return (net.neoforged.neoforge.model.data.ModelProperty<String>) field.get(null);
                        } catch (ReflectiveOperationException e) {
                            throw new IllegalStateException(e);
                        }
                    }
                }""");
        return sources;
    }
}
