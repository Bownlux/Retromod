/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.objectweb.asm.Opcodes.*;

/** A 1.18.2 animation loader reads its files through the 1.19 resource manager. */
class LegacyResourceApiBridgeTest {

    private static final String MOD = "com/example/AnimationFileLoader";
    private static final String PKG = "net/minecraft/server/packs/resources/";
    private static final String MANAGER = PKG + "ResourceManager";
    private static final String RESOURCE = PKG + "Resource";
    private static final String LOCATION = "Lnet/minecraft/resources/ResourceLocation;";

    private final String previousHost = RetromodVersion.TARGET_MC_VERSION;
    private final RetromodTransformer transformer = RetromodTransformer.getInstance();

    @BeforeEach
    void setUp() {
        RetromodVersion.TARGET_MC_VERSION = "1.20.1";
        transformer.clearRedirectsForTesting();
        LegacyResourceApiBridge.register(transformer);
    }

    @AfterEach
    void tearDown() {
        RetromodVersion.TARGET_MC_VERSION = previousHost;
        transformer.clearRedirectsForTesting();
    }

    @Test
    void oldLoaderOpensListsAndClosesThroughTheNewApi() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, MOD, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "load",
                "(L" + MANAGER + ";" + LOCATION + "Ljava/util/function/Predicate;)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitLdcInsn("animations");
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKEINTERFACE, MANAGER, "listResources",
                "(Ljava/lang/String;Ljava/util/function/Predicate;)Ljava/util/Collection;", true);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEINTERFACE, MANAGER, "getResource", "(" + LOCATION + ")L" + RESOURCE + ";", true);
        mv.visitVarInsn(ASTORE, 3);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitMethodInsn(INVOKEINTERFACE, RESOURCE, "getInputStream", "()Ljava/io/InputStream;", true);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitMethodInsn(INVOKEINTERFACE, RESOURCE, "close", "()V", true);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        List<String> calls = new ArrayList<>();
        ClassNode node = new ClassNode();
        new ClassReader(transformer.transformClass(cw.toByteArray(), MOD)).accept(node, 0);
        node.methods.get(0).instructions.forEach(insn -> {
            if (insn instanceof MethodInsnNode call) {
                String owner = call.owner.substring(call.owner.lastIndexOf('/') + 1);
                calls.add(opcode(call.getOpcode()) + " " + owner + "." + call.name + (call.itf ? " itf" : ""));
            }
        });

        assertEquals(List.of(
                "static LegacyResourceApi.listResources",
                "interface ResourceManager.getResourceOrThrow itf",
                "virtual Resource.open",
                "static LegacyResourceApi.close"), calls,
                "the lookup keeps its exception, Resource is called as the class it became, "
                        + "and the listing and the close go through helpers");
    }

    @Test
    void generatedHelpersAreValid() {
        new ClassReader(LegacyResourceApiBridge.generateHelpers())
                .accept(new CheckClassAdapter(new ClassWriter(0), false), 0);
        new ClassReader(LegacyResourceApiBridge.generateFilter())
                .accept(new CheckClassAdapter(new ClassWriter(0), false), 0);
    }

    private static String opcode(int opcode) {
        return switch (opcode) {
            case INVOKESTATIC -> "static";
            case INVOKEINTERFACE -> "interface";
            case INVOKEVIRTUAL -> "virtual";
            default -> "special";
        };
    }
}
