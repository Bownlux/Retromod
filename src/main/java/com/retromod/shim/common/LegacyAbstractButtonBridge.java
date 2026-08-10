package com.retromod.shim.common;

import java.util.Set;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/** Repairs old custom buttons for the abstract rendering contract used by 26.x. */
public final class LegacyAbstractButtonBridge {
    private static final Set<String> BUTTON_SUPERCLASSES = Set.of(
            "net/minecraft/client/gui/components/Button",
            "net/minecraft/client/gui/components/AbstractButton",
            "net/minecraft/client/gui/components/AbstractWidget");
    private static final String METHOD_NAME = "extractContents";
    private static final String METHOD_DESC =
            "(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V";

    private LegacyAbstractButtonBridge() {
    }

    /**
     * Adds a no-op implementation when an old direct button subclass lacks the new abstract
     * method. The bridge intentionally applies only to direct subclasses so unrelated widgets
     * are not changed.
     */
    public static byte[] apply(byte[] classBytes) {
        if (classBytes == null) {
            return null;
        }

        ClassReader reader = new ClassReader(classBytes);
        if (!BUTTON_SUPERCLASSES.contains(reader.getSuperName())) {
            return classBytes;
        }

        ClassNode classNode = new ClassNode();
        reader.accept(classNode, 0);
        boolean hasMethod = classNode.methods.stream()
                .anyMatch(method -> METHOD_NAME.equals(method.name)
                        && METHOD_DESC.equals(method.desc));
        if (hasMethod) {
            return classBytes;
        }

        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, METHOD_NAME, METHOD_DESC,
                null, null);
        boolean hasLegacyRenderer = classNode.methods.stream()
                .anyMatch(candidate -> "renderContents".equals(candidate.name)
                        && METHOD_DESC.equals(candidate.desc));
        MethodVisitor body = method;
        body.visitCode();
        if (hasLegacyRenderer) {
            body.visitVarInsn(Opcodes.ALOAD, 0);
            body.visitVarInsn(Opcodes.ALOAD, 1);
            body.visitVarInsn(Opcodes.ILOAD, 2);
            body.visitVarInsn(Opcodes.ILOAD, 3);
            body.visitVarInsn(Opcodes.FLOAD, 4);
            body.visitMethodInsn(Opcodes.INVOKEVIRTUAL, classNode.name, "renderContents",
                    METHOD_DESC, false);
        }
        body.visitInsn(Opcodes.RETURN);
        body.visitMaxs(hasLegacyRenderer ? 5 : 0, 5);
        body.visitEnd();
        classNode.methods.add(method);

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        classNode.accept(writer);
        return writer.toByteArray();
    }
}
