/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Old calls that take or return a buffer source reach a rebuilt helper, or are dropped when no host method can take the stand-in. */
class LegacyStandInCallsBridgeTest {

    private static final String HELPER = LegacyStandInCallsBridge.HELPER;
    private static final String MBS = "net/minecraft/client/renderer/MultiBufferSource";
    private static final String BS = MBS + "$BufferSource";
    private static final String FONT = "net/minecraft/client/gui/Font";
    private static final String DRAW = "(Ljava/lang/String;FFIZLorg/joml/Matrix4f;L" + MBS + ";Lnet/minecraft/client/gui/Font$DisplayMode;II)I";
    private static final String OUTLINE = "(Lnet/minecraft/util/FormattedCharSequence;FFIILorg/joml/Matrix4f;L" + MBS + ";I)V";
    private static final String BUFFER = "(L" + MBS + ";Ljava/util/function/Function;)Lcom/mojang/blaze3d/vertex/VertexConsumer;";
    private static final String RENDER = "(Lnet/minecraft/world/entity/Entity;FFLcom/mojang/blaze3d/vertex/PoseStack;L" + MBS + ";I)V";
    private static final String IN_HAND = LegacyStandInCallsBridge.ITEM_IN_HAND;
    private static final String RENDER_ITEM = "(" + LegacyStandInCallsBridge.RENDER_ITEM_ARGS + "L" + MBS + ";I)V";
    private static final String OLD_CONTEXT = "(Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;"
            + "Lnet/minecraft/client/renderer/entity/ItemRenderer;Lnet/minecraft/client/renderer/block/BlockRenderDispatcher;L"
            + LegacyStandInCallsBridge.ITEM_IN_HAND + ";Lnet/minecraft/server/packs/resources/ResourceManager;"
            + "Lnet/minecraft/client/model/geom/EntityModelSet;Lnet/minecraft/client/gui/Font;)V";
    private static final Path LIBRARIES = Path.of(System.getProperty("user.home", ""),
            "Library/Application Support/PrismLauncher/libraries");
    private String savedVersion;

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
        if (savedVersion != null) RetromodVersion.TARGET_MC_VERSION = savedVersion;
    }

    @Test
    @DisplayName("offline for 26.3 each old call reaches its helper or is dropped, and the code still verifies")
    void offline263() throws Exception {
        ClassNode out = transformOldCalls("26.3", name -> null);
        List<String> refs = refs(out);

        assertTrue(refs.contains(HELPER + ".drawInBatch(L" + FONT + ";Ljava/lang/String;FFIZLjava/lang/Object;Ljava/lang/Object;"
                + "Ljava/lang/Object;II)I"), refs.toString());
        assertTrue(refs.contains(HELPER + ".sprite(L" + LegacyStandInCallsBridge.SPRITE_ID
                + ";)Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;"), refs.toString());
        assertTrue(refs.stream().anyMatch(r -> r.startsWith(HELPER + ".buffer(L" + LegacyStandInCallsBridge.SPRITE_ID + ";L"
                + RenderBufferSynthetics.MBS + ";")), refs.toString());
        assertTrue(refs.stream().anyMatch(r -> r.startsWith(HELPER + ".graphics(Lnet/minecraft/client/Minecraft;Ljava/lang/Object;)")),
                refs.toString());
        // 26.3 removed the item-in-hand renderer, so both getters hand out the stand-in, which keeps the old draw.
        String legacy = LegacyStandInCallsBridge.LEGACY_ITEM_IN_HAND;
        assertEquals(2, refs.stream().filter(r -> r.equals(legacy + ".get(Ljava/lang/Object;)L" + legacy + ";")).count(), refs.toString());
        assertTrue(refs.contains(legacy + ".renderItem(" + LegacyStandInCallsBridge.RENDER_ITEM_ARGS + "L" + RenderBufferSynthetics.MBS + ";I)V"),
                refs.toString());
        // A dropped call that returned a vertex consumer hands out the shared builder, so the next vertex call still runs.
        assertTrue(refs.contains(RenderBufferSynthetics.TES + ".sink()Lcom/mojang/blaze3d/vertex/BufferBuilder;"), refs.toString());
        // Constructors that took the old renderers build the host's layers and context, with no stand-in left in them.
        assertTrue(refs.contains(LegacyStandInCallsBridge.HELD_ITEM_LAYERS[0] + ".<init>(Lnet/minecraft/client/renderer/entity/RenderLayerParent;)V"),
                refs.toString());
        assertTrue(refs.contains(LegacyStandInCallsBridge.HEAD_LAYER + ".<init>(Lnet/minecraft/client/renderer/entity/RenderLayerParent;"
                + "Lnet/minecraft/client/model/geom/EntityModelSet;Lnet/minecraft/client/renderer/PlayerSkinRenderCache;)V"), refs.toString());
        assertTrue(refs.stream().anyMatch(r -> r.startsWith(LegacyStandInCallsBridge.CONTEXTS + ".create(")), refs.toString());
        assertFalse(refs.stream().anyMatch(r -> r.startsWith("net/minecraft/") && r.contains("<init>") && r.contains("com/retromod/")),
                refs.toString());
        // bufferSource() answers the shared stand-in, and the outline and entity draws are dropped.
        assertTrue(refs.contains(RenderBufferSynthetics.BS + ".shared()L" + RenderBufferSynthetics.BS + ";"), refs.toString());
        assertFalse(refs.stream().anyMatch(r -> r.contains("bufferSource") || r.contains("drawInBatch8xOutline")
                || r.contains(".render(")), refs.toString());
        for (MethodNode method : out.methods) new Analyzer<>(new BasicVerifier()).analyze(out.name, method);
    }

    @Test
    @DisplayName("offline for 26.1 the old text draw still draws through the void form")
    void offline261DrawsText() throws Exception {
        List<String> refs = refs(transformOldCalls("26.1", name -> null));
        assertTrue(refs.contains(FONT + ".drawInBatch(Ljava/lang/String;FFIZLorg/joml/Matrix4fc;L" + MBS
                + ";Lnet/minecraft/client/gui/Font$DisplayMode;II)V"), refs.toString());
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("real hosts: 26.1 keeps drawing text through the void draw, 26.2 and newer get the helper")
    @CsvSource({
            "26.1.2, com/mojang/minecraft/26.1.2/minecraft-26.1.2-client.jar, true",
            "26.2, com/mojang/minecraft/26.2/minecraft-26.2-client.jar, false",
            "26.3, com/mojang/minecraft/26.3/minecraft-26.3-client.jar, false",
            "26.3, net/neoforged/minecraft-client-patched/26.3.0.43-beta/minecraft-client-patched-26.3.0.43-beta.jar, false",
            "26.2, net/minecraftforge/forge/26.2-65.0.0/forge-26.2-65.0.0-client.jar, false"})
    void realHosts(String version, String jar, boolean keepsBuffers) throws Exception {
        Path host = LIBRARIES.resolve(jar);
        assumeTrue(Files.isRegularFile(host), "needs " + host);
        List<String> refs;
        try (ZipFile zip = new ZipFile(host.toFile())) {
            refs = refs(transformOldCalls(version, name -> read(zip, name)));
        }
        String buffers = keepsBuffers ? MBS : RenderBufferSynthetics.MBS;
        assertEquals(keepsBuffers, refs.contains(FONT + ".drawInBatch(Ljava/lang/String;FFIZLorg/joml/Matrix4fc;L" + MBS
                + ";Lnet/minecraft/client/gui/Font$DisplayMode;II)V"), refs.toString());
        assertEquals(!keepsBuffers, refs.stream().anyMatch(r -> r.startsWith(HELPER + ".drawInBatch(")), refs.toString());
        assertTrue(refs.stream().anyMatch(r -> r.startsWith(HELPER + ".buffer(L" + LegacyStandInCallsBridge.SPRITE_ID + ";L" + buffers + ";")),
                refs.toString());
        assertTrue(refs.stream().anyMatch(r -> r.startsWith(HELPER + ".sprite(")), refs.toString());
        assertTrue(refs.stream().anyMatch(r -> r.startsWith(HELPER + ".graphics(")), refs.toString());
        String legacy = LegacyStandInCallsBridge.LEGACY_ITEM_IN_HAND;
        boolean classGone = version.equals("26.3");
        assertEquals(classGone, refs.contains(legacy + ".get(Ljava/lang/Object;)L" + legacy + ";"), refs.toString());
        assertEquals(!classGone, refs.contains(LegacyStandInCallsBridge.HOST_ITEM_IN_HAND + ".get(Ljava/lang/Object;)L" + IN_HAND + ";"),
                refs.toString());
        assertEquals(!classGone, refs.contains(legacy + ".renderItem(Ljava/lang/Object;" + LegacyStandInCallsBridge.RENDER_ITEM_ARGS + "L"
                + buffers + ";I)V"), refs.toString());
    }

    @Test
    @DisplayName("a supplier taking an Object receives the call's receiver")
    void supplierTakesReceiver() throws Exception {
        String shaper = "com/example/Shaper";
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/StandIn", null, "java/lang/Object", null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/client/resources/model/ModelManager", "getBlockModelShaper",
                "()L" + shaper + ";", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        byte[] out = StandInCallFallback.apply(writer.toByteArray(),
                Map.of(shaper, shaper + ".of(Ljava/lang/Object;)L" + shaper + ";"), Map.of(), name -> false);
        ClassNode node = new ClassNode();
        new ClassReader(out).accept(node, 0);
        List<String> refs = refs(node);
        assertEquals(List.of(shaper + ".of(Ljava/lang/Object;)L" + shaper + ";"), refs);
        new Analyzer<>(new BasicVerifier()).analyze(node.name, node.methods.get(0));
    }

    @Test
    @DisplayName("a Retromod class in a game package keeps its stand-in call")
    void syntheticOwnerIsLeftAlone() {
        MethodInsnNode call = new MethodInsnNode(Opcodes.INVOKESTATIC, "net/minecraft/Rebuilt", "draw",
                "(L" + RenderBufferSynthetics.MBS + ";)V", false);
        Map<String, String> standIns = Map.of(RenderBufferSynthetics.MBS, "");
        assertTrue(StandInCallFallback.cannotLink(call, standIns, name -> false));
        assertFalse(StandInCallFallback.cannotLink(call, standIns, name -> name.equals("net/minecraft/Rebuilt")));
    }

    private ClassNode transformOldCalls(String version, Function<String, ClassNode> host) {
        savedVersion = RetromodVersion.TARGET_MC_VERSION;
        RetromodVersion.TARGET_MC_VERSION = version;
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        // The 26.1 renames the full chain registers, then the 26.2 stand-ins where the host lost the buffer source.
        t.registerClassRedirect("net/minecraft/client/gui/GuiGraphics", LegacyStandInCallsBridge.EXTRACTOR);
        t.registerClassRedirect(LegacyStandInCallsBridge.OLD_MATERIAL, LegacyStandInCallsBridge.SPRITE_ID);
        if (!HostBuffers.keeps(host)) RenderBufferSynthetics.register(t);
        LegacyStandInCallsBridge.register(t, host);
        ClassNode node = new ClassNode();
        new ClassReader(t.transformClass(oldCalls(), "test/render/Old")).accept(node, 0);
        return node;
    }

    /** One call of each kind, the way a 1.21.1 Forge mod made it. */
    private static byte[] oldCalls() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/render/Old", null, "java/lang/Object", null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
        mv.visitCode();
        nulls(mv, 1, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LegacyStandInCallsBridge.OLD_MATERIAL, "sprite",
                "()Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;", false);
        mv.visitInsn(Opcodes.POP);
        nulls(mv, 3, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LegacyStandInCallsBridge.OLD_MATERIAL, "buffer", BUFFER, false);
        mv.visitInsn(Opcodes.POP);
        // font.drawInBatch("", 0, 0, 0, false, null, null, null, 0, 0)
        nulls(mv, 2, 0);
        mv.visitInsn(Opcodes.FCONST_0);
        mv.visitInsn(Opcodes.FCONST_0);
        nulls(mv, 0, 2);
        nulls(mv, 3, 2);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FONT, "drawInBatch", DRAW, false);
        mv.visitInsn(Opcodes.POP);
        nulls(mv, 2, 0);
        mv.visitInsn(Opcodes.FCONST_0);
        mv.visitInsn(Opcodes.FCONST_0);
        nulls(mv, 0, 2);
        nulls(mv, 2, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FONT, "drawInBatch8xOutline", OUTLINE, false);
        // new GuiGraphics(minecraft, buffers).bufferSource()
        mv.visitTypeInsn(Opcodes.NEW, "net/minecraft/client/gui/GuiGraphics");
        mv.visitInsn(Opcodes.DUP);
        nulls(mv, 2, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "net/minecraft/client/gui/GuiGraphics", "<init>",
                "(Lnet/minecraft/client/Minecraft;L" + BS + ";)V", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/client/gui/GuiGraphics", "bufferSource", "()L" + BS + ";", false);
        mv.visitInsn(Opcodes.POP);
        // context.getItemInHandRenderer().renderItem(entity, stack, context, left, pose, buffers, light)
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LegacyStandInCallsBridge.RENDERER_CONTEXT, "getItemInHandRenderer", "()L" + IN_HAND + ";", false);
        nulls(mv, 3, 1);
        nulls(mv, 2, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, IN_HAND, "renderItem", RENDER_ITEM, false);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LegacyStandInCallsBridge.ENTITY_DISPATCHER, "getItemInHandRenderer", "()L" + IN_HAND + ";", false);
        mv.visitInsn(Opcodes.POP);
        // ItemRenderer.getFoilBuffer(buffers, type, item, foil).addVertex(0, 0, 0)
        nulls(mv, 2, 2);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/client/renderer/entity/ItemRenderer", "getFoilBuffer", "(L" + MBS
                + ";Lnet/minecraft/client/renderer/rendertype/RenderType;ZZ)Lcom/mojang/blaze3d/vertex/VertexConsumer;", false);
        mv.visitInsn(Opcodes.FCONST_0);
        mv.visitInsn(Opcodes.FCONST_0);
        mv.visitInsn(Opcodes.FCONST_0);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "com/mojang/blaze3d/vertex/VertexConsumer", "addVertex",
                "(FFF)Lcom/mojang/blaze3d/vertex/VertexConsumer;", true);
        mv.visitInsn(Opcodes.POP);
        // new ItemInHandLayer(parent, renderer), new CustomHeadLayer(parent, models, renderer), and the 1.21.1 renderer context
        String layer = LegacyStandInCallsBridge.HELD_ITEM_LAYERS[0];
        String parent = "Lnet/minecraft/client/renderer/entity/RenderLayerParent;";
        String models = "Lnet/minecraft/client/model/geom/EntityModelSet;";
        mv.visitTypeInsn(Opcodes.NEW, layer);
        mv.visitInsn(Opcodes.DUP);
        nulls(mv, 2, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, layer, "<init>", "(" + parent + "L" + IN_HAND + ";)V", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitTypeInsn(Opcodes.NEW, LegacyStandInCallsBridge.HEAD_LAYER);
        mv.visitInsn(Opcodes.DUP);
        nulls(mv, 3, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, LegacyStandInCallsBridge.HEAD_LAYER, "<init>", "(" + parent + models + "L" + IN_HAND + ";)V", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitTypeInsn(Opcodes.NEW, LegacyStandInCallsBridge.RENDERER_CONTEXT);
        mv.visitInsn(Opcodes.DUP);
        nulls(mv, 7, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, LegacyStandInCallsBridge.RENDERER_CONTEXT, "<init>", OLD_CONTEXT, false);
        mv.visitInsn(Opcodes.POP);
        // renderer.render(entity, yaw, partial, pose, buffers, light)
        nulls(mv, 2, 0);
        mv.visitInsn(Opcodes.FCONST_0);
        mv.visitInsn(Opcodes.FCONST_0);
        nulls(mv, 2, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/client/renderer/entity/EntityRenderer", "render", RENDER, false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void nulls(MethodVisitor mv, int references, int ints) {
        for (int i = 0; i < references; i++) mv.visitInsn(Opcodes.ACONST_NULL);
        for (int i = 0; i < ints; i++) mv.visitInsn(Opcodes.ICONST_0);
    }

    private static ClassNode read(ZipFile zip, String name) {
        try {
            ZipEntry entry = zip.getEntry(name + ".class");
            if (entry == null) return null;
            ClassNode node = new ClassNode();
            new ClassReader(zip.getInputStream(entry).readAllBytes()).accept(node, ClassReader.SKIP_CODE);
            return node;
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static List<String> refs(ClassNode node) {
        List<String> refs = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) refs.add(call.owner + "." + call.name + call.desc);
            }
        }
        return refs;
    }
}
