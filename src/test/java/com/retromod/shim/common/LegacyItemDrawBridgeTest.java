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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.zip.ZipFile;

import static com.retromod.shim.common.BridgeHarness.*;
import static org.junit.jupiter.api.Assertions.*;

/** Sophisticated Backpacks' pattern: an ItemRenderer kept from a layer's constructor, then renderStatic while the layer draws. */
class LegacyItemDrawBridgeTest {

    private static final String PROBE = "testhost/Probe";
    private static final String ITEMS = "testhost/Items";
    private static final String BACKPACK = "mod/Backpack";
    private static final String LIBRARIES = System.getProperty("user.home") + "/Library/Application Support/PrismLauncher/libraries/";

    @TempDir
    static Path dir;

    private static Map<String, byte[]> mod;
    private static Map<String, byte[]> host;

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @BeforeAll
    static void compileShapes() throws Exception {
        Map<String, String> old = new HashMap<>(LegacyPlayerLayersBridgeTest.shared());
        old.putAll(items());
        old.put("net/minecraft/client/renderer/MultiBufferSource.java", """
                package net.minecraft.client.renderer;
                public interface MultiBufferSource {
                    com.mojang.blaze3d.vertex.VertexConsumer getBuffer(net.minecraft.client.renderer.rendertype.RenderType type);
                }""");
        old.put("net/minecraft/world/level/Level.java", "package net.minecraft.world.level; public class Level {}");
        old.put("net/minecraft/client/renderer/entity/ItemRenderer.java", """
                package net.minecraft.client.renderer.entity;
                import net.minecraft.world.item.*;
                public class ItemRenderer {
                    public static final Object ENCHANTED_GLINT_ITEM = null, ENCHANTED_GLINT_ENTITY = null;
                    public void renderStatic(ItemStack stack, ItemDisplayContext context, int light, int overlay, com.mojang.blaze3d.vertex.PoseStack pose,
                            net.minecraft.client.renderer.MultiBufferSource buffers, net.minecraft.world.level.Level level, int seed) {}
                    public void renderStatic(net.minecraft.world.entity.LivingEntity entity, ItemStack stack, ItemDisplayContext context, boolean leftHand,
                            com.mojang.blaze3d.vertex.PoseStack pose, net.minecraft.client.renderer.MultiBufferSource buffers,
                            net.minecraft.world.level.Level level, int light, int overlay, int seed) {}
                }""");
        old.put("net/minecraft/client/Minecraft.java", """
                package net.minecraft.client;
                public class Minecraft {
                    public static Minecraft getInstance() { return null; }
                    public net.minecraft.client.renderer.entity.ItemRenderer getItemRenderer() { return null; }
                }""");
        old.put("net/minecraft/client/renderer/entity/RenderLayerParent.java", """
                package net.minecraft.client.renderer.entity;
                public interface RenderLayerParent<S, M> {}""");
        old.put("net/minecraft/client/renderer/entity/layers/RenderLayer.java", """
                package net.minecraft.client.renderer.entity.layers;
                public abstract class RenderLayer<T extends net.minecraft.world.entity.Entity, M> {
                    public RenderLayer(net.minecraft.client.renderer.entity.RenderLayerParent<T, M> parent) {}
                    public abstract void render(com.mojang.blaze3d.vertex.PoseStack pose, net.minecraft.client.renderer.MultiBufferSource buffers,
                            int light, T entity, float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks, float netHeadYaw,
                            float headPitch);
                }""");
        // Like Sophisticated Backpacks' layer: the item renderer is kept from the constructor, which runs in a reload listener.
        old.put("mod/Backpack.java", """
                package mod;
                import com.mojang.blaze3d.vertex.PoseStack;
                import net.minecraft.client.Minecraft;
                import net.minecraft.client.renderer.MultiBufferSource;
                import net.minecraft.client.renderer.entity.ItemRenderer;
                import net.minecraft.world.entity.LivingEntity;
                import net.minecraft.world.item.*;
                public class Backpack extends net.minecraft.client.renderer.entity.layers.RenderLayer<LivingEntity, Object> {
                    private final ItemRenderer items;
                    public Backpack(net.minecraft.client.renderer.entity.RenderLayerParent<LivingEntity, Object> parent) {
                        super(parent);
                        items = Minecraft.getInstance().getItemRenderer();
                    }
                    @Override
                    public void render(PoseStack pose, MultiBufferSource buffers, int light, LivingEntity entity, float a, float b, float c, float d,
                            float e, float f) {
                        pose.pushPose();
                        pose.last().x = 5;
                        items.renderStatic(new ItemStack("backpack"), ItemDisplayContext.NONE, light, 655360, pose, buffers, null, 3);
                        items.renderStatic(entity, new ItemStack("sword"), ItemDisplayContext.THIRD_PERSON_LEFT_HAND, true, pose, buffers, null, light, 655360, 0);
                        // The pose the item was drawn at is kept, whatever the layer does to its stack afterwards.
                        pose.last().x = 9;
                        pose.popPose();
                        if (entity.name.equals("Broken")) throw new IllegalStateException("after the items");
                    }
                    // As Create and Punchy read the glint textures' ids.
                    public static Object glints() { return java.util.List.of(ItemRenderer.ENCHANTED_GLINT_ITEM, ItemRenderer.ENCHANTED_GLINT_ENTITY); }
                    public static boolean sameRenderer() { return Minecraft.getInstance().getItemRenderer() == Minecraft.getInstance().getItemRenderer(); }
                    // An old screen drawing an item with a buffer source of its own, outside any draw Retromod runs.
                    public static void outside() {
                        Minecraft.getInstance().getItemRenderer().renderStatic(new ItemStack("apple"), ItemDisplayContext.GUI, 15, 655360, new PoseStack(),
                                type -> null, null, 0);
                    }
                }""");
        mod = only(compile(dir.resolve("old"), old), "mod/");
        host = compile(dir.resolve("host"), hostSources());
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("getItemRenderer() fails as written, then answers one stand-in from a constructor, before any draw")
    void rendererFromConstructor() throws Throwable {
        Run old = new Run(host, mod);
        Object before = old.call(PROBE, "dispatcher");
        // The probe builds the layer by reflection, which wraps what its constructor threw.
        Throwable thrown = assertThrows(Throwable.class, () -> old.call(PROBE, "add", before, "mod.Backpack"));
        assertInstanceOf(NoSuchMethodError.class, thrown.getCause(), "as Sophisticated Backpacks failed in its reload listener");

        Map<String, byte[]> out = transform("26.2", bridgeOn(host), mod);
        Run game = new Run(host, out);
        game.call(PROBE, "add", game.call(PROBE, "dispatcher"), "mod.Backpack");
        assertEquals(true, game.call(BACKPACK, "sameRenderer"), "the same object every time");
        ClassNode layer = node(out.get(BACKPACK));
        assertEquals("L" + LegacyItemDrawBridge.ITEMS + ";", layer.fields.stream().filter(f -> f.name.equals("items")).findFirst().orElseThrow().desc,
                "the field holds the stand-in's type");
    }

    @Test
    @DisplayName("renderStatic inside an old layer's draw submits each item at the pose it was drawn at")
    void itemsDrawInsideLayer() throws Throwable {
        Run game = new Run(host, transform("26.2", bridgeOn(host), mod));
        Object dispatcher = game.call(PROBE, "dispatcher");
        game.call(PROBE, "add", dispatcher, "mod.Backpack");
        game.call(PROBE, "world");
        assertEquals("[item backpack@NONE seed 3 light 15728880 (5.0,0.0,0.0), item sword@THIRD_PERSON_LEFT_HAND held by Steve light 15728880"
                + " (5.0,0.0,0.0)]", game.call(ITEMS, "frame", dispatcher));
    }

    @Test
    @DisplayName("A layer that throws after drawing items leaves none of them in the frame, and stays off")
    void throwingLayerLeavesNoItems() throws Throwable {
        Run game = new Run(host, transform("26.2", bridgeOn(host), mod));
        Object dispatcher = game.call(PROBE, "dispatcher");
        game.call(PROBE, "add", dispatcher, "mod.Backpack");
        game.call(PROBE, "brokenWorld");
        assertEquals("[]", game.call(ITEMS, "frame", dispatcher));
        assertEquals("[]", game.call(ITEMS, "frame", dispatcher));
    }

    @Test
    @DisplayName("Item states are made once and used again once a later frame is drawn")
    void itemStatesComeFromAPool() throws Throwable {
        Run game = new Run(host, transform("26.2", bridgeOn(host), mod));
        Object dispatcher = game.call(PROBE, "dispatcher");
        game.call(PROBE, "add", dispatcher, "mod.Backpack");
        game.call(PROBE, "world");
        game.call(ITEMS, "frame", dispatcher);
        int first = (int) game.call(ITEMS, "created");
        for (int i = 0; i < 5; i++) game.call(ITEMS, "frame", dispatcher);
        assertEquals(first, game.call(ITEMS, "created"), "no new item state after the first frame");
        game.call(ITEMS, "frameWithoutTimer", dispatcher);
        assertEquals(first, game.call(ITEMS, "created"), "the frame before was marked drawn");
        game.call(ITEMS, "frameWithoutTimer", dispatcher);
        assertEquals(first + 2, game.call(ITEMS, "created"), "a frame the game has not marked drawn never reuses one still held");
    }

    @Test
    @DisplayName("The glint textures' ids are read from the item feature renderer, where 26.1 to 26.3 keep them")
    void glintIdsMoved() {
        Map<String, byte[]> out = transform("26.3", bridgeOn(host), mod);
        assertEquals(List.of(LegacyItemDrawBridge.ITEM_FEATURE_RENDERER + ".ENCHANTED_GLINT_ITEM:Ljava/lang/Object;",
                LegacyItemDrawBridge.ITEM_FEATURE_RENDERER + ".ENCHANTED_GLINT_ARMOR:Ljava/lang/Object;"), fields(out.get(BACKPACK), "glints"));
    }

    @Test
    @DisplayName("renderStatic outside any draw Retromod runs draws nothing and throws nothing")
    void outsideDrawIsQuiet() throws Throwable {
        Run game = new Run(host, transform("26.2", bridgeOn(host), mod));
        game.call(PROBE, "world");
        int before = (int) game.call(ITEMS, "created");
        game.call(BACKPACK, "outside");
        assertEquals(before, game.call(ITEMS, "created"));
    }

    @Test
    @DisplayName("Each loader's 26.1 shim takes the bridge on 26.1 to 26.3, and a host that keeps ItemRenderer does not")
    void everyLoaderOn26() {
        String getter = LegacyItemDrawBridge.ITEMS + ".get(Ljava/lang/Object;)L" + LegacyItemDrawBridge.ITEMS + ";";
        for (VersionShim shim : List.of(new Fabric_1_21_11_to_26_1(), new NeoForge_1_21_11_to_26_1(), new Forge_1_21_11_to_26_1())) {
            for (String target : List.of("26.1", "26.2", "26.3")) {
                Map<String, byte[]> out = transform(target, shim::registerRedirects, mod);
                assertTrue(calls(out.get(BACKPACK), "<init>").contains(getter), shim.getShimName() + " on " + target);
                assertTrue(calls(out.get(BACKPACK), "render").stream().anyMatch(c -> c.startsWith(LegacyItemDrawBridge.ITEMS + ".renderStatic(")),
                        shim.getShimName() + " on " + target);
            }
        }
        Map<String, byte[]> kept = transform("26.2", t -> LegacyItemDrawBridge.register(t,
                name -> name.equals(HostBuffers.CLIENT_ANCHOR) || name.equals(LegacyItemDrawBridge.OLD_ITEM_RENDERER) ? new ClassNode() : null), mod);
        assertTrue(calls(kept.get(BACKPACK), "<init>").contains("net/minecraft/client/Minecraft.getItemRenderer()"
                + "Lnet/minecraft/client/renderer/entity/ItemRenderer;"), "a host with ItemRenderer keeps the call");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "net/minecraftforge/forge/26.2-65.0.0/forge-26.2-65.0.0-client.jar",
            "net/neoforged/minecraft-client-patched/26.1.2.60-beta/minecraft-client-patched-26.1.2.60-beta.jar",
            "net/neoforged/minecraft-client-patched/26.3.0.43-beta/minecraft-client-patched-26.3.0.43-beta.jar",
            "com/mojang/minecraft/26.1.2/minecraft-26.1.2-client.jar",
            "com/mojang/minecraft/26.2/minecraft-26.2-client.jar",
            "com/mojang/minecraft/26.3/minecraft-26.3-client.jar"})
    @DisplayName("Every 26.x client jar on this machine lost ItemRenderer, its getters and the dispatcher's old draw")
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
            assertTrue(LegacyItemDrawBridge.hostDroppedItemRenderer(read), jar);
            for (String owner : List.of(LegacyItemDrawBridge.MINECRAFT, LegacyItemDrawBridge.BLOCK_ENTITY_CONTEXT, LegacyItemDrawBridge.ENTITY_CONTEXT)) {
                assertTrue(read.apply(owner).methods.stream().noneMatch(m -> m.name.equals("getItemRenderer")), jar + " " + owner);
            }
            assertTrue(read.apply(LegacyItemDrawBridge.DISPATCHER).methods.stream()
                    .noneMatch(m -> m.name.equals("setRenderShadow") || m.desc.equals(LegacyItemDrawBridge.OLD_RENDER_DESC)), jar);
        }
    }

    // The bridge with the stand-in host readable as the game's loader would read it, and the 26.2 buffer stand-ins.
    static Consumer<RetromodTransformer> bridgeOn(Map<String, byte[]> classes) {
        return t -> {
            RenderBufferSynthetics.register(t);
            LegacyPlayerLayersBridge.register(t, LegacyPlayerLayersBridgeTest.reader(classes));
            LegacyItemDrawBridge.register(t, LegacyPlayerLayersBridgeTest.reader(classes));
        };
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    /** The item classes both sides share. */
    static Map<String, String> items() {
        Map<String, String> sources = new HashMap<>();
        sources.put("net/minecraft/world/item/ItemStack.java", """
                package net.minecraft.world.item;
                public class ItemStack {
                    public final String name;
                    public ItemStack(String name) { this.name = name; }
                    public boolean isEmpty() { return name.isEmpty(); }
                }""");
        sources.put("net/minecraft/world/item/ItemDisplayContext.java", """
                package net.minecraft.world.item;
                public enum ItemDisplayContext { NONE, THIRD_PERSON_LEFT_HAND, GUI, FIXED }""");
        sources.put("net/minecraft/world/entity/ItemOwner.java", "package net.minecraft.world.entity; public interface ItemOwner {}");
        return sources;
    }

    /** The 26.2 host of the layer test, with 26.x's item model resolver, item states and a frame timer. */
    static Map<String, String> hostSources() {
        Map<String, String> sources = new HashMap<>(LegacyPlayerLayersBridgeTest.hostSources());
        sources.putAll(items());
        sources.put("net/minecraft/client/Minecraft.java", """
                package net.minecraft.client;
                public class Minecraft {
                    public static Minecraft instance;
                    public static long frameTime;
                    public net.minecraft.client.multiplayer.ClientLevel level;
                    public static Minecraft getInstance() { return instance; }
                    public DeltaTracker getDeltaTracker() { return runsNormally -> 0.5f; }
                    public long getFrameTimeNs() { return frameTime; }
                    public net.minecraft.client.renderer.item.ItemModelResolver getItemModelResolver() {
                        return new net.minecraft.client.renderer.item.ItemModelResolver();
                    }
                }""");
        sources.put("net/minecraft/client/renderer/item/ItemModelResolver.java", """
                package net.minecraft.client.renderer.item;
                import net.minecraft.world.item.*;
                public class ItemModelResolver {
                    public void updateForTopItem(ItemStackRenderState state, ItemStack stack, ItemDisplayContext context, net.minecraft.world.level.Level level,
                            net.minecraft.world.entity.ItemOwner owner, int seed) {
                        state.clear();
                        state.label = stack.name + "@" + context + " seed " + seed;
                    }
                    public void updateForLiving(ItemStackRenderState state, ItemStack stack, ItemDisplayContext context,
                            net.minecraft.world.entity.LivingEntity entity) {
                        state.clear();
                        state.label = stack.name + "@" + context + " held by " + entity.name;
                    }
                }""");
        // Like 26.2's: the submit hands the collector the pose and what the state holds right then.
        sources.put("net/minecraft/client/renderer/item/ItemStackRenderState.java", """
                package net.minecraft.client.renderer.item;
                public class ItemStackRenderState {
                    public static int created;
                    String label;
                    public ItemStackRenderState() { created++; }
                    public void clear() { label = null; }
                    public boolean isEmpty() { return label == null; }
                    public void submit(com.mojang.blaze3d.vertex.PoseStack poseStack, net.minecraft.client.renderer.SubmitNodeCollector collector,
                            int lightCoords, int overlayCoords, int outlineColor) {
                        collector.submitCustomGeometry(poseStack, new net.minecraft.client.renderer.rendertype.RenderType("item " + label + " light "
                                + lightCoords), (pose, buffer) -> buffer.addVertex(pose.x, pose.y, pose.z));
                    }
                }""");
        sources.put("testhost/Items.java", """
                package testhost;
                public class Items {
                    // A frame, which the game marks drawn by writing how long it took.
                    public static String frame(Object dispatcher) {
                        String drawn = Probe.frame(dispatcher);
                        net.minecraft.client.Minecraft.frameTime++;
                        return drawn;
                    }
                    public static String frameWithoutTimer(Object dispatcher) { return Probe.frame(dispatcher); }
                    public static int created() { return net.minecraft.client.renderer.item.ItemStackRenderState.created; }
                }""");
        return sources;
    }
}
