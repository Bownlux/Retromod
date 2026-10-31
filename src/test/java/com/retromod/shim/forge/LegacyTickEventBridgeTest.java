/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** A Forge 1.20.1 tick listener checks the phase of one event; Forge 26.2 fires a Pre and a Post event instead. */
class LegacyTickEventBridgeTest {

    private static final String TICK = LegacyTickEventBridge.TICK;
    private static final String CLIENT_TICK = TICK + "$ClientTickEvent";
    private static final String SUBSCRIBE = "Lnet/minecraftforge/eventbus/api/listener/SubscribeEvent;";
    private final String target = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void reset() {
        RetromodVersion.TARGET_MC_VERSION = target;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("An old tick listener gets a Pre and a Post listener, and reads its phase through the helper")
    void listenerIsSplit() {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        LegacyTickEventBridge.register(t);

        byte[] out = t.transformClass(listenerClass(), "mod/Ticks.class");
        new ClassReader(out).accept(new CheckClassAdapter(new ClassWriter(0), true), 0);
        ClassNode node = new ClassNode();
        new ClassReader(out).accept(node, 0);

        MethodNode original = method(node, "onTick");
        assertFalse(subscribed(original), "the original no longer listens to an interface the bus cannot fire");
        assertTrue(subscribed(method(node, "retromod$onTick$Pre")));
        assertEquals("(L" + CLIENT_TICK + "$Post;)V", method(node, "retromod$onTick$Post").desc);
        List<String> refs = refs(original);
        assertTrue(refs.contains(INVOKESTATIC + " " + LegacyTickEventBridge.TICKS + ".phase(Ljava/lang/Object;)L"
                + LegacyTickEventBridge.PHASE + ";"), refs.toString());
        assertTrue(refs.contains(LegacyTickEventBridge.PHASE + ".END"), refs.toString());
        assertTrue(refs.stream().noneMatch(ref -> ref.contains("$Phase")), "the removed phase enum is still named: " + refs);
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow(() -> new AssertionError(name));
    }

    private static boolean subscribed(MethodNode method) {
        List<AnnotationNode> annotations = method.visibleAnnotations == null ? List.of() : method.visibleAnnotations;
        return annotations.stream().anyMatch(a -> a.desc.equals(SUBSCRIBE));
    }

    private static List<String> refs(MethodNode method) {
        List<String> refs = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call) refs.add(call.getOpcode() + " " + call.owner + "." + call.name + call.desc);
            if (insn instanceof FieldInsnNode field) refs.add(field.owner + "." + field.name);
        }
        return refs;
    }

    /** {@code @SubscribeEvent static void onTick(ClientTickEvent e) { if (e.phase == Phase.END) {...} }}. */
    private static byte[] listenerClass() {
        String phase = "L" + TICK + "$Phase;";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String a, String b) {
                return "java/lang/Object";
            }
        };
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "mod/Ticks", null, "java/lang/Object", null);
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "onTick", "(L" + CLIENT_TICK + ";)V", null, null);
        AnnotationVisitor annotation = m.visitAnnotation("Lnet/minecraftforge/eventbus/api/SubscribeEvent;", true);
        annotation.visitEnd();
        m.visitCode();
        Label done = new Label();
        m.visitVarInsn(ALOAD, 0);
        m.visitFieldInsn(GETFIELD, CLIENT_TICK, "phase", phase);
        m.visitFieldInsn(GETSTATIC, TICK + "$Phase", "END", phase);
        m.visitJumpInsn(IF_ACMPNE, done);
        m.visitInsn(NOP);
        m.visitLabel(done);
        m.visitInsn(RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
