/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** Calls on a host type take the form its current kind needs, whichever way it changed between class and interface. */
class HostKindCallFormTest {

    /** An interface through 1.21.11 and a record class from 26.1. */
    private static final String SERIALIZER = "net/minecraft/world/item/crafting/RecipeSerializer";
    /** An abstract class through 26.1 and an interface from 26.2. */
    private static final String PROCESSOR = "net/minecraft/world/level/levelgen/structure/templatesystem/StructureProcessor";
    private static final String MAP_CODEC = "()Lcom/mojang/serialization/MapCodec;";

    @TempDir
    Path dir;

    @Test
    @DisplayName("an interface call on a type the host now has as a class takes the class form, in calls and method references")
    void interfaceCallsOnANewClassTakeTheClassForm() throws Exception {
        RetromodTransformer transformer = indexedHost(Map.of(SERIALIZER, false, PROCESSOR, true));
        // Any method redirect will do: with none, method bodies are left as they are.
        transformer.registerMethodRedirect("test/kind/Old", "run", "()V", "test/kind/New", "run", "()V");
        ClassNode out = node(transformer.transformClass(callers(), "mod/Callers"));

        MethodInsnNode virtual = call(method(out, "codecOf"), SERIALIZER);
        assertEquals(INVOKEVIRTUAL, virtual.getOpcode(), "a record class has no interface methods to call");
        assertFalse(virtual.itf);
        assertFalse(call(method(out, "registerStatic"), SERIALIZER).itf, "a static call on a class names a plain method");
        Handle reference = (Handle) indy(method(out, "reference")).bsmArgs[1];
        assertEquals(H_INVOKEVIRTUAL, reference.getTag());
        assertFalse(reference.isInterface());

        MethodInsnNode stillInterface = call(method(out, "processorCodec"), PROCESSOR);
        assertEquals(INVOKEINTERFACE, stillInterface.getOpcode(), "a type that is still an interface keeps its call");
        assertTrue(stillInterface.itf);
    }

    @Test
    @DisplayName("an owner the host does not have keeps the form the mod wrote")
    void unknownOwnersStay() throws Exception {
        RetromodTransformer transformer = indexedHost(Map.of(PROCESSOR, true));
        transformer.registerMethodRedirect("test/kind/Old", "run", "()V", "test/kind/New", "run", "()V");
        MethodInsnNode call = call(method(node(transformer.transformClass(callers(), "mod/Callers")), "codecOf"), SERIALIZER);
        assertEquals(INVOKEINTERFACE, call.getOpcode());
        assertTrue(call.itf);
    }

    @Test
    @DisplayName("a super call into a base that became an interface names the interface after the rebase")
    void superCallIntoARebasedInterface() throws Exception {
        RetromodTransformer transformer = indexedHost(Map.of(PROCESSOR, true));
        transformer.registerSuperclassRebase(PROCESSOR, "java/lang/Object", PROCESSOR);
        ClassNode out = node(transformer.transformClass(processor(), "mod/RotProcessor"));
        assertEquals("java/lang/Object", out.superName);
        assertEquals(List.of(PROCESSOR), out.interfaces);
        MethodInsnNode superCall = call(method(out, "codec"), PROCESSOR);
        assertEquals(INVOKESPECIAL, superCall.getOpcode());
        assertTrue(superCall.itf, "a super call to a direct superinterface takes an interface method constant");
    }

    /** A fresh transformer indexing a host jar with each named type as an interface (true) or a class (false). */
    private RetromodTransformer indexedHost(Map<String, Boolean> kinds) throws Exception {
        Path file = Files.createTempFile(dir, "host", ".jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(file))) {
            for (Map.Entry<String, Boolean> kind : kinds.entrySet()) {
                ClassWriter cw = new ClassWriter(0);
                int access = kind.getValue() ? ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT : ACC_PUBLIC | ACC_FINAL | ACC_SUPER;
                cw.visit(V21, access, kind.getKey(), null, "java/lang/Object", null);
                cw.visitMethod(ACC_PUBLIC | (kind.getValue() ? ACC_ABSTRACT : 0), "codec", MAP_CODEC, null, null).visitEnd();
                cw.visitEnd();
                out.putNextEntry(new JarEntry(kind.getKey() + ".class"));
                out.write(cw.toByteArray());
                out.closeEntry();
            }
        }
        Constructor<RetromodTransformer> constructor = RetromodTransformer.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        RetromodTransformer transformer = constructor.newInstance();
        transformer.initFuzzyResolver(file);
        return transformer;
    }

    /** Calls written when the serializer was an interface, and one on the processor interface. */
    private static byte[] callers() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC | ACC_SUPER, "mod/Callers", null, "java/lang/Object", null);
        MethodVisitor codecOf = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "codecOf", "(L" + SERIALIZER + ";)Ljava/lang/Object;", null, null);
        codecOf.visitCode();
        codecOf.visitVarInsn(ALOAD, 0);
        codecOf.visitMethodInsn(INVOKEINTERFACE, SERIALIZER, "codec", MAP_CODEC, true);
        codecOf.visitInsn(ARETURN);
        codecOf.visitMaxs(0, 0);
        codecOf.visitEnd();
        MethodVisitor registerStatic = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "registerStatic", "()V", null, null);
        registerStatic.visitCode();
        registerStatic.visitMethodInsn(INVOKESTATIC, SERIALIZER, "bootstrap", "()V", true);
        registerStatic.visitInsn(RETURN);
        registerStatic.visitMaxs(0, 0);
        registerStatic.visitEnd();
        MethodVisitor reference = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "reference", "()Ljava/util/function/Function;", null, null);
        reference.visitCode();
        Handle metafactory = new Handle(H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                        + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                        + "Ljava/lang/invoke/CallSite;", false);
        reference.visitInvokeDynamicInsn("apply", "()Ljava/util/function/Function;", metafactory,
                Type.getType("(Ljava/lang/Object;)Ljava/lang/Object;"),
                new Handle(H_INVOKEINTERFACE, SERIALIZER, "codec", MAP_CODEC, true),
                Type.getType("(L" + SERIALIZER + ";)Lcom/mojang/serialization/MapCodec;"));
        reference.visitInsn(ARETURN);
        reference.visitMaxs(0, 0);
        reference.visitEnd();
        MethodVisitor processorCodec = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "processorCodec", "(L" + PROCESSOR + ";)Ljava/lang/Object;", null, null);
        processorCodec.visitCode();
        processorCodec.visitVarInsn(ALOAD, 0);
        processorCodec.visitMethodInsn(INVOKEINTERFACE, PROCESSOR, "codec", MAP_CODEC, true);
        processorCodec.visitInsn(ARETURN);
        processorCodec.visitMaxs(0, 0);
        processorCodec.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code class RotProcessor extends StructureProcessor} whose codec() calls the base's, as written before 26.2. */
    private static byte[] processor() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC | ACC_SUPER, "mod/RotProcessor", null, PROCESSOR, null);
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, PROCESSOR, "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor codec = cw.visitMethod(ACC_PUBLIC, "codec", MAP_CODEC, null, null);
        codec.visitCode();
        codec.visitVarInsn(ALOAD, 0);
        codec.visitMethodInsn(INVOKESPECIAL, PROCESSOR, "codec", MAP_CODEC, false);
        codec.visitInsn(ARETURN);
        codec.visitMaxs(0, 0);
        codec.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }

    private static MethodInsnNode call(MethodNode method, String owner) {
        List<MethodInsnNode> calls = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call && call.owner.equals(owner)) calls.add(call);
        }
        assertEquals(1, calls.size(), method.name + " calls " + owner + " once");
        return calls.get(0);
    }

    private static InvokeDynamicInsnNode indy(MethodNode method) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof InvokeDynamicInsnNode indy) return indy;
        }
        return fail(method.name + " has no invokedynamic");
    }
}
