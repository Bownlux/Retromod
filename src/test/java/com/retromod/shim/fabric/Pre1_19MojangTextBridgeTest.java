/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.ArrayList;
import java.util.List;

import static com.retromod.shim.fabric.Pre1_19MojangTextBridge.*;
import static org.junit.jupiter.api.Assertions.*;

/** A 1.16.5 Fabric mod's {@code new LiteralText(s).formatted(f)} after the 26.x remap, which named the text contents records. */
class Pre1_19MojangTextBridgeTest {

    private static final String MOD = "test/text/Old";
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private RetromodTransformer transformer;

    @BeforeEach
    void setUp() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        // The whole 1.19 and 26.1 shims, so the 26.1 contents constructor repair meets this bridge as it does in a run.
        new Fabric_1_18_2_to_1_19().registerRedirects(transformer);
        new Fabric_1_21_11_to_26_1().registerRedirects(transformer);
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
        transformer.setModSourceVersion(null);
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
    }

    @Test
    @DisplayName("a pre-1.19 mod builds components with the factories and styles them as MutableComponent")
    void oldTextBecomesComponents() {
        transformer.setModSourceVersion("1.16.5");
        List<String> code = code(transformer.transformClass(oldText(), MOD));
        assertTrue(code.contains("CALL " + COMPONENT + ".literal(Ljava/lang/String;)L" + MUTABLE + ";"), code.toString());
        assertTrue(code.contains("CALL " + COMPONENT + ".translatable(Ljava/lang/String;)L" + MUTABLE + ";"), code.toString());
        assertTrue(code.contains("CALL " + MUTABLE + ".withStyle(Lnet/minecraft/ChatFormatting;)L" + MUTABLE + ";"), code.toString());
        assertTrue(code.contains("CALL " + TEXTS + ".getKey(L" + COMPONENT + ";)Ljava/lang/String;"),
                "the key is read from the component's contents: " + code);
        assertTrue(code.contains("FIELD net/minecraft/network/chat/CommonComponents.EMPTY"), "the old empty literal: " + code);
        assertFalse(code.stream().anyMatch(line -> line.contains("contents/")), "no contents record is built or called: " + code);
    }

    @Test
    @DisplayName("a mod built for 1.19 or newer keeps the contents records it means")
    void newerModsKeepTheContents() {
        transformer.setModSourceVersion("1.19.2");
        List<String> code = code(transformer.transformClass(oldText(), MOD));
        assertTrue(code.contains("NEW " + LITERAL), code.toString());
        assertTrue(code.contains("CALL " + TRANSLATABLE + ".getKey()Ljava/lang/String;"), code.toString());
    }

    @Test
    @DisplayName("a mod that names no Minecraft version keeps the contents records, which a 1.19 mod may extend")
    void unknownVersionKeepsTheContents() {
        transformer.setModSourceVersion(null);
        List<String> code = code(transformer.transformClass(oldText(), MOD));
        assertTrue(code.contains("NEW " + LITERAL), code.toString());
        assertFalse(code.stream().anyMatch(line -> line.startsWith("NEW " + MUTABLE)), code.toString());
    }

    @Test
    @DisplayName("a mod's own subclass of the old literal text keeps its superclass, since MutableComponent is final")
    void subclassesKeepTheirSuperclass() {
        transformer.setModSourceVersion("1.18.2");
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/text/Marker", null, LITERAL, null);
        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitLdcInsn("  ");
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, LITERAL, "<init>", KEY, false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        writer.visitEnd();

        byte[] out = transformer.transformClass(writer.toByteArray(), "test/text/Marker");
        assertEquals(LITERAL, new ClassReader(out).getSuperName());
        assertTrue(code(out).contains("CALL " + LITERAL + ".<init>" + KEY), code(out).toString());
        assertTrue(code(transformer.transformClass(oldText(), MOD)).contains("CALL " + COMPONENT + ".literal(Ljava/lang/String;)L" + MUTABLE + ";"),
                "other classes still build components");
    }

    /** What the 26.x remap leaves of {@code new LiteralText("hi").formatted(RED)} and {@code new TranslatableText("k").getKey()}. */
    private static byte[] oldText() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, MOD, null, "java/lang/Object", null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, LITERAL);
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn("hi");
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, LITERAL, "<init>", KEY, false);
        mv.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraft/ChatFormatting", "RED", "Lnet/minecraft/ChatFormatting;");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LITERAL, "withStyle", "(Lnet/minecraft/ChatFormatting;)L" + MUTABLE + ";", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitTypeInsn(Opcodes.NEW, TRANSLATABLE);
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn("k");
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, TRANSLATABLE, "<init>", KEY, false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, TRANSLATABLE, "getKey", "()Ljava/lang/String;", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitFieldInsn(Opcodes.GETSTATIC, LITERAL, "text", "L" + COMPONENT + ";");
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static List<String> code(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        List<String> code = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) code.add("CALL " + call.owner + "." + call.name + call.desc);
                if (insn instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW) code.add("NEW " + type.desc);
                if (insn instanceof org.objectweb.asm.tree.FieldInsnNode field) code.add("FIELD " + field.owner + "." + field.name);
            }
        }
        return code;
    }
}
