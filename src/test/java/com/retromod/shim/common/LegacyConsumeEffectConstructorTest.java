/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

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
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * #271: Minepathy keeps a chorus-fruit teleport effect in a static field of its {@code Consumable}
 * mixin, built with {@code new TeleportRandomlyConsumeEffect(16.0f)}. 26.3 removed that
 * constructor, and because Mixin merges the static initializer into {@code Consumable}, the
 * dedicated server failed to start with {@code NoSuchMethodError} during bootstrap.
 */
class LegacyConsumeEffectConstructorTest {

    private static final String EFFECT = "net/minecraft/world/item/consume_effects/TeleportRandomlyConsumeEffect";

    private RetromodTransformer transformer;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        Mc26_2To26_3CoreMoves.registerConsumeEffectConstructors(transformer);
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("The one-argument teleport effect is built with directional particles on")
    void oldConstructorGetsTheDefaultFlag() throws Exception {
        MethodNode clinit = transformedStaticInit();

        MethodInsnNode init = null;
        for (AbstractInsnNode insn : clinit.instructions) {
            if (insn instanceof MethodInsnNode call && call.owner.equals(EFFECT)) init = call;
        }
        assertNotNull(init);
        assertEquals("(FZ)V", init.desc, "26.3 only has the (float, boolean) constructor");
        assertEquals(ICONST_1, init.getPrevious().getOpcode(),
                "true is what 26.3 reads for an effect that does not name the flag");
        new Analyzer<>(new BasicVerifier()).analyze("test/mod/ConsumableMixin", clinit);
    }

    private MethodNode transformedStaticInit() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/mod/ConsumableMixin", null, "java/lang/Object", null);
        cw.visitField(ACC_PRIVATE | ACC_STATIC | ACC_FINAL, "CHORUS_TASTE_EFFECT", "L" + EFFECT + ";",
                null, null).visitEnd();
        MethodVisitor m = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        m.visitCode();
        m.visitTypeInsn(NEW, EFFECT);
        m.visitInsn(DUP);
        m.visitLdcInsn(16.0f);
        m.visitMethodInsn(INVOKESPECIAL, EFFECT, "<init>", "(F)V", false);
        m.visitFieldInsn(PUTSTATIC, "test/mod/ConsumableMixin", "CHORUS_TASTE_EFFECT", "L" + EFFECT + ";");
        m.visitInsn(RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        cw.visitEnd();

        ClassNode out = new ClassNode();
        new ClassReader(transformer.transformClass(cw.toByteArray(), "test/mod/ConsumableMixin")).accept(out, 0);
        return out.methods.stream().filter(method -> method.name.equals("<clinit>")).findFirst().orElseThrow();
    }
}
