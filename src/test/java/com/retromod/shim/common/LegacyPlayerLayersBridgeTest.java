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
import static com.retromod.shim.common.LegacyPlayerLayersBridge.*;
import static org.junit.jupiter.api.Assertions.*;

/** Sophisticated Backpacks' pattern: getSkinMap() and the renderer map in a reload listener, then an old layer drawn on 26.x. */
class LegacyPlayerLayersBridgeTest {

    private static final String INSTALL = "mod/Install";
    private static final String PROBE = "testhost/Probe";
    private static final String LIBRARIES = System.getProperty("user.home") + "/Library/Application Support/PrismLauncher/libraries/";

    @TempDir
    static Path dir;

    private static Map<String, byte[]> mod;
    private static Map<String, byte[]> host;
    private static Map<String, byte[]> modern;

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = com.retromod.util.McReflect.isForceForge();
    private final boolean savedNeoForge = com.retromod.util.McReflect.isForceNeoForge();

    @BeforeAll
    static void compileShapes() throws Exception {
        Map<String, String> old = new HashMap<>(shared());
        old.put("net/minecraft/client/renderer/MultiBufferSource.java", """
                package net.minecraft.client.renderer;
                public interface MultiBufferSource {
                    com.mojang.blaze3d.vertex.VertexConsumer getBuffer(net.minecraft.client.renderer.rendertype.RenderType type);
                }""");
        old.put("net/minecraft/client/renderer/entity/EntityRenderer.java", """
                package net.minecraft.client.renderer.entity;
                public abstract class EntityRenderer<T> {}""");
        old.put("net/minecraft/client/renderer/entity/LivingEntityRenderer.java", """
                package net.minecraft.client.renderer.entity;
                public abstract class LivingEntityRenderer<T extends net.minecraft.world.entity.Entity, M> extends EntityRenderer<T> implements RenderLayerParent<T, M> {
                    public final boolean addLayer(net.minecraft.client.renderer.entity.layers.RenderLayer<T, M> layer) { return true; }
                }""");
        old.put("net/minecraft/client/renderer/entity/layers/RenderLayer.java", """
                package net.minecraft.client.renderer.entity.layers;
                public abstract class RenderLayer<T extends net.minecraft.world.entity.Entity, M> {
                    public RenderLayer(net.minecraft.client.renderer.entity.RenderLayerParent<T, M> parent) {}
                    public abstract void render(com.mojang.blaze3d.vertex.PoseStack pose, net.minecraft.client.renderer.MultiBufferSource buffers,
                            int light, T entity, float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks, float netHeadYaw,
                            float headPitch);
                }""");
        old.put("net/minecraft/client/renderer/entity/EntityRenderDispatcher.java", """
                package net.minecraft.client.renderer.entity;
                public class EntityRenderDispatcher {
                    public java.util.Map<net.minecraft.world.entity.EntityType<?>, EntityRenderer<?>> renderers;
                    public java.util.Map<String, EntityRenderer<?>> getSkinMap() { return null; }
                }""");
        // Like Sophisticated Backpacks' layer: it draws for the entity it is handed.
        old.put("mod/Pack.java", """
                package mod;
                import net.minecraft.world.entity.LivingEntity;
                public class Pack extends net.minecraft.client.renderer.entity.layers.RenderLayer<LivingEntity, Object> {
                    public static final java.util.List<String> SEEN = new java.util.ArrayList<>();
                    static final net.minecraft.client.renderer.rendertype.RenderType TYPE = new net.minecraft.client.renderer.rendertype.RenderType("pack");
                    public Pack(net.minecraft.client.renderer.entity.RenderLayerParent<LivingEntity, Object> parent) { super(parent); }
                    @Override
                    public void render(com.mojang.blaze3d.vertex.PoseStack pose, net.minecraft.client.renderer.MultiBufferSource buffers, int light,
                            LivingEntity entity, float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {
                        SEEN.add(entity.name + " limb " + limbSwing + "/" + limbSwingAmount + " partial " + partialTick + " age " + ageInTicks
                                + " head " + netHeadYaw + "/" + headPitch + " light " + light);
                        buffers.getBuffer(TYPE).addVertex(1, 2, 3).setColor(-1);
                        // As Sophisticated Backpacks' layer does when it reaches the item renderer 26.x removed.
                        if (entity.name.equals("Broken")) throw new NoClassDefFoundError("net/minecraft/client/renderer/entity/ItemRenderer");
                    }
                }""");
        // Its reload listener: a layer on each player renderer, then on every other living renderer.
        old.put("mod/Install.java", """
                package mod;
                import net.minecraft.client.renderer.entity.*;
                @SuppressWarnings({"rawtypes", "unchecked"})
                public class Install {
                    public static void install(Object dispatcher) {
                        EntityRenderDispatcher d = (EntityRenderDispatcher) dispatcher;
                        for (EntityRenderer<?> renderer : d.getSkinMap().values()) {
                            if (renderer instanceof LivingEntityRenderer living) living.addLayer(new Pack(living));
                        }
                        d.renderers.forEach((type, renderer) -> {
                            if (renderer instanceof LivingEntityRenderer living) living.addLayer(new Pack(living));
                        });
                    }
                    public static Object skin(Object dispatcher, String name) { return ((EntityRenderDispatcher) dispatcher).getSkinMap().get(name); }
                    public static String skins(Object dispatcher) { return ((EntityRenderDispatcher) dispatcher).getSkinMap().keySet().toString(); }
                    public static String seen() { return Pack.SEEN.toString(); }
                }""");
        old.put("org/spongepowered/asm/mixin/Mixin.java", """
                package org.spongepowered.asm.mixin;
                @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.CLASS)
                public @interface Mixin { String[] targets() default {}; }""");
        // The usual armor-layer mixin: Mixin requires it to keep the base and members its target has.
        old.put("mod/ArmorMixin.java", """
                package mod;
                import net.minecraft.world.entity.LivingEntity;
                @org.spongepowered.asm.mixin.Mixin(targets = "net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer")
                public abstract class ArmorMixin extends net.minecraft.client.renderer.entity.layers.RenderLayer<LivingEntity, Object> {
                    ArmorMixin() { super(null); }
                    @Override public void render(com.mojang.blaze3d.vertex.PoseStack pose, net.minecraft.client.renderer.MultiBufferSource buffers,
                            int light, LivingEntity entity, float a, float b, float c, float d, float e, float f) {}
                }""");
        // An abstract base that declares no render, and the layer that does.
        old.put("mod/BaseLayer.java", """
                package mod;
                public abstract class BaseLayer<T extends net.minecraft.world.entity.LivingEntity> extends net.minecraft.client.renderer.entity.layers.RenderLayer<T, Object> {
                    protected BaseLayer(net.minecraft.client.renderer.entity.RenderLayerParent<T, Object> parent) { super(parent); }
                }""");
        old.put("mod/Cape.java", layerSource("Cape", "BaseLayer<LivingEntity>", "cape "));
        // A layer, and a subclass that overrides its render: the base's bridge reaches the override.
        old.put("mod/Glow.java", layerSource("Glow", "net.minecraft.client.renderer.entity.layers.RenderLayer<LivingEntity, Object>", "glow "));
        old.put("mod/BrightGlow.java", layerSource("BrightGlow", "Glow", "bright glow "));
        // A base with a helper of the old render's shape but another name, and the layer under it that declares render.
        old.put("mod/ExtraBase.java", """
                package mod;
                import net.minecraft.world.entity.LivingEntity;
                public abstract class ExtraBase extends net.minecraft.client.renderer.entity.layers.RenderLayer<LivingEntity, Object> {
                    protected ExtraBase(net.minecraft.client.renderer.entity.RenderLayerParent<LivingEntity, Object> parent) { super(parent); }
                    protected void renderExtra(com.mojang.blaze3d.vertex.PoseStack pose, net.minecraft.client.renderer.MultiBufferSource buffers, int light,
                            LivingEntity entity, float a, float b, float c, float d, float e, float f) { Pack.SEEN.add("extra " + entity.name); }
                }""");
        old.put("mod/ExtraLayer.java", layerSource("ExtraLayer", "ExtraBase", "layer under a helper "));
        mod = only(compile(dir.resolve("old"), old), "mod/");
        mod.put("mod/FabricPack", fabricPack());
        mod.put("mod/FabricBright", fabricBright());
        host = compile(dir.resolve("host"), hostSources());
        // A layer written for 26.x, which overrides submit itself.
        Map<String, String> current = new HashMap<>(hostSources());
        current.put("mod/NewLayer.java", """
                package mod;
                import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
                public class NewLayer extends net.minecraft.client.renderer.entity.layers.RenderLayer<LivingEntityRenderState, Object> {
                    public NewLayer(net.minecraft.client.renderer.entity.RenderLayerParent<LivingEntityRenderState, Object> parent) { super(parent); }
                    @Override public void submit(com.mojang.blaze3d.vertex.PoseStack pose, net.minecraft.client.renderer.SubmitNodeCollector collector,
                            int light, LivingEntityRenderState state, float yRot, float xRot) {}
                }""");
        modern = only(compile(dir.resolve("modern"), current), "mod/");
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        com.retromod.util.McReflect.setForceForge(savedForge);
        com.retromod.util.McReflect.setForceNeoForge(savedNeoForge);
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("getSkinMap() fails as written, then answers \"default\" and \"slim\" with the game's own player renderers")
    void skinMapIsAView() throws Throwable {
        Run old = new Run(host, mod);
        Object before = old.call(PROBE, "dispatcher");
        assertInstanceOf(NoSuchMethodError.class, assertThrows(Throwable.class, () -> old.call(INSTALL, "install", before)));

        Run game = new Run(host, transform("26.2", bridgeOn(host), mod));
        Object dispatcher = game.call(PROBE, "dispatcher");
        assertEquals("wide player renderer", String.valueOf(game.call(INSTALL, "skin", dispatcher, "default")));
        assertEquals("slim player renderer", String.valueOf(game.call(INSTALL, "skin", dispatcher, "slim")));
        assertNull(game.call(INSTALL, "skin", dispatcher, "giant"), "a name 1.20.1 never had");
        assertEquals("[slim, default]", game.call(INSTALL, "skins", dispatcher));
        game.call(PROBE, "reload", dispatcher);
        assertEquals("wide player renderer 2", String.valueOf(game.call(INSTALL, "skin", dispatcher, "default")),
                "the view reads the renderers a resource reload built");
    }

    @Test
    @DisplayName("The old layer runs on each frame with the player and the mob its state came from, and its geometry is submitted")
    void oldLayerDrawsForItsEntity() throws Throwable {
        Run game = new Run(host, transform("26.2", bridgeOn(host), mod));
        Object dispatcher = game.call(PROBE, "dispatcher");
        game.call(INSTALL, "install", dispatcher);
        assertEquals("[slim player renderer: 1 layers, wide player renderer: 1 layers, zombie renderer: 1 layers, arrow renderer]",
                game.call(PROBE, "layers", dispatcher), "a layer on both player renderers and the living one in the private map");

        game.call(PROBE, "world");
        assertEquals("[pack (1.0,2.0,3.0) ffffffff, pack (1.0,2.0,3.0) ffffffff]", game.call(PROBE, "frame", dispatcher));
        assertEquals("[Steve limb 0.25/0.5 partial 0.5 age 100.5 head 10.0/20.0 light 15728880,"
                + " Zombie limb 1.0/0.75 partial 0.5 age 40.5 head 10.0/20.0 light 15728880]", game.call(INSTALL, "seen"),
                "the player found by its id, the zombie by its type and the path it moved along this tick");
    }

    @Test
    @DisplayName("A layer that reaches a removed API stops drawing instead of stopping the game")
    void throwingLayerStops() throws Throwable {
        Run game = new Run(host, transform("26.2", bridgeOn(host), mod));
        Object dispatcher = game.call(PROBE, "dispatcher");
        game.call(INSTALL, "install", dispatcher);
        game.call(PROBE, "brokenWorld");
        assertEquals("[]", game.call(PROBE, "frame", dispatcher), "what each layer wrote before it threw is dropped");
        assertEquals(2, game.call(INSTALL, "seen").toString().split("Broken").length - 1, "the player's layer and the zombie's each ran");
        assertEquals("[]", game.call(PROBE, "frame", dispatcher));
        assertEquals(2, game.call(INSTALL, "seen").toString().split("Broken").length - 1, "and each stayed off on the next frame");
    }

    @Test
    @DisplayName("A mixin that extends RenderLayer keeps the shape Mixin checks against its target")
    void mixinStaysAsWritten() {
        ClassNode mixin = node(transform("26.2", bridgeOn(host), mod).get("mod/ArmorMixin"));
        assertEquals(RENDER_LAYER, mixin.superName);
        assertFalse(mixin.interfaces.contains(LegacyRenderLayerAdapter.OLD_LAYER), "no interface added");
        assertFalse(hasSubmit(mixin), "no submit added");
    }

    @Test
    @DisplayName("A layer written for 26.x, which overrides submit, is left as it is, whatever version the mod names")
    void modernLayerStaysAsWritten() {
        for (String version : java.util.Arrays.asList(null, "1.20.1", "1.21.10")) {
            ClassNode layer = node(transform("26.2", t -> {
                t.setModSourceVersion(version);
                bridgeOn(host).accept(t);
            }, modern).get("mod/NewLayer"));
            assertFalse(layer.interfaces.contains(LegacyRenderLayerAdapter.OLD_LAYER), "built for " + version);
            // Its own submit and the bridge javac wrote for it, and nothing added.
            assertEquals(methods(node(modern.get("mod/NewLayer"))), methods(layer), "built for " + version);
        }
    }

    @Test
    @DisplayName("An abstract base with its layer, and a layer with a subclass overriding its render, each draw once")
    void hierarchiesDrawOnce() throws Throwable {
        Map<String, byte[]> out = transform("26.2", bridgeOn(host), mod);
        assertFalse(hasSubmit(node(out.get("mod/BaseLayer"))), "the base declares no render, so nothing to run");
        assertTrue(hasSubmit(node(out.get("mod/Cape"))), "the submit goes where the render is declared");
        assertTrue(hasSubmit(node(out.get("mod/Glow"))));
        assertFalse(hasSubmit(node(out.get("mod/BrightGlow"))), "the subclass inherits its base's submit");

        Run game = new Run(host, out);
        Object dispatcher = game.call(PROBE, "dispatcher");
        game.call(PROBE, "add", dispatcher, "mod.Cape");
        game.call(PROBE, "add", dispatcher, "mod.BrightGlow");
        game.call(PROBE, "world");
        game.call(PROBE, "frame", dispatcher);
        assertEquals("[cape Steve, bright glow Steve]", game.call(INSTALL, "seen"));
    }

    @Test
    @DisplayName("A Fabric layer, whose old render the intermediary map names submit, gets it back as render and draws")
    void fabricOldRenderNamedSubmit() throws Throwable {
        Map<String, byte[]> out = transform("26.2", t -> {
            t.setModSourceVersion("1.20.1");
            bridgeOn(host).accept(t);
        }, mod);
        ClassNode layer = node(out.get("mod/FabricPack"));
        assertTrue(layer.methods.stream().anyMatch(m -> m.name.equals("render") && LegacyRenderLayerAdapter.isOldRender(m.desc)), "renamed back");
        assertTrue(hasSubmit(layer) && layer.interfaces.contains(LegacyRenderLayerAdapter.OLD_LAYER));

        Run game = new Run(host, out);
        Object dispatcher = game.call(PROBE, "dispatcher");
        game.call(PROBE, "add", dispatcher, "mod.FabricPack");
        game.call(PROBE, "world");
        game.call(PROBE, "frame", dispatcher);
        assertEquals("[fabric Steve]", game.call(INSTALL, "seen"));
    }

    @Test
    @DisplayName("A Fabric layer's super.method_4199 follows its base's render back to its name, and both draw")
    void fabricSuperCallFollowsRename() throws Throwable {
        Map<String, byte[]> out = transform("26.2", t -> {
            t.setModSourceVersion("1.20.1");
            bridgeOn(host).accept(t);
        }, mod);
        assertTrue(calls(out.get("mod/FabricBright"), "render").stream().anyMatch(c -> c.startsWith("mod/FabricPack.render(")), "the super call is renamed");
        assertFalse(hasSubmit(node(out.get("mod/FabricBright"))), "it inherits its base's submit");
        Run game = new Run(host, out);
        Object dispatcher = game.call(PROBE, "dispatcher");
        game.call(PROBE, "add", dispatcher, "mod.FabricBright");
        game.call(PROBE, "world");
        game.call(PROBE, "frame", dispatcher);
        assertEquals("[bright, fabric Steve]", game.call(INSTALL, "seen"));
    }

    @Test
    @DisplayName("A base's helper shaped like the old render, under another name, does not keep the layer under it from drawing")
    void ancestorMatchesByName() throws Throwable {
        Map<String, byte[]> out = transform("26.2", bridgeOn(host), mod);
        assertTrue(hasSubmit(node(out.get("mod/ExtraLayer"))), "the layer declaring render gets the submit");
        Run game = new Run(host, out);
        Object dispatcher = game.call(PROBE, "dispatcher");
        game.call(PROBE, "add", dispatcher, "mod.ExtraLayer");
        game.call(PROBE, "world");
        game.call(PROBE, "frame", dispatcher);
        assertEquals("[layer under a helper Steve]", game.call(INSTALL, "seen"));
    }

    @Test
    @DisplayName("Each loader's 26.1 shim takes the bridge on 26.1 to 26.3")
    void everyLoaderOn26() {
        String getSkinMap = DISPATCHER + ".getSkinMap()Ljava/util/Map;";
        for (VersionShim shim : List.of(new Fabric_1_21_11_to_26_1(), new NeoForge_1_21_11_to_26_1(), new Forge_1_21_11_to_26_1())) {
            for (String target : List.of("26.1", "26.2", "26.3")) {
                Map<String, byte[]> out = transform(target, shim::registerRedirects, mod);
                assertFalse(calls(out.get(INSTALL), "install").contains(getSkinMap), shim.getShimName() + " on " + target);
                ClassNode layer = node(out.get("mod/Pack"));
                assertEquals(RENDER_LAYER, layer.superName, shim.getShimName() + " on " + target + ": the base stays the host's");
                assertTrue(layer.interfaces.contains(LegacyRenderLayerAdapter.OLD_LAYER) && hasSubmit(layer), shim.getShimName() + " on " + target);
            }
        }
    }

    @Test
    @DisplayName("The gates follow the host: Forge's public renderer map stays, and a host with getSkinMap keeps it")
    void gates() {
        Function<String, ClassNode> read = reader(host);
        assertTrue(hostDroppedSkinMap(read));
        assertTrue(hostHidesRenderers(read), "vanilla and NeoForge keep it private");
        assertTrue(hostLayersSubmitStates(read));
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        assertFalse(HostBuffers.keeps(read), "26.2 removed MultiBufferSource");
        assertTrue(hostDroppedSkinMap(name -> null), "a server or an offline run: the 26.1 layer stands for the host");

        ClassNode forgeDispatcher = read.apply(DISPATCHER);
        forgeDispatcher.fields.forEach(field -> field.access = org.objectweb.asm.Opcodes.ACC_PUBLIC);
        assertFalse(hostHidesRenderers(name -> name.equals(DISPATCHER) ? forgeDispatcher : read.apply(name)), "Forge 26.2 made it public");
        com.retromod.util.McReflect.setForceNeoForge(false);
        com.retromod.util.McReflect.setForceForge(true);
        assertFalse(hostHidesRenderers(name -> null), "offline, a Forge target reads Forge's public map, as the running game does");
        com.retromod.util.McReflect.setForceForge(false);
        assertTrue(hostHidesRenderers(name -> null), "offline NeoForge and Fabric targets take the view");
        ClassNode oldDispatcher = reader(oldShapes()).apply(DISPATCHER);
        assertFalse(hostDroppedSkinMap(name -> name.equals(DISPATCHER) ? oldDispatcher : null), "1.20.1 keeps its own");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "net/minecraftforge/forge/26.2-65.0.0/forge-26.2-65.0.0-client.jar",
            "net/neoforged/minecraft-client-patched/26.1.2.60-beta/minecraft-client-patched-26.1.2.60-beta.jar",
            "net/neoforged/minecraft-client-patched/26.3.0.43-beta/minecraft-client-patched-26.3.0.43-beta.jar",
            "com/mojang/minecraft/26.1.2/minecraft-26.1.2-client.jar",
            "com/mojang/minecraft/26.3/minecraft-26.3-client.jar"})
    @DisplayName("Every 26.x client jar on this machine takes the skin map and the layer bridge")
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
            assertTrue(hostDroppedSkinMap(read), jar);
            assertTrue(hostLayersSubmitStates(read), jar);
            assertEquals(!jar.contains("forge-26.2"), hostHidesRenderers(read), jar + ": only Forge makes the renderer map public");
            assertEquals(jar.contains("26.1.2"), HostBuffers.keeps(read), jar + ": only 26.1 still has MultiBufferSource");
        }
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static List<String> methods(ClassNode node) {
        return node.methods.stream().map(m -> m.name + m.desc).sorted().toList();
    }

    private static boolean hasSubmit(ClassNode node) {
        return node.methods.stream().anyMatch(m -> m.name.equals("submit") && m.desc.equals(LegacyRenderLayerAdapter.SUBMIT_DESC));
    }

    // An old layer that notes the entity it drew for.
    private static String layerSource(String name, String base, String note) {
        return """
                package mod;
                import net.minecraft.world.entity.LivingEntity;
                public class %s extends %s {
                    public %s(net.minecraft.client.renderer.entity.RenderLayerParent<LivingEntity, Object> parent) { super(parent); }
                    @Override
                    public void render(com.mojang.blaze3d.vertex.PoseStack pose, net.minecraft.client.renderer.MultiBufferSource buffers, int light,
                            LivingEntity entity, float a, float b, float c, float d, float e, float f) { Pack.SEEN.add("%s" + entity.name); }
                }""".formatted(name, base, name, note);
    }

    // As a Fabric 1.20.1 layer reaches the class pass on 26.x: its render, intermediary method_4199, mapped to the name submit.
    private static byte[] fabricPack() {
        org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_MAXS);
        cw.visit(org.objectweb.asm.Opcodes.V17, org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_SUPER, "mod/FabricPack", null,
                RENDER_LAYER, null);
        org.objectweb.asm.MethodVisitor init = cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "<init>",
                "(Lnet/minecraft/client/renderer/entity/RenderLayerParent;)V", null, null);
        init.visitCode();
        init.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
        init.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 1);
        init.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL, RENDER_LAYER, "<init>", "(Lnet/minecraft/client/renderer/entity/RenderLayerParent;)V", false);
        init.visitInsn(org.objectweb.asm.Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        org.objectweb.asm.MethodVisitor render = cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "submit",
                "(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/Entity;FFFFFF)V",
                null, null);
        render.visitCode();
        render.visitFieldInsn(org.objectweb.asm.Opcodes.GETSTATIC, "mod/Pack", "SEEN", "Ljava/util/List;");
        render.visitLdcInsn("fabric ");
        render.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 4);
        render.visitFieldInsn(org.objectweb.asm.Opcodes.GETFIELD, "net/minecraft/world/entity/Entity", "name", "Ljava/lang/String;");
        render.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false);
        render.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true);
        render.visitInsn(org.objectweb.asm.Opcodes.POP);
        render.visitInsn(org.objectweb.asm.Opcodes.RETURN);
        render.visitMaxs(0, 0);
        render.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    // A Fabric 1.20.1 layer over FabricPack whose render calls super.method_4199, both mapped to the name submit.
    private static byte[] fabricBright() {
        String desc = "(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/Entity;FFFFFF)V";
        org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_MAXS);
        cw.visit(org.objectweb.asm.Opcodes.V17, org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_SUPER, "mod/FabricBright", null,
                "mod/FabricPack", null);
        org.objectweb.asm.MethodVisitor init = cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "<init>",
                "(Lnet/minecraft/client/renderer/entity/RenderLayerParent;)V", null, null);
        init.visitCode();
        init.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
        init.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 1);
        init.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL, "mod/FabricPack", "<init>", "(Lnet/minecraft/client/renderer/entity/RenderLayerParent;)V", false);
        init.visitInsn(org.objectweb.asm.Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        org.objectweb.asm.MethodVisitor render = cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "submit", desc, null, null);
        render.visitCode();
        render.visitFieldInsn(org.objectweb.asm.Opcodes.GETSTATIC, "mod/Pack", "SEEN", "Ljava/util/List;");
        render.visitLdcInsn("bright");
        render.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true);
        render.visitInsn(org.objectweb.asm.Opcodes.POP);
        for (int slot = 0; slot <= 3; slot++) {
            render.visitVarInsn(slot == 3 ? org.objectweb.asm.Opcodes.ILOAD : org.objectweb.asm.Opcodes.ALOAD, slot);
        }
        render.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 4);
        for (int slot = 5; slot <= 10; slot++) render.visitVarInsn(org.objectweb.asm.Opcodes.FLOAD, slot);
        render.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL, "mod/FabricPack", "submit", desc, false);
        render.visitInsn(org.objectweb.asm.Opcodes.RETURN);
        render.visitMaxs(0, 0);
        render.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    // The bridge with the stand-in host readable as the game's loader would read it, and the 26.2 buffer stand-ins.
    private static Consumer<RetromodTransformer> bridgeOn(Map<String, byte[]> classes) {
        return t -> {
            RenderBufferSynthetics.register(t);
            LegacyPlayerLayersBridge.register(t, reader(classes));
        };
    }

    static Function<String, ClassNode> reader(Map<String, byte[]> classes) {
        return name -> {
            byte[] bytes = classes.get(name);
            if (bytes == null) return null;
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE);
            return node;
        };
    }

    private static Map<String, byte[]> oldShapes() {
        try {
            Map<String, String> sources = new HashMap<>(shared());
            sources.put("net/minecraft/client/renderer/entity/EntityRenderDispatcher.java", """
                    package net.minecraft.client.renderer.entity;
                    public class EntityRenderDispatcher { public java.util.Map<String, Object> getSkinMap() { return null; } }""");
            return compile(dir.resolve("oldshape"), sources);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // Classes both sides share: vertices, poses, render types and entities.
    static Map<String, String> shared() {
        Map<String, String> sources = new HashMap<>();
        sources.put("com/mojang/blaze3d/vertex/PoseStack.java", """
                package com.mojang.blaze3d.vertex;
                public class PoseStack {
                    private final java.util.ArrayList<Pose> poses = new java.util.ArrayList<>(java.util.List.of(new Pose()));
                    public Pose last() { return poses.get(poses.size() - 1); }
                    public void pushPose() { Pose next = new Pose(); next.set(last()); poses.add(next); }
                    public void popPose() { poses.remove(poses.size() - 1); }
                    public boolean isEmpty() { return poses.size() == 1; }
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
                }""");
        sources.put("com/mojang/blaze3d/vertex/ByteBufferBuilder.java", "package com.mojang.blaze3d.vertex; public class ByteBufferBuilder {}");
        sources.put("net/minecraft/client/renderer/rendertype/RenderType.java", """
                package net.minecraft.client.renderer.rendertype;
                public class RenderType {
                    private final String name;
                    public RenderType(String name) { this.name = name; }
                    @Override public String toString() { return name; }
                }""");
        sources.put("net/minecraft/world/entity/EntityType.java", """
                package net.minecraft.world.entity;
                public class EntityType<T> {
                    public static final EntityType<?> PLAYER = new EntityType<>(), ZOMBIE = new EntityType<>(), ARROW = new EntityType<>();
                }""");
        sources.put("net/minecraft/world/entity/Entity.java", """
                package net.minecraft.world.entity;
                public class Entity {
                    public String name;
                    public int id;
                    public EntityType<?> type;
                    public double x, y, z, xOld, yOld, zOld;
                    public int tickCount;
                    public final double getX() { return x; }
                    public final double getY() { return y; }
                    public final double getZ() { return z; }
                    public EntityType<?> getType() { return type; }
                }""");
        sources.put("net/minecraft/world/entity/LivingEntity.java", "package net.minecraft.world.entity; public class LivingEntity extends Entity {}");
        sources.put("net/minecraft/client/renderer/entity/RenderLayerParent.java", """
                package net.minecraft.client.renderer.entity;
                public interface RenderLayerParent<S, M> {}""");
        return sources;
    }

    /** The 26.2 shapes: renderers picked by state, layers that submit a state, and a client level holding entities by id. */
    static Map<String, String> hostSources() {
        Map<String, String> sources = new HashMap<>(shared());
        sources.put("net/minecraft/client/renderer/OrderedSubmitNodeCollector.java", """
                package net.minecraft.client.renderer;
                public interface OrderedSubmitNodeCollector {
                    void submitCustomGeometry(com.mojang.blaze3d.vertex.PoseStack poseStack,
                            net.minecraft.client.renderer.rendertype.RenderType renderType, SubmitNodeCollector.CustomGeometryRenderer renderer);
                }""");
        sources.put("net/minecraft/client/renderer/SubmitNodeCollector.java", """
                package net.minecraft.client.renderer;
                public interface SubmitNodeCollector extends OrderedSubmitNodeCollector {
                    interface CustomGeometryRenderer {
                        void render(com.mojang.blaze3d.vertex.PoseStack.Pose pose, com.mojang.blaze3d.vertex.VertexConsumer buffer);
                    }
                }""");
        sources.put("net/minecraft/client/renderer/entity/state/EntityRenderState.java", """
                package net.minecraft.client.renderer.entity.state;
                public class EntityRenderState {
                    public net.minecraft.world.entity.EntityType<?> entityType;
                    public double x, y, z;
                    public float ageInTicks;
                }""");
        sources.put("net/minecraft/client/renderer/entity/state/LivingEntityRenderState.java", """
                package net.minecraft.client.renderer.entity.state;
                public class LivingEntityRenderState extends EntityRenderState { public float walkAnimationPos, walkAnimationSpeed; }""");
        sources.put("net/minecraft/client/renderer/entity/state/AvatarRenderState.java", """
                package net.minecraft.client.renderer.entity.state;
                public class AvatarRenderState extends LivingEntityRenderState { public net.minecraft.world.entity.player.PlayerSkin skin; public int id; }""");
        sources.put("net/minecraft/world/entity/player/PlayerModelType.java", """
                package net.minecraft.world.entity.player;
                public enum PlayerModelType {
                    SLIM("slim"), WIDE("wide");
                    private final String id;
                    PlayerModelType(String id) { this.id = id; }
                    public String getSerializedName() { return id; }
                }""");
        sources.put("net/minecraft/core/ClientAsset.java", "package net.minecraft.core; public interface ClientAsset { interface Texture extends ClientAsset {} }");
        sources.put("net/minecraft/world/entity/player/PlayerSkin.java", """
                package net.minecraft.world.entity.player;
                public record PlayerSkin(net.minecraft.core.ClientAsset.Texture body, net.minecraft.core.ClientAsset.Texture cape,
                        net.minecraft.core.ClientAsset.Texture elytra, PlayerModelType model, boolean secure) {
                    public static PlayerSkin insecure(net.minecraft.core.ClientAsset.Texture body, net.minecraft.core.ClientAsset.Texture cape,
                            net.minecraft.core.ClientAsset.Texture elytra, PlayerModelType model) { return new PlayerSkin(body, cape, elytra, model, false); }
                }""");
        sources.put("net/minecraft/client/renderer/entity/EntityRenderer.java", """
                package net.minecraft.client.renderer.entity;
                public abstract class EntityRenderer<T, S> {
                    private final String name;
                    protected EntityRenderer(String name) { this.name = name; }
                    @Override public String toString() { return name; }
                }""");
        sources.put("net/minecraft/client/renderer/entity/LivingEntityRenderer.java", """
                package net.minecraft.client.renderer.entity;
                import net.minecraft.client.renderer.entity.layers.RenderLayer;
                public class LivingEntityRenderer<T, S extends net.minecraft.client.renderer.entity.state.LivingEntityRenderState, M>
                        extends EntityRenderer<T, S> implements RenderLayerParent<S, M> {
                    public final java.util.List<RenderLayer<S, M>> layers = new java.util.ArrayList<>();
                    public LivingEntityRenderer(String name) { super(name); }
                    public final boolean addLayer(RenderLayer<S, M> layer) { return layers.add(layer); }
                    // What 26.2's submit does for the layers once the model is posed.
                    public void submitLayers(S state, net.minecraft.client.renderer.SubmitNodeCollector collector) {
                        for (RenderLayer<S, M> layer : layers) layer.submit(new com.mojang.blaze3d.vertex.PoseStack(), collector, 15728880, state, 10f, 20f);
                    }
                }""");
        sources.put("net/minecraft/client/renderer/entity/layers/RenderLayer.java", """
                package net.minecraft.client.renderer.entity.layers;
                public abstract class RenderLayer<S extends net.minecraft.client.renderer.entity.state.EntityRenderState, M> {
                    public RenderLayer(net.minecraft.client.renderer.entity.RenderLayerParent<S, M> parent) {}
                    public abstract void submit(com.mojang.blaze3d.vertex.PoseStack poseStack, net.minecraft.client.renderer.SubmitNodeCollector collector,
                            int lightCoords, S state, float yRot, float xRot);
                }""");
        sources.put("net/minecraft/client/renderer/entity/EntityRenderDispatcher.java", """
                package net.minecraft.client.renderer.entity;
                import java.util.*;
                import net.minecraft.client.renderer.entity.state.*;
                import net.minecraft.world.entity.EntityType;
                import net.minecraft.world.entity.player.PlayerModelType;
                public class EntityRenderDispatcher {
                    private Map<EntityType<?>, EntityRenderer<?, ?>> renderers = new LinkedHashMap<>();
                    private Map<PlayerModelType, EntityRenderer<?, ?>> playerRenderers = new EnumMap<>(PlayerModelType.class);
                    public <S extends EntityRenderState> EntityRenderer<?, ? super S> getRenderer(S state) {
                        if (state instanceof AvatarRenderState avatar) return (EntityRenderer) playerRenderers.get(avatar.skin.model());
                        return (EntityRenderer) renderers.get(state.entityType);
                    }
                    // A resource reload builds every renderer again.
                    public void build(String suffix) {
                        playerRenderers.put(PlayerModelType.WIDE, new LivingEntityRenderer<>("wide player renderer" + suffix));
                        playerRenderers.put(PlayerModelType.SLIM, new LivingEntityRenderer<>("slim player renderer" + suffix));
                        renderers = new LinkedHashMap<>();
                        renderers.put(EntityType.ZOMBIE, new LivingEntityRenderer<>("zombie renderer" + suffix));
                        renderers.put(EntityType.ARROW, new EntityRenderer<Object, EntityRenderState>("arrow renderer" + suffix) {});
                    }
                    public List<String> layers() {
                        List<String> out = new ArrayList<>();
                        for (EntityRenderer<?, ?> r : playerRenderers.values()) out.add(describe(r));
                        for (EntityRenderer<?, ?> r : renderers.values()) out.add(describe(r));
                        out.sort(Comparator.comparing(s -> s.startsWith("arrow") ? 2 : s.startsWith("zombie") ? 1 : 0));
                        return out;
                    }
                    private static String describe(EntityRenderer<?, ?> r) {
                        return r instanceof LivingEntityRenderer<?, ?, ?> l ? r + ": " + l.layers.size() + " layers" : r.toString();
                    }
                    public LivingEntityRenderer<?, ?, ?> player(PlayerModelType type) { return (LivingEntityRenderer<?, ?, ?>) playerRenderers.get(type); }
                    public LivingEntityRenderer<?, ?, ?> living(EntityType<?> type) { return (LivingEntityRenderer<?, ?, ?>) renderers.get(type); }
                }""");
        sources.put("net/minecraft/core/Registry.java", "package net.minecraft.core; public interface Registry<T> extends Iterable<T> {}");
        sources.put("net/minecraft/core/DefaultedRegistry.java", "package net.minecraft.core; public interface DefaultedRegistry<T> extends Registry<T> {}");
        sources.put("net/minecraft/core/registries/BuiltInRegistries.java", """
                package net.minecraft.core.registries;
                import net.minecraft.world.entity.EntityType;
                public class BuiltInRegistries {
                    public static final net.minecraft.core.DefaultedRegistry<EntityType<?>> ENTITY_TYPE =
                            () -> java.util.List.<EntityType<?>>of(EntityType.PLAYER, EntityType.ZOMBIE, EntityType.ARROW).iterator();
                }""");
        sources.put("net/minecraft/world/phys/AABB.java", """
                package net.minecraft.world.phys;
                public class AABB {
                    public final double minX, minY, minZ, maxX, maxY, maxZ;
                    public AABB(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
                        this.minX = minX; this.minY = minY; this.minZ = minZ; this.maxX = maxX; this.maxY = maxY; this.maxZ = maxZ;
                    }
                }""");
        sources.put("net/minecraft/world/level/Level.java", """
                package net.minecraft.world.level;
                import net.minecraft.world.entity.Entity;
                public class Level {
                    public final java.util.List<Entity> entities = new java.util.ArrayList<>();
                    public Entity getEntity(int id) { return entities.stream().filter(e -> e.id == id).findFirst().orElse(null); }
                    public java.util.List<Entity> getEntities(Entity except, net.minecraft.world.phys.AABB area, java.util.function.Predicate<? super Entity> filter) {
                        return entities.stream().filter(e -> e != except && e.x >= area.minX && e.x <= area.maxX && e.z >= area.minZ && e.z <= area.maxZ)
                                .filter(filter).toList();
                    }
                }""");
        sources.put("net/minecraft/client/multiplayer/ClientLevel.java", "package net.minecraft.client.multiplayer; public class ClientLevel extends net.minecraft.world.level.Level {}");
        sources.put("net/minecraft/client/DeltaTracker.java", "package net.minecraft.client; public interface DeltaTracker { float getGameTimeDeltaPartialTick(boolean runsNormally); }");
        sources.put("net/minecraft/client/Minecraft.java", """
                package net.minecraft.client;
                public class Minecraft {
                    public static Minecraft instance;
                    public net.minecraft.client.multiplayer.ClientLevel level;
                    public static Minecraft getInstance() { return instance; }
                    public DeltaTracker getDeltaTracker() { return runsNormally -> 0.5f; }
                }""");
        // A frame: each renderer submits its layers for one state, and the collector draws what they submitted.
        sources.put("testhost/Probe.java", """
                package testhost;
                import java.util.*;
                import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
                import net.minecraft.client.renderer.entity.state.*;
                import net.minecraft.world.entity.*;
                import net.minecraft.world.entity.player.*;
                @SuppressWarnings({"rawtypes", "unchecked"})
                public class Probe {
                    public static Object dispatcher() { EntityRenderDispatcher d = new EntityRenderDispatcher(); d.build(""); return d; }
                    public static void reload(Object d) { ((EntityRenderDispatcher) d).build(" 2"); }
                    public static String layers(Object d) { return ((EntityRenderDispatcher) d).layers().toString(); }
                    public static void world() { world("Steve", "Zombie"); }
                    public static void brokenWorld() { world("Broken", "Broken"); }
                    private static void world(String player, String mob) {
                        net.minecraft.client.Minecraft.instance = new net.minecraft.client.Minecraft();
                        net.minecraft.client.Minecraft.instance.level = new net.minecraft.client.multiplayer.ClientLevel();
                        net.minecraft.client.Minecraft.instance.level.entities.add(entity(player, 7, EntityType.PLAYER, 1, 1, 1, 1, 100));
                        // Two zombies; the one whose path this tick holds the state is the one.
                        net.minecraft.client.Minecraft.instance.level.entities.add(entity("Other", 8, EntityType.ZOMBIE, 10, 10.6, 10, 10.6, 40));
                        net.minecraft.client.Minecraft.instance.level.entities.add(entity(mob, 9, EntityType.ZOMBIE, 10, 10, 11, 10, 40));
                    }
                    private static Entity entity(String name, int id, EntityType<?> type, double xOld, double zOld, double x, double z, int ticks) {
                        Entity e = new LivingEntity();
                        e.name = name; e.id = id; e.type = type; e.xOld = xOld; e.zOld = zOld; e.x = x; e.z = z; e.y = e.yOld = 64; e.tickCount = ticks;
                        return e;
                    }
                    // A layer the test builds itself, given the wide player renderer as its parent.
                    public static void add(Object dispatcher, String layerClass) throws Exception {
                        net.minecraft.client.renderer.entity.LivingEntityRenderer r = ((EntityRenderDispatcher) dispatcher).player(PlayerModelType.WIDE);
                        Object layer = Class.forName(layerClass, true, Probe.class.getClassLoader())
                                .getConstructor(net.minecraft.client.renderer.entity.RenderLayerParent.class).newInstance(r);
                        r.addLayer((net.minecraft.client.renderer.entity.layers.RenderLayer) layer);
                    }
                    public static String frame(Object dispatcher) {
                        EntityRenderDispatcher d = (EntityRenderDispatcher) dispatcher;
                        List<String> drawn = new ArrayList<>();
                        net.minecraft.client.renderer.SubmitNodeCollector collector = (pose, type, renderer) -> renderer.render(pose.last(), new Recorder(type.toString(), drawn));
                        AvatarRenderState player = new AvatarRenderState();
                        player.entityType = EntityType.PLAYER; player.id = 7; player.skin = PlayerSkin.insecure(null, null, null, PlayerModelType.WIDE);
                        player.x = 1; player.y = 64; player.z = 1; player.ageInTicks = 100.5f; player.walkAnimationPos = 0.25f; player.walkAnimationSpeed = 0.5f;
                        ((net.minecraft.client.renderer.entity.LivingEntityRenderer) d.player(PlayerModelType.WIDE)).submitLayers(player, collector);
                        LivingEntityRenderState zombie = new LivingEntityRenderState();
                        zombie.entityType = EntityType.ZOMBIE; zombie.x = 10.5; zombie.y = 64; zombie.z = 10; zombie.ageInTicks = 40.5f;
                        zombie.walkAnimationPos = 1f; zombie.walkAnimationSpeed = 0.75f;
                        ((net.minecraft.client.renderer.entity.LivingEntityRenderer) d.living(EntityType.ZOMBIE)).submitLayers(zombie, collector);
                        return drawn.toString();
                    }
                    static final class Recorder implements com.mojang.blaze3d.vertex.VertexConsumer {
                        private final String type; private final List<String> drawn;
                        Recorder(String type, List<String> drawn) { this.type = type; this.drawn = drawn; }
                        public com.mojang.blaze3d.vertex.VertexConsumer addVertex(float x, float y, float z) { drawn.add(type + " (" + x + "," + y + "," + z + ")"); return this; }
                        public com.mojang.blaze3d.vertex.VertexConsumer setColor(int r, int g, int b, int a) { return this; }
                        public com.mojang.blaze3d.vertex.VertexConsumer setColor(int argb) {
                            drawn.set(drawn.size() - 1, drawn.get(drawn.size() - 1) + " " + Integer.toHexString(argb)); return this;
                        }
                        public com.mojang.blaze3d.vertex.VertexConsumer setUv(float u, float v) { return this; }
                        public com.mojang.blaze3d.vertex.VertexConsumer setUv1(int u, int v) { return this; }
                        public com.mojang.blaze3d.vertex.VertexConsumer setUv2(int u, int v) { return this; }
                        public com.mojang.blaze3d.vertex.VertexConsumer setNormal(float x, float y, float z) { return this; }
                        public com.mojang.blaze3d.vertex.VertexConsumer setLineWidth(float width) { return this; }
                    }
                }""");
        return sources;
    }
}
