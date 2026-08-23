/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.api.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.retromod.core.RetromodTransformer;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

class MixinExtrasProviderPreservationTest {
    private static final String PROVIDER_PACKAGE = "com/llamalad7/mixinextras/";
    private static final String BOOTSTRAP = PROVIDER_PACKAGE + "MixinExtrasBootstrap";
    private static final String SERVICE = PROVIDER_PACKAGE + "service/MixinExtrasService";
    private static final String COMPONENT = PROVIDER_PACKAGE + "internal/Component";
    private static final String PROVIDER_CALLER = PROVIDER_PACKAGE + "internal/ProviderCaller";
    private static final String SHIM =
            "com/retromod/shim/api/common/embedded/MixinExtrasShim";
    private static final String LMF_DESC =
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                    + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;"
                    + "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                    + "Ljava/lang/invoke/CallSite;";
    private static final Handle METAFACTORY = new Handle(
            Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
            LMF_DESC, false);

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();

    @BeforeEach
    void setUp() {
        transformer.clearRedirectsForTesting();
        new MixinExtrasApiShim().registerRedirects(transformer);
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    void providerConfigPluginKeepsBootstrapCall() {
        String caller = PROVIDER_PACKAGE + "platform/fabric/MixinExtrasConfigPlugin";

        MethodInsnNode call = transformCall(caller, BOOTSTRAP, "init", false);

        assertEquals(BOOTSTRAP, call.owner);
        assertEquals("init", call.name);
        assertEquals("()V", call.desc);
    }

    @Test
    void providerBootstrapKeepsServiceCall() {
        MethodInsnNode call = transformCall(BOOTSTRAP, SERVICE, "setup", true);

        assertEquals(SERVICE, call.owner);
        assertEquals("setup", call.name);
        assertEquals("()V", call.desc);
        assertEquals(Opcodes.INVOKESTATIC, call.getOpcode());
    }

    @Test
    void providerFieldAccessKeepsRedirectedField() {
        transformer.registerFieldRedirect(
                COMPONENT, "legacyState", "I", SHIM, "currentState", "I");

        ClassNode node = transformNode(
                classWithStaticFieldRead(PROVIDER_CALLER, COMPONENT, "legacyState"),
                PROVIDER_CALLER);
        FieldInsnNode field = node.methods.stream()
                .flatMap(method -> java.util.Arrays.stream(method.instructions.toArray()))
                .filter(FieldInsnNode.class::isInstance)
                .map(FieldInsnNode.class::cast)
                .findFirst()
                .orElseThrow();

        assertEquals(COMPONENT, field.owner);
        assertEquals("legacyState", field.name);
        assertEquals("I", field.desc);
    }

    @Test
    void providerMemberNamesStayUnmappedInDeclarationsAndReferences() {
        transformer.registerIntermediaryNameMappings(
                Map.of("method_9999", "currentMethod"),
                Map.of("field_9999", "currentField"));

        ClassNode node = transformNode(classWithMappedMembers(COMPONENT), COMPONENT);

        assertTrue(node.fields.stream().anyMatch(field -> field.name.equals("field_9999")));
        assertTrue(node.methods.stream().anyMatch(method -> method.name.equals("method_9999")));
        assertTrue(node.methods.stream()
                .flatMap(method -> java.util.Arrays.stream(method.instructions.toArray()))
                .filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast)
                .anyMatch(call -> call.owner.equals(COMPONENT)
                        && call.name.equals("method_9999")));
        assertTrue(node.methods.stream()
                .flatMap(method -> java.util.Arrays.stream(method.instructions.toArray()))
                .filter(FieldInsnNode.class::isInstance)
                .map(FieldInsnNode.class::cast)
                .anyMatch(field -> field.owner.equals(COMPONENT)
                        && field.name.equals("field_9999")));
    }

    @Test
    void providerDirectConstructorStaysDirect() {
        transformer.registerConstructorRedirect(
                COMPONENT, "()V", SHIM, "createComponent", "()L" + COMPONENT + ";");

        ClassNode node = transformNode(classWithDirectConstructor(PROVIDER_CALLER), PROVIDER_CALLER);
        TypeInsnNode allocation = node.methods.stream()
                .flatMap(method -> java.util.Arrays.stream(method.instructions.toArray()))
                .filter(TypeInsnNode.class::isInstance)
                .map(TypeInsnNode.class::cast)
                .filter(type -> type.getOpcode() == Opcodes.NEW)
                .findFirst()
                .orElseThrow();
        MethodInsnNode constructor = node.methods.stream()
                .flatMap(method -> java.util.Arrays.stream(method.instructions.toArray()))
                .filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast)
                .filter(call -> call.name.equals("<init>"))
                .findFirst()
                .orElseThrow();

        assertEquals(COMPONENT, allocation.desc);
        assertEquals(COMPONENT, constructor.owner);
        assertEquals(Opcodes.INVOKESPECIAL, constructor.getOpcode());
    }

    @Test
    void providerConstructorReferenceKeepsConstructorHandle() {
        transformer.registerConstructorRedirect(
                COMPONENT, "()V", SHIM, "createComponent", "()L" + COMPONENT + ";");

        Handle handle = implementationHandle(transformer.transformClass(
                classWithConstructorReference(PROVIDER_CALLER), PROVIDER_CALLER));

        assertNotNull(handle);
        assertEquals(Opcodes.H_NEWINVOKESPECIAL, handle.getTag());
        assertEquals(COMPONENT, handle.getOwner());
        assertEquals("<init>", handle.getName());
        assertEquals("()V", handle.getDesc());
    }

    @Test
    void providerMethodReferenceKeepsConvertingHandle() {
        transformer.registerConvertingRedirect(
                COMPONENT, "legacyRun", "()V", SHIM, "currentRun", "()V", 0, 0);

        Handle handle = implementationHandle(transformer.transformClass(
                classWithMethodReference(PROVIDER_CALLER), PROVIDER_CALLER));

        assertNotNull(handle);
        assertEquals(Opcodes.H_INVOKESTATIC, handle.getTag());
        assertEquals(COMPONENT, handle.getOwner());
        assertEquals("legacyRun", handle.getName());
        assertEquals("()V", handle.getDesc());
    }

    @Test
    void legacyModCallerStillRedirectsBootstrapCall() {
        MethodInsnNode call = transformCall("example/LegacyCaller", BOOTSTRAP, "init", false);

        assertEquals(SHIM, call.owner);
        assertEquals("noopInit", call.name);
        assertEquals("()V", call.desc);
    }

    @Test
    void testingResetClearsProviderPackageState() {
        transformer.clearRedirectsForTesting();
        transformer.registerMethodRedirect(
                BOOTSTRAP, "init", "()V", SHIM, "noopInit", "()V");

        String providerCaller = PROVIDER_PACKAGE + "platform/fabric/MixinExtrasConfigPlugin";
        MethodInsnNode call = transformCall(providerCaller, BOOTSTRAP, "init", false);

        assertEquals(SHIM, call.owner);
        assertEquals("noopInit", call.name);
    }

    private MethodInsnNode transformCall(
            String caller, String owner, String methodName, boolean interfaceOwner) {
        byte[] output = transformer.transformClass(
                classWithStaticCall(caller, owner, methodName, interfaceOwner), caller);
        ClassNode node = new ClassNode();
        new ClassReader(output).accept(node, 0);
        return node.methods.stream()
                .flatMap(method -> java.util.Arrays.stream(method.instructions.toArray()))
                .filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast)
                .findFirst()
                .orElseThrow();
    }

    private ClassNode transformNode(byte[] input, String className) {
        ClassNode node = new ClassNode();
        new ClassReader(transformer.transformClass(input, className)).accept(node, 0);
        return node;
    }

    private static Handle implementationHandle(byte[] classBytes) {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        return node.methods.stream()
                .flatMap(method -> java.util.Arrays.stream(method.instructions.toArray()))
                .filter(InvokeDynamicInsnNode.class::isInstance)
                .map(InvokeDynamicInsnNode.class::cast)
                .flatMap(indy -> java.util.Arrays.stream(indy.bsmArgs))
                .filter(Handle.class::isInstance)
                .map(Handle.class::cast)
                .findFirst()
                .orElse(null);
    }

    private static byte[] classWithStaticCall(
            String className, String owner, String methodName, boolean interfaceOwner) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, className, null,
                "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
        method.visitCode();
        method.visitMethodInsn(
                Opcodes.INVOKESTATIC, owner, methodName, "()V", interfaceOwner);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] classWithStaticFieldRead(
            String className, String owner, String fieldName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, className, null,
                "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()I", null, null);
        method.visitCode();
        method.visitFieldInsn(Opcodes.GETSTATIC, owner, fieldName, "I");
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] classWithMappedMembers(String className) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, className, null,
                "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "field_9999", "I", null, null).visitEnd();
        MethodVisitor mapped = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "method_9999", "()V", null, null);
        mapped.visitCode();
        mapped.visitInsn(Opcodes.RETURN);
        mapped.visitMaxs(0, 0);
        mapped.visitEnd();
        MethodVisitor caller = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
        caller.visitCode();
        caller.visitFieldInsn(Opcodes.GETSTATIC, className, "field_9999", "I");
        caller.visitInsn(Opcodes.POP);
        caller.visitMethodInsn(Opcodes.INVOKESTATIC, className, "method_9999", "()V", false);
        caller.visitInsn(Opcodes.RETURN);
        caller.visitMaxs(0, 0);
        caller.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] classWithDirectConstructor(String className) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, className, null,
                "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "make", "()L" + COMPONENT + ";",
                null, null);
        method.visitCode();
        method.visitTypeInsn(Opcodes.NEW, COMPONENT);
        method.visitInsn(Opcodes.DUP);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, COMPONENT, "<init>", "()V", false);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] classWithConstructorReference(String className) {
        return classWithReference(className, "get", "()Ljava/lang/Object;",
                new Handle(Opcodes.H_NEWINVOKESPECIAL, COMPONENT, "<init>", "()V", false),
                "()L" + COMPONENT + ";", "java/util/function/Supplier");
    }

    private static byte[] classWithMethodReference(String className) {
        return classWithReference(className, "run", "()V",
                new Handle(Opcodes.H_INVOKESTATIC, COMPONENT, "legacyRun", "()V", false),
                "()V", "java/lang/Runnable");
    }

    private static byte[] classWithReference(
            String className, String samName, String erasedDescriptor, Handle implementation,
            String instantiatedDescriptor, String interfaceName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, className, null,
                "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "reference",
                "()L" + interfaceName + ";", null, null);
        method.visitCode();
        method.visitInvokeDynamicInsn(samName, "()L" + interfaceName + ";", METAFACTORY,
                Type.getMethodType(erasedDescriptor), implementation,
                Type.getMethodType(instantiatedDescriptor));
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
