/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.fabric;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 26.x refuses to build a block whose Properties carry no registry id.
 *
 * <p>A Fabric mod builds the block inside the {@code Registry.register} argument list, so nothing
 * Retromod owns runs before the constructor. Every Fabric mod that adds a block failed at its own
 * class init with {@code NullPointerException: Block id not set}, found by launching Hearth and Home
 * on 26.3. The id is reachable because arguments evaluate left to right, so it is already on the
 * stack when the block expression starts.
 */
class FabricRegistryIdAdapterTest {

    private static final String BRIDGE = FabricRegistryIdSynthetic.INTERNAL;
    private static final String PROPS =
            "net/minecraft/world/level/block/state/BlockBehaviour$Properties";
    private static final String IDENTIFIER = "net/minecraft/resources/Identifier";

    /** The shape a Fabric mod emits: id computed, then the block built, then registered. */
    private static byte[] fabricRegistration() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/mod/Registrar", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "registerBlock", "(Ljava/lang/String;)Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraft/core/registries/BuiltInRegistries",
                "BLOCK", "Lnet/minecraft/core/Registry;");
        mv.visitLdcInsn("testmod");
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, IDENTIFIER, "fromNamespaceAndPath",
                "(Ljava/lang/String;Ljava/lang/String;)L" + IDENTIFIER + ";", false);
        // the block expression starts here, after the id is already on the stack
        mv.visitTypeInsn(Opcodes.NEW, "net/minecraft/world/level/block/Block");
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, PROPS, "of", "()L" + PROPS + ";", false);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "net/minecraft/world/level/block/Block",
                "<init>", "(L" + PROPS + ";)V", false);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/core/Registry", "register",
                "(Lnet/minecraft/core/Registry;L" + IDENTIFIER + ";Ljava/lang/Object;)"
                        + "Ljava/lang/Object;", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private record Call(int opcode, String owner, String name) {}

    private static List<Call> callsIn(byte[] bytes) {
        List<Call> calls = new ArrayList<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int a, String n, String d, String s, String[] e) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int op, String o, String nm, String de, boolean i) {
                        calls.add(new Call(op, o, nm));
                    }
                };
            }
        }, 0);
        return calls;
    }

    @Test
    @DisplayName("the id is stamped before the block is built and cleared after registration")
    void stampsAroundRegistration() {
        List<Call> calls = callsIn(FabricRegistryIdAdapter.apply(fabricRegistration()));

        int push = indexOf(calls, "push");
        int construct = indexOf(calls, "<init>");
        int register = indexOf(calls, "register");
        int pop = indexOf(calls, "pop");

        assertTrue(push >= 0, "the id must be stamped, or the block constructor still sees none");
        assertTrue(pop >= 0, "the id must be cleared, or it leaks into the next registration");
        assertTrue(push < construct,
                "stamping after the constructor is useless: the constructor is what reads the id");
        assertTrue(register < pop, "the id has to stay live until the registration returns");
    }

    @Test
    @DisplayName("a Properties handed to a constructor is stamped where it is used")
    void stampsPropertiesAtTheConstructor() {
        List<Call> calls = callsIn(FabricRegistryIdAdapter.apply(fabricRegistration()));

        int stamp = indexOf(calls, "stampBlock");
        int construct = indexOf(calls, "<init>");
        assertTrue(stamp >= 0,
                "a mod can build one Properties into a shared static long before any registration "
                + "runs, so stamping only where Properties is created misses it");
        assertTrue(stamp < construct, "the stamp has to land before the constructor reads the id");
    }

    @Test
    @DisplayName("a class with no registration is returned untouched")
    void leavesUnrelatedClassesAlone() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/mod/Plain", null, "java/lang/Object", null);
        cw.visitEnd();
        byte[] plain = cw.toByteArray();
        assertSame(plain, FabricRegistryIdAdapter.apply(plain),
                "an untouched class must come back as the same array, not a reserialized copy");
    }

    private static int indexOf(List<Call> calls, String name) {
        for (int i = 0; i < calls.size(); i++) {
            if (calls.get(i).name().equals(name)
                    && (name.equals("<init>") || calls.get(i).owner().equals(BRIDGE)
                        || calls.get(i).owner().equals("net/minecraft/core/Registry"))) {
                return i;
            }
        }
        return -1;
    }
}
