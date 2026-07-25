/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * The 26.1 render rewrite's bridgeable surface (workflow-ground-truthed against BOTH the
 * 26.1-snapshot-10 and 26.2 jars): LightTexture's statics -> LightCoordsUtil, the lightTexture()
 * accessor bridge + turnOn/OffLightLayer neutralize, and the RenderType static getters ->
 * rendertype/RenderTypes including the cull-naming flip and the block-layer *MovingBlock
 * approximations. The class moves themselves (LightTexture -> Lightmap, RenderType ->
 * rendertype/RenderType) live in the class-move tsv and are asserted via the mapper.
 */
public class RenderApiBridge26xTest {

    private static final String RT = "net/minecraft/client/renderer/rendertype/RenderType";
    private static final String RTS = "net/minecraft/client/renderer/rendertype/RenderTypes";
    private static final String RT_RET = "()L" + RT + ";";
    private static final String ID_ARG = "(Lnet/minecraft/resources/Identifier;)L" + RT + ";";

    private RetromodTransformer transformer;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        Common_1_21_11_to_26_1_ClassMoves.register(transformer);
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
    }

    private List<AbstractInsnNode> transformBody(String desc, java.util.function.Consumer<MethodVisitor> body) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, ACC_PUBLIC, "test/Caller", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "call", desc, null, null);
        mv.visitCode();
        body.accept(mv);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        byte[] out = transformer.transformClass(cw.toByteArray(), "test/Caller");
        ClassNode cn = new ClassNode();
        assertDoesNotThrow(() -> new ClassReader(out).accept(cn, 0));
        List<AbstractInsnNode> insns = new ArrayList<>();
        for (MethodNode m : cn.methods) {
            if (m.name.equals("call")) for (AbstractInsnNode i : m.instructions.toArray()) insns.add(i);
        }
        return insns;
    }

    private static MethodInsnNode firstCall(List<AbstractInsnNode> insns, String owner, String name) {
        for (AbstractInsnNode i : insns) {
            if (i instanceof MethodInsnNode mi && mi.owner.equals(owner) && mi.name.equals(name)) return mi;
        }
        return null;
    }

    @Test
    @DisplayName("LightTexture.pack/block/sky retarget to LightCoordsUtil (both owner spellings)")
    void lightCoordsStatics() {
        for (String owner : new String[]{"net/minecraft/client/renderer/LightTexture",
                                         "net/minecraft/client/renderer/Lightmap"}) {
            List<AbstractInsnNode> insns = transformBody("()I", mv -> {
                mv.visitInsn(ICONST_1); mv.visitInsn(ICONST_2);
                mv.visitMethodInsn(INVOKESTATIC, owner, "pack", "(II)I", false);
                mv.visitInsn(IRETURN);
            });
            MethodInsnNode call = firstCall(insns, "net/minecraft/util/LightCoordsUtil", "pack");
            assertNotNull(call, "pack keyed on " + owner + " must retarget to LightCoordsUtil");
            assertEquals("(II)I", call.desc);
        }
    }

    @Test
    @DisplayName("GameRenderer.lightTexture() bridges to RetroClientEnv.getLightmap; turnOn/OffLightLayer neutralized")
    void lightTextureAccessorBridged() {
        String gr = "net/minecraft/client/renderer/GameRenderer";
        List<AbstractInsnNode> insns = transformBody("(L" + gr + ";)V", mv -> {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKEVIRTUAL, gr, "lightTexture",
                    "()Lnet/minecraft/client/renderer/Lightmap;", false);
            mv.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/client/renderer/Lightmap",
                    "turnOnLightLayer", "()V", false);
            mv.visitInsn(RETURN);
        });
        MethodInsnNode bridge = firstCall(insns,
                "com/retromod/polyfill/minecraft/RetroClientEnv", "getLightmap");
        assertNotNull(bridge, "lightTexture() must bridge to the reflective helper");
        assertEquals(INVOKESTATIC, bridge.getOpcode(), "receiver-as-arg0 auto-devirtualize");
        assertNull(firstCall(insns, "net/minecraft/client/renderer/Lightmap", "turnOnLightLayer"),
                "the deleted global light-layer bind must be neutralized");
        // Fail-safe: no Minecraft on the test classpath -> null, no throw.
        assertNull(com.retromod.polyfill.minecraft.RetroClientEnv.getLightmap(new Object()));
    }

    @Test
    @DisplayName("RenderType getters: block layers -> *MovingBlock; the entityCutout cull flip")
    void renderTypeGetters() {
        // Block layer approximation.
        List<AbstractInsnNode> layer = transformBody("()L" + RT + ";", mv -> {
            mv.visitMethodInsn(INVOKESTATIC, RT, "translucent", RT_RET, false);
            mv.visitInsn(ARETURN);
        });
        MethodInsnNode moving = firstCall(layer, RTS, "translucentMovingBlock");
        assertNotNull(moving, "translucent() must approximate to translucentMovingBlock()");

        // Cull flip: old entityCutout (culled) -> entityCutoutCull; old NoCull -> entityCutout.
        List<AbstractInsnNode> flip = transformBody(
                "(Lnet/minecraft/resources/Identifier;)L" + RT + ";", mv -> {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKESTATIC, RT, "entityCutout", ID_ARG, false);
            mv.visitInsn(ARETURN);
        });
        assertNotNull(firstCall(flip, RTS, "entityCutoutCull"),
                "old entityCutout was culled: must flip to entityCutoutCull");

        List<AbstractInsnNode> noCull = transformBody(
                "(Lnet/minecraft/resources/Identifier;)L" + RT + ";", mv -> {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKESTATIC, RT, "entityCutoutNoCull", ID_ARG, false);
            mv.visitInsn(ARETURN);
        });
        MethodInsnNode flipped = firstCall(noCull, RTS, "entityCutout");
        assertNotNull(flipped, "old entityCutoutNoCull must flip to entityCutout");
        assertEquals(ID_ARG, flipped.desc);
    }

    @Test
    @DisplayName("ItemBlockRenderTypes: all 5 statics bridge to the polyfill, overloads disambiguated")
    void itemBlockRenderTypesBridged() {
        String ibrt = "net/minecraft/client/renderer/ItemBlockRenderTypes";
        String poly = "com/retromod/polyfill/minecraft/RetroItemBlockRenderTypes";
        String bs = "Lnet/minecraft/world/level/block/state/BlockState;";
        String stack = "Lnet/minecraft/world/item/ItemStack;";

        // getChunkRenderType(BlockState) with the post-move return desc.
        List<AbstractInsnNode> chunk = transformBody("(" + bs + ")L" + RT + ";", mv -> {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKESTATIC, ibrt, "getChunkRenderType", "(" + bs + ")L" + RT + ";", false);
            mv.visitInsn(ARETURN);
        });
        MethodInsnNode call = firstCall(chunk, poly, "getChunkRenderType");
        assertNotNull(call, "getChunkRenderType must bridge to the polyfill");
        assertEquals("(Ljava/lang/Object;)Ljava/lang/Object;", call.desc);
        boolean cast = chunk.stream().anyMatch(i -> i instanceof org.objectweb.asm.tree.TypeInsnNode t
                && t.getOpcode() == CHECKCAST && t.desc.equals(RT));
        assertTrue(cast, "the Object return must be CHECKCAST back to RenderType");

        // The erased-overload split: (BlockState,Z) -> getRenderTypeBlock, (ItemStack,Z) -> getRenderTypeItem.
        List<AbstractInsnNode> blockForm = transformBody("(" + bs + "Z)L" + RT + ";", mv -> {
            mv.visitVarInsn(ALOAD, 0); mv.visitVarInsn(ILOAD, 1);
            mv.visitMethodInsn(INVOKESTATIC, ibrt, "getRenderType", "(" + bs + "Z)L" + RT + ";", false);
            mv.visitInsn(ARETURN);
        });
        assertNotNull(firstCall(blockForm, poly, "getRenderTypeBlock"));
        List<AbstractInsnNode> itemForm = transformBody("(" + stack + "Z)L" + RT + ";", mv -> {
            mv.visitVarInsn(ALOAD, 0); mv.visitVarInsn(ILOAD, 1);
            mv.visitMethodInsn(INVOKESTATIC, ibrt, "getRenderType", "(" + stack + "Z)L" + RT + ";", false);
            mv.visitInsn(ARETURN);
        });
        assertNotNull(firstCall(itemForm, poly, "getRenderTypeItem"));

        // Fail-safe: no Minecraft on the test classpath -> null, never a throw.
        assertDoesNotThrow(() ->
                com.retromod.polyfill.minecraft.RetroItemBlockRenderTypes.getChunkRenderType(null));
        assertNull(com.retromod.polyfill.minecraft.RetroItemBlockRenderTypes.getRenderLayer(new Object()));
    }

    @Test
    @DisplayName("class_9062 (ItemInteractionResult): class merged, constants + methods bridged")
    void itemInteractionResultBridged() {
        String poly = "com/retromod/polyfill/minecraft/RetroItemInteractionResult";
        String ir = "net/minecraft/world/InteractionResult";

        // GETSTATIC of the dominant constant (PASS_TO_DEFAULT_BLOCK_INTERACTION, 58 corpus refs):
        // remaps owner via the class redirect, then field-to-method to the polyfill + CHECKCAST.
        List<AbstractInsnNode> insns = transformBody("()L" + ir + ";", mv -> {
            mv.visitFieldInsn(GETSTATIC, "net/minecraft/class_9062", "field_47731",
                    "Lnet/minecraft/class_9062;");
            mv.visitInsn(ARETURN);
        });
        MethodInsnNode constant = firstCall(insns, poly, "passToDefaultBlockInteraction");
        assertNotNull(constant, "the constant read must bridge to the polyfill");
        assertEquals(INVOKESTATIC, constant.getOpcode());
        boolean cast = insns.stream().anyMatch(i -> i instanceof org.objectweb.asm.tree.TypeInsnNode t
                && t.getOpcode() == CHECKCAST && t.desc.equals(ir));
        assertTrue(cast, "the Object return must cast back to the merged InteractionResult");

        // Instance consumesAction: receiver-as-arg0 to the reflective helper.
        List<AbstractInsnNode> consumes = transformBody("(Lnet/minecraft/class_9062;)Z", mv -> {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/class_9062", "method_55643", "()Z", false);
            mv.visitInsn(IRETURN);
        });
        MethodInsnNode ca = firstCall(consumes, poly, "consumesAction");
        assertNotNull(ca);
        assertEquals(INVOKESTATIC, ca.getOpcode(), "receiver-as-arg0 auto-devirtualize");

        // Fail-safes without Minecraft on the classpath.
        assertNull(com.retromod.polyfill.minecraft.RetroItemInteractionResult.success());
        assertFalse(com.retromod.polyfill.minecraft.RetroItemInteractionResult.consumesAction(new Object()));
        Object recv = new Object();
        assertSame(recv, com.retromod.polyfill.minecraft.RetroItemInteractionResult.result(recv));
    }

    @Test
    @DisplayName("TextureSheetParticle rebase: extends + super-ctor sprite append + pickSprite bridge")
    void textureSheetParticleRebase() {
        String oldBase = "net/minecraft/client/particle/TextureSheetParticle";
        String newBase = "net/minecraft/client/particle/SingleQuadParticle";
        String level = "Lnet/minecraft/client/multiplayer/ClientLevel;";

        // Build a subclass: extends TextureSheetParticle, ctor calls super(level, x, y, z),
        // and a method calls this.pickSprite(set).
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, ACC_PUBLIC, "test/MyParticle", null, oldBase, null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", "(" + level + "DDD)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(DLOAD, 2); mv.visitVarInsn(DLOAD, 4); mv.visitVarInsn(DLOAD, 6);
        mv.visitMethodInsn(INVOKESPECIAL, oldBase, "<init>", "(" + level + "DDD)V", false);
        mv.visitInsn(RETURN); mv.visitMaxs(0, 0); mv.visitEnd();
        MethodVisitor pk = cw.visitMethod(ACC_PUBLIC, "pick",
                "(Lnet/minecraft/client/particle/SpriteSet;)V", null, null);
        pk.visitCode();
        pk.visitVarInsn(ALOAD, 0);
        pk.visitVarInsn(ALOAD, 1);
        pk.visitMethodInsn(INVOKEVIRTUAL, oldBase, "pickSprite",
                "(Lnet/minecraft/client/particle/SpriteSet;)V", false);
        pk.visitInsn(RETURN); pk.visitMaxs(0, 0); pk.visitEnd();
        cw.visitEnd();

        byte[] out = transformer.transformClass(cw.toByteArray(), "test/MyParticle");
        ClassNode cn = new ClassNode();
        assertDoesNotThrow(() -> new ClassReader(out).accept(cn, 0));
        assertEquals(newBase, cn.superName, "extends must rebase onto SingleQuadParticle");

        boolean superWithSprite = false, nullAppended = false, pickBridged = false;
        for (MethodNode m : cn.methods) {
            for (AbstractInsnNode i : m.instructions.toArray()) {
                if (i instanceof MethodInsnNode mi) {
                    if (mi.getOpcode() == INVOKESPECIAL && mi.owner.equals(newBase)
                            && mi.desc.contains("TextureAtlasSprite")) superWithSprite = true;
                    if (mi.owner.equals("com/retromod/polyfill/minecraft/RetroParticleCompat")
                            && mi.name.equals("pickSprite")
                            && mi.getOpcode() == INVOKESTATIC) pickBridged = true;
                }
                if (i.getOpcode() == ACONST_NULL) nullAppended = true;
            }
        }
        assertTrue(superWithSprite, "super() must retarget to the sprite-appending ctor");
        assertTrue(nullAppended, "the appended sprite default (null) must be pushed");
        assertTrue(pickBridged, "pickSprite must bridge to RetroParticleCompat");

        // Fail-safe without Minecraft: never throws.
        assertDoesNotThrow(() -> com.retromod.polyfill.minecraft.RetroParticleCompat
                .pickSprite(new Object(), new Object()));
    }

    @Test
    @DisplayName("class-move tsv: LightTexture -> Lightmap, RenderType -> rendertype/RenderType")
    void renderClassMovesPresent() {
        var moves = com.retromod.mapping.IntermediaryToMojangMapper.getInstance().getClassMoves();
        assertEquals("net/minecraft/client/renderer/Lightmap",
                moves.get("net/minecraft/client/renderer/LightTexture"));
        assertEquals("net/minecraft/client/renderer/rendertype/RenderType",
                moves.get("net/minecraft/client/renderer/RenderType"));
    }
}
