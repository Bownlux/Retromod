/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;
import org.objectweb.asm.signature.SignatureWriter;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** Reads the old draw methods of a mod's renderers, in the jar's own classes as well as in the class being transformed. */
final class OldRenderShapes {

    static final String POSE_STACK = "com/mojang/blaze3d/vertex/PoseStack";
    static final String ENTITY = "net/minecraft/world/entity/Entity";
    static final String BLOCK_ENTITY = "net/minecraft/world/level/block/entity/BlockEntity";
    static final String EMBEDDED_BUFFERS = RenderBufferSynthetics.MBS;
    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    // A Fabric jar's own classes still name these by intermediary, since the remap has not reached them.
    private static final Map<String, String> INTERMEDIARY = Map.of(
            "net/minecraft/class_4587", POSE_STACK,
            "net/minecraft/class_4597", HostBuffers.HOST_BUFFERS,
            "net/minecraft/class_1297", ENTITY,
            "net/minecraft/class_2586", BLOCK_ENTITY,
            "net/minecraft/class_3887", "net/minecraft/client/renderer/entity/layers/RenderLayer",
            "net/minecraft/class_827", "net/minecraft/client/renderer/blockentity/BlockEntityRenderer",
            "net/minecraft/class_897", "net/minecraft/client/renderer/entity/EntityRenderer");
    // Deeper mod hierarchies than this are not followed; a class that deep keeps the old behavior.
    private static final int MAX_DEPTH = 16;

    private OldRenderShapes() {}

    /** The Mojang name of a type a jar's own class names, which may still be intermediary. */
    static String mojang(String internalName) {
        return INTERMEDIARY.getOrDefault(internalName, internalName);
    }

    /** Whether a buffer source type is the host's or Retromod's stand-in, under any jar's embedded prefix. */
    static boolean isBuffers(String internalName) {
        String name = mojang(internalName);
        return name.equals(HostBuffers.HOST_BUFFERS) || name.endsWith(EMBEDDED_BUFFERS);
    }

    /** Whether {@code desc} takes exactly {@code expected}, with every buffer source type counted as one, and returns nothing. */
    static boolean takes(String desc, Type... expected) {
        if (Type.getReturnType(desc) != Type.VOID_TYPE) return false;
        Type[] args = Type.getArgumentTypes(desc);
        if (args.length != expected.length) return false;
        for (int i = 0; i < args.length; i++) {
            if (args[i].getSort() != expected[i].getSort()) return false;
            if (args[i].getSort() != Type.OBJECT) continue;
            String name = mojang(args[i].getInternalName());
            String wanted = expected[i].getInternalName();
            boolean same = wanted.equals(HostBuffers.HOST_BUFFERS) ? isBuffers(name) : name.equals(wanted);
            if (!same) return false;
        }
        return true;
    }

    /**
     * The class signature with {@code type}'s one old type argument followed by {@code state}, as the host's two-parameter
     * renderer base declares; reflection on an old renderer's generic supertypes otherwise throws. Null stays null.
     */
    static String withStateArgument(String signature, String type, String state) {
        if (signature == null) return null;
        SignatureWriter out = new SignatureWriter();
        new SignatureReader(signature).accept(new StateArgument(out, type, state));
        return out.toString();
    }

    /** Copies a signature, adding the state argument to each one-argument use of the renderer base. */
    private static final class StateArgument extends SignatureVisitor {
        private final SignatureWriter out;
        private final String type;
        private final String state;
        // For each class type being written: whether it is the renderer base, and how many type arguments it had.
        private final ArrayDeque<int[]> open = new ArrayDeque<>();

        StateArgument(SignatureWriter out, String type, String state) {
            super(Opcodes.ASM9);
            this.out = out;
            this.type = type;
            this.state = state;
        }

        @Override public void visitFormalTypeParameter(String name) { out.visitFormalTypeParameter(name); }
        @Override public SignatureVisitor visitClassBound() { out.visitClassBound(); return this; }
        @Override public SignatureVisitor visitInterfaceBound() { out.visitInterfaceBound(); return this; }
        @Override public SignatureVisitor visitSuperclass() { out.visitSuperclass(); return this; }
        @Override public SignatureVisitor visitInterface() { out.visitInterface(); return this; }
        @Override public SignatureVisitor visitParameterType() { out.visitParameterType(); return this; }
        @Override public SignatureVisitor visitReturnType() { out.visitReturnType(); return this; }
        @Override public SignatureVisitor visitExceptionType() { out.visitExceptionType(); return this; }
        @Override public void visitBaseType(char descriptor) { out.visitBaseType(descriptor); }
        @Override public void visitTypeVariable(String name) { out.visitTypeVariable(name); }
        @Override public SignatureVisitor visitArrayType() { out.visitArrayType(); return this; }

        @Override
        public void visitClassType(String name) {
            open.push(new int[]{name.equals(type) ? 1 : 0, 0});
            out.visitClassType(name);
        }

        @Override
        public void visitInnerClassType(String name) {
            // An inner class starts its own argument list.
            if (!open.isEmpty()) open.peek()[1] = 0;
            out.visitInnerClassType(name);
        }

        @Override
        public void visitTypeArgument() {
            if (!open.isEmpty()) open.peek()[1]++;
            out.visitTypeArgument();
        }

        @Override
        public SignatureVisitor visitTypeArgument(char wildcard) {
            if (!open.isEmpty()) open.peek()[1]++;
            out.visitTypeArgument(wildcard);
            return this;
        }

        @Override
        public void visitEnd() {
            int[] current = open.isEmpty() ? new int[2] : open.pop();
            if (current[0] == 1 && current[1] == 1) {
                out.visitTypeArgument(SignatureVisitor.INSTANCEOF);
                out.visitClassType(state);
                out.visitEnd();
            }
            out.visitEnd();
        }
    }

    static boolean isMixin(ClassNode node) {
        return hasMixin(node.visibleAnnotations) || hasMixin(node.invisibleAnnotations);
    }

    private static boolean hasMixin(List<AnnotationNode> annotations) {
        return annotations != null && annotations.stream().anyMatch(a -> a.desc.equals(MIXIN));
    }

    static boolean isInstance(MethodNode method) {
        return (method.access & Opcodes.ACC_STATIC) == 0;
    }

    /** A class of the jar being transformed, without code, or null when the jar does not have it. */
    static ClassNode jarClass(String name) {
        byte[] bytes = RetromodTransformer.readCurrentJarClassForAdapter(name);
        if (bytes == null) return null;
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE);
        return node;
    }

    /** Whether one of the jar's own superclasses, starting at {@code superName}, declares a method {@code match} accepts. */
    static boolean jarAncestorDeclares(String superName, Predicate<MethodNode> match) {
        String current = superName;
        for (int depth = 0; depth < MAX_DEPTH && current != null; depth++) {
            ClassNode ancestor = jarClass(current);
            if (ancestor == null) return false;
            if (ancestor.methods.stream().anyMatch(match)) return true;
            current = ancestor.superName;
        }
        return false;
    }

    /** Whether the class, or a superclass or interface the jar has, lists {@code type} among its interfaces. */
    static boolean implementsThroughJar(ClassNode node, String type) {
        ArrayDeque<String> work = new ArrayDeque<>(node.interfaces);
        if (node.superName != null) work.add(node.superName);
        Set<String> seen = new HashSet<>();
        while (!work.isEmpty() && seen.size() < MAX_DEPTH * 4) {
            String name = work.poll();
            if (!seen.add(name)) continue;
            if (mojang(name).equals(type)) return true;
            ClassNode jar = jarClass(name);
            if (jar == null) continue;
            work.addAll(jar.interfaces);
            if (jar.superName != null) work.add(jar.superName);
        }
        return false;
    }
}
