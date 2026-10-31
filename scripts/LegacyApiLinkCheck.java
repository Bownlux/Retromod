/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Checks that every host class and member the rebuilt loader APIs use exists in real host jars,
 * with the same kind and descriptor, so a wrong compile-only stub cannot reach a release.
 *
 * <p>Run after {@code mvn package}:
 * {@code java -cp ~/.m2/repository/org/ow2/asm/asm/9.11.1/asm-9.11.1.jar scripts/LegacyApiLinkCheck.java
 * target/legacyapi-classes <minecraft-client-patched.jar> <neoforge-universal.jar> [more jars]}.
 */
public class LegacyApiLinkCheck {

    record Member(String name, String desc, boolean isStatic) {}

    record HostClass(String name, boolean isInterface, String superName, String[] interfaces,
                     List<Member> methods, List<Member> fields) {}

    private final List<ZipFile> jars = new ArrayList<>();
    private final Map<String, HostClass> cache = new HashMap<>();

    public static void main(String[] args) throws IOException {
        LegacyApiLinkCheck check = new LegacyApiLinkCheck();
        for (int i = 1; i < args.length; i++) check.jars.add(new ZipFile(args[i]));
        Set<String> problems = new TreeSet<>();
        int classes = 0;
        try (Stream<Path> files = Files.walk(Path.of(args[0]))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".class")
                    && f.toString().contains("com/retromod/legacyapi/")).toList()) {
                classes++;
                check.checkClass(Files.readAllBytes(file), problems);
            }
        }
        problems.forEach(System.out::println);
        System.out.println("# " + classes + " rebuilt classes, " + problems.size() + " problems");
        System.exit(problems.isEmpty() ? 0 : 1);
    }

    private void checkClass(byte[] bytes, Set<String> problems) {
        ClassReader reader = new ClassReader(bytes);
        String self = reader.getClassName();
        // Supertypes outside Retromod must be host types of the right kind.
        if (reader.getSuperName() != null && isHost(reader.getSuperName())) requireClass(self, reader.getSuperName(), false, problems);
        for (String iface : reader.getInterfaces()) if (isHost(iface)) requireClass(self, iface, true, problems);
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String mName, String mDesc, boolean itf) {
                        if (!isHost(owner)) return;
                        HostClass host = load(owner);
                        if (host == null) {
                            problems.add(self + ": missing class " + owner);
                            return;
                        }
                        if (host.isInterface() != itf) problems.add(self + ": " + owner + " interface flag differs for " + mName);
                        boolean isStatic = opcode == Opcodes.INVOKESTATIC;
                        if (!hasMethod(owner, mName, mDesc, isStatic, mName.equals("<init>"))) {
                            problems.add(self + ": missing " + (isStatic ? "static " : "") + owner + "." + mName + mDesc);
                        }
                    }

                    @Override
                    public void visitFieldInsn(int opcode, String owner, String fName, String fDesc) {
                        if (!isHost(owner)) return;
                        boolean isStatic = opcode == Opcodes.GETSTATIC || opcode == Opcodes.PUTSTATIC;
                        if (!hasField(owner, fName, fDesc, isStatic)) {
                            problems.add(self + ": missing " + (isStatic ? "static " : "") + owner + "." + fName + ":" + fDesc);
                        }
                    }

                    @Override
                    public void visitTypeInsn(int opcode, String type) {
                        if (isHost(type) && !type.startsWith("[") && load(type) == null) problems.add(self + ": missing class " + type);
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG);
    }

    private void requireClass(String self, String name, boolean expectInterface, Set<String> problems) {
        HostClass host = load(name);
        if (host == null) problems.add(self + ": missing supertype " + name);
        else if (host.isInterface() != expectInterface) problems.add(self + ": supertype " + name + " interface flag differs");
    }

    private static boolean isHost(String owner) {
        return !owner.startsWith("java/") && !owner.startsWith("com/retromod/legacyapi/") && !owner.startsWith("[");
    }

    /** Looks the member up the way the JVM resolves it: the class, its superclasses, then interfaces. */
    private boolean hasMethod(String owner, String name, String desc, boolean isStatic, boolean exactClass) {
        HostClass host = load(owner);
        if (host == null) return owner.startsWith("java/") && jdkHas(owner);
        for (Member m : host.methods()) if (m.name().equals(name) && m.desc().equals(desc) && m.isStatic() == isStatic) return true;
        if (exactClass) return false;
        if (host.superName() != null && hasMethod(host.superName(), name, desc, isStatic, false)) return true;
        for (String iface : host.interfaces()) if (hasMethod(iface, name, desc, isStatic, false)) return true;
        return false;
    }

    private boolean hasField(String owner, String name, String desc, boolean isStatic) {
        HostClass host = load(owner);
        if (host == null) return false;
        for (Member f : host.fields()) if (f.name().equals(name) && f.desc().equals(desc) && f.isStatic() == isStatic) return true;
        if (host.superName() != null && hasField(host.superName(), name, desc, isStatic)) return true;
        for (String iface : host.interfaces()) if (hasField(iface, name, desc, isStatic)) return true;
        return false;
    }

    private static boolean jdkHas(String owner) {
        try {
            Class.forName(owner.replace('/', '.'), false, ClassLoader.getPlatformClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private HostClass load(String name) {
        if (cache.containsKey(name)) return cache.get(name);
        HostClass found = null;
        byte[] bytes = read(name);
        if (bytes != null) found = parse(bytes);
        else if (name.startsWith("java/")) found = jdkClass(name);
        cache.put(name, found);
        return found;
    }

    private HostClass jdkClass(String name) {
        try (InputStream in = ClassLoader.getPlatformClassLoader().getResourceAsStream(name + ".class")) {
            if (in != null) return parse(in.readAllBytes());
        } catch (IOException ignored) {
        }
        try {
            Class<?> type = Class.forName(name.replace('/', '.'), false, ClassLoader.getPlatformClassLoader());
            byte[] bytes = type.getModule().getResourceAsStream(name + ".class").readAllBytes();
            return parse(bytes);
        } catch (Exception e) {
            return null;
        }
    }

    private byte[] read(String name) {
        for (ZipFile jar : jars) {
            ZipEntry entry = jar.getEntry(name + ".class");
            if (entry == null) continue;
            try (InputStream in = jar.getInputStream(entry)) {
                return in.readAllBytes();
            } catch (IOException e) {
                return null;
            }
        }
        return null;
    }

    private static HostClass parse(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        List<Member> methods = new ArrayList<>();
        List<Member> fields = new ArrayList<>();
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                if ((access & Opcodes.ACC_PRIVATE) == 0) methods.add(new Member(name, desc, (access & Opcodes.ACC_STATIC) != 0));
                return null;
            }

            @Override
            public FieldVisitor visitField(int access, String name, String desc, String sig, Object value) {
                if ((access & Opcodes.ACC_PRIVATE) == 0) fields.add(new Member(name, desc, (access & Opcodes.ACC_STATIC) != 0));
                return null;
            }
        }, ClassReader.SKIP_CODE);
        return new HostClass(reader.getClassName(), (reader.getAccess() & Opcodes.ACC_INTERFACE) != 0,
                reader.getSuperName(), reader.getInterfaces(), methods, fields);
    }
}
