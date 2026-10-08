/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** A method reference follows the same redirects as a direct call to its method. */
class MethodReferenceRedirectTest {

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();

    @AfterEach
    void cleanUp() {
        transformer.clearRedirectsForTesting();
    }

    private static final Handle METAFACTORY = new Handle(H_INVOKESTATIC,
            "java/lang/invoke/LambdaMetafactory", "metafactory",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                    + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                    + "Ljava/lang/invoke/CallSite;", false);

    /** {@code Consumer<Object> c = receiver::method;} with the given implementation handle. */
    private static byte[] reference(String receiver, Handle implementation) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/Registrar", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "bind",
                "(L" + receiver + ";)Ljava/util/function/Consumer;", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitInvokeDynamicInsn("accept", "(L" + receiver + ";)Ljava/util/function/Consumer;",
                METAFACTORY, Type.getType("(Ljava/lang/Object;)V"), implementation,
                Type.getType("(Ljava/lang/Object;)V"));
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private Handle implementationAfterTransform(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(transformer.transformClass(bytes, "test/Registrar.class")).accept(cn, 0);
        InvokeDynamicInsnNode indy = Arrays.stream(cn.methods.stream()
                        .filter(m -> m.name.equals("bind")).findFirst().orElseThrow().instructions.toArray())
                .filter(InvokeDynamicInsnNode.class::isInstance).map(InvokeDynamicInsnNode.class::cast)
                .findFirst().orElseThrow();
        return (Handle) indy.bsmArgs[1];
    }

    @Test
    void instanceToStaticRedirectRetargetsTheReference() {
        transformer.registerMethodRedirect("test/OldRegistry", "register", "(Ljava/lang/Object;)V",
                "test/RegistryBridge", "register", "(Ljava/lang/Object;Ljava/lang/Object;)V", true);

        Handle handle = implementationAfterTransform(reference("test/OldRegistry",
                new Handle(H_INVOKEINTERFACE, "test/OldRegistry", "register", "(Ljava/lang/Object;)V", true)));

        assertEquals(new Handle(H_INVOKESTATIC, "test/RegistryBridge", "register",
                        "(Ljava/lang/Object;Ljava/lang/Object;)V", false), handle,
                "registry::register must call the bridge with the captured registry as its first argument");
    }

    /** {@code interface OldRegistry { void register(Object value); }} */
    private static byte[] oldRegistry() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC | ACC_ABSTRACT | ACC_INTERFACE, "test/OldRegistry", null, "java/lang/Object", null);
        cw.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "register", "(Ljava/lang/Object;)V", null, null).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code class ListRegistry implements OldRegistry}, whose own register does nothing. */
    private static byte[] listRegistry() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/ListRegistry", null, "java/lang/Object",
                new String[]{"test/OldRegistry"});
        MethodVisitor ctor = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(ALOAD, 0);
        ctor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();
        MethodVisitor register = cw.visitMethod(ACC_PUBLIC, "register", "(Ljava/lang/Object;)V", null, null);
        register.visitCode();
        register.visitInsn(RETURN);
        register.visitMaxs(0, 0);
        register.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code static void register(Object registry, Object value) { ((List) value).add(registry); }} */
    private static byte[] registryBridge() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/RegistryBridge", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "register",
                "(Ljava/lang/Object;Ljava/lang/Object;)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 1);
        mv.visitTypeInsn(CHECKCAST, "java/util/List");
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true);
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    @SuppressWarnings("unchecked")
    void boundReferenceToAnObjectHelperLinksAndPassesTheReceiver() throws Exception {
        transformer.registerMethodRedirect("test/OldRegistry", "register", "(Ljava/lang/Object;)V",
                "test/RegistryBridge", "register", "(Ljava/lang/Object;Ljava/lang/Object;)V", true);
        byte[] registrar = transformer.transformClass(reference("test/OldRegistry",
                        new Handle(H_INVOKEINTERFACE, "test/OldRegistry", "register", "(Ljava/lang/Object;)V", true)),
                "test/Registrar.class");
        java.util.Map<String, byte[]> classes = java.util.Map.of(
                "test.OldRegistry", oldRegistry(), "test.ListRegistry", listRegistry(),
                "test.RegistryBridge", registryBridge(), "test.Registrar", registrar);
        ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classes.get(name);
                if (bytes == null) throw new ClassNotFoundException(name);
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
        Class<?> registryType = loader.loadClass("test.OldRegistry");
        Object registry = loader.loadClass("test.ListRegistry").getConstructor().newInstance();

        java.util.function.Consumer<Object> consumer = (java.util.function.Consumer<Object>)
                loader.loadClass("test.Registrar").getMethod("bind", registryType).invoke(null, registry);
        java.util.List<Object> received = new java.util.ArrayList<>();
        consumer.accept(received);

        assertEquals(java.util.List.of(registry), received,
                "the captured registry must reach the bridge, which needs the captured type widened to Object");
    }

    @Test
    void renamedMethodKeepsTheReceiverKind() {
        transformer.registerMethodRedirect("test/Widget", "oldName", "(Ljava/lang/Object;)V",
                "test/Widget", "newName", "(Ljava/lang/Object;)V");

        Handle handle = implementationAfterTransform(reference("test/Widget",
                new Handle(H_INVOKEVIRTUAL, "test/Widget", "oldName", "(Ljava/lang/Object;)V", false)));

        assertEquals(new Handle(H_INVOKEVIRTUAL, "test/Widget", "newName", "(Ljava/lang/Object;)V", false),
                handle);
    }

    @Test
    void redirectThatChangesAParameterTypeLeavesTheReferenceAlone() {
        transformer.registerMethodRedirect("test/Widget", "oldName", "(Ljava/lang/Object;)V",
                "test/Widget", "newName", "(Ljava/lang/String;)V");
        Handle original = new Handle(H_INVOKEVIRTUAL, "test/Widget", "oldName", "(Ljava/lang/Object;)V", false);

        assertEquals(original, implementationAfterTransform(reference("test/Widget", original)),
                "the metafactory never casts a parameter, so a narrower parameter cannot be linked");
    }

    @Test
    void interfaceReferenceOnAClassOwnerUsesTheVirtualKind() {
        transformer.registerClassOwner("test/StateClass");
        // A host always has method redirects; with none the transformer skips method bodies.
        transformer.registerMethodRedirect("test/Unrelated", "old", "()V", "test/Unrelated", "renamed", "()V");

        Handle handle = implementationAfterTransform(reference("test/StateClass",
                new Handle(H_INVOKEINTERFACE, "test/StateClass", "describe", "(Ljava/lang/Object;)V", true)));

        assertEquals(new Handle(H_INVOKEVIRTUAL, "test/StateClass", "describe", "(Ljava/lang/Object;)V", false),
                handle, "a former interface that is now a class needs a virtual handle");
    }
}
