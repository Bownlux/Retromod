/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim;

import com.retromod.core.RetromodTransformer;
import com.retromod.shim.fabric.Fabric_1_18_2_to_1_19;
import com.retromod.shim.fabric.Fabric_1_21_8_to_1_21_9;
import com.retromod.shim.forge.Forge_1_13_2_to_1_14_4;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies that legacy constructor shims use constructor-aware bytecode rewrites. */
class ConstructorShimMigrationTest {

    private static final String KEY_BINDING = "net/minecraft/client/option/KeyBinding";
    private static final String KEY_BINDING_DESC =
            "(Ljava/lang/String;Lnet/minecraft/client/util/InputUtil$Type;ILjava/lang/String;)V";
    private static final String KEY_BINDING_FACTORY =
            "com/retromod/shim/fabric/embedded/KeyBindingShim";
    private static final String LITERAL_TEXT = "net/minecraft/text/LiteralText";
    private static final String TEXT = "net/minecraft/text/Text";
    private static final String BLOCK = "net/minecraft/block/Block";
    private static final String OLD_PROPERTIES = "net/minecraft/block/Block$Properties";
    private static final String NEW_PROPERTIES = "net/minecraft/block/AbstractBlock$Properties";

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
    void objectReturningKeyBindingFactoryRemovesNewAndAddsCast() {
        new Fabric_1_21_8_to_1_21_9().registerRedirects(transformer);

        ClassNode output = transform(keyBindingMaker(), "test/KeyBindingMaker");
        MethodInsnNode factory = findCall(output, KEY_BINDING_FACTORY, "create");

        assertNotNull(factory, "KeyBinding constructor must become a factory call");
        assertEquals(Opcodes.INVOKESTATIC, factory.getOpcode());
        assertEquals("(Ljava/lang/String;Ljava/lang/Object;ILjava/lang/String;)Ljava/lang/Object;",
                factory.desc, "factory descriptor must match the helper's real Object return");
        assertFalse(hasNew(output, KEY_BINDING), "factory conversion must remove NEW KeyBinding");
        assertTrue(hasTypeInsn(output, Opcodes.CHECKCAST, KEY_BINDING),
                "Object-returning factory needs a cast back to KeyBinding");
    }

    @Test
    void textFactoryUsesInterfaceMethodReferenceAndMutableReturn() {
        new Fabric_1_18_2_to_1_19().registerRedirects(transformer);

        ClassNode output = transform(literalTextMaker(), "test/LiteralTextMaker");
        MethodInsnNode factory = findCall(output, TEXT, "literal");

        assertNotNull(factory, "LiteralText constructor must become Text.literal");
        assertEquals(Opcodes.INVOKESTATIC, factory.getOpcode());
        assertTrue(factory.itf, "Text.literal must use an InterfaceMethodref");
        assertEquals("(Ljava/lang/String;)Lnet/minecraft/text/MutableText;", factory.desc);
        assertFalse(hasNew(output, LITERAL_TEXT), "factory conversion must remove NEW LiteralText");
    }

    @Test
    void movedBlockPropertiesTypeRetypesConstructorDescriptor() {
        new Forge_1_13_2_to_1_14_4().registerRedirects(transformer);

        ClassNode output = transform(blockMaker(), "test/BlockMaker");
        MethodInsnNode constructor = findCall(output, BLOCK, "<init>");

        assertNotNull(constructor);
        assertEquals("(L" + NEW_PROPERTIES + ";)V", constructor.desc);
        assertFalse(constructor.desc.contains(OLD_PROPERTIES),
                "old Block.Properties type must not survive in the constructor descriptor");
    }

    private ClassNode transform(byte[] input, String className) {
        ClassNode output = new ClassNode();
        new ClassReader(transformer.transformClass(input, className)).accept(output, 0);
        return output;
    }

    private static byte[] keyBindingMaker() {
        ClassWriter writer = maker("test/KeyBindingMaker");
        MethodVisitor method = makeMethod(writer);
        method.visitTypeInsn(Opcodes.NEW, KEY_BINDING);
        method.visitInsn(Opcodes.DUP);
        method.visitLdcInsn("retromod.test");
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitInsn(Opcodes.ICONST_0);
        method.visitLdcInsn("retromod.category");
        method.visitMethodInsn(
                Opcodes.INVOKESPECIAL, KEY_BINDING, "<init>", KEY_BINDING_DESC, false);
        finishMaker(writer, method);
        return writer.toByteArray();
    }

    private static byte[] literalTextMaker() {
        ClassWriter writer = maker("test/LiteralTextMaker");
        MethodVisitor method = makeMethod(writer);
        method.visitTypeInsn(Opcodes.NEW, LITERAL_TEXT);
        method.visitInsn(Opcodes.DUP);
        method.visitLdcInsn("retromod.test");
        method.visitMethodInsn(
                Opcodes.INVOKESPECIAL, LITERAL_TEXT, "<init>", "(Ljava/lang/String;)V", false);
        finishMaker(writer, method);
        return writer.toByteArray();
    }

    private static byte[] blockMaker() {
        ClassWriter writer = maker("test/BlockMaker");
        MethodVisitor method = makeMethod(writer);
        method.visitTypeInsn(Opcodes.NEW, BLOCK);
        method.visitInsn(Opcodes.DUP);
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, BLOCK, "<init>",
                "(L" + OLD_PROPERTIES + ";)V", false);
        finishMaker(writer, method);
        return writer.toByteArray();
    }

    private static ClassWriter maker(String name) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        return writer;
    }

    private static MethodVisitor makeMethod(ClassWriter writer) {
        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "make", "()Ljava/lang/Object;", null, null);
        method.visitCode();
        return method;
    }

    private static void finishMaker(ClassWriter writer, MethodVisitor method) {
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
    }

    private static MethodInsnNode findCall(ClassNode node, String owner, String name) {
        for (var method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call
                        && owner.equals(call.owner) && name.equals(call.name)) {
                    return call;
                }
            }
        }
        return null;
    }

    private static boolean hasNew(ClassNode node, String type) {
        return hasTypeInsn(node, Opcodes.NEW, type);
    }

    private static boolean hasTypeInsn(ClassNode node, int opcode, String type) {
        for (var method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof TypeInsnNode typeInsn
                        && typeInsn.getOpcode() == opcode && type.equals(typeInsn.desc)) {
                    return true;
                }
            }
        }
        return false;
    }
}
