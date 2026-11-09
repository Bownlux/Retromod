/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mixin;

import com.retromod.core.FuzzyMethodResolver;
import com.retromod.util.HostLibraryNames;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Finds the first class or member a mixin's own code would load that the indexed host lacks. */
final class MixinLinkCheck {

    private static final String SHADOW_DESC = "Lorg/spongepowered/asm/mixin/Shadow;";
    private static final Set<String> OBJECT_METHODS = Set.of("equals", "hashCode", "toString", "getClass",
            "notify", "notifyAll", "wait");
    // Libraries every loader puts beside Minecraft. The host index holds none of their classes.
    private static final List<String> LIBRARY_PREFIXES = List.of("java/", "javax/", "jdk/", "sun/", "com/mojang/",
            "org/spongepowered/", "com/llamalad7/", "com/google/", HostLibraryNames.SLF4J_PACKAGE + "/", "org/apache/", "it/unimi/",
            "io/netty/", "org/joml/", "org/lwjgl/", "org/objectweb/", "net/minecraftforge/", "net/neoforged/",
            "net/fabricmc/", "com/retromod/");
    private static final int MAX_JAR_CLASSES = 32;

    private final FuzzyMethodResolver host;
    private final Map<String, String> classRedirects;
    private final Function<String, byte[]> jarClasses;

    /** {@code jarClasses} reads the jar's classes before translation; a mod class it cannot supply counts as missing. */
    MixinLinkCheck(FuzzyMethodResolver host, Map<String, String> classRedirects,
            Function<String, byte[]> jarClasses) {
        this.host = host;
        this.classRedirects = classRedirects;
        this.jarClasses = jarClasses;
    }

    /** What the mixin's code, every method but its shadows and {@code skipped}, would fail to load, or null. */
    String firstMissing(ClassNode mixin, MethodNode skipped) {
        Set<String> initialized = new LinkedHashSet<>();
        for (MethodNode method : mixin.methods) {
            if (method == skipped || isShadow(method)) continue;
            for (AbstractInsnNode insn : method.instructions) {
                String missing = missingIn(insn, initialized);
                if (missing != null) return missing;
            }
        }
        // A class of the mod the code initializes loads with it, and so does every class it names.
        initialized.remove(mixin.name);
        Set<String> checked = new HashSet<>();
        for (String owner : initialized) {
            String missing = missingInModClass(owner, checked);
            if (missing != null) return missing;
        }
        return null;
    }

    /** The missing class or member one instruction uses; a class it initializes goes into {@code initialized}. */
    private String missingIn(AbstractInsnNode insn, Set<String> initialized) {
        // A call or field access loads only its owner, while a call site, a method handle and a
        // method type load every class their descriptor names.
        if (insn instanceof MethodInsnNode call) {
            if (provablyMissing(call.owner, call.name, call.desc, false)) return call.owner + "." + call.name + call.desc;
            if (call.getOpcode() == Opcodes.INVOKESTATIC) noteInitialized(call.owner, initialized);
            return null;
        }
        if (insn instanceof FieldInsnNode access) {
            if (provablyMissing(access.owner, access.name, access.desc, true)) {
                return access.owner + "." + access.name + ":" + access.desc;
            }
            int op = access.getOpcode();
            if (op == Opcodes.GETSTATIC || op == Opcodes.PUTSTATIC) noteInitialized(access.owner, initialized);
            return null;
        }
        if (insn instanceof TypeInsnNode type) {
            if (type.getOpcode() == Opcodes.NEW) noteInitialized(type.desc, initialized);
            return firstAbsentIn(Type.getObjectType(type.desc));
        }
        if (insn instanceof InvokeDynamicInsnNode indy) {
            String missing = missingInHandle(indy.bsm, initialized);
            if (missing == null) missing = firstAbsentIn(Type.getMethodType(indy.desc));
            for (int i = 0; missing == null && i < indy.bsmArgs.length; i++) {
                if (indy.bsmArgs[i] instanceof Handle handle) missing = missingInHandle(handle, initialized);
                if (indy.bsmArgs[i] instanceof Type type) missing = firstAbsentIn(type);
            }
            return missing;
        }
        if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Type type) return firstAbsentIn(type);
        if (insn instanceof MultiANewArrayInsnNode array) return firstAbsentIn(Type.getType(array.desc));
        return null;
    }

    /** A method handle: its member, and the class a static or constructor handle initializes when called. */
    private String missingInHandle(Handle handle, Set<String> initialized) {
        int tag = handle.getTag();
        boolean field = tag <= Opcodes.H_PUTSTATIC;
        if (provablyMissing(handle.getOwner(), handle.getName(), handle.getDesc(), field)) {
            return handle.getOwner() + "." + handle.getName() + handle.getDesc();
        }
        if (tag == Opcodes.H_GETSTATIC || tag == Opcodes.H_PUTSTATIC || tag == Opcodes.H_INVOKESTATIC
                || tag == Opcodes.H_NEWINVOKESPECIAL) {
            noteInitialized(handle.getOwner(), initialized);
        }
        return firstAbsentIn(field ? Type.getType(handle.getDesc()) : Type.getMethodType(handle.getDesc()));
    }

    private void noteInitialized(String owner, Set<String> initialized) {
        if (!owner.startsWith("[") && !host.hasClass(owner)) initialized.add(owner);
    }

    /** What a mod class the mixin initializes, or a supertype of it from the mod, would fail to load, or null. */
    private String missingInModClass(String owner, Set<String> checked) {
        // Only the classes they name are checked, not their members, because the jar still holds
        // them in the names of the version they were built for.
        Deque<Supertype> pending = new ArrayDeque<>(List.of(new Supertype(owner, null)));
        while (!pending.isEmpty()) {
            Supertype next = pending.pop();
            String name = next.name();
            if (!checked.add(name)) continue;
            // A host class, here or where the registered moves send it, loads if the mod names it as the kind it is.
            String hostClass = hostClassFor(name);
            if (hostClass != null) {
                String clash = kindClash(hostClass, next.asInterface());
                if (clash != null) return clash + " (named by a supertype list as " + name + ")";
                continue;
            }
            // A move to a class Retromod supplies, such as a removed class's stand-in, loads too.
            if (name.startsWith("net/minecraft/") && classRedirects.containsKey(name) && !absentHostClass(name)) continue;
            byte[] bytes = jarClasses == null ? null : jarClasses.apply(name);
            if (bytes == null) {
                // A library is there at run time. A class from another mod cannot be checked here.
                if (isLibrary(name)) continue;
                return name + ", which Retromod cannot read from this jar";
            }
            if (checked.size() > MAX_JAR_CLASSES) return name + " and more classes than Retromod checks";
            ClassReader reader;
            try {
                reader = new ClassReader(bytes);
            } catch (RuntimeException unreadable) {
                return name + ", which Retromod cannot read";
            }
            for (String named : namedClasses(reader)) {
                if (absentHostClass(named)) return named + " (named by " + name + ")";
            }
            if (reader.getSuperName() != null) pending.add(new Supertype(reader.getSuperName(), false));
            for (String itf : reader.getInterfaces()) pending.add(new Supertype(itf, true));
        }
        return null;
    }

    /** A class to check, and whether the class below lists it as an interface; null for the class the code initializes. */
    private record Supertype(String name, Boolean asInterface) {}

    /** {@code name}, or the class its registered moves send it to, when the host has that class; otherwise null. */
    private String hostClassFor(String name) {
        String current = name;
        for (int hop = 0; hop < 8 && current != null; hop++) {
            if (host.hasClass(current)) return current;
            String next = classRedirects.get(current);
            if (next == null || next.equals(current)) return null;
            current = next;
        }
        return null;
    }

    /** Why the host class cannot be a supertype listed this way, or null: an interface named as a superclass, or the reverse. */
    private String kindClash(String hostClass, Boolean asInterface) {
        int access = host.classAccess(hostClass);
        if (asInterface == null || access == -1) return null;
        boolean isInterface = (access & Opcodes.ACC_INTERFACE) != 0;
        if (asInterface && !isInterface) return hostClass + " is a class on this host, not an interface";
        if (!asInterface && isInterface) return hostClass + " is an interface on this host, not a class";
        if ((access & Opcodes.ACC_FINAL) != 0) return hostClass + " is final on this host";
        if (host.isSealed(hostClass)) return hostClass + " is sealed on this host";
        return null;
    }

    /** Every class a class file names in its constant pool, its own descriptors included. */
    private static Set<String> namedClasses(ClassReader reader) {
        Set<String> names = new LinkedHashSet<>();
        char[] buffer = new char[reader.getMaxStringLength()];
        for (int i = 1; i < reader.getItemCount(); i++) {
            int offset = reader.getItem(i);
            // The slot after a long or a double has no entry.
            if (offset == 0) continue;
            switch (reader.readByte(offset - 1)) {
                case 7 -> addTypes(names, typeOfClassEntry(reader.readUTF8(offset, buffer)));
                case 12 -> addTypes(names, typeOfDescriptor(reader.readUTF8(offset + 2, buffer)));
                case 16 -> addTypes(names, Type.getMethodType(reader.readUTF8(offset, buffer)));
                default -> { }
            }
        }
        ClassNode outline = new ClassNode();
        reader.accept(outline, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        for (FieldNode field : outline.fields) addTypes(names, Type.getType(field.desc));
        for (MethodNode method : outline.methods) addTypes(names, Type.getMethodType(method.desc));
        return names;
    }

    private static Type typeOfClassEntry(String name) {
        return name.startsWith("[") ? Type.getType(name) : Type.getObjectType(name);
    }

    private static Type typeOfDescriptor(String descriptor) {
        return descriptor.startsWith("(") ? Type.getMethodType(descriptor) : Type.getType(descriptor);
    }

    private static void addTypes(Set<String> names, Type type) {
        if (type.getSort() == Type.METHOD) {
            for (Type argument : type.getArgumentTypes()) addTypes(names, argument);
            addTypes(names, type.getReturnType());
            return;
        }
        Type element = type.getSort() == Type.ARRAY ? type.getElementType() : type;
        if (element.getSort() == Type.OBJECT) names.add(element.getInternalName());
    }

    /** The first Minecraft class a type names that the host lacks, or null. */
    private String firstAbsentIn(Type type) {
        Set<String> names = new LinkedHashSet<>();
        addTypes(names, type);
        for (String name : names) {
            if (absentHostClass(name)) return name;
        }
        return null;
    }

    /** A Minecraft class the host has neither under this name nor where the registered class moves send it. */
    private boolean absentHostClass(String name) {
        if (!name.startsWith("net/minecraft/")) return false;
        String current = name;
        for (int hop = 0; hop < 8; hop++) {
            if (host.hasClass(current)) return false;
            String next = classRedirects.get(current);
            if (next == null || next.equals(current)) break;
            current = next;
        }
        // A move to a class Retromod supplies, such as a removed class's stand-in, loads, and so
        // does a class the mod ships under Minecraft's packages.
        return current.startsWith("net/minecraft/") && (jarClasses == null || jarClasses.apply(current) == null);
    }

    /** Whether the host lacks the member, or lacks a Minecraft class that owns it. */
    private boolean provablyMissing(String owner, String name, String desc, boolean field) {
        if (owner.startsWith("[")) return false;
        if (!host.hasClass(owner)) return absentHostClass(owner);
        if (field ? host.hasField(owner, name, desc) : host.hasMethod(owner, name, desc)) return false;
        if (!field && OBJECT_METHODS.contains(name)) return false;
        // An ancestor the index cannot see may declare it.
        return "<init>".equals(name) || ancestorsKnown(owner, new HashSet<>());
    }

    /** Whether every ancestor is indexed or a JDK class, whose members the index reads from the JDK itself. */
    private boolean ancestorsKnown(String type, Set<String> seen) {
        if (!seen.add(type)) return true;
        for (String parent : host.directSupertypes(type)) {
            if (parent.equals("java/lang/Object")) continue;
            // Record, Enum and the other JDK bases are read from the running JDK, which has them.
            if (parent.startsWith("java/") && isPlatformClass(parent)) continue;
            if (!host.hasClass(parent) || !ancestorsKnown(parent, seen)) return false;
        }
        return true;
    }

    private static boolean isPlatformClass(String internalName) {
        try {
            Class.forName(internalName.replace('/', '.'), false, ClassLoader.getPlatformClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    private static boolean isLibrary(String name) {
        for (String prefix : LIBRARY_PREFIXES) {
            if (name.startsWith(prefix)) return true;
        }
        return false;
    }

    private static boolean isShadow(MethodNode method) {
        return hasAnnotation(method.visibleAnnotations) || hasAnnotation(method.invisibleAnnotations);
    }

    private static boolean hasAnnotation(List<AnnotationNode> annotations) {
        if (annotations == null) return false;
        for (AnnotationNode annotation : annotations) {
            if (SHADOW_DESC.equals(annotation.desc)) return true;
        }
        return false;
    }
}
