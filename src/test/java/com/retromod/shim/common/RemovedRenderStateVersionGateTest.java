/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.VersionShim;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The RenderSystem setters went away between 1.21.2 and 1.21.11, but only 26.1 dropped the calls,
 * so 79 of 200 NeoForge 1.21.1 mods still failed on enableBlend() on NeoForge 1.21.11.
 */
class RemovedRenderStateVersionGateTest {

    private static final String RS = "com/mojang/blaze3d/systems/RenderSystem";

    /** One setter from each removal version, with the shim version that must drop it. */
    private static final Map<String, String[]> SAMPLE = new LinkedHashMap<>();

    static {
        SAMPLE.put("1.21.2", new String[] {"setShader", "(Ljava/util/function/Supplier;)V", "1_21_1_to_1_21_2"});
        SAMPLE.put("1.21.5", new String[] {"enableBlend", "()V", "1_21_4_to_1_21_5"});
        SAMPLE.put("1.21.6", new String[] {"setShaderColor", "(FFFF)V", "1_21_5_to_1_21_6"});
        SAMPLE.put("1.21.11", new String[] {"lineWidth", "(F)V", "1_21_10_to_1_21_11"});
    }

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("every removal version has a shim on each loader that drops its setters")
    void everyVersionIsRegisteredByItsShims() {
        Set<String> versions = new TreeSet<>();
        for (RemovedRenderStateNeutralize.Removed removed : RemovedRenderStateNeutralize.REMOVED) versions.add(removed.since());
        assertEquals(new TreeSet<>(SAMPLE.keySet()), versions,
                "a setter removed in another version needs a sample here and a call from that version's shims");
    }

    @Test
    @DisplayName("each loader's shim drops the setters its version removed and keeps later ones")
    void shimsDropOnlyTheirOwnVersion() throws Exception {
        for (String loader : new String[] {"neoforge.NeoForge", "forge.Forge", "fabric.Fabric"}) {
            for (Map.Entry<String, String[]> group : SAMPLE.entrySet()) {
                RetromodTransformer transformer = RetromodTransformer.getInstance();
                transformer.clearRedirectsForTesting();
                String shim = "com.retromod.shim." + loader + "_" + group.getValue()[2];
                ((VersionShim) Class.forName(shim).getDeclaredConstructor().newInstance()).registerRedirects(transformer);

                List<String> left = renderSystemCalls(transformer.transformClass(callsEverySample(), "test/render/Setters"));

                for (Map.Entry<String, String[]> other : SAMPLE.entrySet()) {
                    String call = other.getValue()[0] + other.getValue()[1];
                    if (other.getKey().equals(group.getKey())) {
                        assertFalse(left.contains(call), shim + " must drop " + call);
                    } else {
                        assertTrue(left.contains(call), shim + " must leave " + call + " to the " + other.getKey() + " shim");
                    }
                }
            }
        }
    }

    /** A method calling one sample setter from each removal version. */
    private static byte[] callsEverySample() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/render/Setters", null, "java/lang/Object", null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "render", "()V", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, RS, "setShader", "(Ljava/util/function/Supplier;)V", false);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, RS, "enableBlend", "()V", false);
        for (int i = 0; i < 4; i++) mv.visitInsn(Opcodes.FCONST_1);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, RS, "setShaderColor", "(FFFF)V", false);
        mv.visitInsn(Opcodes.FCONST_1);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, RS, "lineWidth", "(F)V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(4, 0);
        mv.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static List<String> renderSystemCalls(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        List<String> calls = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && RS.equals(call.owner)) calls.add(call.name + call.desc);
            }
        }
        return calls;
    }
}
