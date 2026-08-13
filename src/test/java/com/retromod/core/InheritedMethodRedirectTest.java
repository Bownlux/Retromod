/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.retromod.shim.fabric.Fabric_1_21_8_to_1_21_9;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Regression for inherited calls whose constant-pool owner is a mod subclass (#179). */
class InheritedMethodRedirectTest {

    private static final String ENTITY = "net/minecraft/class_1297";
    private static final String WORLD = "net/minecraft/class_1937";
    private static final String OLD_NAME = "method_37908";
    private static final String NEW_NAME = "method_73183";
    private static final String DESC = "()L" + WORLD + ";";
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void reset() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        transformer.clearJarClassBytesProvider();
    }

    @Test
    @DisplayName("#179: inherited Entity world call is repaired when bytecode names a mod subclass")
    void redirectsExactAliasThroughProvenModHierarchy() {
        RetromodVersion.TARGET_MC_VERSION = "1.21.11";
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        new Fabric_1_21_8_to_1_21_9().registerRedirects(transformer);

        String intermediate = "horror/blueice129/entity/LegacyMonster";
        String engramEntity = "horror/blueice129/entity/Blueice129Entity";
        String unreadableMarker = "example/UnreadableMarker";
        Map<String, byte[]> classes = new HashMap<>();
        classes.put(intermediate, emptyClass(intermediate, ENTITY, unreadableMarker));
        classes.put(unreadableMarker, new byte[]{0});
        classes.put(engramEntity, callerClass(engramEntity, intermediate, OLD_NAME, DESC));
        transformer.setJarClassBytesProvider(classes::get);

        MethodCall call = singleCall(transformer.transformClass(
                classes.get(engramEntity), engramEntity), "probe");
        assertEquals(new MethodCall(ENTITY, NEW_NAME, DESC), call,
                "the exact alias must move to the Minecraft base owner");
    }

    @Test
    @DisplayName("Hierarchy-aware redirects do not guess for an unrelated owner or overload")
    void leavesUnprovenOwnerAndDifferentDescriptorAlone() {
        RetromodVersion.TARGET_MC_VERSION = "1.21.11";
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        new Fabric_1_21_8_to_1_21_9().registerRedirects(transformer);

        String unrelated = "example/Unrelated";
        byte[] sameShape = callerClass(unrelated, "java/lang/Object", OLD_NAME, DESC);
        transformer.setJarClassBytesProvider(name -> unrelated.equals(name) ? sameShape : null);
        assertEquals(new MethodCall(unrelated, OLD_NAME, DESC),
                singleCall(transformer.transformClass(sameShape, unrelated), "probe"),
                "an exact method name is not enough without a proven Entity hierarchy");

        String overloadDesc = "(I)L" + WORLD + ";";
        String descendant = "example/EntityChild";
        byte[] overload = callerClass(descendant, ENTITY, OLD_NAME, overloadDesc);
        transformer.setJarClassBytesProvider(name -> descendant.equals(name) ? overload : null);
        assertEquals(new MethodCall(descendant, OLD_NAME, overloadDesc),
                singleCall(transformer.transformClass(overload, descendant), "probe"),
                "a different descriptor must not inherit an alias registered for the no-arg method");
    }

    private static byte[] emptyClass(String name, String superName, String... interfaces) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                name, null, superName, interfaces);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] callerClass(
            String name, String superName, String calledName, String calledDesc) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                name, null, superName, null);
        String probeDesc = "(L" + name + ";"
                + (calledDesc.startsWith("(I)") ? "I" : "") + ")L" + WORLD + ";";
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "probe", probeDesc, null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        if (calledDesc.startsWith("(I)")) method.visitVarInsn(Opcodes.ILOAD, 1);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, name, calledName, calledDesc, false);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(2, 2);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static MethodCall singleCall(byte[] classBytes, String methodName) {
        MethodCall[] found = {null};
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                if (!methodName.equals(name)) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String calledName,
                            String calledDesc, boolean isInterface) {
                        found[0] = new MethodCall(owner, calledName, calledDesc);
                    }
                };
            }
        }, 0);
        return found[0];
    }

    private record MethodCall(String owner, String name, String descriptor) {}
}
