/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
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

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Retromod runs on the loader's own ASM, so no class may use an ASM class or member the oldest loader ASM lacks. */
class HostAsmFloorTest {

    private static final String ASM = "org/objectweb/asm/";

    private static Path classes;
    private static String floorVersion;
    private static final Map<String, Shape> FLOOR = new HashMap<>();
    private static final Map<String, Shape> RETROMOD = new HashMap<>();

    /** The declared names of one class: super type, interfaces, fields and methods as name plus descriptor. */
    private record Shape(String superName, List<String> interfaces, Set<String> fields, Set<String> methods) {
        static Shape of(ClassNode node) {
            Set<String> fields = new HashSet<>();
            for (FieldNode field : node.fields) fields.add(field.name + ":" + field.desc);
            Set<String> methods = new HashSet<>();
            for (MethodNode method : node.methods) methods.add(method.name + method.desc);
            return new Shape(node.superName, node.interfaces, fields, methods);
        }
    }

    @BeforeAll
    static void loadShapes() throws Exception {
        classes = Path.of(HostAsmFloorTest.class.getResource("/com/retromod/core/RetromodTransformer.class").toURI())
                .getParent().getParent().getParent().getParent();
        // The pom copies the floor ASM jars (asm.floor.version) here before the tests run.
        Path floorJars = classes.getParent().resolve("asm-floor");
        assertTrue(Files.isDirectory(floorJars), "No floor ASM jars in " + floorJars
                + ". Run the tests through Maven, which copies them in the generate-test-resources phase.");
        Set<String> versions = new TreeSet<>();
        try (Stream<Path> jars = Files.list(floorJars)) {
            for (Path jar : jars.filter(p -> p.toString().endsWith(".jar")).toList()) {
                String file = jar.getFileName().toString();
                versions.add(file.substring(file.lastIndexOf('-') + 1, file.length() - ".jar".length()));
                readFloorJar(jar);
            }
        }
        assertEquals(1, versions.size(), "Every floor ASM jar should be one version: " + versions);
        floorVersion = versions.iterator().next();
        for (Path root : retromodRoots()) readRetromodClasses(root);
    }

    private static List<Path> retromodRoots() {
        return Stream.of(classes, classes.getParent().resolve("boot-classes"))
                .map(root -> root.resolve("com/retromod"))
                .filter(Files::isDirectory)
                .toList();
    }

    @Test
    @DisplayName("no Retromod class uses an ASM class or member the oldest host ASM lacks")
    void staysOnTheOldestHostAsm() throws IOException {
        // Main classes include the rebuilt legacyapi classes the build copies in; boot classes ship separately.
        List<String> found = new ArrayList<>();
        for (Path root : retromodRoots()) {
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(f -> f.toString().endsWith(".class")).toList()) {
                    found.addAll(scan(Files.readAllBytes(file)));
                }
            }
        }
        assertTrue(found.isEmpty(), "ASM " + floorVersion + " (the oldest loader ASM) lacks these, so they fail with "
                + "NoSuchMethodError or NoClassDefFoundError there:\n" + String.join("\n", new TreeSet<>(found)));
    }

    @Test
    @DisplayName("no Retromod class overrides an ASM method the oldest host ASM never calls")
    void overridesOnlyFloorMethods() throws IOException {
        List<String> found = new ArrayList<>();
        for (Path root : retromodRoots()) {
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(f -> f.toString().endsWith(".class")).toList()) {
                    ClassNode node = new ClassNode();
                    new ClassReader(Files.readAllBytes(file)).accept(node, ClassReader.SKIP_CODE);
                    if (!extendsAsm(node.name)) continue;
                    for (MethodNode method : node.methods) {
                        if ((method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) != 0
                                || method.name.startsWith("<")) continue;
                        String member = method.name + method.desc;
                        // The override compiles and links, but the floor ASM has nothing that calls it.
                        if (asmDeclares(node, member, false) && !asmDeclares(node, member, true)) {
                            found.add(node.name + "." + member);
                        }
                    }
                }
            }
        }
        assertTrue(found.isEmpty(), "ASM " + floorVersion + " never calls these overrides, so they silently do "
                + "nothing on the oldest loaders:\n" + String.join("\n", new TreeSet<>(found)));
    }

    @Test
    @DisplayName("the scan flags a member newer than the floor and passes the floor equivalent")
    void scanTellsNewerMembersApart() {
        byte[] newer = classCalling(Type.getInternalName(Type.class), "getArgumentCount", "(Ljava/lang/String;)I");
        byte[] floor = classCalling(Type.getInternalName(Type.class), "getArgumentTypes",
                "(Ljava/lang/String;)[Lorg/objectweb/asm/Type;");
        assertEquals(1, scan(newer).size(), "Type.getArgumentCount(String) came after ASM " + floorVersion);
        assertEquals(List.of(), scan(floor), "Type.getArgumentTypes(String) is in every ASM 9 release");
    }

    @Test
    @DisplayName("the floor jars really are the oldest host ASM, which reads Java 21 class files and no newer")
    void floorIsTheJava21Asm() {
        // Forge 46 and early 47 and 48, most NeoForge builds from 47.1 to 21.1.47, and Quilt Loader 0.20
        // and 0.21 ship ASM 9.5.
        // Raise the floor only together with those loader minimums.
        Set<String> opcodes = FLOOR.get(ASM + "Opcodes").fields();
        assertTrue(opcodes.contains("V21:I"), "The floor ASM should read Java 21 class files");
        assertFalse(opcodes.contains("V22:I"), "The floor ASM should be older than ASM 9.6");
    }

    private static void readFloorJar(Path jar) throws IOException {
        try (JarFile file = new JarFile(jar.toFile())) {
            for (JarEntry entry : file.stream().toList()) {
                if (!entry.getName().endsWith(".class") || entry.getName().endsWith("module-info.class")) continue;
                ClassNode node = new ClassNode();
                new ClassReader(file.getInputStream(entry).readAllBytes()).accept(node, ClassReader.SKIP_CODE);
                FLOOR.put(node.name, Shape.of(node));
            }
        }
    }

    private static void readRetromodClasses(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".class")).toList()) {
                ClassNode node = new ClassNode();
                new ClassReader(Files.readAllBytes(file)).accept(node, ClassReader.SKIP_CODE);
                RETROMOD.put(node.name, Shape.of(node));
            }
        }
    }

    /** Lists what one class uses from ASM that the floor ASM lacks. */
    private static List<String> scan(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        List<String> found = new ArrayList<>();
        String where = node.name;
        // The supertypes and declared member types link when the class loads.
        checkType(Type.getObjectType(node.superName == null ? "java/lang/Object" : node.superName), where, found);
        for (String type : node.interfaces) checkType(Type.getObjectType(type), where, found);
        for (FieldNode field : node.fields) checkType(Type.getType(field.desc), where + "." + field.name, found);
        for (MethodNode method : node.methods) {
            String at = where + "." + method.name;
            checkType(Type.getMethodType(method.desc), at, found);
            for (TryCatchBlockNode block : method.tryCatchBlocks) {
                if (block.type != null) checkType(Type.getObjectType(block.type), at, found);
            }
            for (AbstractInsnNode insn : method.instructions) checkInsn(insn, at, found);
        }
        return found;
    }

    private static void checkInsn(AbstractInsnNode insn, String at, List<String> found) {
        if (insn instanceof MethodInsnNode call) {
            checkMember(call.owner, call.name, call.desc, true, at, found);
        } else if (insn instanceof FieldInsnNode field) {
            checkMember(field.owner, field.name, field.desc, false, at, found);
        } else if (insn instanceof TypeInsnNode type) {
            checkType(type.desc.startsWith("[") ? Type.getType(type.desc) : Type.getObjectType(type.desc), at, found);
        } else if (insn instanceof MultiANewArrayInsnNode array) {
            checkType(Type.getType(array.desc), at, found);
        } else if (insn instanceof LdcInsnNode ldc) {
            checkConstant(ldc.cst, at, found);
        } else if (insn instanceof InvokeDynamicInsnNode indy) {
            // Method references such as SimpleRemapper::new travel as handles in the bootstrap arguments.
            checkType(Type.getMethodType(indy.desc), at, found);
            checkConstant(indy.bsm, at, found);
            for (Object arg : indy.bsmArgs) checkConstant(arg, at, found);
        }
    }

    private static void checkConstant(Object constant, String at, List<String> found) {
        if (constant instanceof Type type) {
            checkType(type, at, found);
        } else if (constant instanceof Handle handle) {
            boolean method = handle.getTag() > Opcodes.H_PUTSTATIC;
            checkMember(handle.getOwner(), handle.getName(), handle.getDesc(), method, at, found);
        }
    }

    private static void checkMember(String owner, String name, String desc, boolean method, String at,
                                    List<String> found) {
        checkType(method ? Type.getMethodType(desc) : Type.getType(desc), at, found);
        // Array members (clone, length) belong to the JVM, not to ASM.
        if (owner.startsWith("[")) {
            checkType(Type.getType(owner), at, found);
            return;
        }
        if (!checkType(Type.getObjectType(owner), at, found) || !extendsAsm(owner)) return;
        String member = method ? name + desc : name + ":" + desc;
        if (!resolves(owner, member, method)) {
            found.add(at + " uses " + owner + "." + (method ? name + desc : name + " " + desc));
        }
    }

    /** Records an ASM class the floor lacks; answers false when it did. */
    private static boolean checkType(Type type, String at, List<String> found) {
        if (type.getSort() == Type.METHOD) {
            boolean ok = checkType(type.getReturnType(), at, found);
            for (Type argument : type.getArgumentTypes()) ok &= checkType(argument, at, found);
            return ok;
        }
        Type element = type.getSort() == Type.ARRAY ? type.getElementType() : type;
        if (element.getSort() != Type.OBJECT) return true;
        String name = element.getInternalName();
        if (name.startsWith(ASM) && !FLOOR.containsKey(name)) {
            found.add(at + " uses class " + name);
            return false;
        }
        return true;
    }

    /** Answers whether the class is ASM's or one of Retromod's classes that extends or implements ASM's. */
    private static boolean extendsAsm(String owner) {
        for (String name = owner; name != null; ) {
            if (name.startsWith(ASM)) return true;
            Shape shape = RETROMOD.get(name);
            if (shape == null) return false;
            for (String type : shape.interfaces()) {
                if (extendsAsm(type)) return true;
            }
            name = shape.superName();
        }
        return false;
    }

    /** Answers whether an ASM supertype declares the method, in the floor ASM or the one Retromod builds with. */
    private static boolean asmDeclares(ClassNode node, String member, boolean floor) {
        Deque<String> pending = new ArrayDeque<>(node.interfaces);
        if (node.superName != null) pending.add(node.superName);
        Set<String> seen = new HashSet<>();
        while (!pending.isEmpty()) {
            String name = pending.poll();
            if (!seen.add(name)) continue;
            if (RETROMOD.containsKey(name)) {
                Shape shape = RETROMOD.get(name);
                if (shape.superName() != null) pending.add(shape.superName());
                pending.addAll(shape.interfaces());
            } else if (name.startsWith(ASM) && floor) {
                Shape shape = FLOOR.get(name);
                if (shape == null) continue;
                if (shape.methods().contains(member)) return true;
                if (shape.superName() != null) pending.add(shape.superName());
                pending.addAll(shape.interfaces());
            } else if (name.startsWith(ASM)) {
                Class<?> type = loadForScan(name, member);
                for (Method method : type.getDeclaredMethods()) {
                    if (member.equals(method.getName() + Type.getMethodDescriptor(method))) return true;
                }
                if (type.getSuperclass() != null) pending.add(Type.getInternalName(type.getSuperclass()));
                for (Class<?> parent : type.getInterfaces()) pending.add(Type.getInternalName(parent));
            }
        }
        return false;
    }

    /** Looks a member up through superclasses and interfaces like the JVM does. */
    private static boolean resolves(String owner, String member, boolean method) {
        Deque<String> pending = new ArrayDeque<>(List.of(owner));
        Set<String> seen = new HashSet<>();
        while (!pending.isEmpty()) {
            String name = pending.poll();
            if (!seen.add(name)) continue;
            Shape shape = FLOOR.containsKey(name) ? FLOOR.get(name) : RETROMOD.get(name);
            if (shape == null) {
                // A JDK supertype such as Object or Iterable: ask the running JVM.
                if (declaredByJdk(name, member, method, pending)) return true;
                continue;
            }
            if ((method ? shape.methods() : shape.fields()).contains(member)) return true;
            if (shape.superName() != null) pending.add(shape.superName());
            pending.addAll(shape.interfaces());
        }
        return false;
    }

    /** Answers whether a JDK class declares the member, queueing its supertypes. */
    private static boolean declaredByJdk(String name, String member, boolean method, Deque<String> pending) {
        Class<?> type = loadForScan(name, member);
        if (type.getSuperclass() != null) pending.add(Type.getInternalName(type.getSuperclass()));
        for (Class<?> parent : type.getInterfaces()) pending.add(Type.getInternalName(parent));
        if (!method) {
            for (Field field : type.getDeclaredFields()) {
                if (member.equals(field.getName() + ":" + Type.getDescriptor(field.getType()))) return true;
            }
            return false;
        }
        for (Method candidate : type.getDeclaredMethods()) {
            if (member.equals(candidate.getName() + Type.getMethodDescriptor(candidate))) return true;
        }
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (member.equals("<init>" + Type.getConstructorDescriptor(constructor))) return true;
        }
        return false;
    }

    private static Class<?> loadForScan(String name, String member) {
        try {
            return Class.forName(name.replace('/', '.'), false, HostAsmFloorTest.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            throw new AssertionError("The scan cannot load " + name + " to look up " + member, e);
        }
    }

    /** A one-method class that calls the given static method, for checking the scan itself. */
    private static byte[] classCalling(String owner, String name, String desc) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "probe/Caller", null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_STATIC, "call", "()V", null, null);
        method.visitCode();
        method.visitLdcInsn("()V");
        method.visitMethodInsn(Opcodes.INVOKESTATIC, owner, name, desc, false);
        method.visitInsn(Type.getReturnType(desc).getSize() == 2 ? Opcodes.POP2 : Opcodes.POP);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
