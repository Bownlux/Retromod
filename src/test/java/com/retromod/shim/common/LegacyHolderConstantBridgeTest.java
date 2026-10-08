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
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** A 1.20.1 armor material reading its equip sound, on a host where that constant is a holder. */
class LegacyHolderConstantBridgeTest {

    private static final String SOUNDS = "net/minecraft/sounds/SoundEvents";
    private static final String SOUND = "Lnet/minecraft/sounds/SoundEvent;";
    private static final String REFERENCE = "Lnet/minecraft/core/Holder$Reference;";

    private RetromodTransformer transformer;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("Only the host's holder fields are bridged")
    void holderFieldsComeFromTheHost() {
        ClassNode host = new ClassNode();
        host.fields.add(new FieldNode(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "ARMOR_EQUIP_IRON", REFERENCE, null, null));
        host.fields.add(new FieldNode(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "ITEM_PICKUP", SOUND, null, null));
        assertEquals(Map.of("ARMOR_EQUIP_IRON", REFERENCE), LegacyHolderConstantBridge.holderFields(host),
                "a constant that is still a bare sound event needs no bridge");
    }

    @Test
    @DisplayName("An old bare read unwraps the holder, and a holder read is left alone")
    void oldReadUnwrapsTheHolder() {
        LegacyHolderConstantBridge.registerRedirects(transformer,
                Map.of(SOUNDS, Map.of("ARMOR_EQUIP_IRON", REFERENCE)));
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/ModArmorMaterials", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "sounds", "()V", null, null);
        mv.visitCode();
        mv.visitFieldInsn(GETSTATIC, SOUNDS, "ARMOR_EQUIP_IRON", SOUND);
        mv.visitInsn(POP);
        mv.visitFieldInsn(GETSTATIC, SOUNDS, "ARMOR_EQUIP_IRON", REFERENCE);
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        ClassNode out = new ClassNode();
        new ClassReader(transformer.transformClass(cw.toByteArray(), "test/ModArmorMaterials")).accept(out, 0);
        List<AbstractInsnNode> insns = new ArrayList<>();
        out.methods.forEach(m -> m.instructions.forEach(insns::add));
        assertTrue(insns.stream().anyMatch(i -> i instanceof MethodInsnNode call
                        && call.owner.equals(LegacyHolderConstantBridge.HELPER)
                        && call.desc.equals("()" + SOUND)),
                "the SoundEvent read must go through the unwrapping getter");
        assertTrue(insns.stream().anyMatch(i -> i instanceof FieldInsnNode field
                        && field.owner.equals(SOUNDS) && field.desc.equals(REFERENCE)),
                "a 1.20.5+ mod's holder read must stay a field read");
    }
}
