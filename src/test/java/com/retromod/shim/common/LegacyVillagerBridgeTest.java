/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.objectweb.asm.Opcodes.*;

/** #311: Archipelago Additions' trader profession and workstation failed to register on 1.20.1. */
class LegacyVillagerBridgeTest {

    private static final String MOD = "com/example/ModVillagers";
    private static final String POI = "net/minecraft/world/entity/ai/village/poi/PoiType";
    private static final String PROFESSION = "net/minecraft/world/entity/npc/VillagerProfession";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();

    @BeforeEach
    void setUp() {
        transformer.clearRedirectsForTesting();
        LegacyMinecraft119Bridge.register(transformer);
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    void workstationAndProfessionAreBuiltThroughTheBridge() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, MOD, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "register",
                "(Lnet/minecraft/world/level/block/Block;)Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitTypeInsn(NEW, POI);
        mv.visitInsn(DUP);
        mv.visitLdcInsn("dragon_hunter_trader_poi");
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESTATIC, POI, "getBlockStates",
                "(Lnet/minecraft/world/level/block/Block;)Ljava/util/Set;", false);
        mv.visitInsn(ICONST_1);
        mv.visitInsn(ICONST_1);
        mv.visitMethodInsn(INVOKESPECIAL, POI, "<init>", LegacyVillagerBridge.OLD_POI, false);
        mv.visitVarInsn(ASTORE, 1);
        mv.visitTypeInsn(NEW, PROFESSION);
        mv.visitInsn(DUP);
        mv.visitLdcInsn("dragon_hunter_trader");
        mv.visitVarInsn(ALOAD, 1);
        mv.visitInsn(ACONST_NULL);
        mv.visitInsn(ACONST_NULL);
        mv.visitInsn(ACONST_NULL);
        mv.visitMethodInsn(INVOKESPECIAL, PROFESSION, "<init>", LegacyVillagerBridge.OLD_PROFESSION, false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        assertEquals(List.of("LegacyVillagers.blockStates", "LegacyVillagers.poiType",
                        "LegacyVillagers.profession"),
                calls(transformer.transformClass(cw.toByteArray(), MOD)),
                "each removed constructor and helper goes through a generated factory");
    }

    @Test
    void professionSupplierReadingAnUnregisteredWorkstationGetsAPlaceholder() {
        String registryObject = "net/minecraftforge/registries/RegistryObject";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, MOD, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "workstation",
                "(L" + registryObject + ";)Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, registryObject, "get", "()Ljava/lang/Object;", false);
        mv.visitTypeInsn(CHECKCAST, POI);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        assertEquals(List.of("LegacyVillagers.poiOrPending"), calls(LegacyVillagerBridge.apply(cw.toByteArray())),
                "1.19 registers professions first, so the workstation may not be registered yet");
    }

    @Test
    void generatedHelpersAreValid() {
        for (byte[] generated : List.of(LegacyVillagerBridge.generate(), LegacyVillagerBridge.generateMatch())) {
            new ClassReader(generated).accept(new CheckClassAdapter(new ClassWriter(0), false), 0);
        }
    }

    private static List<String> calls(byte[] classBytes) {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode insn : node.methods.get(0).instructions) {
            if (insn instanceof MethodInsnNode call) {
                calls.add(call.owner.substring(call.owner.lastIndexOf('/') + 1) + "." + call.name);
            }
        }
        return calls;
    }
}
