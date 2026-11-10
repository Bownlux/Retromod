/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodVersion;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.util.RetromodLog;
import com.retromod.util.ZipSecurity;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.MethodRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.stream.Stream;

/** Gives a NeoForge dedicated server's copy of a mod empty stand-ins for the client classes that server lacks. */
public final class ServerClientStandIns {

    /** Hosts with a bundled table; a host uses the table of its own release line. */
    static final List<String> TABLE_VERSIONS = List.of("26.3", "26.2", "26.1.2", "1.21.11");
    private static final String TABLE = "/retromod/client-only/neoforge-%s.tsv";
    /** The folder inside a mod's embedded package that holds its stand-ins. */
    public static final String FOLDER = "clientonly/";
    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final Map<String, Map<String, Row>> TABLES = new ConcurrentHashMap<>();
    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-ServerClient");
    private static final AtomicBoolean NO_TABLE_LOGGED = new AtomicBoolean();

    /** One client class: its kind (class, abstract, or interface), its superclass, and its interfaces. */
    record Row(String kind, String superName, List<String> interfaces) {
        boolean isInterface() {
            return kind.equals("interface");
        }
    }

    private ServerClientStandIns() {}

    /** Points the mod's references to client classes the host lacks at stand-ins in its folder; returns how many it wrote. */
    public static int embed(Path modDir, String uniqueKey, String hostVersion) throws IOException {
        Map<String, Row> table = tableFor(hostVersion);
        if (table.isEmpty() && NO_TABLE_LOGGED.compareAndSet(false, true)) {
            LOGGER.debug(RetromodLog.DETAIL, "No client class table for Minecraft {}, so mods translated on this "
                    + "dedicated server get no stand-ins for client classes", hostVersion);
        }
        Map<String, Boolean> known = new HashMap<>();
        return embed(modDir, uniqueKey, table, name -> known.computeIfAbsent(name, ClassResourceInspector::exists));
    }

    static int embed(Path modDir, String uniqueKey, Map<String, Row> table, Predicate<String> hostHas)
            throws IOException {
        if (table.isEmpty()) return 0;
        Map<Path, byte[]> classes = readClasses(modDir);
        Set<String> own = new HashSet<>();
        for (Path file : classes.keySet()) own.add(internalName(modDir, file));

        // A name the mod or the host defines is real and keeps pointing there.
        Predicate<String> absent = name -> table.containsKey(name) && !own.contains(name) && !hostHas.test(name);
        Set<String> needed = new TreeSet<>();
        for (byte[] bytes : classes.values()) {
            if (isMixin(bytes)) continue;
            for (String name : referencedClasses(bytes)) {
                if (absent.test(name)) needed.add(name);
            }
        }
        if (needed.isEmpty()) return 0;
        addAbsentSupertypes(needed, table, absent);

        String base = SyntheticEmbedder.embeddedBase(uniqueKey) + FOLDER;
        Map<String, String> rename = new HashMap<>();
        for (String name : needed) rename.put(name, base + name);

        // Write the stand-ins first, so a collision leaves the mod's classes untouched.
        for (String name : needed) {
            Path target = ZipSecurity.safeResolve(modDir, rename.get(name) + ".class");
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("client stand-in collides with an existing class: " + modDir.relativize(target));
            }
            Files.createDirectories(target.getParent());
            Files.write(target, standIn(name, rename.get(name), table.get(name), rename, hostHas));
        }

        // Mixins keep their targets and selectors; a client mixin never applies on a server anyway.
        for (Map.Entry<Path, byte[]> entry : classes.entrySet()) {
            if (isMixin(entry.getValue())) continue;
            byte[] rewritten = remap(entry.getValue(), rename);
            if (!Arrays.equals(rewritten, entry.getValue())) Files.write(entry.getKey(), rewritten);
        }
        return needed.size();
    }

    /** The table for the host's release line, or an empty one when Retromod has none. */
    static Map<String, Row> tableFor(String hostVersion) {
        String version = tableVersion(hostVersion);
        if (version == null) return Map.of();
        return TABLES.computeIfAbsent(version, v -> readTable(String.format(TABLE, v)));
    }

    /** The bundled table whose release line the host is on: 26.3.1 uses 26.3, 26.1 uses 26.1.2. */
    static String tableVersion(String hostVersion) {
        if (hostVersion == null) return null;
        for (String version : TABLE_VERSIONS) {
            if (releaseLine(version).equals(releaseLine(hostVersion))) return version;
        }
        // A newer line uses the newest table; only classes that host lacks get stand-ins anyway.
        String newest = TABLE_VERSIONS.get(0);
        return RetromodVersion.compareMcVersions(hostVersion, newest) > 0 ? newest : null;
    }

    private static String releaseLine(String version) {
        String[] parts = version.split("[.-]");
        // Before 26.1 a release line is the third number: 1.21.11 and 1.21.10 have different clients.
        int length = parts[0].equals("1") ? 3 : 2;
        return String.join(".", Arrays.copyOf(parts, Math.min(length, parts.length)));
    }

    static Map<String, Row> readTable(String resource) {
        Map<String, Row> rows = new HashMap<>();
        try (InputStream in = ServerClientStandIns.class.getResourceAsStream(resource)) {
            if (in == null) return Map.of();
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] columns = line.split("\t");
                if (columns.length != 4) continue;
                List<String> interfaces = columns[3].equals("-") ? List.of() : List.of(columns[3].split(","));
                rows.put(columns[1], new Row(columns[0], columns[2], interfaces));
            }
        } catch (IOException unreadable) {
            return Map.of();
        }
        return Map.copyOf(rows);
    }

    /** Adds the absent superclasses and interfaces of every needed class, so each stand-in has its client hierarchy. */
    private static void addAbsentSupertypes(Set<String> needed, Map<String, Row> table, Predicate<String> absent) {
        ArrayDeque<String> pending = new ArrayDeque<>(needed);
        while (!pending.isEmpty()) {
            Row row = table.get(pending.poll());
            List<String> supertypes = new ArrayList<>(row.interfaces());
            supertypes.add(row.superName());
            for (String supertype : supertypes) {
                if (absent.test(supertype) && needed.add(supertype)) pending.add(supertype);
            }
        }
    }

    /**
     * A member-less class or interface with the client type's hierarchy. A class fails its static
     * initializer with the error a missing class gave, so creating one, or one of the mod's
     * subclasses, fails as it did before.
     */
    private static byte[] standIn(String clientName, String name, Row row, Map<String, String> rename,
            Predicate<String> hostHas) {
        int access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC
                | (row.isInterface() ? Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT : 0)
                | (row.kind().equals("abstract") ? Opcodes.ACC_ABSTRACT : 0);
        // A supertype neither stood in nor on the host, such as an LWJGL callback, is left out.
        String superName = row.isInterface() ? "java/lang/Object"
                : rename.getOrDefault(row.superName(), hostHas.test(row.superName()) ? row.superName() : "java/lang/Object");
        List<String> interfaces = new ArrayList<>();
        for (String itf : row.interfaces()) {
            if (rename.containsKey(itf)) interfaces.add(rename.get(itf));
            else if (hostHas.test(itf)) interfaces.add(itf);
        }
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, access, name, null, superName, interfaces.toArray(String[]::new));
        if (!row.isInterface()) {
            MethodVisitor init = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
            init.visitCode();
            init.visitTypeInsn(Opcodes.NEW, "java/lang/NoClassDefFoundError");
            init.visitInsn(Opcodes.DUP);
            init.visitLdcInsn(clientName + " (a client class; this dedicated server does not have it)");
            init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/NoClassDefFoundError", "<init>",
                    "(Ljava/lang/String;)V", false);
            init.visitInsn(Opcodes.ATHROW);
            init.visitMaxs(3, 0);
            init.visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static Map<Path, byte[]> readClasses(Path modDir) throws IOException {
        Map<Path, byte[]> classes = new LinkedHashMap<>();
        try (Stream<Path> files = Files.walk(modDir)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".class")).sorted().toList()) {
                String name = internalName(modDir, file);
                if (name.startsWith("META-INF/") || name.endsWith("module-info")) continue;
                classes.put(file, Files.readAllBytes(file));
            }
        }
        return classes;
    }

    private static String internalName(Path modDir, Path classFile) {
        String relative = modDir.relativize(classFile).toString().replace('\\', '/');
        return relative.substring(0, relative.length() - ".class".length());
    }

    private static boolean isMixin(byte[] classBytes) {
        boolean[] mixin = {false};
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                if (MIXIN.equals(descriptor)) mixin[0] = true;
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return mixin[0];
    }

    // The loader's ASM can be as old as 9.5, so this uses the remapper constructors that version has.
    private static Set<String> referencedClasses(byte[] classBytes) {
        Set<String> names = new HashSet<>();
        new ClassReader(classBytes).accept(new ClassRemapper(new ClassWriter(0), new Remapper() {
            @Override
            public String map(String internalName) {
                names.add(internalName);
                return internalName;
            }
        }), ClassReader.SKIP_DEBUG);
        return names;
    }

    /** Renames the client types the verifier checks; frames and stack sizes stay valid, since the hierarchy is the client's. */
    private static byte[] remap(byte[] classBytes, Map<String, String> rename) {
        Remapper remapper = new SimpleRemapper(rename);
        ClassWriter writer = new ClassWriter(0);
        new ClassReader(classBytes).accept(new ClassRemapper(Opcodes.ASM9, writer, remapper) {
            @Override
            protected MethodVisitor createMethodRemapper(MethodVisitor methodVisitor) {
                return new KeepsMissingClassChecks(methodVisitor, remapper, rename.keySet());
            }
        }, 0);
        return writer.toByteArray();
    }

    /**
     * Leaves the original client class where running the code, not verifying it, resolves the class:
     * a static member, a method reference, and a class literal. Those still fail with
     * {@code NoClassDefFoundError} on a server, so a mod that probes for the client that way sees
     * no client, as before.
     */
    private static final class KeepsMissingClassChecks extends MethodRemapper {
        private final Set<String> clientClasses;

        KeepsMissingClassChecks(MethodVisitor methodVisitor, Remapper remapper, Set<String> clientClasses) {
            super(Opcodes.ASM9, methodVisitor, remapper);
            this.clientClasses = clientClasses;
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            if (opcode == Opcodes.INVOKESTATIC && clientClasses.contains(owner)) {
                mv.visitMethodInsn(opcode, owner, name, remapper.mapMethodDesc(descriptor), isInterface);
            } else {
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
            }
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            boolean staticAccess = opcode == Opcodes.GETSTATIC || opcode == Opcodes.PUTSTATIC;
            if (staticAccess && clientClasses.contains(owner)) {
                mv.visitFieldInsn(opcode, owner, name, remapper.mapDesc(descriptor));
            } else {
                super.visitFieldInsn(opcode, owner, name, descriptor);
            }
        }

        @Override
        public void visitLdcInsn(Object value) {
            if (value instanceof Type type && namesClientClass(type)) {
                mv.visitLdcInsn(value);
            } else {
                super.visitLdcInsn(value);
            }
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrap, Object... arguments) {
            Object[] mapped = new Object[arguments.length];
            for (int i = 0; i < arguments.length; i++) {
                mapped[i] = arguments[i] instanceof Handle handle && clientClasses.contains(handle.getOwner())
                        ? keepOwner(handle) : remapper.mapValue(arguments[i]);
            }
            mv.visitInvokeDynamicInsn(remapper.mapInvokeDynamicMethodName(name, descriptor),
                    remapper.mapMethodDesc(descriptor), (Handle) remapper.mapValue(bootstrap), mapped);
        }

        private Handle keepOwner(Handle handle) {
            boolean field = handle.getTag() <= Opcodes.H_PUTSTATIC;
            String descriptor = field ? remapper.mapDesc(handle.getDesc()) : remapper.mapMethodDesc(handle.getDesc());
            return new Handle(handle.getTag(), handle.getOwner(), handle.getName(), descriptor, handle.isInterface());
        }

        private boolean namesClientClass(Type type) {
            Type element = type.getSort() == Type.ARRAY ? type.getElementType() : type;
            return element.getSort() == Type.OBJECT && clientClasses.contains(element.getInternalName());
        }
    }
}
