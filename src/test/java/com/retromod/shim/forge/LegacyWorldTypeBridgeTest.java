/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** A 1.15 world type mod, as Survival Island Generator registers its island world type. */
class LegacyWorldTypeBridgeTest {

    private static final String WORLD_TYPE = "net/minecraft/world/WorldType";
    private static final String GENERATOR = "Lnet/minecraft/world/gen/ChunkGenerator;";

    private RetromodTransformer transformer;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyWorldTypeBridge.register(transformer);
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("A world type subclass extends the stand-in and its callbacks throw instead of naming 1.15 types")
    void worldTypeLoadsWithRetiredCallbacks() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V1_8, ACC_PUBLIC | ACC_SUPER, "test/IslandWorldType", null, WORLD_TYPE,
                new String[] {LegacyWorldTypeBridge.FORGE_EXTENSION});
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitLdcInsn("islands");
        init.visitMethodInsn(INVOKESPECIAL, WORLD_TYPE, "<init>", "(Ljava/lang/String;)V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor callback = cw.visitMethod(ACC_PUBLIC, "createChunkGenerator",
                "(Lnet/minecraft/world/World;)" + GENERATOR, null, null);
        callback.visitCode();
        callback.visitTypeInsn(NEW, "net/minecraft/world/gen/OverworldGenSettings");
        callback.visitInsn(ACONST_NULL);
        callback.visitInsn(ARETURN);
        callback.visitMaxs(0, 0);
        callback.visitEnd();
        cw.visitEnd();

        ClassNode out = transform(cw.toByteArray(), "test/IslandWorldType");
        assertEquals(LegacyWorldTypeBridge.BASE, out.superName, "the removed base is replaced by the stand-in");
        assertTrue(out.interfaces.isEmpty(), "Forge's world type extension is dropped");
        MethodNode retired = method(out, "createChunkGenerator");
        assertEquals(ATHROW, retired.instructions.getLast().getOpcode(), "the callback throws");
        for (AbstractInsnNode insn : retired.instructions) {
            if (insn instanceof TypeInsnNode type) {
                assertEquals("java/lang/UnsupportedOperationException", type.desc, "no 1.15 type is named");
            }
        }
        assertNotNull(transformer.getSyntheticClasses().get(LegacyWorldTypeBridge.BASE),
                "the stand-in is generated for the jar");
    }

    @Test
    @DisplayName("Storing the world type into a Minecraft field returns quietly when the field is gone")
    void storeIntoMinecraftIsGuarded() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V1_8, ACC_PUBLIC | ACC_SUPER, "test/ServerSetup", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "makeDefault",
                "(Lnet/minecraft/server/dedicated/ServerProperties;L" + WORLD_TYPE + ";)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitFieldInsn(PUTFIELD, "net/minecraft/server/dedicated/ServerProperties", "worldType", "L" + WORLD_TYPE + ";");
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        MethodNode guarded = method(transform(cw.toByteArray(), "test/ServerSetup"), "makeDefault");
        assertEquals(List.of("java/lang/LinkageError"),
                guarded.tryCatchBlocks.stream().map(block -> block.type).toList());
    }

    private ClassNode transform(byte[] input, String name) {
        ClassNode node = new ClassNode();
        new ClassReader(transformer.transformClass(input, name)).accept(node, 0);
        return node;
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }
}
