/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.fabric;

import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyPatchouliBufferRepairTest {

    private static final String INITIALIZER =
            "vazkii/patchouli/fabric/client/FabricClientInitializer";
    private static final String HELPER =
            "net/fabricmc/fabric/api/resource/ResourceManagerHelper";

    @Test
    void removesOnlyTheUnsupportedResourcePackBookListener() {
        RetromodVersion.TARGET_MC_VERSION = "1.21.11";
        byte[] repaired = LegacyPatchouliBufferRepair.apply(initializer(), INITIALIZER + ".class");
        ClassNode node = new ClassNode();
        new ClassReader(repaired).accept(node, 0);

        List<String> constructedListeners = new ArrayList<>();
        List<MethodInsnNode> registrations = new ArrayList<>();
        List<InvokeDynamicInsnNode> dynamicCalls = new ArrayList<>();
        node.methods.stream().filter(method -> "onInitializeClient".equals(method.name))
                .findFirst().orElseThrow().instructions.forEach(instruction -> {
                    if (instruction instanceof TypeInsnNode type
                            && type.getOpcode() == Opcodes.NEW) {
                        constructedListeners.add(type.desc);
                    }
                    if (instruction instanceof MethodInsnNode call
                            && "registerReloadListener".equals(call.name)) {
                        registrations.add(call);
                    }
                    if (instruction instanceof InvokeDynamicInsnNode dynamic) {
                        dynamicCalls.add(dynamic);
                    }
                });

        assertFalse(constructedListeners.contains(INITIALIZER + "$1"));
        assertTrue(constructedListeners.contains(INITIALIZER + "$2"));
        assertEquals(1, registrations.size(), "the normal reload hook must remain registered");
        assertTrue(dynamicCalls.isEmpty(),
                "the incompatible baked-model plugin must not run on the current item model API");
    }

    private static byte[] initializer() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, INITIALIZER,
                null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC,
                "onInitializeClient", "()V", null, null);
        method.visitCode();
        String context =
                "net/fabricmc/fabric/api/client/model/loading/v1/ModelLoadingPlugin$Context";
        String plugin = "net/fabricmc/fabric/api/client/model/loading/v1/ModelLoadingPlugin";
        Handle metafactory = new Handle(Opcodes.H_INVOKESTATIC,
                "java/lang/invoke/LambdaMetafactory", "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;"
                + "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                + "Ljava/lang/invoke/CallSite;", false);
        String samDesc = "(L" + context + ";)V";
        method.visitInvokeDynamicInsn("onInitializeModelLoader", "()L" + plugin + ";",
                metafactory, Type.getMethodType(samDesc),
                new Handle(Opcodes.H_INVOKESTATIC, INITIALIZER, "modelPlugin", samDesc, false),
                Type.getMethodType(samDesc));
        method.visitMethodInsn(Opcodes.INVOKESTATIC, plugin, "register",
                "(L" + plugin + ";)V", true);
        registerListener(method, INITIALIZER + "$1");
        registerListener(method, INITIALIZER + "$2");
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(3, 1);
        method.visitEnd();
        MethodVisitor pluginMethod = writer.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                "modelPlugin", "(L" + context + ";)V", null, null);
        pluginMethod.visitCode();
        pluginMethod.visitInsn(Opcodes.RETURN);
        pluginMethod.visitMaxs(0, 1);
        pluginMethod.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void registerListener(MethodVisitor method, String listener) {
        method.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraft/class_3264",
                "field_14188", "Lnet/minecraft/class_3264;");
        method.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "get",
                "(Lnet/minecraft/class_3264;)L" + HELPER + ";", true);
        method.visitTypeInsn(Opcodes.NEW, listener);
        method.visitInsn(Opcodes.DUP);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, listener, "<init>",
                "(L" + INITIALIZER + ";)V", false);
        method.visitMethodInsn(Opcodes.INVOKEINTERFACE, HELPER,
                "registerReloadListener", "(Ljava/lang/Object;)V", true);
    }
}
