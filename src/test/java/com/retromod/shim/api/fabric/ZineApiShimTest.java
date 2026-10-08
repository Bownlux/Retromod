/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.api.fabric;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Zine 1.10 renamed its Yarn-style helpers after Mojang's types. A mod built against Zine 1.8
 * failed with {@code NoClassDefFoundError: .../PacketCodecUtil} on the installed Zine, from a
 * component's static initializer.
 */
class ZineApiShimTest {

    private static final String MOD_CLASS = "test/mod/DiseaseState";
    private static final String STREAM_CODEC = "Lnet/minecraft/network/codec/StreamCodec;";

    private RetromodTransformer transformer;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("an old codec call and field read reach the renamed Zine helpers")
    void oldNamesFollowTheRename() {
        assertTrue(ZineApiShim.registerIfHostRenamed(transformer,
                Set.of(ZineApiShim.NEW_CODEC_UTIL)::contains));

        MethodNode method = transformedMethod();
        MethodInsnNode call = firstOf(method, MethodInsnNode.class);
        assertEquals(ZineApiShim.NEW_CODEC_UTIL, call.owner);
        assertEquals("ofEnum", call.name);
        FieldInsnNode read = firstOf(method, FieldInsnNode.class);
        assertEquals(ZineApiShim.NEW_CODEC_UTIL, read.owner);
        assertEquals("AABB", read.name);
    }

    @Test
    @DisplayName("an installed Zine that still has the old names is left alone")
    void oldZineKeepsItsNames() {
        assertFalse(ZineApiShim.registerIfHostRenamed(transformer,
                Set.of(ZineApiShim.OLD_CODEC_UTIL)::contains));
        assertFalse(ZineApiShim.registerIfHostRenamed(transformer, name -> false),
                "without Zine installed nothing is registered");

        MethodInsnNode call = firstOf(transformedMethod(), MethodInsnNode.class);
        assertEquals(ZineApiShim.OLD_CODEC_UTIL, call.owner);
        assertEquals("enumPacketCodec", call.name);
    }

    @Test
    @DisplayName("the block entity package moves only where the installed Zine moved it")
    void blockEntityMoveFollowsItsOwnProbe() {
        ZineApiShim.registerIfHostRenamed(transformer, Set.of(ZineApiShim.NEW_CODEC_UTIL,
                ZineApiShim.OLD_SYNCING_BLOCK_ENTITY)::contains);
        assertEquals(ZineApiShim.OLD_SYNCING_BLOCK_ENTITY, transformedSyncingCast(),
                "a Zine that kept block_entity must keep the old name");

        transformer.clearRedirectsForTesting();
        ZineApiShim.registerIfHostRenamed(transformer, Set.of(ZineApiShim.NEW_CODEC_UTIL,
                ZineApiShim.NEW_SYNCING_BLOCK_ENTITY)::contains);
        assertEquals(ZineApiShim.NEW_SYNCING_BLOCK_ENTITY, transformedSyncingCast());
    }

    /** The type of {@code (SyncingBlockEntity) entity} in a mod class after the transform. */
    private String transformedSyncingCast() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, MOD_CLASS, null,
                "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "sync",
                "(Ljava/lang/Object;)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitTypeInsn(Opcodes.CHECKCAST, ZineApiShim.OLD_SYNCING_BLOCK_ENTITY);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        ClassNode out = new ClassNode();
        new ClassReader(transformer.transformClass(cw.toByteArray(), MOD_CLASS)).accept(out, 0);
        MethodNode sync = out.methods.stream().filter(m -> m.name.equals("sync")).findFirst()
                .orElseThrow();
        return firstOf(sync, org.objectweb.asm.tree.TypeInsnNode.class).desc;
    }

    private MethodNode transformedMethod() {
        ClassNode out = new ClassNode();
        new ClassReader(transformer.transformClass(modClass(), MOD_CLASS)).accept(out, 0);
        return out.methods.stream().filter(m -> m.name.equals("codecs")).findFirst().orElseThrow();
    }

    private static <T> T firstOf(MethodNode method, Class<T> type) {
        for (var insn : method.instructions) {
            if (type.isInstance(insn)) return type.cast(insn);
        }
        fail("no " + type.getSimpleName() + " in the transformed method");
        return null;
    }

    /** {@code static void codecs() { PacketCodecUtil.enumPacketCodec(values); PacketCodecUtil.BOX; }} */
    private static byte[] modClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, MOD_CLASS, null,
                "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "codecs",
                "()V", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Enum");
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, ZineApiShim.OLD_CODEC_UTIL, "enumPacketCodec",
                "([Ljava/lang/Enum;)" + STREAM_CODEC, false);
        mv.visitInsn(Opcodes.POP);
        mv.visitFieldInsn(Opcodes.GETSTATIC, ZineApiShim.OLD_CODEC_UTIL, "BOX", STREAM_CODEC);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
