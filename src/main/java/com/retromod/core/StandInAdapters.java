/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.retromod.util.JsonSecurity;
import com.retromod.util.RetromodLog;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/** Converts stand-ins between copies where an addon calls or overrides a library that took another copy. */
final class StandInAdapters {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");
    private static final String OBJECT = "java/lang/Object";
    private static final String DELEGATE = "delegate";
    private static final String CACHE = "cache";
    private static final String ADAPT = "adapt";
    private static final String CACHE_SOURCE = "com/retromod/core/StandInAdapterCache";
    private static final String KEY_SOURCE = "com/retromod/core/StandInAdapterKey";
    private static final int MAX_DEPTH = 64;
    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    /** The largest mixin config read; real ones are a few kilobytes. */
    static final long MAX_MIXIN_CONFIG = 1024 * 1024;

    /** One stand-in a library takes as another copy, with the two adapter names and each side's enum view. */
    private record Pair(String standIn, String target, String to, String from, String view, String targetView) {}

    private final String folder;
    private final Map<String, String> names;
    private final Function<String, byte[]> libraryClassBytes;
    private final Map<Set<String>, Map<String, Pair>> byLibrary = new IdentityHashMap<>();
    private final Map<String, ClassNode> standIns = new HashMap<>();
    private final Map<String, ClassNode> libraryHeaders = new HashMap<>();
    private final Set<Pair> used = new LinkedHashSet<>();
    private final Set<String> crossed = new TreeSet<>();
    private final Set<String> mixinPackages = new HashSet<>();

    private StandInAdapters(String folder, Map<String, String> names, Function<String, byte[]> libraryClassBytes) {
        this.folder = folder;
        this.names = names;
        this.libraryClassBytes = libraryClassBytes;
    }

    /** Plans adapters in the jar's package {@code base} for each library copy that differs from the jar's own. */
    static StandInAdapters plan(String base, List<SyntheticLibraries.OtherCopy> others, Map<String, byte[]> synthetics,
            Map<String, String> names, SyntheticLibraries libraries) {
        StandInAdapters plan = new StandInAdapters(base + "retromod/adapt/", names, libraries::classBytes);
        for (SyntheticLibraries.OtherCopy other : others) {
            plan.crossed.add(other.copy());
            // Only an interface can be implemented by an adapter; a class stand-in keeps the jar's copy.
            byte[] registered = synthetics.get(other.standIn());
            byte[] target = isInterface(registered) ? libraries.heldCopyBytes(other.copy()) : null;
            if (!isInterface(target)) {
                LOGGER.debug(RetromodLog.DETAIL, "A library takes its own copy of {}, which is not an interface,"
                        + " so values passed to it keep this jar's copy", other.standIn());
                continue;
            }
            String suffix = simpleName(other.standIn()) + "$" + digest(other.copy());
            ClassNode node = plan.standIns.computeIfAbsent(other.standIn(), any -> read(registered));
            // Old enum constants convert by name through the other copy's valueOf, so identity checks still match.
            // The library's constants class is the one its copy reads them from, in whichever jar holds it.
            String view = viewOf(node);
            String targetView = view == null ? null : viewOf(read(target));
            if (targetView == null || !names.containsKey(view) || !libraries.holdsCopy(targetView)) {
                view = null;
                targetView = null;
            }
            Pair pair = new Pair(other.standIn(), other.copy(), plan.folder + "To" + suffix,
                    plan.folder + "From" + suffix, view, targetView);
            plan.byLibrary.computeIfAbsent(other.libraryClasses(), any -> new HashMap<>())
                    .putIfAbsent(other.standIn(), pair);
        }
        return plan;
    }

    /** Whether a jar entry could be a mixin config, which {@link #readMixinConfig} then checks by content. */
    static boolean mayBeMixinConfig(String entryName) {
        String fileName = entryName.substring(entryName.lastIndexOf('/') + 1).toLowerCase(java.util.Locale.ROOT);
        return fileName.endsWith(".json") && fileName.contains("mixin");
    }

    /** Notes the package of one of the jar's mixin configs; anything that is not one is ignored. */
    void readMixinConfig(byte[] json) {
        if (json == null || json.length > MAX_MIXIN_CONFIG) return;
        try {
            JsonSecurity.validate(json, MAX_MIXIN_CONFIG, JsonSecurity.DEFAULT_MAX_DEPTH, "mixin config");
            JsonElement root = JsonParser.parseString(new String(json, StandardCharsets.UTF_8));
            if (!root.isJsonObject()) return;
            JsonObject config = root.getAsJsonObject();
            // Mixin reserves the whole package, so every class it lists sits inside it.
            JsonElement pkg = config.get("package");
            if (pkg != null && pkg.isJsonPrimitive() && !pkg.getAsString().isBlank()) {
                mixinPackages.add(pkg.getAsString().trim().replace('.', '/') + "/");
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.debug(RetromodLog.DETAIL, "Skipping a JSON file that is not a readable mixin config: {}",
                    e.toString());
        }
    }

    /** Every library copy the jar hands a stand-in to while it uses another copy, which its link file lists. */
    Set<String> crossed() {
        return crossed;
    }

    /** The adapters the rewritten classes call, and the cache they share, by final name; call after every rewrite. */
    Map<String, byte[]> adapters() {
        Map<String, byte[]> adapters = new LinkedHashMap<>();
        if (used.isEmpty()) return adapters;
        // Each adapter can hand a value back through the other, so both directions are written.
        for (Pair pair : used) {
            ClassNode standIn = standIns.get(pair.standIn());
            adapters.put(pair.to(), adapter(pair, standIn, true));
            adapters.put(pair.from(), adapter(pair, standIn, false));
        }
        Map<String, String> cache = Map.of(CACHE_SOURCE, cacheName(), KEY_SOURCE, folder + "StandInAdapterKey");
        for (String source : List.of(CACHE_SOURCE, KEY_SOURCE)) {
            ClassWriter writer = new ClassWriter(0);
            new ClassReader(resource(source)).accept(new ClassRemapper(writer, new SimpleRemapper(cache)), 0);
            adapters.put(cache.get(source), writer.toByteArray());
        }
        return adapters;
    }

    /** A jar class with its crossing calls, field accesses and overrides converted; the same array when none is. */
    byte[] rewrite(byte[] classBytes, Map<String, byte[]> jarClasses) {
        if (byLibrary.isEmpty()) return classBytes;
        ClassNode node = read(classBytes);
        int converted = 0;
        for (MethodNode method : node.methods) {
            if (method.instructions == null || method.instructions.size() == 0) continue;
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof MethodInsnNode call) {
                    converted += convertCall(method, call, jarClasses);
                } else if (insn instanceof FieldInsnNode field) {
                    converted += convertField(method, field, jarClasses);
                }
            }
        }
        // Mixin merges a mixin's methods into its target, so a bridge there would land in another mod's class.
        List<MethodNode> bridges = isMixin(node) ? List.of() : bridges(node, jarClasses);
        node.methods.addAll(bridges);
        if (converted == 0 && bridges.isEmpty()) return classBytes;
        LOGGER.debug(RetromodLog.DETAIL, "Converted {} stand-in value(s) and added {} bridge(s) in {}",
                converted, bridges.size(), node.name);
        // Only straight-line code, new locals and branch-free bridges are added, so the frames stay valid.
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    /** Converts the stand-in arguments and result of one call into a library; how many values it converted. */
    private int convertCall(MethodNode method, MethodInsnNode call, Map<String, byte[]> jarClasses) {
        if (!namesConvertedStandIn(call.desc)) return 0;
        Map<String, Pair> pairs = pairsFor(call.owner, call.name, call.desc, jarClasses, false);
        if (pairs == null) return 0;
        Type[] arguments = Type.getArgumentTypes(call.desc);
        Type result = Type.getReturnType(call.desc);
        Pair[] argumentPairs = new Pair[arguments.length];
        int count = 0;
        for (int i = 0; i < arguments.length; i++) {
            argumentPairs[i] = pairOf(pairs, arguments[i]);
            if (argumentPairs[i] != null) count++;
        }
        Pair resultPair = pairOf(pairs, result);
        if (count == 0 && resultPair == null) return 0;

        InsnList before = new InsnList();
        boolean onlyLast = count == 1 && argumentPairs[arguments.length - 1] != null;
        if (onlyLast) {
            before.add(toLibrary(argumentPairs[arguments.length - 1]));
        } else if (count > 0) {
            // Every argument goes to a new local, then comes back converted where it has to be.
            int[] slots = new int[arguments.length];
            int next = method.maxLocals;
            for (int i = 0; i < arguments.length; i++) {
                slots[i] = next;
                next += arguments[i].getSize();
            }
            for (int i = arguments.length - 1; i >= 0; i--) {
                before.add(new VarInsnNode(arguments[i].getOpcode(Opcodes.ISTORE), slots[i]));
            }
            for (int i = 0; i < arguments.length; i++) {
                before.add(new VarInsnNode(arguments[i].getOpcode(Opcodes.ILOAD), slots[i]));
                if (argumentPairs[i] != null) before.add(toLibrary(argumentPairs[i]));
            }
            method.maxLocals = next;
        }
        if (before.size() > 0) method.instructions.insertBefore(call, before);
        if (resultPair != null) method.instructions.insert(call, toJar(resultPair));

        // The call now names the library's copy wherever it named the stand-in.
        Type[] retyped = new Type[arguments.length];
        for (int i = 0; i < arguments.length; i++) {
            retyped[i] = argumentPairs[i] == null ? arguments[i] : Type.getObjectType(argumentPairs[i].target());
        }
        Type returned = resultPair == null ? result : Type.getObjectType(resultPair.target());
        call.desc = Type.getMethodDescriptor(returned, retyped);
        return count + (resultPair == null ? 0 : 1);
    }

    /** Converts the value of one access to a library field typed as a stand-in; how many values it converted. */
    private int convertField(MethodNode method, FieldInsnNode field, Map<String, byte[]> jarClasses) {
        if (!namesConvertedStandIn(field.desc)) return 0;
        Map<String, Pair> pairs = pairsFor(field.owner, field.name, field.desc, jarClasses, true);
        if (pairs == null) return 0;
        Pair pair = pairOf(pairs, Type.getType(field.desc));
        if (pair == null) return 0;
        boolean read = field.getOpcode() == Opcodes.GETFIELD || field.getOpcode() == Opcodes.GETSTATIC;
        if (read) {
            method.instructions.insert(field, toJar(pair));
        } else {
            method.instructions.insertBefore(field, toLibrary(pair));
        }
        field.desc = Type.getObjectType(pair.target()).getDescriptor();
        return 1;
    }

    /** The pairs of the library a member of {@code owner} resolves into, through all supertypes, else null. */
    private Map<String, Pair> pairsFor(String owner, String name, String desc, Map<String, byte[]> jarClasses,
            boolean field) {
        ArrayDeque<String> pending = new ArrayDeque<>(List.of(owner));
        Set<String> seen = new HashSet<>();
        while (!pending.isEmpty() && seen.size() < MAX_DEPTH) {
            String current = pending.poll();
            if (!seen.add(current)) continue;
            Map<String, Pair> library = libraryOf(current);
            if (library != null) return library;
            byte[] own = jarClasses.get(current);
            if (own == null) continue;
            // A member the jar's own class declares is the jar's, typed with its own copy.
            ClassNode node = header(own);
            boolean declared = field
                    ? node.fields.stream().anyMatch(f -> f.name.equals(name) && f.desc.equals(desc))
                    : node.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(desc));
            if (declared) return null;
            if (node.superName != null) pending.add(node.superName);
            pending.addAll(node.interfaces);
        }
        return null;
    }

    /** Whether a class is a mixin: annotated with {@code @Mixin}, or inside a package a mixin config reserves. */
    private boolean isMixin(ClassNode node) {
        for (List<AnnotationNode> annotations : java.util.Arrays.asList(node.invisibleAnnotations,
                node.visibleAnnotations)) {
            if (annotations == null) continue;
            for (AnnotationNode annotation : annotations) {
                if (MIXIN.equals(annotation.desc)) return true;
            }
        }
        for (String pkg : mixinPackages) {
            if (node.name.startsWith(pkg)) return true;
        }
        return false;
    }

    /** Whether a descriptor names a stand-in some library takes as another copy; most calls do not. */
    private boolean namesConvertedStandIn(String desc) {
        for (String standIn : standIns.keySet()) {
            if (desc.contains("L" + standIn + ";")) return true;
        }
        return false;
    }

    private Map<String, Pair> libraryOf(String className) {
        for (Map.Entry<Set<String>, Map<String, Pair>> library : byLibrary.entrySet()) {
            if (library.getKey().contains(className)) return library.getValue();
        }
        return null;
    }

    /** Bridges for the library methods a class overrides with its own copy, each taking that library's signature. */
    private List<MethodNode> bridges(ClassNode node, Map<String, byte[]> jarClasses) {
        List<MethodNode> bridges = new ArrayList<>();
        if ((node.access & Opcodes.ACC_INTERFACE) != 0) return bridges;
        Set<String> declared = new HashSet<>();
        for (MethodNode method : node.methods) declared.add(method.name + method.desc);
        // Every superclass and interface counts, through the jar's own classes and within each library.
        ArrayDeque<String> pending = new ArrayDeque<>();
        if (node.superName != null) pending.add(node.superName);
        pending.addAll(node.interfaces);
        Set<String> seen = new HashSet<>();
        while (!pending.isEmpty() && seen.size() < MAX_DEPTH) {
            String current = pending.poll();
            if (!seen.add(current)) continue;
            Map<String, Pair> library = libraryOf(current);
            ClassNode ancestor = library != null ? libraryHeader(current) : jarHeader(jarClasses, current);
            if (ancestor == null) continue;
            if (ancestor.superName != null) pending.add(ancestor.superName);
            pending.addAll(ancestor.interfaces);
            if (library == null) continue;
            for (MethodNode method : ancestor.methods) {
                MethodNode bridge = bridge(node, method, library, declared);
                if (bridge != null) {
                    declared.add(bridge.name + bridge.desc);
                    bridges.add(bridge);
                }
            }
        }
        return bridges;
    }

    /** A bridge from a library method to the class's own override of it, or null when none is needed or possible. */
    private MethodNode bridge(ClassNode node, MethodNode libraryMethod, Map<String, Pair> pairs, Set<String> declared) {
        // Only public and protected methods can be overridden from another jar's package.
        int skip = Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL;
        int open = Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED;
        if ((libraryMethod.access & skip) != 0 || (libraryMethod.access & open) == 0
                || libraryMethod.name.startsWith("<")
                || !libraryMethod.desc.contains(SyntheticEmbedder.PREFIX)
                || declared.contains(libraryMethod.name + libraryMethod.desc)) {
            return null;
        }
        // The class's own method names each stand-in by its registered name where the library names its copy.
        Type[] arguments = Type.getArgumentTypes(libraryMethod.desc);
        Type result = Type.getReturnType(libraryMethod.desc);
        Pair[] argumentPairs = new Pair[arguments.length];
        Type[] own = new Type[arguments.length];
        boolean converts = false;
        for (int i = 0; i < arguments.length; i++) {
            argumentPairs[i] = pairWithTarget(pairs, arguments[i]);
            own[i] = ownType(arguments[i], argumentPairs[i]);
            if (own[i] == null) return null;
            converts |= argumentPairs[i] != null;
        }
        Pair resultPair = pairWithTarget(pairs, result);
        Type ownResult = ownType(result, resultPair);
        if (ownResult == null) return null;
        converts |= resultPair != null;
        String ownDesc = Type.getMethodDescriptor(ownResult, own);
        if (!converts || !declared.contains(libraryMethod.name + ownDesc)) return null;

        MethodNode bridge = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE,
                libraryMethod.name, libraryMethod.desc, null, null);
        InsnList code = bridge.instructions;
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        int slot = 1;
        for (int i = 0; i < arguments.length; i++) {
            code.add(new VarInsnNode(arguments[i].getOpcode(Opcodes.ILOAD), slot));
            if (argumentPairs[i] != null) code.add(toJar(argumentPairs[i]));
            slot += arguments[i].getSize();
        }
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, libraryMethod.name, ownDesc, false));
        if (resultPair != null) code.add(toLibrary(resultPair));
        code.add(new InsnNode(result.getOpcode(Opcodes.IRETURN)));
        bridge.maxLocals = slot;
        bridge.maxStack = Math.max(slot, Math.max(result.getSize(), 1));
        return bridge;
    }

    /**
     * How the class's own method names a type of the library's signature: a converted copy or the
     * jar's own copy by registered name, anything else as it is; null for a copy it cannot convert.
     */
    private Type ownType(Type type, Pair pair) {
        if (pair != null) return Type.getObjectType(pair.standIn());
        if (type.getSort() != Type.OBJECT || !type.getInternalName().startsWith(SyntheticEmbedder.PREFIX)) return type;
        String standIn = SyntheticLibraries.standInOf(type.getInternalName());
        boolean ownCopy = standIn != null && type.getInternalName().equals(names.get(standIn));
        return ownCopy ? Type.getObjectType(standIn) : null;
    }

    /** The pair whose library copy is {@code type}, or null. */
    private static Pair pairWithTarget(Map<String, Pair> pairs, Type type) {
        if (type.getSort() != Type.OBJECT) return null;
        for (Pair pair : pairs.values()) {
            if (pair.target().equals(type.getInternalName())) return pair;
        }
        return null;
    }

    private static Pair pairOf(Map<String, Pair> pairs, Type type) {
        return type.getSort() == Type.OBJECT ? pairs.get(type.getInternalName()) : null;
    }

    private MethodInsnNode toLibrary(Pair pair) {
        used.add(pair);
        return new MethodInsnNode(Opcodes.INVOKESTATIC, pair.to(), ADAPT,
                "(L" + OBJECT + ";)L" + pair.target() + ";", false);
    }

    private MethodInsnNode toJar(Pair pair) {
        used.add(pair);
        return new MethodInsnNode(Opcodes.INVOKESTATIC, pair.from(), ADAPT,
                "(L" + OBJECT + ";)L" + pair.standIn() + ";", false);
    }

    private String cacheName() {
        return folder + "StandInAdapterCache";
    }

    /** An adapter that holds one copy's value and answers as the other copy: jar to library, or the reverse. */
    private byte[] adapter(Pair pair, ClassNode standIn, boolean toLibrary) {
        String name = toLibrary ? pair.to() : pair.from();
        String reverse = toLibrary ? pair.from() : pair.to();
        String implemented = toLibrary ? pair.target() : pair.standIn();
        String held = toLibrary ? pair.standIn() : pair.target();
        String heldView = toLibrary ? pair.view() : pair.targetView();
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                return OBJECT;
            }
        };
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, name, null, OBJECT,
                new String[]{implemented});
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, DELEGATE, "L" + held + ";", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, CACHE, "L" + cacheName() + ";",
                null, null).visitEnd();

        MethodVisitor clinit = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitTypeInsn(Opcodes.NEW, cacheName());
        clinit.visitInsn(Opcodes.DUP);
        clinit.visitMethodInsn(Opcodes.INVOKESPECIAL, cacheName(), "<init>", "()V", false);
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, name, CACHE, "L" + cacheName() + ";");
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();

        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PRIVATE, "<init>", "(L" + held + ";)V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, OBJECT, "<init>", "()V", false);
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitVarInsn(Opcodes.ALOAD, 1);
        init.visitFieldInsn(Opcodes.PUTFIELD, name, DELEGATE, "L" + held + ";");
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        writeAdapt(cw, name, reverse, implemented, held, heldView);

        // A method whose signature names a stand-in would need that value converted too, so it throws.
        for (MethodNode method : standIn.methods) {
            if ((method.access & Opcodes.ACC_ABSTRACT) == 0 || (method.access & Opcodes.ACC_STATIC) != 0) continue;
            writeDelegate(cw, name, held, method, namesStandIn(method.desc));
        }
        MethodVisitor text = cw.visitMethod(Opcodes.ACC_PUBLIC, "toString", "()Ljava/lang/String;", null, null);
        text.visitCode();
        text.visitVarInsn(Opcodes.ALOAD, 0);
        text.visitFieldInsn(Opcodes.GETFIELD, name, DELEGATE, "L" + held + ";");
        text.visitMethodInsn(Opcodes.INVOKEVIRTUAL, OBJECT, "toString", "()Ljava/lang/String;", false);
        text.visitInsn(Opcodes.ARETURN);
        text.visitMaxs(0, 0);
        text.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code adapt(Object)}, which turns a value of the held copy into the implemented one. */
    private void writeAdapt(ClassWriter cw, String name, String reverse, String implemented, String held,
            String heldView) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, ADAPT,
                "(L" + OBJECT + ";)L" + implemented + ";", null, null);
        mv.visitCode();
        // Null stays null, and a value already of the wanted copy passes as it is.
        Label notNull = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitJumpInsn(Opcodes.IFNONNULL, notNull);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitLabel(notNull);

        Label notWanted = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, implemented);
        mv.visitJumpInsn(Opcodes.IFEQ, notWanted);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitTypeInsn(Opcodes.CHECKCAST, implemented);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitLabel(notWanted);

        // A value that went the other way and came back is the original object again.
        Label notReturning = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, reverse);
        mv.visitJumpInsn(Opcodes.IFEQ, notReturning);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitTypeInsn(Opcodes.CHECKCAST, reverse);
        mv.visitFieldInsn(Opcodes.GETFIELD, reverse, DELEGATE, "L" + implemented + ";");
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitLabel(notReturning);

        if (heldView != null) {
            Label notConstant = new Label();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitTypeInsn(Opcodes.INSTANCEOF, heldView);
            mv.visitJumpInsn(Opcodes.IFEQ, notConstant);
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitTypeInsn(Opcodes.CHECKCAST, held);
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, held, "name", "()Ljava/lang/String;", true);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, implemented, "valueOf",
                    "(Ljava/lang/String;)L" + implemented + ";", true);
            mv.visitInsn(Opcodes.ARETURN);
            mv.visitLabel(notConstant);
        }

        // Anything else gets one adapter, kept by identity while the value or the adapter is in use.
        String cacheType = "L" + cacheName() + ";";
        Label make = new Label();
        mv.visitFieldInsn(Opcodes.GETSTATIC, name, CACHE, cacheType);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, cacheName(), "find",
                "(Ljava/lang/Object;)Ljava/lang/Object;", false);
        mv.visitVarInsn(Opcodes.ASTORE, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitJumpInsn(Opcodes.IFNULL, make);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitTypeInsn(Opcodes.CHECKCAST, implemented);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitLabel(make);
        mv.visitFieldInsn(Opcodes.GETSTATIC, name, CACHE, cacheType);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitTypeInsn(Opcodes.NEW, name);
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitTypeInsn(Opcodes.CHECKCAST, held);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, name, "<init>", "(L" + held + ";)V", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, cacheName(), "keep",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false);
        mv.visitTypeInsn(Opcodes.CHECKCAST, implemented);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** One interface method answered by the held value, or by an exception when its signature names a stand-in. */
    private static void writeDelegate(ClassWriter cw, String name, String held, MethodNode method,
            boolean unsupported) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, method.name, method.desc, null, null);
        mv.visitCode();
        if (unsupported) {
            mv.visitTypeInsn(Opcodes.NEW, "java/lang/UnsupportedOperationException");
            mv.visitInsn(Opcodes.DUP);
            mv.visitLdcInsn("Retromod cannot pass " + method.name + " between two mods' copies of "
                    + simpleName(held));
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/UnsupportedOperationException", "<init>",
                    "(Ljava/lang/String;)V", false);
            mv.visitInsn(Opcodes.ATHROW);
        } else {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, name, DELEGATE, "L" + held + ";");
            int slot = 1;
            for (Type argument : Type.getArgumentTypes(method.desc)) {
                mv.visitVarInsn(argument.getOpcode(Opcodes.ILOAD), slot);
                slot += argument.getSize();
            }
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, held, method.name, method.desc, true);
            mv.visitInsn(Type.getReturnType(method.desc).getOpcode(Opcodes.IRETURN));
        }
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** Whether a method signature names a stand-in, whose copies would differ on the two sides. */
    private static boolean namesStandIn(String desc) {
        return desc.contains("com/retromod/") || desc.contains(SyntheticEmbedder.PREFIX);
    }

    /** The view class the old enum statics read their constants from, or null when the stand-in has none. */
    private static String viewOf(ClassNode node) {
        boolean hasValueOf = node.methods.stream().anyMatch(m -> m.name.equals("valueOf")
                && (m.access & Opcodes.ACC_STATIC) != 0);
        boolean hasName = node.methods.stream()
                .anyMatch(m -> m.name.equals("name") && m.desc.equals("()Ljava/lang/String;"));
        if (!hasValueOf || !hasName) return null;
        for (MethodNode method : node.methods) {
            if (!method.name.equals("values") || method.instructions == null) continue;
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC
                        && !call.owner.equals(node.name)) {
                    return call.owner;
                }
            }
        }
        return null;
    }

    private static boolean isInterface(byte[] classBytes) {
        return classBytes != null && (new ClassReader(classBytes).getAccess() & Opcodes.ACC_INTERFACE) != 0;
    }

    private static ClassNode read(byte[] classBytes) {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        return node;
    }

    /** A library class's members without code, read once per jar, or null when no library holds it. */
    private ClassNode libraryHeader(String className) {
        if (libraryHeaders.containsKey(className)) return libraryHeaders.get(className);
        byte[] bytes = libraryClassBytes.apply(className);
        ClassNode node = bytes == null ? null : header(bytes);
        libraryHeaders.put(className, node);
        return node;
    }

    private static ClassNode jarHeader(Map<String, byte[]> jarClasses, String className) {
        byte[] bytes = jarClasses.get(className);
        return bytes == null ? null : header(bytes);
    }

    private static ClassNode header(byte[] classBytes) {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node,
                ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return node;
    }

    /** The bytes of one of Retromod's own classes, which an addon gets a renamed copy of. */
    private static byte[] resource(String internalName) {
        try (InputStream in = StandInAdapters.class.getResourceAsStream("/" + internalName + ".class")) {
            if (in == null) throw new IllegalStateException("Retromod is missing its own class " + internalName);
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Could not read Retromod's own class " + internalName, e);
        }
    }

    private static String simpleName(String internalName) {
        return internalName.substring(internalName.lastIndexOf('/') + 1);
    }

    private static String digest(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder(8);
            for (int i = 0; i < 4; i++) value.append(String.format("%02x", hash[i]));
            return value.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(text.hashCode());
        }
    }
}
