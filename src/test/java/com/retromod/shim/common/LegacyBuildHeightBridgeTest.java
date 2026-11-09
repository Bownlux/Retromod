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
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** A 1.21.1 mod's exclusive max build height getters answer one above the inclusive 1.21.2 ones. */
class LegacyBuildHeightBridgeTest {

    private final String savedHost = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedHost;
    }

    @Test
    @DisplayName("The helper adds one to getMaxY and getMaxSectionY")
    void helperAddsOne() {
        ClassNode helper = new ClassNode();
        new ClassReader(LegacyBuildHeightBridge.generate()).accept(helper, 0);

        for (String[] getter : new String[][]{{"getMaxBuildHeight", "getMaxY"}, {"getMaxSection", "getMaxSectionY"}}) {
            MethodNode method = helper.methods.stream().filter(m -> m.name.equals(getter[0])).findFirst().orElseThrow();
            List<String> code = new ArrayList<>();
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) code.add(call.name);
                else if (insn.getOpcode() >= 0) code.add(String.valueOf(insn.getOpcode()));
            }
            assertEquals(List.of(String.valueOf(Opcodes.ALOAD), getter[1], String.valueOf(Opcodes.ICONST_1),
                    String.valueOf(Opcodes.IADD), String.valueOf(Opcodes.IRETURN)), code, getter[0]);
        }
    }

    @Test
    @DisplayName("The intermediary helper for Fabric hosts before 26.1 adds one to the ids that now name the inclusive getters")
    void intermediaryHelperAddsOne() {
        ClassNode helper = new ClassNode();
        new ClassReader(LegacyBuildHeightBridge.generate(LegacyBuildHeightBridge.INTERMEDIARY)).accept(helper, 0);
        MethodNode method = helper.methods.stream().filter(m -> m.name.equals("getMaxBuildHeight")).findFirst().orElseThrow();
        MethodInsnNode call = (MethodInsnNode) method.instructions.get(1);
        assertEquals("net/minecraft/class_5539.method_31600()I", call.owner + "." + call.name + call.desc);
        assertEquals(Opcodes.IADD, method.instructions.get(3).getOpcode());
    }

    @Test
    @DisplayName("A call through ServerLevel goes to the helper instead of being renamed to the inclusive getter")
    void serverLevelCallUsesTheHelper() throws Exception {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        Constructor<RetromodTransformer> constructor = RetromodTransformer.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        RetromodTransformer transformer = constructor.newInstance();
        LegacyBuildHeightBridge.register(transformer);
        com.retromod.mapping.MojangRenameTable.register(transformer, "26.3");
        transformer.setModSourceVersion("1.21.1");

        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "mod/Top", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "top",
                "(Lnet/minecraft/server/level/ServerLevel;)I", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/server/level/ServerLevel", "getMaxBuildHeight", "()I", false);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
        cw.visitEnd();

        ClassNode node = new ClassNode();
        new ClassReader(transformer.transformClass(cw.toByteArray(), "mod/Top")).accept(node, 0);
        MethodInsnNode call = null;
        for (AbstractInsnNode insn : node.methods.stream().filter(m -> m.name.equals("top")).findFirst().orElseThrow().instructions) {
            if (insn instanceof MethodInsnNode found) call = found;
        }
        assertNotNull(call);
        assertEquals(LegacyBuildHeightBridge.HELPER + ".getMaxBuildHeight" + LegacyBuildHeightBridge.HELPER_DESC,
                call.owner + "." + call.name + call.desc);
    }
}
