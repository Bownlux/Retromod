/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Lets NeoForge construct a Forge {@code @Mod} class whose constructor takes Forge 47.2's FMLJavaModLoadingContext. */
public final class NeoForgeModConstructorArgs {

    /** Turns the pass on; ForgeEventApiShim enables it wherever a Forge mod moves to NeoForge. */
    public static final String FLAG = "neoforge-mod-constructor-args";

    static final String CONTEXT = ForgeNeoForgeSynthetics.FMLJMLC;
    static final String MOD_CONTAINER = "net/neoforged/fml/ModContainer";
    static final String FML_MOD_CONTAINER = "net/neoforged/fml/javafmlmod/FMLModContainer";
    private static final List<String> MOD_ANNOTATIONS = List.of(
            "Lnet/neoforged/fml/common/Mod;", "Lnet/minecraftforge/fml/common/Mod;");

    private NeoForgeModConstructorArgs() {}

    /** The class with each {@code @Mod} constructor taking a container where it took the context, or the same bytes. */
    public static byte[] apply(byte[] classBytes) {
        if (classBytes == null || !mentionsContext(classBytes)) return classBytes;
        ClassNode node = new ClassNode();
        // Expanded frames list every local, so none inherits the parameter type this pass changes.
        new ClassReader(classBytes).accept(node, ClassReader.EXPAND_FRAMES);
        if (!isModClass(node)) return classBytes;
        boolean changed = false;
        for (MethodNode method : node.methods) {
            if (method.name.equals("<init>") && (method.access & Opcodes.ACC_PUBLIC) != 0) {
                changed |= rewrite(method);
            }
        }
        if (!changed) return classBytes;
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    /** A cheap text check, so a class that never names the context is not parsed. */
    private static boolean mentionsContext(byte[] classBytes) {
        String text = new String(classBytes, StandardCharsets.ISO_8859_1);
        return text.contains(CONTEXT) && text.contains("fml/common/Mod;");
    }

    private static boolean isModClass(ClassNode node) {
        if (node.visibleAnnotations == null) return false;
        for (AnnotationNode annotation : node.visibleAnnotations) {
            if (MOD_ANNOTATIONS.contains(annotation.desc)) return true;
        }
        return false;
    }

    /** Swaps each context parameter for a container FML accepts and rebuilds the context from it first thing. */
    private static boolean rewrite(MethodNode constructor) {
        Type[] params = Type.getArgumentTypes(constructor.desc);
        List<Integer> contexts = new ArrayList<>();
        List<String> taken = new ArrayList<>();
        for (int i = 0; i < params.length; i++) {
            if (params[i].getSort() != Type.OBJECT) continue;
            if (params[i].getInternalName().equals(CONTEXT)) contexts.add(i);
            else taken.add(params[i].getInternalName());
        }
        if (contexts.isEmpty()) return false;
        // FML hands each accepted type at most once, so a second context takes the other container type.
        List<String> free = new ArrayList<>(List.of(MOD_CONTAINER, FML_MOD_CONTAINER));
        free.removeAll(taken);
        if (free.size() < contexts.size()) return false;

        InsnList head = new InsnList();
        int slot = 1;
        for (int i = 0; i < params.length; i++) {
            if (contexts.contains(i)) {
                String container = free.remove(0);
                params[i] = Type.getObjectType(container);
                head.add(new VarInsnNode(Opcodes.ALOAD, slot));
                head.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CONTEXT, ForgeNeoForgeSynthetics.CONTEXT_OF,
                        ForgeNeoForgeSynthetics.CONTEXT_OF_DESC, false));
                head.add(new VarInsnNode(Opcodes.ASTORE, slot));
            }
            slot += params[i].getSize();
        }
        // The slot keeps the context type the body was compiled for once these run, before the super call.
        constructor.instructions.insert(head);
        constructor.desc = Type.getMethodDescriptor(Type.VOID_TYPE, params);
        // FML reads only the erased types, so a generic signature naming the old context is dropped.
        if (constructor.signature != null && constructor.signature.contains(CONTEXT)) constructor.signature = null;
        return true;
    }
}
