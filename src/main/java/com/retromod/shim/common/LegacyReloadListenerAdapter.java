/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.shim.common.LegacyReloadListenerBridge.Names;
import com.retromod.shim.common.LegacyReloadListenerBridge.Shape;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Gives a mod's old-shape reload listener the host's reload method, and moves old reload calls onto the host's. */
public final class LegacyReloadListenerAdapter implements Opcodes {

    private static final List<Names> NAMESPACES = List.of(Names.MOJANG, Names.INTERMEDIARY);
    private static final List<byte[]> BARRIER_NAMES = List.of(
            Names.MOJANG.barrier().getBytes(StandardCharsets.UTF_8),
            Names.INTERMEDIARY.barrier().getBytes(StandardCharsets.UTF_8));
    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final int MAX_HIERARCHY = 64;

    private LegacyReloadListenerAdapter() {}

    /** An old reload descriptor in one namespace. */
    private record OldShape(Names names, Shape shape) {}

    /** Adapts one class for a host whose reload has the shape {@code host}. */
    public static byte[] apply(byte[] classBytes, Shape host) {
        // Every old reload names the barrier type, so other classes are left unparsed.
        if (BARRIER_NAMES.stream().noneMatch(name -> contains(classBytes, name))) return classBytes;
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        // Calls first, so the method added below keeps its call to the class's own old reload.
        boolean changed = adaptCalls(node, host);
        changed |= addHostMethod(node, host);
        if (!changed) return classBytes;
        // Existing methods gain no branch and their new locals never reach a frame, and the added method
        // carries its own frame, so the class's frames stay valid.
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    /** Adds the host's reload to a class or interface that declares an old one and not the host's. */
    private static boolean addHostMethod(ClassNode node, Shape host) {
        // A mixin's methods merge into a Minecraft class, which has the host method already.
        if (isMixin(node)) return false;
        for (Names names : NAMESPACES) {
            Shape old = declaredOldShape(node, names, host);
            if (old == null) continue;
            if (declares(node, names.reload(), names.desc(host))) return false;
            // 26.x made the model and shader managers' reload final. Overriding a final method stops the
            // class from loading at all, so a subclass of one keeps only its old method.
            if (superclassFinal(node, names.reload(), names.desc(host))) return false;
            boolean isInterface = (node.access & ACC_INTERFACE) != 0;
            // An interface takes it as a default method, which needs a Java 8 class file.
            if (isInterface && (node.version & 0xFFFF) < V1_8) return false;
            LegacyReloadListenerBridge.emitHostMethod(node, names, old, host, node.name, isInterface);
            return true;
        }
        return false;
    }

    /** The newest old shape the class declares as an instance method, or null. */
    private static Shape declaredOldShape(ClassNode node, Names names, Shape host) {
        for (int ordinal = host.ordinal() - 1; ordinal >= 0; ordinal--) {
            Shape shape = Shape.values()[ordinal];
            for (MethodNode method : node.methods) {
                if ((method.access & ACC_STATIC) == 0 && method.name.equals(names.reload())
                        && method.desc.equals(names.desc(shape))) {
                    return shape;
                }
            }
        }
        return null;
    }

    /** Rewrites calls to an old reload that the receiver's type no longer has. */
    private static boolean adaptCalls(ClassNode node, Shape host) {
        boolean mixin = isMixin(node);
        boolean changed = false;
        for (MethodNode method : node.methods) {
            if (method.instructions == null || method.instructions.size() == 0) continue;
            int spill = method.maxLocals;
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (!(insn instanceof MethodInsnNode call)) continue;
                OldShape old = oldShape(call, host);
                // A class of this jar on the way still declares the old method, and that is what runs.
                if (old == null || call.getOpcode() == INVOKESTATIC || jarDeclaresOld(node, call.owner)) continue;
                if (call.getOpcode() == INVOKESPECIAL) {
                    // A mixin's super call names the target's superclass, which this pass cannot see.
                    if (mixin) continue;
                    convertInPlace(method.instructions, call, old, host, spill);
                } else if (host == Shape.STATE) {
                    // A listener wrapping another hands it a state of its own through the helper.
                    call.setOpcode(INVOKESTATIC);
                    call.owner = LegacyReloadListenerBridge.HELPER;
                    call.desc = LegacyReloadListenerBridge.oldCallDesc(old.names(), old.shape());
                    call.itf = false;
                } else {
                    convertInPlace(method.instructions, call, old, host, spill);
                }
                method.maxLocals = Math.max(method.maxLocals, spill + 6);
                changed = true;
            }
        }
        return changed;
    }

    /** The old shape a call names, when the host's reload has another one. */
    private static OldShape oldShape(MethodInsnNode call, Shape host) {
        for (Names names : NAMESPACES) {
            if (!call.name.equals(names.reload())) continue;
            for (Shape shape : Shape.values()) {
                if (shape.ordinal() < host.ordinal() && call.desc.equals(names.desc(shape))) {
                    return new OldShape(names, shape);
                }
            }
        }
        return null;
    }

    /**
     * Turns the old arguments on the stack into the host's and calls the host method on the same
     * owner, so a super call stays a super call. They wait in locals above the method's own.
     */
    private static void convertInPlace(InsnList code, MethodInsnNode call, OldShape old, Shape host, int spill) {
        Names names = old.names();
        int count = old.shape() == Shape.PROFILED ? 6 : 4;
        InsnList moved = new InsnList();
        for (int i = count - 1; i >= 0; i--) moved.add(new VarInsnNode(ASTORE, spill + i));
        int barrier = spill;
        int resources = spill + 1;
        int background = spill + count - 2;
        int game = spill + count - 1;
        if (host == Shape.STATE) {
            // The host reload's state while one is active, else a new state over the resources.
            moved.add(new VarInsnNode(ALOAD, resources));
            moved.add(new MethodInsnNode(INVOKESTATIC, LegacyReloadListenerBridge.HELPER, "state",
                    "(L" + names.resources() + ";)L" + names.state() + ";", false));
            moved.add(new VarInsnNode(ALOAD, background));
            moved.add(new VarInsnNode(ALOAD, barrier));
        } else {
            moved.add(new VarInsnNode(ALOAD, barrier));
            moved.add(new VarInsnNode(ALOAD, resources));
            moved.add(new VarInsnNode(ALOAD, background));
        }
        moved.add(new VarInsnNode(ALOAD, game));
        code.insertBefore(call, moved);
        call.desc = names.desc(host);
    }

    /** Whether {@code owner}, or a type above it in this jar, declares an old reload itself. */
    private static boolean jarDeclaresOld(ClassNode self, String owner) {
        Set<String> seen = new HashSet<>();
        Deque<String> pending = new ArrayDeque<>();
        pending.add(owner);
        while (!pending.isEmpty() && seen.size() < MAX_HIERARCHY) {
            String name = pending.poll();
            if (!seen.add(name)) continue;
            ClassNode type = name.equals(self.name) ? self : readJarClass(name);
            // A Minecraft or loader type, or one from another jar, is answered by the host.
            if (type == null) continue;
            if (declaresAnyOld(type)) return true;
            if (type.superName != null) pending.add(type.superName);
            if (type.interfaces != null) pending.addAll(type.interfaces);
        }
        return false;
    }

    /** Whether a superclass, from this jar or a readable host, declares {@code name + desc} final. */
    private static boolean superclassFinal(ClassNode node, String name, String desc) {
        String current = node.superName;
        for (int depth = 0; current != null && depth < MAX_HIERARCHY; depth++) {
            ClassNode type = readJarClass(current);
            if (type == null) type = ClassResourceInspector.read(current);
            if (type == null) return false;
            for (MethodNode method : type.methods) {
                if (method.name.equals(name) && method.desc.equals(desc)) return (method.access & ACC_FINAL) != 0;
            }
            current = type.superName;
        }
        return false;
    }

    /** Whether a type declares an instance method with an old reload descriptor, by any name. */
    private static boolean declaresAnyOld(ClassNode type) {
        // The jar's own bytes are read before the remap, so the name may still be SRG or intermediary.
        for (MethodNode method : type.methods) {
            if ((method.access & ACC_STATIC) != 0) continue;
            for (Names names : NAMESPACES) {
                if (method.desc.equals(names.desc(Shape.PROFILED)) || method.desc.equals(names.desc(Shape.BARRIER))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static ClassNode readJarClass(String name) {
        byte[] bytes = RetromodTransformer.readCurrentJarClassForAdapter(name);
        if (bytes == null) return null;
        try {
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE);
            return node;
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    private static boolean declares(ClassNode node, String name, String desc) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return true;
        }
        return false;
    }

    private static boolean isMixin(ClassNode node) {
        return hasAnnotation(node.invisibleAnnotations) || hasAnnotation(node.visibleAnnotations);
    }

    private static boolean hasAnnotation(List<AnnotationNode> annotations) {
        if (annotations == null) return false;
        for (AnnotationNode annotation : annotations) {
            if (MIXIN.equals(annotation.desc)) return true;
        }
        return false;
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }
}
