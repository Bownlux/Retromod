/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The old movement input, GUI sprite manager and camera override reach their rebuilt forms. */
class LegacyClientGapsBridgeTest {

    private static final String INPUTS = LegacyClientGapsBridge.INPUTS;

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("old input fields are read and written through the client input, and the old tick drops its arguments")
    void inputFieldsAreBridged() {
        RetromodTransformer t = fresh();
        LegacyClientGapsBridge.register(t, Map.of(LegacyClientGapsBridge.MINECRAFT, new ClassNode(),
                LegacyClientGapsBridge.SPRITE_ATLASES, new ClassNode(), LegacyClientGapsBridge.DISPATCHER, new ClassNode())::get);

        List<String> refs = refs(t.transformClass(oldCalls(), "test/client/Old"));

        assertTrue(refs.contains(INPUTS + ".jumping(Ljava/lang/Object;)Z"), refs.toString());
        assertTrue(refs.contains(INPUTS + ".setShiftKeyDown(Ljava/lang/Object;Z)V"), refs.toString());
        assertTrue(refs.contains(INPUTS + ".forwardImpulse(Ljava/lang/Object;)F"), refs.toString());
        assertTrue(refs.contains(INPUTS + ".tick(Ljava/lang/Object;ZF)V"), refs.toString());
        assertTrue(refs.contains(LegacyClientGapsBridge.LEGACY_SPRITE_MANAGER + ".of(L" + LegacyClientGapsBridge.MINECRAFT + ";)L"
                + LegacyClientGapsBridge.LEGACY_SPRITE_MANAGER + ";"), refs.toString());
        assertTrue(refs.contains(LegacyClientGapsBridge.LEGACY_SPRITE_MANAGER + ".getSprite(Lnet/minecraft/resources/Identifier;)"
                + "Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;"), refs.toString());
        assertTrue(refs.contains(LegacyClientGapsBridge.CAMERA_ORIENTATION + ".overrideCameraOrientation(Ljava/lang/Object;Ljava/lang/Object;)V"),
                refs.toString());
        assertFalse(refs.stream().anyMatch(r -> r.contains(LegacyClientGapsBridge.INPUT + ".")), refs.toString());
    }

    @Test
    @DisplayName("a readable 1.21.11 host without the sprite atlas manager keeps the GUI sprite calls as they are")
    void spritesWaitFor26() {
        RetromodTransformer t = fresh();
        LegacyClientGapsBridge.register(t, Map.of(LegacyClientGapsBridge.MINECRAFT, new ClassNode(),
                LegacyClientGapsBridge.DISPATCHER, new ClassNode())::get);

        assertFalse(t.getSyntheticClasses().containsKey(LegacyClientGapsBridge.LEGACY_SPRITE_MANAGER));
        assertTrue(t.getSyntheticClasses().containsKey(INPUTS), "the input bridge applies from 1.21.11");
    }

    @Test
    @DisplayName("an old input subclass reads its own fields through the client input and gets the tick() the game calls")
    void inputSubclassKeepsWorking() throws Exception {
        RetromodTransformer t = fresh();
        LegacyClientGapsBridge.register(t, Map.of(LegacyClientGapsBridge.MINECRAFT, new ClassNode(),
                LegacyClientGapsBridge.DISPATCHER, new ClassNode())::get);
        byte[] mine = inputSubclass();
        t.setJarClassBytesProvider(Map.of("test/client/MyInput", mine)::get);
        try {
            ClassNode fixed = new ClassNode();
            new ClassReader(t.transformClass(mine, "test/client/MyInput")).accept(fixed, 0);

            assertEquals(LegacyClientGapsBridge.CLIENT_INPUT, fixed.superName);
            List<String> refs = refs(t.transformClass(mine, "test/client/MyInput"));
            assertTrue(refs.contains(INPUTS + ".setUp(Ljava/lang/Object;Z)V"), "the subclass's own field write: " + refs);
            MethodNode tick = fixed.methods.stream().filter(m -> m.name.equals("tick") && m.desc.equals("()V")).findFirst().orElseThrow();
            MethodInsnNode call = (MethodInsnNode) java.util.Arrays.stream(tick.instructions.toArray())
                    .filter(i -> i instanceof MethodInsnNode).findFirst().orElseThrow();
            assertEquals("test/client/MyInput.tick(ZF)V", call.owner + "." + call.name + call.desc);
            assertTrue(refs.contains(LegacyClientGapsBridge.CLIENT_INPUT + ".tick()V"),
                    "the old super.tick(sneaking, speed) reaches the host's tick(), not the helper that calls back: " + refs);
            assertFalse(refs.contains(INPUTS + ".tick(Ljava/lang/Object;ZF)V"), refs.toString());
            for (MethodNode method : fixed.methods) {
                new org.objectweb.asm.tree.analysis.Analyzer<>(new org.objectweb.asm.tree.analysis.BasicVerifier()).analyze(fixed.name, method);
            }
        } finally {
            t.setJarClassBytesProvider(null);
        }
    }

    /** {@code class MyInput extends Input { public void tick(boolean s, float f) { super.tick(s, f); this.up = true; } }} */
    private static byte[] inputSubclass() {
        String input = LegacyClientGapsBridge.INPUT;
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/client/MyInput", null, input, null);
        MethodVisitor tick = writer.visitMethod(Opcodes.ACC_PUBLIC, "tick", "(ZF)V", null, null);
        tick.visitCode();
        tick.visitVarInsn(Opcodes.ALOAD, 0);
        tick.visitVarInsn(Opcodes.ILOAD, 1);
        tick.visitVarInsn(Opcodes.FLOAD, 2);
        tick.visitMethodInsn(Opcodes.INVOKESPECIAL, input, "tick", "(ZF)V", false);
        tick.visitVarInsn(Opcodes.ALOAD, 0);
        tick.visitInsn(Opcodes.ICONST_1);
        tick.visitFieldInsn(Opcodes.PUTFIELD, "test/client/MyInput", "up", "Z");
        tick.visitInsn(Opcodes.RETURN);
        tick.visitMaxs(3, 3);
        tick.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static RetromodTransformer fresh() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        return t;
    }

    private static byte[] oldCalls() {
        String input = LegacyClientGapsBridge.INPUT;
        String manager = LegacyClientGapsBridge.SPRITE_MANAGER;
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/client/Old", null, "java/lang/Object", null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "(L" + input + ";)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, input, "jumping", "Z");
        mv.visitInsn(Opcodes.POP);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitFieldInsn(Opcodes.PUTFIELD, input, "shiftKeyDown", "Z");
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, input, "forwardImpulse", "F");
        mv.visitInsn(Opcodes.POP);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.FCONST_0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, input, "tick", "(ZF)V", false);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LegacyClientGapsBridge.MINECRAFT, "getGuiSprites", "()L" + manager + ";", false);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, manager, "getSprite",
                "(Lnet/minecraft/resources/Identifier;)Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LegacyClientGapsBridge.DISPATCHER, "overrideCameraOrientation",
                LegacyClientGapsBridge.ORIENT_DESC, false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(4, 1);
        mv.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static List<String> refs(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        List<String> refs = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) refs.add(call.owner + "." + call.name + call.desc);
                if (insn instanceof FieldInsnNode field) refs.add(field.owner + "." + field.name);
            }
        }
        return refs;
    }
}
