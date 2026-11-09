/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.retromod.shim.forge.LegacyForgeModelBridge.*;
import static com.retromod.shim.forge.LegacyForgeRenderBridgeTest.host;
import static com.retromod.shim.forge.LegacyForgeRenderBridgeTest.node;
import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** Old render type sets, render type groups and int[] quad calls point at classes rebuilt inside the mod, on Forge 26.2 and newer only. */
class LegacyForgeModelBridgeTest {

    static final String RENDER_TYPE = "net/minecraft/client/renderer/rendertype/RenderType";
    static final String RENDER_TYPES = "net/minecraft/client/renderer/rendertype/RenderTypes";
    static final String BLOCK = "net/minecraft/world/level/block/Block";
    static final String BLOCK_STATE = "net/minecraft/world/level/block/state/BlockState";
    static final String DIRECTION = "net/minecraft/core/Direction";
    static final String SPRITE = "net/minecraft/client/renderer/texture/TextureAtlasSprite";
    static final String POLYFILL = "com/retromod/polyfill/minecraft/RetroItemBlockRenderTypes";
    static final String CALLER = "mod/ModelCalls";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @BeforeEach
    void forgeTarget() {
        McReflect.setForceNeoForge(false);
        McReflect.setForceForge(true);
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        transformer.clearRedirectsForTesting();
    }

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("The Forge 26.2 shim registers the rebuilt model classes, which carry on to Forge 26.3")
    void forgeHostsFrom262TakeTheBridge(String host) {
        RetromodVersion.TARGET_MC_VERSION = host;
        new Forge_26_1_to_26_2().registerRedirects(transformer);

        Map<String, String> redirects = transformer.getClassRedirects();
        assertEquals(CHUNK_RENDER_TYPE_SET, redirects.get(OLD_CHUNK_RENDER_TYPE_SET));
        assertEquals(QUAD_TRANSFORMER, redirects.get(OLD_QUAD_TRANSFORMER));
        assertEquals(QUAD_TRANSFORMERS, redirects.get(OLD_QUAD_TRANSFORMERS));
        Set<String> synthetics = transformer.getSyntheticClasses().keySet();
        for (String rebuilt : List.of(CHUNK_RENDER_TYPE_SET, QUAD_TRANSFORMER, QUAD_TRANSFORMERS, QUAD_VERTICES, QUAD_MATERIALS,
                RENDER_LAYERS, RENDER_TYPE_GROUPS, POLYFILL, "com/retromod/shim/common/embedded/LegacyLog")) {
            assertTrue(synthetics.contains(rebuilt), rebuilt + " is registered for embedding");
        }
    }

    @Test
    @DisplayName("NeoForge, other loaders, Forge 1.21.11 and 26.1, and a Forge host that still has the old classes take nothing")
    void otherHostsAreRefused() {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        McReflect.setForceForge(false);
        McReflect.setForceNeoForge(true);
        new Forge_26_1_to_26_2().registerRedirects(transformer);
        LegacyForgeModelBridge.register(transformer);
        assertNull(transformer.getClassRedirects().get(OLD_CHUNK_RENDER_TYPE_SET), "NeoForge keeps its own ChunkRenderTypeSet");

        McReflect.setForceNeoForge(false);
        LegacyForgeModelBridge.register(transformer);
        assertNull(transformer.getClassRedirects().get(OLD_CHUNK_RENDER_TYPE_SET), "a Fabric or Quilt run takes no Forge bridge");

        McReflect.setForceForge(true);
        for (String older : List.of("1.21.11", "26.1")) {
            RetromodVersion.TARGET_MC_VERSION = older;
            LegacyForgeModelBridge.register(transformer);
            assertNull(transformer.getClassRedirects().get(OLD_CHUNK_RENDER_TYPE_SET), "Forge " + older + " is not a checked host");
        }

        RetromodVersion.TARGET_MC_VERSION = "26.2";
        LegacyForgeModelBridge.register(transformer, host(ForgeHostShape.ANCHOR, RENDER_TYPE_GROUP, OLD_CHUNK_RENDER_TYPE_SET));
        assertNull(transformer.getClassRedirects().get(OLD_CHUNK_RENDER_TYPE_SET), "a host that still has the old set keeps it");
        assertNull(transformer.getClassRedirects().get(OLD_QUAD_TRANSFORMER));
        LegacyForgeModelBridge.register(transformer, host(ForgeHostShape.ANCHOR));
        assertNull(transformer.getClassRedirects().get(OLD_CHUNK_RENDER_TYPE_SET), "a host with no render type groups at all");
        LegacyForgeModelBridge.register(transformer, host(ForgeHostShape.ANCHOR, RENDER_TYPE_GROUP));
        assertEquals(CHUNK_RENDER_TYPE_SET, transformer.getClassRedirects().get(OLD_CHUNK_RENDER_TYPE_SET),
                "a host with the new groups and no set");
    }

    @Test
    @DisplayName("A Forge 1.20.1 model class reaches the rebuilt set, layer table, groups, transformers and quad factory")
    void oldModelCodeTakesTheRebuiltClasses() {
        register("1.20.1");
        ClassNode out = node(transformer.transformClass(modelCalls(QUAD), CALLER + ".class"));

        assertEquals(List.of(
                RENDER_LAYERS + ".getRenderLayers(L" + BLOCK_STATE + ";)L" + CHUNK_RENDER_TYPE_SET + ";",
                CHUNK_RENDER_TYPE_SET + ".contains(L" + RENDER_TYPE + ";)Z",
                RENDER_LAYERS + ".setRenderLayer(L" + BLOCK + ";L" + CHUNK_RENDER_TYPE_SET + ";)V"),
                calls(method(out, "layers")));
        assertEquals(List.of(RENDER_TYPE_GROUPS + ".create(L" + RENDER_TYPE + ";L" + RENDER_TYPE + ";)L" + RENDER_TYPE_GROUP + ";",
                RENDER_TYPE_GROUPS + ".block(L" + RENDER_TYPE_GROUP + ";)L" + RENDER_TYPE + ";",
                RENDER_TYPE_GROUP + ".entity()L" + RENDER_TYPE + ";"), calls(method(out, "groups")));
        assertEquals(List.of(QUAD_TRANSFORMERS + ".settingEmissivity(I)L" + QUAD_TRANSFORMER + ";",
                QUAD_TRANSFORMER + ".processInPlace(L" + QUAD + ";)V"), calls(method(out, "transformers")));
        assertEquals(List.of(QUAD_TRANSFORMER + ".STRIDE:I"), fields(method(out, "transformers")));
        assertEquals(List.of(QUAD_VERTICES + ".create([IIL" + DIRECTION + ";L" + SPRITE + ";ZZ)L" + QUAD + ";",
                QUAD_VERTICES + ".getVertices(L" + QUAD + ";)[I", QUAD_VERTICES + ".getTintIndex(L" + QUAD + ";)I",
                QUAD_VERTICES + ".getSprite(L" + QUAD + ";)L" + SPRITE + ";", QUAD_VERTICES + ".isShade(L" + QUAD + ";)Z",
                QUAD_VERTICES + ".hasAmbientOcclusion(L" + QUAD + ";)Z", QUAD + ".direction()L" + DIRECTION + ";"),
                calls(method(out, "quads")));
        // An old lambda transformer becomes the rebuilt interface, still edited in place.
        InvokeDynamicInsnNode lambda = indy(method(out, "lambda"));
        assertEquals("()L" + QUAD_TRANSFORMER + ";", lambda.desc);
        assertEquals("(L" + QUAD + ";)V", ((Type) lambda.bsmArgs[0]).getDescriptor());
    }

    @Test
    @DisplayName("Calls written against the old quad package before the class move reach the same rebuilt quad helpers")
    void oldQuadPackageReachesTheFactory() {
        register("1.20.1");
        ClassNode out = node(transformer.transformClass(modelCalls(OLD_QUAD), CALLER + ".class"));
        List<String> quads = calls(method(out, "quads"));
        assertEquals(QUAD_VERTICES + ".create", quads.get(0).substring(0, quads.get(0).indexOf('(')));
        assertTrue(quads.stream().noneMatch(call -> call.startsWith(OLD_QUAD) || call.startsWith(QUAD + ".get")), quads.toString());
    }

    @Test
    @DisplayName("Mods built for 26.1 keep Forge's new quad transformers, and mods built for 26.2 keep every host name")
    void newerModsKeepTheHostNames() {
        register("26.1");
        ClassNode for261 = node(transformer.transformClass(modelCalls(QUAD), CALLER + ".class"));
        assertEquals(OLD_QUAD_TRANSFORMERS + ".settingEmissivity(I)L" + OLD_QUAD_TRANSFORMER + ";",
                calls(method(for261, "transformers")).get(0), "26.1 already had record quads and Forge's new transformers");
        assertEquals(RENDER_LAYERS + ".getRenderLayers(L" + BLOCK_STATE + ";)L" + CHUNK_RENDER_TYPE_SET + ";",
                calls(method(for261, "layers")).get(0), "the old set was still Forge's before 26.2");

        transformer.setModSourceVersion("26.2");
        ClassNode for262 = node(transformer.transformClass(modelCalls(QUAD), CALLER + ".class"));
        assertEquals(List.of("net/minecraft/client/renderer/ItemBlockRenderTypes.getRenderLayers(L" + BLOCK_STATE
                        + ";)L" + OLD_CHUNK_RENDER_TYPE_SET + ";",
                OLD_CHUNK_RENDER_TYPE_SET + ".contains(L" + RENDER_TYPE + ";)Z",
                "net/minecraft/client/renderer/ItemBlockRenderTypes.setRenderLayer(L" + BLOCK + ";L" + OLD_CHUNK_RENDER_TYPE_SET + ";)V"),
                calls(method(for262, "layers")));
        assertEquals(RENDER_TYPE_GROUP + ".block()L" + RENDER_TYPE + ";", calls(method(for262, "groups")).get(1));
    }

    @Test
    @DisplayName("Through the whole Forge chain, cutout mipped and tripwire collapse to the cutout and translucent tokens"
            + " and the render type setter still records through the common polyfill")
    void blockLayerGettersCollapseAndTheSetterRecords() {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        new Forge_1_21_11_to_26_1().registerRedirects(transformer);
        new Forge_26_1_to_26_2().registerRedirects(transformer);
        // RetromodForge adds the vanilla class moves after the shims, which brings RenderType to its 26.x package.
        com.retromod.mapping.IntermediaryToMojangMapper.applyClassMovesOnly(transformer);
        transformer.setModSourceVersion("1.20.1");
        ClassNode out = node(transformer.transformClass(layerTokens(), "mod/LayerTokens.class"));
        assertEquals(List.of(RENDER_TYPES + ".cutoutMovingBlock()L" + RENDER_TYPE + ";",
                RENDER_TYPES + ".translucentMovingBlock()L" + RENDER_TYPE + ";",
                CHUNK_RENDER_TYPE_SET + ".of([L" + RENDER_TYPE + ";)L" + CHUNK_RENDER_TYPE_SET + ";",
                RENDER_TYPES + ".cutoutMovingBlock()L" + RENDER_TYPE + ";",
                POLYFILL + ".setRenderLayer(Ljava/lang/Object;Ljava/lang/Object;)V"), calls(method(out, "tokens")));
    }

    // ---- helpers ----

    private void register(String modVersion) {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        new Forge_26_1_to_26_2().registerRedirects(transformer);
        transformer.setModSourceVersion(modVersion);
    }

    private static MethodNode method(ClassNode node, String name) {
        return LegacyForgeRenderBridgeTest.method(node, name);
    }

    private static List<String> calls(MethodNode method) {
        List<String> found = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call) found.add(call.owner + "." + call.name + call.desc);
        }
        return found;
    }

    private static List<String> fields(MethodNode method) {
        List<String> found = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof FieldInsnNode field) found.add(field.owner + "." + field.name + ":" + field.desc);
        }
        return found;
    }

    private static InvokeDynamicInsnNode indy(MethodNode method) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof InvokeDynamicInsnNode dynamic) return dynamic;
        }
        throw new AssertionError("no invokedynamic in " + method.name);
    }

    /**
     * A Forge 1.20.1 model helper after the SRG remap: a layer read and write, a render type group, a quad transformer,
     * an old quad built and read, and a lambda transformer. {@code quad} is the quad class as the code names it.
     */
    static byte[] modelCalls(String quad) {
        return modelCalls(quad, RENDER_TYPE);
    }

    /** The same helper naming {@code renderType} as the render type class, such as the 1.20.1 package before the move. */
    static byte[] modelCalls(String quad, String renderType) {
        String set = OLD_CHUNK_RENDER_TYPE_SET;
        String ibrt = "net/minecraft/client/renderer/ItemBlockRenderTypes";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, CALLER, null, "java/lang/Object", null);

        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "layers", "(L" + BLOCK_STATE + ";L" + BLOCK + ";L"
                + renderType + ";)Z", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESTATIC, ibrt, "getRenderLayers", "(L" + BLOCK_STATE + ";)L" + set + ";", false);
        mv.visitInsn(DUP);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKEVIRTUAL, set, "contains", "(L" + renderType + ";)Z", false);
        mv.visitVarInsn(ISTORE, 3);
        mv.visitMethodInsn(INVOKESTATIC, ibrt, "setRenderLayer", "(L" + BLOCK + ";L" + set + ";)V", false);
        mv.visitVarInsn(ILOAD, 3);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "groups", "(L" + renderType + ";L" + renderType + ";)V", null, null);
        mv.visitCode();
        mv.visitTypeInsn(NEW, RENDER_TYPE_GROUP);
        mv.visitInsn(DUP);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKESPECIAL, RENDER_TYPE_GROUP, "<init>", "(L" + renderType + ";L" + renderType + ";)V", false);
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKEVIRTUAL, RENDER_TYPE_GROUP, "block", "()L" + renderType + ";", false);
        mv.visitInsn(POP);
        mv.visitMethodInsn(INVOKEVIRTUAL, RENDER_TYPE_GROUP, "entity", "()L" + renderType + ";", false);
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "transformers", "(L" + quad + ";)I", null, null);
        mv.visitCode();
        mv.visitIntInsn(BIPUSH, 15);
        mv.visitMethodInsn(INVOKESTATIC, OLD_QUAD_TRANSFORMERS, "settingEmissivity", "(I)L" + OLD_QUAD_TRANSFORMER + ";", false);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEINTERFACE, OLD_QUAD_TRANSFORMER, "processInPlace", "(L" + quad + ";)V", true);
        mv.visitFieldInsn(GETSTATIC, OLD_QUAD_TRANSFORMER, "STRIDE", "I");
        mv.visitInsn(IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "quads", "([IL" + DIRECTION + ";L" + SPRITE + ";)V", null, null);
        mv.visitCode();
        mv.visitTypeInsn(NEW, quad);
        mv.visitInsn(DUP);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitInsn(ICONST_M1);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitInsn(ICONST_1);
        mv.visitInsn(ICONST_0);
        mv.visitMethodInsn(INVOKESPECIAL, quad, "<init>", "([IIL" + DIRECTION + ";L" + SPRITE + ";ZZ)V", false);
        mv.visitVarInsn(ASTORE, 3);
        for (String[] getter : new String[][]{{"getVertices", "[I"}, {"getTintIndex", "I"}, {"getSprite", "L" + SPRITE + ";"},
                {"isShade", "Z"}, {"hasAmbientOcclusion", "Z"}, {"getDirection", "L" + DIRECTION + ";"}}) {
            mv.visitVarInsn(ALOAD, 3);
            mv.visitMethodInsn(INVOKEVIRTUAL, quad, getter[0], "()" + getter[1], false);
            mv.visitInsn(POP);
        }
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        // IQuadTransformer t = q -> {};
        mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "lambda", "()L" + OLD_QUAD_TRANSFORMER + ";", null, null);
        mv.visitCode();
        Handle metafactory = new Handle(H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;"
                        + "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false);
        mv.visitInvokeDynamicInsn("processInPlace", "()L" + OLD_QUAD_TRANSFORMER + ";", metafactory,
                Type.getType("(L" + quad + ";)V"),
                new Handle(H_INVOKESTATIC, CALLER, "edit", "(L" + quad + ";)V", false),
                Type.getType("(L" + quad + ";)V"));
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        mv = cw.visitMethod(ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC, "edit", "(L" + quad + ";)V", null, null);
        mv.visitCode();
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code ChunkRenderTypeSet.of(RenderType.cutoutMipped(), RenderType.tripwire())} and setRenderLayer(block, cutoutMipped()). */
    static byte[] layerTokens() {
        String oldRenderType = "net/minecraft/client/renderer/RenderType";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "mod/LayerTokens", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "tokens", "(L" + BLOCK + ";)V", null, null);
        mv.visitCode();
        mv.visitInsn(ICONST_2);
        mv.visitTypeInsn(ANEWARRAY, oldRenderType);
        mv.visitInsn(DUP);
        mv.visitInsn(ICONST_0);
        mv.visitMethodInsn(INVOKESTATIC, oldRenderType, "cutoutMipped", "()L" + oldRenderType + ";", false);
        mv.visitInsn(AASTORE);
        mv.visitInsn(DUP);
        mv.visitInsn(ICONST_1);
        mv.visitMethodInsn(INVOKESTATIC, oldRenderType, "tripwire", "()L" + oldRenderType + ";", false);
        mv.visitInsn(AASTORE);
        mv.visitMethodInsn(INVOKESTATIC, OLD_CHUNK_RENDER_TYPE_SET, "of", "([L" + oldRenderType + ";)L"
                + OLD_CHUNK_RENDER_TYPE_SET + ";", false);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESTATIC, oldRenderType, "cutoutMipped", "()L" + oldRenderType + ";", false);
        mv.visitMethodInsn(INVOKESTATIC, "net/minecraft/client/renderer/ItemBlockRenderTypes", "setRenderLayer",
                "(L" + BLOCK + ";L" + oldRenderType + ";)V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
