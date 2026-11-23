/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** A Forge 1.20.1 mod's config screen factory reaches NeoForge as an IConfigScreenFactory. */
class LegacyConfigScreenFactoryBridgeTest {

    private static final String CONTEXT = "net/minecraftforge/fml/ModLoadingContext";

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("the factory becomes the rebuilt one and its registration goes through the key swap")
    void factoryAndRegistrationAreBridged() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        LegacyConfigScreenFactoryBridge.register(t);

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/forge/ModMain", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "init", "(L" + CONTEXT + ";)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitLdcInsn(Type.getObjectType(LegacyConfigScreenFactoryBridge.FORGE_FACTORY));
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CONTEXT, "registerExtensionPoint", LegacyConfigScreenFactoryBridge.REGISTER_DESC, false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        ClassNode out = new ClassNode();
        new ClassReader(t.transformClass(cw.toByteArray(), "test/forge/ModMain")).accept(out, 0);
        List<String> refs = new ArrayList<>();
        for (MethodNode method : out.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) refs.add(call.owner + "." + call.name + call.desc);
                if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Type type) refs.add("class " + type.getInternalName());
            }
        }
        assertTrue(refs.contains("class " + LegacyConfigScreenFactoryBridge.STAND_IN), refs.toString());
        assertTrue(refs.contains(LegacyConfigScreenFactoryBridge.EXTENSION_POINTS + ".registerExtensionPoint(Ljava/lang/Object;"
                + LegacyConfigScreenFactoryBridge.REGISTER_DESC.substring(1)), refs.toString());
    }
}
