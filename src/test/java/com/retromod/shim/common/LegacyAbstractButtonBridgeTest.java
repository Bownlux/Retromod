package com.retromod.shim.common;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

class LegacyAbstractButtonBridgeTest {
    private static final String EXTRACT_DESC =
            "(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V";

    @Test
    void addsExtractContentsToLegacyButtonSubclass() {
        byte[] original = legacyButton("example/LegacyButton");

        byte[] repaired = LegacyAbstractButtonBridge.apply(original);
        ClassNode node = read(repaired);

        assertEquals(1, node.methods.stream()
                .filter(method -> "extractContents".equals(method.name)
                        && EXTRACT_DESC.equals(method.desc))
                .count());
        MethodNode bridge = node.methods.stream()
                .filter(method -> "extractContents".equals(method.name))
                .findFirst()
                .orElseThrow();
        assertEquals("renderContents", invokedMethod(bridge));
        assertArrayEquals(repaired, LegacyAbstractButtonBridge.apply(repaired));
    }

    @Test
    void leavesUnrelatedClassesUntouched() {
        byte[] original = legacyButton("example/PlainClass", "java/lang/Object");

        assertArrayEquals(original, LegacyAbstractButtonBridge.apply(original));
    }

    private static byte[] legacyButton(String name) {
        return legacyButton(name, "net/minecraft/client/gui/components/Button");
    }

    private static byte[] legacyButton(String name, String superName) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, superName, null);
        MethodNode render = new MethodNode(Opcodes.ACC_PROTECTED, "renderContents",
                EXTRACT_DESC, null, null);
        render.visitCode();
        render.visitInsn(Opcodes.RETURN);
        render.visitMaxs(0, 5);
        render.visitEnd();
        render.accept(writer);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static String invokedMethod(MethodNode method) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call) {
                return call.name;
            }
        }
        return null;
    }
}
