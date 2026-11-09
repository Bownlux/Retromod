package com.retromod.shim.common;

import java.util.Set;

import com.retromod.core.RetromodVersion;
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

    // 1.21.11 made AbstractButton.renderWidget final over an abstract renderContents, before 26.1
    // renamed both. Fabric runs that host under intermediary names (class_4185 Button, class_4264
    // AbstractButton, class_332 GuiGraphics).
    private static final Set<String> BUTTONS_1_21_11 = Set.of(
            "net/minecraft/client/gui/components/Button", "net/minecraft/client/gui/components/AbstractButton");
    private static final Set<String> INTERMEDIARY_BUTTONS = Set.of("net/minecraft/class_4185", "net/minecraft/class_4264");
    private static final String RENDER_CONTENTS = "renderContents";
    private static final String RENDER_DESC = "(Lnet/minecraft/client/gui/GuiGraphics;IIF)V";
    private static final String INTERMEDIARY_RENDER_CONTENTS = "method_75752";
    private static final String INTERMEDIARY_RENDER_DESC = "(Lnet/minecraft/class_332;IIF)V";

    private LegacyAbstractButtonBridge() {
    }

    /** The repair for {@code targetMcVersion}: the 26.x method from 26.1, the 1.21.11 one before, and none earlier. */
    public static byte[] apply(byte[] classBytes, String targetMcVersion) {
        if (classBytes == null || targetMcVersion == null) return classBytes;
        if (RetromodVersion.isUnobfuscatedTarget(targetMcVersion)) return apply(classBytes);
        if (RetromodVersion.compareMcVersions(targetMcVersion, "1.21.11") < 0) return classBytes;
        // A button left with the old renderWidget, which the final method no longer calls, gets an
        // empty renderContents instead of an AbstractMethodError when it is drawn.
        ClassReader reader = new ClassReader(classBytes);
        String superName = reader.getSuperName();
        if (superName != null && BUTTONS_1_21_11.contains(superName)) {
            return addEmpty(classBytes, reader, RENDER_CONTENTS, RENDER_DESC);
        }
        if (superName != null && INTERMEDIARY_BUTTONS.contains(superName)) {
            return addEmpty(classBytes, reader, INTERMEDIARY_RENDER_CONTENTS, INTERMEDIARY_RENDER_DESC);
        }
        return classBytes;
    }

    private static byte[] addEmpty(byte[] classBytes, ClassReader reader, String name, String desc) {
        ClassNode classNode = new ClassNode();
        reader.accept(classNode, 0);
        if (classNode.methods.stream().anyMatch(method -> name.equals(method.name) && desc.equals(method.desc))) {
            return classBytes;
        }
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, name, desc, null, null);
        method.visitCode();
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 5);
        method.visitEnd();
        classNode.methods.add(method);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        classNode.accept(writer);
        return writer.toByteArray();
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
        // A module descriptor has no superclass, and an immutable set refuses a null lookup.
        String superName = reader.getSuperName();
        if (superName == null || !BUTTON_SUPERCLASSES.contains(superName)) {
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
