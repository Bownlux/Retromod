/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.VersionShim;
import com.retromod.shim.fabric.Fabric_1_21_11_to_26_1;
import com.retromod.shim.forge.Forge_1_21_11_to_26_1;
import com.retromod.shim.neoforge.NeoForge_1_21_11_to_26_1;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.zip.ZipFile;

import static com.retromod.shim.common.BridgeHarness.*;
import static org.junit.jupiter.api.Assertions.*;

/** Farmer's Delight's, Sophisticated Backpacks' and Carry On's model calls, written for 1.21.1, drawn by a 26.x host. */
class LegacyBakedItemDrawTest {

    private static final String PROBE = "testhost/Probe";
    private static final String ITEMS = "testhost/Items";
    private static final String SHELF = "mod/Shelf";
    private static final String LIBRARIES = System.getProperty("user.home") + "/Library/Application Support/PrismLauncher/libraries/";

    @TempDir
    static Path dir;

    private static Map<String, byte[]> mod;
    private static Map<String, byte[]> host;
    // 26.3's shape: glint drawn inside each item render type, with no glint render type of its own.
    private static Map<String, byte[]> hostWithoutGlint;
    // 26.3's item sheets, each with a variant that draws the item and its glint in one pass.
    private static Map<String, byte[]> hostWithGlintSheets;

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @BeforeAll
    static void compileShapes() throws Exception {
        mod = only(compile(dir.resolve("old"), oldSources()), "mod/");
        // As ChunkRenderTypeSet is gone from NeoForge 26.3: Fusion's wrapped model names it in another method.
        mod.remove("mod/Removed");
        host = compile(dir.resolve("host"), hostSources(true));
        hostWithoutGlint = compile(dir.resolve("host263"), hostSources(false));
        Map<String, String> glintSheets = hostSources(false);
        glintSheets.put("net/minecraft/client/renderer/Sheets.java", """
                package net.minecraft.client.renderer;
                public class Sheets {
                    static final net.minecraft.client.renderer.rendertype.RenderType TRANSLUCENT = new net.minecraft.client.renderer.rendertype.RenderType(
                            "translucent item");
                    static final net.minecraft.client.renderer.rendertype.RenderType TRANSLUCENT_GLINT
                            = new net.minecraft.client.renderer.rendertype.RenderType("translucent item glint");
                    public static net.minecraft.client.renderer.rendertype.RenderType translucentItemSheet() { return TRANSLUCENT; }
                    public static net.minecraft.client.renderer.rendertype.RenderType translucentItemGlintSheet() { return TRANSLUCENT_GLINT; }
                }""");
        hostWithGlintSheets = compile(dir.resolve("host263glint"), glintSheets);
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("Inside an old layer's draw: the model's answers, the item drawn with it, its quads with tint and glint, a block model and a block")
    void modelCallsDrawInsideLayer() throws Throwable {
        Run game = new Run(host, transform("26.2", bridgeOn(host), mod));
        Object dispatcher = game.call(PROBE, "dispatcher");
        game.call(PROBE, "add", dispatcher, "mod.Shelf");
        game.call(PROBE, "world");
        assertEquals("[translucent item (6.0,0.0,0.0) ff00ff00, translucent item (7.0,0.0,0.0) ffffffff, glint (6.0,0.0,0.0) ff00ff00,"
                + " glint (7.0,0.0,0.0) ffffffff, cutout item (11.5,-0.5,-0.5) ff00ff00, plate (12.0,0.0,0.0) ffff0000,"
                + " item pie@FIXED seed 0 light 15728880 (5.0,0.0,0.0), block stone light 15728880 (5.0,0.0,0.0)]",
                game.call(ITEMS, "frame", dispatcher), "quads in render type order, then the item and block the host resolves");
        assertEquals("gui3d false, particle pie_particle, quads 2, same after overrides true, gui transform 20.0, block quads 1,"
                + " gem gui3d true, gem quads 3, gem particle null", game.call(SHELF, "seen"),
                "FD's FIXED isGui3d, the handle's own answers, a block model's quads and a mod's own model's members");
    }

    @Test
    @DisplayName("On 26.3, where glint has no render type of its own, a foil buffer is the plain buffer")
    void foilWithoutGlintType() throws Throwable {
        Run game = new Run(hostWithoutGlint, transform("26.3", bridgeOn(hostWithoutGlint), mod));
        Object dispatcher = game.call(PROBE, "dispatcher");
        game.call(PROBE, "add", dispatcher, "mod.Shelf");
        game.call(PROBE, "world");
        String frame = (String) game.call(ITEMS, "frame", dispatcher);
        assertFalse(frame.contains("glint"), frame);
        assertTrue(frame.startsWith("[translucent item (6.0,0.0,0.0) ff00ff00, translucent item (7.0,0.0,0.0) ffffffff"), frame);
    }

    @Test
    @DisplayName("On 26.3, a foil buffer for an item sheet is that sheet's glint variant, which draws the item with its glint")
    void foilThroughGlintSheet() throws Throwable {
        Run game = new Run(hostWithGlintSheets, transform("26.3", bridgeOn(hostWithGlintSheets), mod));
        Object dispatcher = game.call(PROBE, "dispatcher");
        game.call(PROBE, "add", dispatcher, "mod.Shelf");
        game.call(PROBE, "world");
        String frame = (String) game.call(ITEMS, "frame", dispatcher);
        assertTrue(frame.startsWith("[translucent item glint (6.0,0.0,0.0) ff00ff00, translucent item glint (7.0,0.0,0.0) ffffffff"), frame);
        assertFalse(frame.contains("[glint (") || frame.contains(", glint ("), "no second glint pass: " + frame);
        assertFalse(frame.contains("translucent item (6.0"), "the plain sheet draws nothing the glint variant drew: " + frame);
    }

    @Test
    @DisplayName("getBlockRenderer() answers one stand-in from a reload listener, before models load and after")
    void blockRendererAtAnyTime() throws Throwable {
        Run game = new Run(host, transform("26.2", bridgeOn(host), mod));
        game.call(PROBE, "world");
        assertEquals("same true, model empty, particle null", game.call(SHELF, "reload"), "as Sophisticated Backpacks' shape provider asks");
        game.call(ITEMS, "loadModels");
        assertEquals("same true, model stone, particle stone_particle", game.call(SHELF, "reload"));
    }

    @Test
    @DisplayName("A mod's own model whose other method names a removed class still answers its own members, and neutral values for the rest")
    void ownModelNamingRemovedClass() throws Throwable {
        Run game = new Run(host, transform("26.2", bridgeOn(host), mod));
        game.call(PROBE, "world");
        game.call(ITEMS, "loadModels");
        String expected = "gui3d true, occlusion true, particle null, quads 2, written 2";
        assertEquals(expected, game.call(SHELF, "wrapped"), "its own isGui3d and quads; no AbstractMethodError for Minecraft's members");
        assertEquals(expected, game.call(SHELF, "wrapped"), "the second call reads the cached lookups");
    }

    @Test
    @DisplayName("Model draws outside any draw Retromod runs draw nothing and throw nothing")
    void outsideDrawIsQuiet() throws Throwable {
        Run game = new Run(host, transform("26.2", bridgeOn(host), mod));
        game.call(PROBE, "world");
        game.call(ITEMS, "loadModels");
        int before = (int) game.call(ITEMS, "created");
        assertDoesNotThrow(() -> game.call(SHELF, "outside"));
        assertEquals(before + 1, game.call(ITEMS, "created"), "only the scratch state that reads answers; nothing submitted");
    }

    @Test
    @DisplayName("A frame of model calls allocates about the same each frame, with no growth from pooled states")
    void allocationPerFrame() throws Throwable {
        Run game = new Run(host, transform("26.2", bridgeOn(host), mod));
        Object dispatcher = game.call(PROBE, "dispatcher");
        game.call(PROBE, "add", dispatcher, "mod.Shelf");
        game.call(PROBE, "world");
        for (int i = 0; i < 50; i++) game.call(ITEMS, "frame", dispatcher);
        int states = (int) game.call(ITEMS, "created");
        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long start = threads.getCurrentThreadAllocatedBytes();
        for (int i = 0; i < 200; i++) game.call(ITEMS, "frame", dispatcher);
        long perFrame = (threads.getCurrentThreadAllocatedBytes() - start) / 200;
        assertEquals(states, game.call(ITEMS, "created"), "item and block states come from the frame pools");
        // The fake frame's own strings and reflection dominate; the bound only catches a leak or a per-frame cache rebuild.
        assertTrue(perFrame < 64 * 1024, "allocated " + perFrame + " bytes per frame");
        System.out.println("LegacyBakedItemDrawTest: about " + perFrame + " bytes allocated per frame of model calls");
    }

    @Test
    @DisplayName("Each loader's 26.1 shim sends the model calls to the rebuilt renderers on 26.1 to 26.3")
    void everyLoaderOn26() {
        for (VersionShim shim : List.of(new Fabric_1_21_11_to_26_1(), new NeoForge_1_21_11_to_26_1(), new Forge_1_21_11_to_26_1())) {
            for (String target : List.of("26.1", "26.2", "26.3")) {
                List<String> calls = calls(transform(target, shim::registerRedirects, mod).get(SHELF), "render");
                for (String expected : List.of(LegacyItemDrawBridge.ITEMS + ".getModel(", LegacyBakedItemBridge.CALLS + ".isGui3d(",
                        LegacyItemDrawBridge.ITEMS + ".render(", LegacyBakedItemBridge.BLOCKS + ".get(", LegacyBakedItemBridge.BLOCKS
                        + ".renderSingleBlock(", LegacyBakedItemBridge.CALLS + ".renderModel(")) {
                    assertTrue(calls.stream().anyMatch(c -> c.startsWith(expected)), shim.getShimName() + " on " + target + ": " + expected);
                }
            }
        }
    }

    @Test
    @DisplayName("A host that keeps BlockRenderDispatcher and BakedModel keeps the calls as written")
    void hostWithOldClassesKeepsThem() {
        Map<String, byte[]> kept = transform("26.2", t -> LegacyBakedItemBridge.register(t, name -> name.equals(HostBuffers.CLIENT_ANCHOR)
                || name.equals(LegacyBakedItemBridge.OLD_BLOCK_DISPATCHER) || name.equals(LegacyBakedItemBridge.OLD_BAKED_MODEL)
                ? new ClassNode() : null, null), mod);
        List<String> calls = calls(kept.get(SHELF), "reload");
        assertTrue(calls.contains("net/minecraft/client/renderer/block/BlockRenderDispatcher.getBlockModel(Lnet/minecraft/world/level/block/state/"
                + "BlockState;)Lnet/minecraft/client/resources/model/BakedModel;"), calls.toString());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "net/minecraftforge/forge/26.2-65.0.0/forge-26.2-65.0.0-client.jar",
            "net/neoforged/minecraft-client-patched/26.1.2.60-beta/minecraft-client-patched-26.1.2.60-beta.jar",
            "net/neoforged/minecraft-client-patched/26.2.0.0-beta/minecraft-client-patched-26.2.0.0-beta.jar",
            "net/neoforged/minecraft-client-patched/26.3.0.43-beta/minecraft-client-patched-26.3.0.43-beta.jar",
            "com/mojang/minecraft/26.1.2/minecraft-26.1.2-client.jar",
            "com/mojang/minecraft/26.2/minecraft-26.2-client.jar",
            "com/mojang/minecraft/26.3/minecraft-26.3-client.jar"})
    @DisplayName("Every 26.x client jar on this machine lost BakedModel, the shapers, BlockRenderDispatcher and the old model members")
    void realHosts(String jar) throws Exception {
        Path file = Path.of(LIBRARIES + jar);
        Assumptions.assumeTrue(Files.isRegularFile(file), "no " + file);
        try (ZipFile zip = new ZipFile(file.toFile())) {
            Function<String, ClassNode> read = name -> {
                try {
                    var entry = zip.getEntry(name + ".class");
                    if (entry == null) return null;
                    ClassNode node = new ClassNode();
                    new ClassReader(zip.getInputStream(entry).readAllBytes()).accept(node, ClassReader.SKIP_CODE);
                    return node;
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            };
            for (String removed : List.of(LegacyBakedItemBridge.OLD_BAKED_MODEL, LegacyBakedItemBridge.OLD_ITEM_OVERRIDES,
                    LegacyBakedItemBridge.OLD_ITEM_SHAPER, LegacyBakedItemBridge.OLD_BLOCK_DISPATCHER, LegacyBakedItemBridge.OLD_BLOCK_SHAPER)) {
                assertNull(read.apply(removed), jar + " " + removed);
            }
            ClassNode model = read.apply(LegacyBakedItemBridge.BLOCK_STATE_MODEL);
            for (String[] member : LegacyBakedItemBridge.OLD_MODEL_MEMBERS) {
                assertTrue(model.methods.stream().noneMatch(m -> m.name.equals(member[0]) && m.desc.equals(member[1])), jar + " " + member[0]);
            }
            ClassNode renderer = read.apply(LegacyBakedItemBridge.MODEL_BLOCK_RENDERER);
            for (String[] member : LegacyBakedItemBridge.OLD_RENDERER_MEMBERS) {
                assertTrue(renderer.methods.stream().noneMatch(m -> m.name.equals(member[0])), jar + " " + member[0]);
            }
            assertTrue(read.apply(LegacyItemDrawBridge.MINECRAFT).methods.stream().noneMatch(m -> m.name.equals("getBlockRenderer")), jar);
        }
    }

    // The layers, the item draws and the model bridge with the stand-in host readable as the game's loader would read it.
    private static java.util.function.Consumer<RetromodTransformer> bridgeOn(Map<String, byte[]> classes) {
        return LegacyItemDrawBridgeTest.bridgeOn(classes);
    }

    /** The mod's 1.21.1 view: ItemRenderer, NeoForge's BakedModel, the shapers, the block renderer and an old layer. */
    private static Map<String, String> oldSources() {
        Map<String, String> old = new HashMap<>(LegacyPlayerLayersBridgeTest.shared());
        old.putAll(models());
        old.put("net/minecraft/world/level/Level.java", "package net.minecraft.world.level; public class Level {}");
        old.put("net/minecraft/client/renderer/MultiBufferSource.java", """
                package net.minecraft.client.renderer;
                public interface MultiBufferSource {
                    com.mojang.blaze3d.vertex.VertexConsumer getBuffer(net.minecraft.client.renderer.rendertype.RenderType type);
                }""");
        old.put("net/minecraft/client/resources/model/BakedModel.java", """
                package net.minecraft.client.resources.model;
                import java.util.List;
                public interface BakedModel {
                    List<net.minecraft.client.resources.model.geometry.BakedQuad> getQuads(net.minecraft.world.level.block.state.BlockState state,
                            net.minecraft.core.Direction side, net.minecraft.util.RandomSource random);
                    boolean isGui3d();
                    net.minecraft.client.renderer.texture.TextureAtlasSprite getParticleIcon();
                    net.minecraft.client.resources.model.cuboid.ItemTransforms getTransforms();
                    net.minecraft.client.renderer.block.model.ItemOverrides getOverrides();
                    default BakedModel applyTransform(net.minecraft.world.item.ItemDisplayContext context, com.mojang.blaze3d.vertex.PoseStack pose,
                            boolean leftHand) { return this; }
                    default List<BakedModel> getRenderPasses(net.minecraft.world.item.ItemStack stack, boolean fabulous) { return List.of(this); }
                    default List<net.minecraft.client.renderer.rendertype.RenderType> getRenderTypes(net.minecraft.world.item.ItemStack stack,
                            boolean fabulous) { return List.of(); }
                    default boolean useAmbientOcclusion() { return true; }
                }""");
        old.put("mod/Removed.java", "package mod; public class Removed {}");
        // Like Fusion's wrapped model: its own quads and 3D flag, the rest inherited, and a method naming a removed class.
        old.put("mod/Wrapped.java", """
                package mod;
                public class Wrapped implements net.minecraft.client.resources.model.BakedModel {
                    public java.util.List<net.minecraft.client.resources.model.geometry.BakedQuad> getQuads(
                            net.minecraft.world.level.block.state.BlockState state, net.minecraft.core.Direction side, net.minecraft.util.RandomSource random) {
                        return side != null ? java.util.List.of() : java.util.List.of(new net.minecraft.client.resources.model.geometry.BakedQuad(),
                                new net.minecraft.client.resources.model.geometry.BakedQuad());
                    }
                    public boolean isGui3d() { return true; }
                    public net.minecraft.client.renderer.texture.TextureAtlasSprite getParticleIcon() { return null; }
                    public net.minecraft.client.resources.model.cuboid.ItemTransforms getTransforms() { return null; }
                    public net.minecraft.client.renderer.block.model.ItemOverrides getOverrides() { return null; }
                    public Removed getRenderTypeSet() { return new Removed(); }
                }""");
        // Counts the vertices written into it.
        old.put("mod/Counter.java", """
                package mod;
                import com.mojang.blaze3d.vertex.VertexConsumer;
                public class Counter implements VertexConsumer {
                    int vertices;
                    public VertexConsumer addVertex(float x, float y, float z) { vertices++; return this; }
                    public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
                    public VertexConsumer setColor(int argb) { return this; }
                    public VertexConsumer setUv(float u, float v) { return this; }
                    public VertexConsumer setUv1(int u, int v) { return this; }
                    public VertexConsumer setUv2(int u, int v) { return this; }
                    public VertexConsumer setNormal(float x, float y, float z) { return this; }
                    public VertexConsumer setLineWidth(float width) { return this; }
                }""");
        old.put("net/minecraft/client/renderer/block/model/ItemOverrides.java", """
                package net.minecraft.client.renderer.block.model;
                import net.minecraft.client.resources.model.BakedModel;
                public class ItemOverrides {
                    public BakedModel resolve(BakedModel model, net.minecraft.world.item.ItemStack stack, net.minecraft.client.multiplayer.ClientLevel level,
                            net.minecraft.world.entity.LivingEntity entity, int seed) { return model; }
                }""");
        old.put("net/minecraft/client/renderer/entity/ItemRenderer.java", """
                package net.minecraft.client.renderer.entity;
                import com.mojang.blaze3d.vertex.*;
                import net.minecraft.client.renderer.MultiBufferSource;
                import net.minecraft.client.resources.model.BakedModel;
                import net.minecraft.world.item.*;
                public class ItemRenderer {
                    public BakedModel getModel(ItemStack stack, net.minecraft.world.level.Level level, net.minecraft.world.entity.LivingEntity entity,
                            int seed) { return null; }
                    public void render(ItemStack stack, ItemDisplayContext context, boolean leftHand, PoseStack pose, MultiBufferSource buffers,
                            int light, int overlay, BakedModel model) {}
                    public void renderModelLists(BakedModel model, ItemStack stack, int light, int overlay, PoseStack pose, VertexConsumer out) {}
                    public static VertexConsumer getFoilBuffer(MultiBufferSource buffers, net.minecraft.client.renderer.rendertype.RenderType type,
                            boolean item, boolean foil) { return null; }
                    public net.minecraft.client.renderer.ItemModelShaper getItemModelShaper() { return null; }
                }""");
        old.put("net/minecraft/client/renderer/ItemModelShaper.java", """
                package net.minecraft.client.renderer;
                public class ItemModelShaper {
                    public net.minecraft.client.resources.model.BakedModel getItemModel(net.minecraft.world.item.ItemStack stack) { return null; }
                }""");
        old.put("net/minecraft/client/renderer/block/BlockModelShaper.java", """
                package net.minecraft.client.renderer.block;
                public class BlockModelShaper {
                    public net.minecraft.client.renderer.texture.TextureAtlasSprite getParticleIcon(net.minecraft.world.level.block.state.BlockState s) {
                        return null;
                    }
                }""");
        old.put("net/minecraft/client/renderer/block/ModelBlockRenderer.java", """
                package net.minecraft.client.renderer.block;
                public class ModelBlockRenderer {
                    public void renderModel(com.mojang.blaze3d.vertex.PoseStack.Pose pose, com.mojang.blaze3d.vertex.VertexConsumer out,
                            net.minecraft.world.level.block.state.BlockState state, net.minecraft.client.resources.model.BakedModel model,
                            float red, float green, float blue, int light, int overlay) {}
                }""");
        old.put("net/minecraft/client/renderer/block/BlockRenderDispatcher.java", """
                package net.minecraft.client.renderer.block;
                public class BlockRenderDispatcher {
                    public net.minecraft.client.resources.model.BakedModel getBlockModel(net.minecraft.world.level.block.state.BlockState state) {
                        return null;
                    }
                    public BlockModelShaper getBlockModelShaper() { return null; }
                    public ModelBlockRenderer getModelRenderer() { return null; }
                    public void renderSingleBlock(net.minecraft.world.level.block.state.BlockState state, com.mojang.blaze3d.vertex.PoseStack pose,
                            net.minecraft.client.renderer.MultiBufferSource buffers, int light, int overlay) {}
                }""");
        old.put("net/minecraft/client/Minecraft.java", """
                package net.minecraft.client;
                public class Minecraft {
                    public static Minecraft getInstance() { return null; }
                    public net.minecraft.client.renderer.entity.ItemRenderer getItemRenderer() { return null; }
                    public net.minecraft.client.renderer.block.BlockRenderDispatcher getBlockRenderer() { return null; }
                }""");
        old.put("net/minecraft/client/renderer/entity/layers/RenderLayer.java", """
                package net.minecraft.client.renderer.entity.layers;
                public abstract class RenderLayer<T extends net.minecraft.world.entity.Entity, M> {
                    public RenderLayer(net.minecraft.client.renderer.entity.RenderLayerParent<T, M> parent) {}
                    public abstract void render(com.mojang.blaze3d.vertex.PoseStack pose, net.minecraft.client.renderer.MultiBufferSource buffers,
                            int light, T entity, float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks, float netHeadYaw,
                            float headPitch);
                }""");
        // A mod's own model, as Sophisticated Backpacks' backpack model is.
        old.put("mod/Gem.java", """
                package mod;
                public class Gem implements net.minecraft.client.resources.model.BakedModel {
                    public java.util.List<net.minecraft.client.resources.model.geometry.BakedQuad> getQuads(
                            net.minecraft.world.level.block.state.BlockState state, net.minecraft.core.Direction side, net.minecraft.util.RandomSource random) {
                        return java.util.List.of(new net.minecraft.client.resources.model.geometry.BakedQuad(), new net.minecraft.client.resources.model.geometry.BakedQuad(),
                                new net.minecraft.client.resources.model.geometry.BakedQuad());
                    }
                    public boolean isGui3d() { return true; }
                    public net.minecraft.client.renderer.texture.TextureAtlasSprite getParticleIcon() { return null; }
                    public net.minecraft.client.resources.model.cuboid.ItemTransforms getTransforms() { return null; }
                    public net.minecraft.client.renderer.block.model.ItemOverrides getOverrides() { return null; }
                }""");
        // Farmer's Delight's board, Sophisticated Backpacks' item renderer and Carry On's carried block, in one layer.
        old.put("mod/Shelf.java", """
                package mod;
                import com.mojang.blaze3d.vertex.PoseStack;
                import net.minecraft.client.Minecraft;
                import net.minecraft.client.renderer.MultiBufferSource;
                import net.minecraft.client.renderer.entity.ItemRenderer;
                import net.minecraft.client.resources.model.BakedModel;
                import net.minecraft.world.entity.LivingEntity;
                import net.minecraft.world.item.*;
                import net.minecraft.world.level.block.state.BlockState;
                public class Shelf extends net.minecraft.client.renderer.entity.layers.RenderLayer<LivingEntity, Object> {
                    static final net.minecraft.client.renderer.rendertype.RenderType PLATE = new net.minecraft.client.renderer.rendertype.RenderType("plate");
                    static final BlockState STONE = new BlockState("stone");
                    static String seen = "";
                    private final ItemRenderer items;
                    public Shelf(net.minecraft.client.renderer.entity.RenderLayerParent<LivingEntity, Object> parent) {
                        super(parent);
                        items = Minecraft.getInstance().getItemRenderer();
                    }
                    @Override
                    public void render(PoseStack pose, MultiBufferSource buffers, int light, LivingEntity entity, float a, float b, float c, float d,
                            float e, float f) {
                        pose.pushPose();
                        pose.last().x = 5;
                        ItemStack pie = new ItemStack("pie");
                        BakedModel model = items.getModel(pie, null, null, 0);
                        pose.pushPose();
                        boolean block = model.applyTransform(ItemDisplayContext.FIXED, pose, false).isGui3d();
                        pose.popPose();
                        BakedModel stone = Minecraft.getInstance().getBlockRenderer().getBlockModel(STONE);
                        BakedModel gem = new Gem();
                        seen = "gui3d " + block + ", particle " + model.getParticleIcon() + ", quads " + model.getQuads(null, null, null).size()
                                + ", same after overrides " + (model.getOverrides().resolve(model, pie, null, null, 0) == model)
                                + ", gui transform " + model.getTransforms().getTransform(ItemDisplayContext.GUI).dx
                                + ", block quads " + stone.getQuads(STONE, null, null).size() + ", gem gui3d " + gem.isGui3d()
                                + ", gem quads " + gem.getQuads(null, null, null).size() + ", gem particle " + gem.getParticleIcon();
                        items.render(pie, ItemDisplayContext.FIXED, false, pose, buffers, light, 655360, model);
                        for (BakedModel pass : model.getRenderPasses(pie, true)) {
                            for (net.minecraft.client.renderer.rendertype.RenderType type : pass.getRenderTypes(pie, true)) {
                                items.renderModelLists(pass, pie, light, 655360, pose, ItemRenderer.getFoilBuffer(buffers, type, true, true));
                            }
                        }
                        items.render(new ItemStack("stone"), ItemDisplayContext.NONE, false, pose, buffers, light, 655360, stone);
                        Minecraft.getInstance().getBlockRenderer().getModelRenderer().renderModel(pose.last(), buffers.getBuffer(PLATE), STONE, stone,
                                1f, 0f, 0f, light, 655360);
                        Minecraft.getInstance().getBlockRenderer().renderSingleBlock(STONE, pose, buffers, light, 655360);
                        pose.popPose();
                    }
                    public static String seen() { return seen; }
                    // Fusion's wrapped model asked for its members and drawn into a buffer, outside any draw.
                    public static String wrapped() {
                        BakedModel model = new Wrapped();
                        Counter out = new Counter();
                        Minecraft.getInstance().getItemRenderer().renderModelLists(model, new ItemStack("pie"), 15, 655360, new PoseStack(), out);
                        return "gui3d " + model.isGui3d() + ", occlusion " + model.useAmbientOcclusion() + ", particle " + model.getParticleIcon()
                                + ", quads " + model.getQuads(null, null, null).size() + ", written " + out.vertices;
                    }
                    // As Sophisticated Backpacks' shape provider, from a resource reload listener.
                    public static String reload() {
                        var blocks = Minecraft.getInstance().getBlockRenderer();
                        BakedModel model = blocks.getBlockModel(STONE);
                        return "same " + (blocks == Minecraft.getInstance().getBlockRenderer()) + ", model " + model.getClass().getSimpleName()
                                .toLowerCase().replace("blockmodel", "") + ", particle " + blocks.getBlockModelShaper().getParticleIcon(STONE);
                    }
                    // An old screen drawing with a buffer source of its own, outside any draw Retromod runs.
                    public static void outside() {
                        ItemRenderer items = Minecraft.getInstance().getItemRenderer();
                        ItemStack apple = new ItemStack("apple");
                        BakedModel model = items.getModel(apple, null, null, 0);
                        model.isGui3d();
                        items.render(apple, ItemDisplayContext.GUI, false, new PoseStack(), type -> null, 15, 655360, model);
                        items.render(apple, ItemDisplayContext.GUI, false, new PoseStack(), type -> null, 15, 655360, new Gem());
                        Minecraft.getInstance().getBlockRenderer().renderSingleBlock(STONE, new PoseStack(), type -> null, 15, 655360);
                    }
                }""");
        return old;
    }

    /** Model classes both sides share, in 26.x's names. */
    private static Map<String, String> models() {
        Map<String, String> sources = new HashMap<>(LegacyItemDrawBridgeTest.items());
        sources.put("net/minecraft/world/item/ItemDisplayContext.java", """
                package net.minecraft.world.item;
                public enum ItemDisplayContext { NONE, THIRD_PERSON_LEFT_HAND, THIRD_PERSON_RIGHT_HAND, FIRST_PERSON_LEFT_HAND, FIRST_PERSON_RIGHT_HAND,
                    HEAD, GUI, GROUND, FIXED, ON_SHELF }""");
        sources.put("net/minecraft/world/item/ItemStack.java", """
                package net.minecraft.world.item;
                public class ItemStack {
                    public final String name;
                    public ItemStack(String name) { this.name = name; }
                    public boolean isEmpty() { return name.isEmpty(); }
                    public boolean hasFoil() { return name.equals("pie"); }
                }""");
        sources.put("net/minecraft/world/level/block/state/BlockState.java", """
                package net.minecraft.world.level.block.state;
                public class BlockState { public final String name; public BlockState(String name) { this.name = name; } }""");
        sources.put("net/minecraft/core/Direction.java", "package net.minecraft.core; public enum Direction { DOWN, UP, NORTH, SOUTH, WEST, EAST }");
        sources.put("net/minecraft/util/RandomSource.java", """
                package net.minecraft.util;
                public interface RandomSource {
                    void setSeed(long seed);
                    static RandomSource create(long seed) { return s -> {}; }
                }""");
        sources.put("net/minecraft/client/renderer/texture/TextureAtlasSprite.java", """
                package net.minecraft.client.renderer.texture;
                public class TextureAtlasSprite {
                    final String name;
                    public TextureAtlasSprite(String name) { this.name = name; }
                    @Override public String toString() { return name; }
                }""");
        sources.put("net/minecraft/client/resources/model/cuboid/ItemTransform.java", """
                package net.minecraft.client.resources.model.cuboid;
                public class ItemTransform {
                    public static final ItemTransform NO_TRANSFORM = new ItemTransform(0);
                    public final float dx;
                    public ItemTransform(float dx) { this.dx = dx; }
                    // As 26.x's: the display transform, then the move to the item's centre.
                    public void apply(boolean leftHand, com.mojang.blaze3d.vertex.PoseStack.Pose pose) {
                        pose.x += dx - 0.5f; pose.y -= 0.5f; pose.z -= 0.5f;
                    }
                }""");
        sources.put("net/minecraft/client/resources/model/cuboid/ItemTransforms.java", """
                package net.minecraft.client.resources.model.cuboid;
                public class ItemTransforms {
                    public static final ItemTransforms NO_TRANSFORMS = new ItemTransforms(ItemTransform.NO_TRANSFORM, ItemTransform.NO_TRANSFORM,
                            ItemTransform.NO_TRANSFORM, ItemTransform.NO_TRANSFORM, ItemTransform.NO_TRANSFORM, ItemTransform.NO_TRANSFORM,
                            ItemTransform.NO_TRANSFORM, ItemTransform.NO_TRANSFORM, ItemTransform.NO_TRANSFORM);
                    private final ItemTransform[] each;
                    public ItemTransforms(ItemTransform a, ItemTransform b, ItemTransform c, ItemTransform d, ItemTransform e, ItemTransform f,
                            ItemTransform g, ItemTransform h, ItemTransform i) { each = new ItemTransform[]{a, b, c, d, e, f, g, h, i}; }
                    public ItemTransform getTransform(net.minecraft.world.item.ItemDisplayContext context) {
                        return switch (context) {
                            case THIRD_PERSON_LEFT_HAND -> each[0]; case THIRD_PERSON_RIGHT_HAND -> each[1]; case FIRST_PERSON_LEFT_HAND -> each[2];
                            case FIRST_PERSON_RIGHT_HAND -> each[3]; case HEAD -> each[4]; case GUI -> each[5]; case GROUND -> each[6];
                            case FIXED -> each[7]; case ON_SHELF -> each[8]; default -> ItemTransform.NO_TRANSFORM;
                        };
                    }
                }""");
        sources.put("net/minecraft/client/resources/model/geometry/BakedQuad.java", """
                package net.minecraft.client.resources.model.geometry;
                public class BakedQuad {
                    public final float x;
                    private final MaterialInfo info;
                    public BakedQuad() { this(0, false, "none"); }
                    public BakedQuad(float x, boolean tinted, String type) { this.x = x; this.info = new MaterialInfo(tinted, type); }
                    public MaterialInfo materialInfo() { return info; }
                    public static final class MaterialInfo {
                        private final boolean tinted;
                        private final net.minecraft.client.renderer.rendertype.RenderType type;
                        MaterialInfo(boolean tinted, String type) {
                            this.tinted = tinted;
                            this.type = type.equals("translucent item") ? net.minecraft.client.renderer.Sheets.translucentItemSheet()
                                    : new net.minecraft.client.renderer.rendertype.RenderType(type);
                        }
                        public boolean isTinted() { return tinted; }
                        public int tintIndex() { return tinted ? 0 : -1; }
                        public net.minecraft.client.renderer.rendertype.RenderType itemRenderType() { return type; }
                    }
                }""");
        sources.put("net/minecraft/client/renderer/Sheets.java", """
                package net.minecraft.client.renderer;
                public class Sheets {
                    static final net.minecraft.client.renderer.rendertype.RenderType TRANSLUCENT = new net.minecraft.client.renderer.rendertype.RenderType(
                            "translucent item");
                    public static net.minecraft.client.renderer.rendertype.RenderType translucentItemSheet() { return TRANSLUCENT; }
                }""");
        sources.put("net/minecraft/client/multiplayer/ClientLevel.java", "package net.minecraft.client.multiplayer; public class ClientLevel {}");
        return sources;
    }

    /** The 26.2 host: the layer test's, with item states that keep layers, block models, and glint render types when asked. */
    private static Map<String, String> hostSources(boolean glintTypes) {
        Map<String, String> sources = new HashMap<>(LegacyItemDrawBridgeTest.hostSources());
        // The host's client level holds the layer test's entities.
        String level = sources.get("net/minecraft/client/multiplayer/ClientLevel.java");
        sources.putAll(models());
        sources.put("net/minecraft/client/multiplayer/ClientLevel.java", level);
        sources.put("com/mojang/blaze3d/vertex/PoseStack.java", """
                package com.mojang.blaze3d.vertex;
                public class PoseStack {
                    private final java.util.ArrayList<Pose> poses = new java.util.ArrayList<>(java.util.List.of(new Pose()));
                    public Pose last() { return poses.get(poses.size() - 1); }
                    public void pushPose() { Pose next = new Pose(); next.set(last()); poses.add(next); }
                    public void popPose() { poses.remove(poses.size() - 1); }
                    public boolean isEmpty() { return poses.size() == 1; }
                    public void translate(float x, float y, float z) { last().x += x; last().y += y; last().z += z; }
                    public static final class Pose {
                        public float x, y, z;
                        public void set(Pose pose) { x = pose.x; y = pose.y; z = pose.z; }
                    }
                }""");
        sources.put("com/mojang/blaze3d/vertex/VertexConsumer.java", """
                package com.mojang.blaze3d.vertex;
                public interface VertexConsumer {
                    VertexConsumer addVertex(float x, float y, float z);
                    VertexConsumer setColor(int red, int green, int blue, int alpha);
                    VertexConsumer setColor(int argb);
                    VertexConsumer setUv(float u, float v);
                    VertexConsumer setUv1(int u, int v);
                    VertexConsumer setUv2(int u, int v);
                    VertexConsumer setNormal(float x, float y, float z);
                    VertexConsumer setLineWidth(float width);
                    // As 26.x's: one vertex standing for the quad, at the pose, in the instance's color.
                    default void putBakedQuad(PoseStack.Pose pose, net.minecraft.client.resources.model.geometry.BakedQuad quad, QuadInstance instance) {
                        addVertex(pose.x + quad.x, pose.y, pose.z);
                        setColor(instance.color);
                    }
                }""");
        sources.put("com/mojang/blaze3d/vertex/QuadInstance.java", """
                package com.mojang.blaze3d.vertex;
                public class QuadInstance {
                    int color;
                    public void setColor(int argb) { color = argb; }
                    public void setLightCoords(int light) {}
                    public void setOverlayCoords(int overlay) {}
                }""");
        sources.put("it/unimi/dsi/fastutil/ints/IntList.java", """
                package it.unimi.dsi.fastutil.ints;
                public interface IntList extends java.util.List<Integer> { int getInt(int index); }""");
        sources.put("it/unimi/dsi/fastutil/ints/IntArrayList.java", """
                package it.unimi.dsi.fastutil.ints;
                public class IntArrayList extends java.util.ArrayList<Integer> implements IntList { public int getInt(int index) { return get(index); } }""");
        sources.put("net/minecraft/client/resources/model/sprite/Material.java", """
                package net.minecraft.client.resources.model.sprite;
                public class Material {
                    public static final class Baked {
                        private final net.minecraft.client.renderer.texture.TextureAtlasSprite sprite;
                        public Baked(net.minecraft.client.renderer.texture.TextureAtlasSprite sprite, boolean forceTranslucent) { this.sprite = sprite; }
                        public net.minecraft.client.renderer.texture.TextureAtlasSprite sprite() { return sprite; }
                    }
                }""");
        sources.put("net/minecraft/client/renderer/item/ItemModelResolver.java", """
                package net.minecraft.client.renderer.item;
                import net.minecraft.client.resources.model.geometry.BakedQuad;
                import net.minecraft.world.item.*;
                public class ItemModelResolver {
                    // Every item resolves to one layer: a tinted and a plain quad, a transform per context, and stone lit as a block.
                    public void updateForTopItem(ItemStackRenderState state, ItemStack stack, ItemDisplayContext context, net.minecraft.world.level.Level level,
                            net.minecraft.world.entity.ItemOwner owner, int seed) {
                        state.clear();
                        state.label = stack.name + "@" + context + " seed " + seed;
                        state.blockLight = stack.name.equals("stone");
                        state.particle = new net.minecraft.client.resources.model.sprite.Material.Baked(
                                new net.minecraft.client.renderer.texture.TextureAtlasSprite(stack.name + "_particle"), false);
                        state.fill(java.util.List.of(new BakedQuad(1, true, "translucent item"), new BakedQuad(2, false, "translucent item")), 0xFF00FF00,
                                new net.minecraft.client.resources.model.cuboid.ItemTransform(context == ItemDisplayContext.FIXED ? 10
                                        : context == ItemDisplayContext.GUI ? 20 : 0));
                    }
                    public void updateForLiving(ItemStackRenderState state, ItemStack stack, ItemDisplayContext context,
                            net.minecraft.world.entity.LivingEntity entity) {
                        updateForTopItem(state, stack, context, null, null, 0);
                        state.label = stack.name + "@" + context + " held by " + entity.name;
                    }
                }""");
        // Like 26.2's: layers kept in a private array; the submit hands the collector the pose and the state's label.
        sources.put("net/minecraft/client/renderer/item/ItemStackRenderState.java", """
                package net.minecraft.client.renderer.item;
                public class ItemStackRenderState {
                    public static int created;
                    String label;
                    boolean blockLight;
                    net.minecraft.client.resources.model.sprite.Material.Baked particle;
                    private LayerRenderState[] layers = new LayerRenderState[]{new LayerRenderState()};
                    private int activeLayerCount;
                    public ItemStackRenderState() { created++; }
                    public void clear() { label = null; activeLayerCount = 0; }
                    public boolean isEmpty() { return label == null; }
                    public boolean usesBlockLight() { return blockLight; }
                    public net.minecraft.client.resources.model.sprite.Material.Baked pickParticleMaterial(net.minecraft.util.RandomSource random) {
                        return particle;
                    }
                    void fill(java.util.List<net.minecraft.client.resources.model.geometry.BakedQuad> quads, int tint,
                            net.minecraft.client.resources.model.cuboid.ItemTransform transform) {
                        LayerRenderState layer = layers[0];
                        layer.quads.clear();
                        layer.quads.addAll(quads);
                        layer.tintLayers.clear();
                        layer.tintLayers.add(tint);
                        layer.itemTransform = transform;
                        activeLayerCount = 1;
                    }
                    public void submit(com.mojang.blaze3d.vertex.PoseStack poseStack, net.minecraft.client.renderer.SubmitNodeCollector collector,
                            int lightCoords, int overlayCoords, int outlineColor) {
                        collector.submitCustomGeometry(poseStack, new net.minecraft.client.renderer.rendertype.RenderType("item " + label + " light "
                                + lightCoords), (pose, buffer) -> buffer.addVertex(pose.x, pose.y, pose.z));
                    }
                    public class LayerRenderState {
                        private final java.util.List<net.minecraft.client.resources.model.geometry.BakedQuad> quads = new java.util.ArrayList<>();
                        private net.minecraft.client.resources.model.cuboid.ItemTransform itemTransform;
                        private it.unimi.dsi.fastutil.ints.IntList tintLayers = new it.unimi.dsi.fastutil.ints.IntArrayList();
                    }
                }""");
        sources.put("net/minecraft/client/renderer/block/dispatch/BlockStateModelPart.java", """
                package net.minecraft.client.renderer.block.dispatch;
                public interface BlockStateModelPart {
                    java.util.List<net.minecraft.client.resources.model.geometry.BakedQuad> getQuads(net.minecraft.core.Direction side);
                    boolean useAmbientOcclusion();
                }""");
        sources.put("net/minecraft/client/renderer/block/dispatch/BlockStateModel.java", """
                package net.minecraft.client.renderer.block.dispatch;
                public interface BlockStateModel {
                    void collectParts(net.minecraft.util.RandomSource random, java.util.List<BlockStateModelPart> parts);
                    net.minecraft.client.resources.model.sprite.Material.Baked particleMaterial();
                    int materialFlags();
                }""");
        // A block's model: one cutout quad on the unculled side.
        sources.put("net/minecraft/client/renderer/block/StoneBlockModel.java", """
                package net.minecraft.client.renderer.block;
                import net.minecraft.client.renderer.block.dispatch.*;
                public class StoneBlockModel implements BlockStateModel {
                    public void collectParts(net.minecraft.util.RandomSource random, java.util.List<BlockStateModelPart> parts) {
                        parts.add(new BlockStateModelPart() {
                            public java.util.List<net.minecraft.client.resources.model.geometry.BakedQuad> getQuads(net.minecraft.core.Direction side) {
                                return side == null ? java.util.List.of(new net.minecraft.client.resources.model.geometry.BakedQuad(7, true, "cutout item"))
                                        : java.util.List.of();
                            }
                            public boolean useAmbientOcclusion() { return true; }
                        });
                    }
                    public net.minecraft.client.resources.model.sprite.Material.Baked particleMaterial() { return null; }
                    public int materialFlags() { return 0; }
                }""");
        sources.put("net/minecraft/client/renderer/block/BlockStateModelSet.java", """
                package net.minecraft.client.renderer.block;
                public class BlockStateModelSet {
                    public net.minecraft.client.renderer.block.dispatch.BlockStateModel get(net.minecraft.world.level.block.state.BlockState state) {
                        return new StoneBlockModel();
                    }
                    public net.minecraft.client.resources.model.sprite.Material.Baked getParticleMaterial(net.minecraft.world.level.block.state.BlockState s) {
                        return new net.minecraft.client.resources.model.sprite.Material.Baked(
                                new net.minecraft.client.renderer.texture.TextureAtlasSprite(s.name + "_particle"), false);
                    }
                }""");
        sources.put("net/minecraft/client/resources/model/ModelManager.java", """
                package net.minecraft.client.resources.model;
                public class ModelManager {
                    public static boolean loaded;
                    public net.minecraft.client.renderer.block.BlockStateModelSet getBlockStateModelSet() {
                        return loaded ? new net.minecraft.client.renderer.block.BlockStateModelSet() : null;
                    }
                }""");
        sources.put("net/minecraft/client/renderer/block/model/BlockDisplayContext.java", """
                package net.minecraft.client.renderer.block.model;
                public class BlockDisplayContext { public static BlockDisplayContext create() { return new BlockDisplayContext(); } }""");
        sources.put("net/minecraft/client/renderer/block/BlockModelRenderState.java", """
                package net.minecraft.client.renderer.block;
                public class BlockModelRenderState {
                    String label;
                    public void clear() { label = null; }
                    public boolean isEmpty() { return label == null; }
                    public void submit(com.mojang.blaze3d.vertex.PoseStack poseStack, net.minecraft.client.renderer.SubmitNodeCollector collector,
                            int lightCoords, int overlayCoords, int outlineColor) {
                        collector.submitCustomGeometry(poseStack, new net.minecraft.client.renderer.rendertype.RenderType("block " + label + " light "
                                + lightCoords), (pose, buffer) -> buffer.addVertex(pose.x, pose.y, pose.z));
                    }
                }""");
        sources.put("net/minecraft/client/renderer/block/BlockModelResolver.java", """
                package net.minecraft.client.renderer.block;
                public class BlockModelResolver {
                    public BlockModelResolver(net.minecraft.client.resources.model.ModelManager models) {}
                    public void update(BlockModelRenderState state, net.minecraft.world.level.block.state.BlockState block,
                            net.minecraft.client.renderer.block.model.BlockDisplayContext context) {
                        state.clear();
                        if (context != null) state.label = block.name;
                    }
                }""");
        sources.put("net/minecraft/client/color/block/BlockColors.java", "package net.minecraft.client.color.block; public class BlockColors {}");
        sources.put("net/minecraft/client/renderer/block/ModelBlockRenderer.java", """
                package net.minecraft.client.renderer.block;
                public class ModelBlockRenderer {
                    public ModelBlockRenderer(boolean ambientOcclusion, boolean cull, net.minecraft.client.color.block.BlockColors colors) {}
                }""");
        if (glintTypes) {
            sources.put("net/minecraft/client/renderer/rendertype/RenderTypes.java", """
                    package net.minecraft.client.renderer.rendertype;
                    public class RenderTypes {
                        static final RenderType GLINT = new RenderType("glint");
                        public static RenderType glint() { return GLINT; }
                        public static RenderType glintTranslucent() { return GLINT; }
                        public static RenderType entityGlint() { return GLINT; }
                        public static RenderType armorEntityGlint() { return GLINT; }
                    }""");
        }
        sources.put("net/minecraft/client/Minecraft.java", """
                package net.minecraft.client;
                public class Minecraft {
                    public static Minecraft instance;
                    public static long frameTime;
                    public net.minecraft.client.multiplayer.ClientLevel level;
                    private final net.minecraft.client.resources.model.ModelManager models = new net.minecraft.client.resources.model.ModelManager();
                    private final net.minecraft.client.renderer.item.ItemModelResolver resolver = new net.minecraft.client.renderer.item.ItemModelResolver();
                    public static Minecraft getInstance() { return instance; }
                    public DeltaTracker getDeltaTracker() { return runsNormally -> 0.5f; }
                    public long getFrameTimeNs() { return frameTime; }
                    public net.minecraft.client.renderer.item.ItemModelResolver getItemModelResolver() { return resolver; }
                    public net.minecraft.client.resources.model.ModelManager getModelManager() { return models; }
                    public net.minecraft.client.color.block.BlockColors getBlockColors() { return new net.minecraft.client.color.block.BlockColors(); }
                }""");
        sources.put("testhost/Items.java", """
                package testhost;
                public class Items {
                    public static String frame(Object dispatcher) {
                        net.minecraft.client.resources.model.ModelManager.loaded = true;
                        String drawn = Probe.frame(dispatcher);
                        net.minecraft.client.Minecraft.frameTime++;
                        return drawn;
                    }
                    public static void loadModels() { net.minecraft.client.resources.model.ModelManager.loaded = true; }
                    public static int created() { return net.minecraft.client.renderer.item.ItemStackRenderState.created; }
                }""");
        return sources;
    }
}
