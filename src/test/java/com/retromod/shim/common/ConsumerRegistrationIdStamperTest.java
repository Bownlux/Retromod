/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.shim.forge.RegistryIdBridgeSynthetic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Shapes javac does not emit but other compilers can, such as Kotlin's
 * {@code helper.register(id, Item(props ?: return))}: a return or throw inside the statement
 * that builds the object would skip putting the thread's id back, so the window is declined.
 */
class ConsumerRegistrationIdStamperTest {

    private static final String OWNER = "test/consumer/Raw";
    private static final String HELPER = "net/minecraftforge/registries/RegisterEvent$RegisterHelper";
    private static final String IDENTIFIER = "net/minecraft/resources/Identifier";
    private static final String ITEM = "net/minecraft/world/item/Item";
    private static final String PROPERTIES = ITEM + "$Properties";
    private static final String BRIDGE = RegistryIdBridgeSynthetic.FORGE_INTERNAL;

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({"local, none, 1", "local, return, 0", "local, throw, 0",
            "inline, none, 1", "inline, return, 0", "inline, throw, 0"})
    @DisplayName("a window the method can return or throw out of is left alone")
    void exitsInsideTheWindowAreDeclined(String shape, String exit, int expectedEnters) {
        byte[] raw = shape.equals("local") ? localShape(exit) : inlineShape(exit);

        byte[] stamped = ConsumerRegistrationIdStamper.apply(raw, StaticFieldRegistryIds.NONE, BRIDGE);

        assertEquals(expectedEnters, bridgeCalls(stamped, "enter"), shape + " window with " + exit + " inside");
        assertEquals(expectedEnters, bridgeCalls(stamped, "leave") / 2,
                "each window puts the id back once on the way out and once in its handler");
    }

    /** {@code Item it = new Item(new Properties()), but exit first when FLAG; helper.register(m:c90, it);} */
    private static byte[] localShape(String exit) {
        return classWith(mv -> {
            mv.visitTypeInsn(Opcodes.NEW, ITEM);
            mv.visitInsn(Opcodes.DUP);
            newProperties(mv);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, ITEM, "<init>", "(L" + PROPERTIES + ";)V", false);
            // The built item stays on the stack across the exit, as a compiler's early return leaves it.
            exitWhenFlagged(mv, exit);
            mv.visitVarInsn(Opcodes.ASTORE, 1);
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            literalId(mv, "c90");
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            register(mv);
        });
    }

    /** {@code helper.register(m:c91, FLAG ? <exit> : new Item(new Properties()));} */
    private static byte[] inlineShape(String exit) {
        return classWith(mv -> {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            literalId(mv, "c91");
            exitWhenFlagged(mv, exit);
            mv.visitTypeInsn(Opcodes.NEW, ITEM);
            mv.visitInsn(Opcodes.DUP);
            newProperties(mv);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, ITEM, "<init>", "(L" + PROPERTIES + ";)V", false);
            register(mv);
        });
    }

    private static void exitWhenFlagged(MethodVisitor mv, String exit) {
        if (exit.equals("none")) return;
        Label stay = new Label();
        mv.visitFieldInsn(Opcodes.GETSTATIC, OWNER, "FLAG", "Z");
        mv.visitJumpInsn(Opcodes.IFEQ, stay);
        if (exit.equals("return")) {
            mv.visitInsn(Opcodes.RETURN);
        } else {
            mv.visitTypeInsn(Opcodes.NEW, "java/lang/IllegalStateException");
            mv.visitInsn(Opcodes.DUP);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "()V", false);
            mv.visitInsn(Opcodes.ATHROW);
        }
        mv.visitLabel(stay);
    }

    private static void newProperties(MethodVisitor mv) {
        mv.visitTypeInsn(Opcodes.NEW, PROPERTIES);
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, PROPERTIES, "<init>", "()V", false);
    }

    private static void literalId(MethodVisitor mv, String path) {
        mv.visitLdcInsn("m");
        mv.visitLdcInsn(path);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, IDENTIFIER, "fromNamespaceAndPath",
                "(Ljava/lang/String;Ljava/lang/String;)L" + IDENTIFIER + ";", false);
    }

    private static void register(MethodVisitor mv) {
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, HELPER, "register", "(L" + IDENTIFIER + ";Ljava/lang/Object;)V", true);
        mv.visitInsn(Opcodes.RETURN);
    }

    /** A class with a static FLAG and one {@code static void build(RegisterHelper)} written by {@code body}. */
    private static byte[] classWith(java.util.function.Consumer<MethodVisitor> body) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                return "java/lang/Object";
            }
        };
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, OWNER, null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_STATIC, "FLAG", "Z", null, null).visitEnd();
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "build", "(L" + HELPER + ";)V", null, null);
        mv.visitCode();
        body.accept(mv);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static int bridgeCalls(byte[] classBytes, String name) {
        ClassNode cn = new ClassNode();
        new ClassReader(classBytes).accept(cn, 0);
        int count = 0;
        for (MethodNode m : cn.methods) {
            for (AbstractInsnNode insn : m.instructions) {
                if (insn instanceof MethodInsnNode call && call.owner.equals(BRIDGE) && call.name.equals(name)) count++;
            }
        }
        return count;
    }
}
