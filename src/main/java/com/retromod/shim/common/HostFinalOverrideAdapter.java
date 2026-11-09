/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.SyntheticEmbedder;
import com.retromod.util.RetromodLog;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Renames an old mod method that overrides a method the host made final, so its class still loads. */
public final class HostFinalOverrideAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger("retromod-final-overrides");

    static final String PREFIX = "retromod$final$";
    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final int MAX_DEPTH = 64;
    private static final int METHODREF = 10;

    /** A class as the pass names it: superclass, method access by name and descriptor, host-owned, and renamed by this pass. */
    public record Shape(String superName, Map<String, Integer> methods, boolean host, boolean renamable) {}

    /** Reads the shape of a class by internal name, or null when nothing can read it. */
    @FunctionalInterface
    public interface Shapes {
        Shape shape(String internalName);
    }

    private HostFinalOverrideAdapter() {}

    /** Renames each override of a final host method and moves the class's own calls to it; the same array when nothing changes. */
    public static byte[] apply(byte[] classBytes, Shapes shapes) {
        return apply(classBytes, shapes, Set.of());
    }

    /** The same, leaving the finals in {@code opened} alone (see {@link OpenedFinalMethods}); a null set changes nothing. */
    public static byte[] apply(byte[] classBytes, Shapes shapes, Set<String> opened) {
        if (classBytes == null || shapes == null || opened == null) return classBytes;
        try {
            ClassReader reader = new ClassReader(classBytes);
            if (outOfScope(reader.getClassName())) return classBytes;
            // Shapes are read once per class: the pass asks for the same superclass many times. A final
            // the jar's own access file opens is legal to override once the host applies it.
            Map<String, Optional<Shape>> cache = new HashMap<>();
            Shapes cached = name -> cache.computeIfAbsent(name,
                    key -> Optional.ofNullable(asOpened(shapes.shape(key), key, opened))).orElse(null);

            // The header and the constant pool decide whether anything changes, so most classes are never expanded.
            Header header = Header.read(reader);
            Set<String> renames = renamedDeclarations(header, cached);
            Shape self = header.shapeAfter(renames);
            Shapes withSelf = name -> name.equals(header.name) ? self : cached.shape(name);
            boolean callsMove = header.methodRefs.stream()
                    .anyMatch(ref -> renamedThrough(ref[0], ref[1], ref[2], withSelf));
            if (renames.isEmpty() && !callsMove) return classBytes;

            ClassNode node = new ClassNode();
            reader.accept(node, 0);
            for (MethodNode method : node.methods) {
                if (!renames.contains(method.name + method.desc)) continue;
                LOGGER.debug(RetromodLog.DETAIL, "{}.{}{} overrides a method the host made final, so it becomes {}",
                        node.name, method.name, method.desc, PREFIX + method.name);
                method.name = PREFIX + method.name;
            }
            int retargeted = retargetCalls(node, withSelf);
            // Only names change, so the frames the class already has stay valid.
            ClassWriter writer = new ClassWriter(0);
            node.accept(writer);
            LOGGER.debug(RetromodLog.DETAIL, "Renamed {} override(s) of final host methods in {} and moved {} call(s) to them",
                    renames.size(), node.name, retargeted);
            return writer.toByteArray();
        } catch (RuntimeException unreadable) {
            LOGGER.debug(RetromodLog.DETAIL, "Left a class unchanged that the final-override pass could not read: {}",
                    unreadable.toString());
            return classBytes;
        }
    }

    /** A mixin is merged into its target, and an interface cannot override a class method, so neither is renamed. */
    private static Set<String> renamedDeclarations(Header header, Shapes shapes) {
        Set<String> renames = new HashSet<>();
        if (!header.renamable) return renames;
        header.methods.forEach((key, access) -> {
            int paren = key.indexOf('(');
            if (!overridable(access) || key.startsWith("<")) return;
            String name = key.substring(0, paren);
            if (finalHostOwner(header.superName, name, key.substring(paren), header.name, shapes) != null) {
                renames.add(key);
            }
        });
        return renames;
    }

    /** Moves calls and method handles made through a mod's own type to the renamed method, so the mod still runs its own code there. */
    private static int retargetCalls(ClassNode node, Shapes shapes) {
        int retargeted = 0;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                // A call or handle through a host type keeps reaching the host's final method.
                if (insn instanceof MethodInsnNode call) {
                    if (call.name.startsWith("<") || call.owner.startsWith("[")
                            || (call.getOpcode() != Opcodes.INVOKEVIRTUAL && call.getOpcode() != Opcodes.INVOKESPECIAL)) {
                        continue;
                    }
                    if (renamedThrough(call.owner, call.name, call.desc, shapes)) {
                        call.name = PREFIX + call.name;
                        retargeted++;
                    }
                } else if (insn instanceof InvokeDynamicInsnNode indy) {
                    // A method reference such as this::isAlliedTo is a handle among the bootstrap arguments.
                    for (int i = 0; i < indy.bsmArgs.length; i++) {
                        Handle moved = retargeted(indy.bsmArgs[i], shapes);
                        if (moved != null) {
                            indy.bsmArgs[i] = moved;
                            retargeted++;
                        }
                    }
                } else if (insn instanceof LdcInsnNode ldc) {
                    Handle moved = retargeted(ldc.cst, shapes);
                    if (moved != null) {
                        ldc.cst = moved;
                        retargeted++;
                    }
                }
            }
        }
        return retargeted;
    }

    /** The handle pointed at the renamed method, or null when {@code constant} is not a handle this pass moves. */
    private static Handle retargeted(Object constant, Shapes shapes) {
        if (!(constant instanceof Handle handle)) return null;
        int tag = handle.getTag();
        if ((tag != Opcodes.H_INVOKEVIRTUAL && tag != Opcodes.H_INVOKESPECIAL) || handle.getName().startsWith("<")
                || !renamedThrough(handle.getOwner(), handle.getName(), handle.getDesc(), shapes)) {
            return null;
        }
        return new Handle(tag, handle.getOwner(), PREFIX + handle.getName(), handle.getDesc(), handle.isInterface());
    }

    /** Whether the method a call names on {@code owner} is a mod override this pass renames, or renamed already. */
    private static boolean renamedThrough(String owner, String name, String desc, Shapes shapes) {
        String current = owner;
        for (int depth = 0; current != null && depth < MAX_DEPTH; depth++) {
            Shape shape = shapes.shape(current);
            if (shape == null || shape.host()) return false;
            // Only a class this pass renames ends up declaring the new name; a mixin or a class in a
            // Minecraft package keeps its own.
            if (declares(shape, PREFIX + name, desc)) return shape.renamable();
            Integer access = shape.methods().get(name + desc);
            if (access != null && overridable(access)) {
                // An earlier adapter moved these onto a hook; calls to them stay on the host's method.
                if (!shape.renamable() || HANDLED_EARLIER.contains(name + desc)) return false;
                return finalHostOwner(shape.superName(), name, desc, current, shapes) != null;
            }
            current = shape.superName();
        }
        return false;
    }

    /** The host class whose final {@code name + desc} a method declared in {@code declarer} would override, or null. */
    static String finalHostOwner(String superName, String name, String desc, String declarer, Shapes shapes) {
        String current = superName;
        for (int depth = 0; current != null && depth < MAX_DEPTH; depth++) {
            if (current.startsWith("java/")) return null;
            Shape shape = shapes.shape(current);
            if (shape == null) return null;
            Integer access = shape.methods().get(name + desc);
            // Mod classes on the way are passed over, since their copies are renamed too. A host method
            // counts as the JVM counts it: neither static nor private, and reachable from the declarer.
            if (shape.host() && access != null && overridable(access)
                    && ((access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) != 0
                        || packageOf(current).equals(packageOf(declarer)))) {
                return (access & Opcodes.ACC_FINAL) != 0 ? current : null;
            }
            current = shape.superName();
        }
        return null;
    }

    /** A host class as the jar's own access file leaves it: the methods it opens are no longer final. */
    private static Shape asOpened(Shape shape, String owner, Set<String> opened) {
        if (shape == null || !shape.host() || opened.isEmpty()) return shape;
        Map<String, Integer> methods = new HashMap<>(shape.methods());
        methods.replaceAll((key, access) -> {
            int paren = key.indexOf('(');
            boolean open = (access & Opcodes.ACC_FINAL) != 0 && paren > 0
                    && OpenedFinalMethods.opens(opened, owner, key.substring(0, paren), key.substring(paren));
            return open ? access & ~Opcodes.ACC_FINAL : access;
        });
        return new Shape(shape.superName(), methods, true, false);
    }

    /** The shape of a parsed class, for a {@link Shapes} built from class bytes. */
    public static Shape shapeOf(ClassNode node, boolean host) {
        Map<String, Integer> methods = new HashMap<>();
        for (MethodNode method : node.methods) methods.put(method.name + method.desc, method.access);
        boolean renamable = !host && (node.access & Opcodes.ACC_INTERFACE) == 0 && !isMixin(node) && !outOfScope(node.name);
        return new Shape(node.superName, methods, host, renamable);
    }

    /** A class's name, superclass, methods and Methodref constants, read without expanding its code. */
    private static final class Header {
        String name;
        String superName;
        boolean renamable;
        final Map<String, Integer> methods = new HashMap<>();
        final List<String[]> methodRefs = new ArrayList<>();

        static Header read(ClassReader reader) {
            Header header = new Header();
            boolean[] mixin = {false};
            int[] access = {0};
            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public void visit(int version, int classAccess, String name, String signature, String superName,
                        String[] interfaces) {
                    header.name = name;
                    header.superName = superName;
                    access[0] = classAccess;
                }

                @Override
                public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                    if (MIXIN.equals(descriptor)) mixin[0] = true;
                    return null;
                }

                @Override
                public MethodVisitor visitMethod(int methodAccess, String name, String descriptor, String signature,
                        String[] exceptions) {
                    header.methods.put(name + descriptor, methodAccess);
                    return null;
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            header.renamable = (access[0] & Opcodes.ACC_INTERFACE) == 0 && !mixin[0];
            // Every call and method handle the code makes names a Methodref constant.
            char[] buffer = new char[reader.getMaxStringLength()];
            for (int i = 1; i < reader.getItemCount(); i++) {
                int offset = reader.getItem(i);
                if (offset == 0 || reader.readByte(offset - 1) != METHODREF) continue;
                int nameAndType = reader.getItem(reader.readUnsignedShort(offset + 2));
                header.methodRefs.add(new String[]{reader.readClass(offset, buffer),
                        reader.readUTF8(nameAndType, buffer), reader.readUTF8(nameAndType + 2, buffer)});
            }
            return header;
        }

        /** The class as the pass leaves it, with {@code renames} under their new names. */
        Shape shapeAfter(Set<String> renames) {
            Map<String, Integer> renamed = new HashMap<>();
            methods.forEach((key, access) -> renamed.put(renames.contains(key) ? PREFIX + key : key, access));
            return new Shape(superName, renamed, false, renamable);
        }
    }

    private static boolean declares(Shape shape, String name, String desc) {
        Integer access = shape.methods().get(name + desc);
        return access != null && overridable(access);
    }

    private static boolean overridable(int access) {
        return (access & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) == 0;
    }

    /** Minecraft's classes and Retromod's own; a mod may live under com/retromod too, as the test mods do. */
    private static boolean outOfScope(String internalName) {
        return internalName.startsWith("net/minecraft/") || SyntheticEmbedder.isRetromodClass(internalName);
    }

    private static boolean isMixin(ClassNode node) {
        for (List<AnnotationNode> annotations : List.of(
                Optional.ofNullable(node.invisibleAnnotations).orElse(List.of()),
                Optional.ofNullable(node.visibleAnnotations).orElse(List.of()))) {
            for (AnnotationNode annotation : annotations) {
                if (MIXIN.equals(annotation.desc)) return true;
            }
        }
        return false;
    }

    private static String packageOf(String internalName) {
        int slash = internalName.lastIndexOf('/');
        return slash < 0 ? "" : internalName.substring(0, slash);
    }

    /** Final methods an earlier adapter moves onto the host's hook, by name and descriptor, in Mojang and intermediary names. */
    private static final Set<String> HANDLED_EARLIER = handledEarlier();

    private static Set<String> handledEarlier() {
        Set<String> handled = new HashSet<>();
        for (FinalOverrideHookAdapter.FinalHook hook : FinalOverrideHookAdapter.HOOKS) {
            handled.add(hook.name() + hook.descriptor());
        }
        for (FinalEyeHeightAdapter.Names names : FinalEyeHeightAdapter.NAMES) {
            handled.add(names.eyeHeight() + names.eyeDesc());
        }
        handled.add(LegacyBreathingOverrideAdapter.NAME + "()Z");
        handled.add(LegacyPositionRiderAdapter.NAME + LegacyPositionRiderAdapter.ONE_ARG);
        return Set.copyOf(handled);
    }
}
