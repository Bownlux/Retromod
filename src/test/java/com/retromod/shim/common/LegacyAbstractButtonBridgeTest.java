package com.retromod.shim.common;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void addsTheShapeOfTheHostVersion() {
        byte[] mojang = legacyButton("example/OldButton", "net/minecraft/client/gui/components/AbstractButton");
        byte[] intermediary = legacyButton("example/OldButton", "net/minecraft/class_4264");

        assertTrue(read(LegacyAbstractButtonBridge.apply(mojang, "1.21.11")).methods.stream().anyMatch(method ->
                method.name.equals("renderContents") && method.desc.equals("(Lnet/minecraft/client/gui/GuiGraphics;IIF)V")),
                "1.21.11 draws a button through an abstract renderContents");
        assertTrue(read(LegacyAbstractButtonBridge.apply(intermediary, "1.21.11")).methods.stream().anyMatch(method ->
                method.name.equals("method_75752") && method.desc.equals("(Lnet/minecraft/class_332;IIF)V")),
                "Fabric runs 1.21.11 under intermediary names");
        assertSame(mojang, LegacyAbstractButtonBridge.apply(mojang, "1.21.10"));
        assertTrue(read(LegacyAbstractButtonBridge.apply(mojang, "26.2")).methods.stream()
                .anyMatch(method -> method.name.equals("extractContents")));
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
