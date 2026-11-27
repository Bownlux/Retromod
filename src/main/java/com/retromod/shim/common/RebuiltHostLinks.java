/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/** Cuts rebuilt classes down to what links on the host, so an embedded copy names only classes and members the host has. */
public final class RebuiltHostLinks {

    /** Packages whose classes change between Minecraft versions; other libraries are taken as present. */
    static final List<String> HOST_PACKAGES = List.of("net/minecraft/", "com/mojang/blaze3d/", "com/mojang/math/",
            "net/neoforged/", "net/minecraftforge/");

    /** One class a rebuilt class names, or one of its members when {@code name} is set, with the call's interface and static flags. */
    record Ref(String owner, String name, String desc, boolean field, boolean itf, boolean isStatic) {
        static Ref type(String owner) {
            return new Ref(owner, null, null, false, false, false);
        }
    }

    /** Rebuilt classes without the methods that name what the host lacks; a class that cannot link at all is left out. */
    public static final class Pruned {
        private final Map<String, ClassNode> live;
        private final Map<String, byte[]> bytes;

        private Pruned(Map<String, ClassNode> live, Map<String, byte[]> bytes) {
            this.live = live;
            this.bytes = bytes;
        }

        /** Whether the class links here and keeps the method. */
        public boolean has(String owner, String name, String desc) {
            ClassNode node = live.get(owner);
            return node != null && node.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(desc));
        }

        public boolean hasClass(String owner) {
            return live.containsKey(owner);
        }

        /** Registers every class that links, cut down, under its own name. */
        public void register(RetromodTransformer t) {
            for (Map.Entry<String, byte[]> entry : bytes.entrySet()) {
                if (!t.getSyntheticClasses().containsKey(entry.getKey())) t.registerSyntheticClass(entry.getKey(), entry.getValue());
            }
        }
    }

    private RebuiltHostLinks() {}

    /** Whether every class in the rebuilt tree under {@code root} links on the host whole. */
    public static boolean linksOn(String root, Function<String, ClassNode> host) {
        Set<String> tree = RebuiltApiClasses.tree(root);
        Map<String, ClassNode> nodes = read(List.of(root), null);
        if (nodes.size() != tree.size()) return false;
        Function<String, ClassNode> cached = cache(host);
        for (ClassNode node : nodes.values()) {
            if (!classLinks(node, cached)) return false;
            for (MethodNode method : node.methods) {
                for (Ref ref : refs(method)) {
                    if (isHost(ref.owner()) && !resolves(ref, cached)) return false;
                }
            }
        }
        return true;
    }

    /** The rebuilt trees under {@code roots}, each method that names what the host lacks removed with every method that calls it. */
    public static Pruned prune(List<String> roots, Function<String, ClassNode> host) {
        return prune(roots, host, null);
    }

    /** The same, with each class first renamed by {@code names}, such as into the id class a host before 1.21.11 has. */
    public static Pruned prune(List<String> roots, Function<String, ClassNode> host, Remapper names) {
        Map<String, ClassNode> nodes = read(roots, names);
        Function<String, ClassNode> cached = cache(host);
        Set<String> deadClasses = new HashSet<>();
        Map<String, Set<String>> deadMethods = new HashMap<>();
        // First what the host itself lacks.
        for (ClassNode node : nodes.values()) {
            if (!classLinks(node, cached)) deadClasses.add(node.name);
            Set<String> dead = deadMethods.computeIfAbsent(node.name, n -> new HashSet<>());
            for (MethodNode method : node.methods) {
                for (Ref ref : refs(method)) {
                    if (isHost(ref.owner()) && !resolves(ref, cached)) dead.add(method.name + method.desc);
                }
            }
        }
        // Then what leans on a removed rebuilt class or method, until nothing more goes.
        boolean changed = true;
        while (changed) {
            changed = false;
            for (ClassNode node : nodes.values()) {
                if (deadClasses.contains(node.name)) continue;
                Set<String> dead = deadMethods.get(node.name);
                // A class whose static setup or rebuilt supertype went cannot be loaded at all.
                if (dead.contains("<clinit>()V") || removedSupertype(node, nodes, deadClasses)) {
                    deadClasses.add(node.name);
                    changed = true;
                    continue;
                }
                for (MethodNode method : node.methods) {
                    if (dead.contains(method.name + method.desc)) continue;
                    for (Ref ref : refs(method)) {
                        if (!isRebuilt(ref.owner()) || !leansOnRemoved(ref, nodes, deadClasses, deadMethods)) continue;
                        dead.add(method.name + method.desc);
                        changed = true;
                        break;
                    }
                }
            }
        }
        Map<String, ClassNode> live = new LinkedHashMap<>();
        Map<String, byte[]> bytes = new LinkedHashMap<>();
        for (ClassNode node : nodes.values()) {
            if (deadClasses.contains(node.name)) continue;
            Set<String> dead = deadMethods.get(node.name);
            live.put(node.name, node);
            // A class that keeps every method and its names ships as Retromod's own bytes, as another bridge registers it.
            if (!node.methods.removeIf(m -> dead.contains(m.name + m.desc)) && names == null) {
                bytes.put(node.name, RebuiltApiClasses.read(node.name));
                continue;
            }
            // Only whole methods go and names change, so each kept method keeps its own stack map frames.
            ClassWriter out = new ClassWriter(0);
            node.accept(out);
            bytes.put(node.name, out.toByteArray());
        }
        return new Pruned(live, bytes);
    }

    private static boolean removedSupertype(ClassNode node, Map<String, ClassNode> nodes, Set<String> deadClasses) {
        List<String> supertypes = new java.util.ArrayList<>(node.interfaces);
        if (node.superName != null) supertypes.add(node.superName);
        for (String type : supertypes) {
            if (isRebuilt(type) && (!nodes.containsKey(type) || deadClasses.contains(type))) return true;
        }
        return false;
    }

    private static boolean leansOnRemoved(Ref ref, Map<String, ClassNode> nodes, Set<String> deadClasses,
            Map<String, Set<String>> deadMethods) {
        if (!nodes.containsKey(ref.owner()) || deadClasses.contains(ref.owner())) return true;
        if (ref.name() == null || ref.field()) return false;
        return deadMethods.get(ref.owner()).contains(ref.name() + ref.desc());
    }

    private static Map<String, ClassNode> read(List<String> roots, Remapper names) {
        Map<String, ClassNode> nodes = new LinkedHashMap<>();
        for (String root : roots) {
            for (String name : RebuiltApiClasses.tree(root)) {
                if (nodes.containsKey(name)) continue;
                byte[] bytes = RebuiltApiClasses.read(name);
                if (bytes == null) continue;
                ClassNode node = new ClassNode();
                new ClassReader(bytes).accept(names == null ? node : new ClassRemapper(node, names), 0);
                nodes.put(name, node);
            }
        }
        return nodes;
    }

    private static Function<String, ClassNode> cache(Function<String, ClassNode> host) {
        Map<String, Optional<ClassNode>> read = new HashMap<>();
        return name -> read.computeIfAbsent(name, n -> Optional.ofNullable(readClass(host, n))).orElse(null);
    }

    /** The host's class, or for a JDK class the running JDK's, which the game shares. */
    private static ClassNode readClass(Function<String, ClassNode> host, String name) {
        ClassNode node = host.apply(name);
        if (node != null || !name.startsWith("java/")) return node;
        try (java.io.InputStream in = Object.class.getModule().getResourceAsStream(name + ".class")) {
            if (in == null) return null;
            ClassNode jdk = new ClassNode();
            new ClassReader(in.readAllBytes()).accept(jdk, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return jdk;
        } catch (java.io.IOException unreadable) {
            return null;
        }
    }

    /** Whether the class's supertypes and field types are on the host. */
    private static boolean classLinks(ClassNode node, Function<String, ClassNode> host) {
        Set<Ref> refs = new LinkedHashSet<>();
        if (node.superName != null) addType(refs, node.superName);
        for (String type : node.interfaces) addType(refs, type);
        for (FieldNode field : node.fields) addDescriptor(refs, field.desc);
        for (Ref ref : refs) {
            if (isHost(ref.owner()) && !resolves(ref, host)) return false;
        }
        return true;
    }

    /** Every class and member a method names through its descriptor and its code, Retromod's own included. */
    static Set<Ref> refs(MethodNode method) {
        Set<Ref> refs = new LinkedHashSet<>();
        addDescriptor(refs, method.desc);
        for (TryCatchBlockNode block : method.tryCatchBlocks) {
            if (block.type != null) addType(refs, block.type);
        }
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call) {
                addMember(refs, call.owner, call.name, call.desc, false, call.itf, call.getOpcode() == Opcodes.INVOKESTATIC);
            } else if (insn instanceof FieldInsnNode field) {
                boolean isStatic = field.getOpcode() == Opcodes.GETSTATIC || field.getOpcode() == Opcodes.PUTSTATIC;
                addMember(refs, field.owner, field.name, field.desc, true, false, isStatic);
            } else if (insn instanceof TypeInsnNode type) {
                addType(refs, type.desc);
            } else if (insn instanceof MultiANewArrayInsnNode array) {
                addDescriptor(refs, array.desc);
            } else if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Type type && type.getSort() == Type.OBJECT) {
                addType(refs, type.getInternalName());
            } else if (insn instanceof InvokeDynamicInsnNode indy) {
                addDescriptor(refs, indy.desc);
                for (Object arg : indy.bsmArgs) {
                    // A lambda body or method reference is a member like any call.
                    if (arg instanceof Handle handle) {
                        int tag = handle.getTag();
                        addMember(refs, handle.getOwner(), handle.getName(), handle.getDesc(), tag <= Opcodes.H_PUTSTATIC,
                                handle.isInterface(), tag == Opcodes.H_GETSTATIC || tag == Opcodes.H_PUTSTATIC || tag == Opcodes.H_INVOKESTATIC);
                    } else if (arg instanceof Type type && type.getSort() == Type.METHOD) {
                        addDescriptor(refs, type.getDescriptor());
                    }
                }
            }
        }
        return refs;
    }

    /** Whether the host has the class, and a member the helper may call in it or a class or interface it extends. */
    static boolean resolves(Ref ref, Function<String, ClassNode> host) {
        ClassNode owner = host.apply(ref.owner());
        if (owner == null) return false;
        if (ref.name() == null) return true;
        // A method call's interface flag must match what the owner is, or the JVM rejects the call.
        if (!ref.field() && ((owner.access & Opcodes.ACC_INTERFACE) != 0) != ref.itf()) return false;
        // Constructors are not inherited.
        if (ref.name().equals("<init>")) return declares(owner, ref);
        ArrayDeque<String> todo = new ArrayDeque<>(List.of(ref.owner()));
        Set<String> seen = new HashSet<>();
        while (!todo.isEmpty()) {
            String name = todo.poll();
            if (!seen.add(name)) continue;
            // A class that cannot be read proves nothing, so the walk goes on and the member counts as missing if no other declares it.
            ClassNode node = host.apply(name);
            if (node == null) continue;
            if (declares(node, ref)) return true;
            if (node.superName != null) todo.add(node.superName);
            todo.addAll(node.interfaces);
        }
        return false;
    }

    /** Whether the class declares the member with the call's static flag, visible from a Retromod class. */
    private static boolean declares(ClassNode node, Ref ref) {
        boolean iface = (node.access & Opcodes.ACC_INTERFACE) != 0;
        if (ref.field()) {
            for (FieldNode field : node.fields) {
                if (field.name.equals(ref.name()) && field.desc.equals(ref.desc())) return matches(field.access, ref, iface);
            }
            return false;
        }
        for (MethodNode method : node.methods) {
            if (method.name.equals(ref.name()) && method.desc.equals(ref.desc())) return matches(method.access, ref, iface);
        }
        return false;
    }

    private static boolean matches(int access, Ref ref, boolean iface) {
        // Retromod's helpers sit in their own package and extend no host class, so they reach only public members.
        boolean visible = (access & Opcodes.ACC_PUBLIC) != 0 || iface && (access & Opcodes.ACC_PRIVATE) == 0;
        return visible && ((access & Opcodes.ACC_STATIC) != 0) == ref.isStatic();
    }

    private static void addMember(Set<Ref> refs, String owner, String name, String desc, boolean field, boolean itf, boolean isStatic) {
        addDescriptor(refs, desc);
        // An array's clone() or length names no member of a class.
        if (owner.startsWith("[")) {
            addType(refs, owner);
            return;
        }
        refs.add(new Ref(owner, name, desc, field, itf, isStatic));
    }

    private static void addType(Set<Ref> refs, String type) {
        String element = elementType(type);
        if (element != null) refs.add(Ref.type(element));
    }

    private static void addDescriptor(Set<Ref> refs, String desc) {
        Type type = Type.getType(desc);
        if (type.getSort() == Type.METHOD) {
            addDescriptor(refs, type.getReturnType().getDescriptor());
            for (Type argument : type.getArgumentTypes()) addDescriptor(refs, argument.getDescriptor());
        } else if (type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY) {
            Type element = type.getSort() == Type.ARRAY ? type.getElementType() : type;
            if (element.getSort() == Type.OBJECT) addType(refs, element.getInternalName());
        }
    }

    /** The class an internal name or array descriptor names, or null for a primitive array. */
    private static String elementType(String name) {
        if (!name.startsWith("[")) return name;
        Type element = Type.getType(name).getElementType();
        return element.getSort() == Type.OBJECT ? element.getInternalName() : null;
    }

    static boolean isHost(String name) {
        for (String prefix : HOST_PACKAGES) {
            if (name.startsWith(prefix)) return true;
        }
        return false;
    }

    private static boolean isRebuilt(String name) {
        return name != null && name.startsWith(RebuiltApiClasses.ROOT);
    }
}
