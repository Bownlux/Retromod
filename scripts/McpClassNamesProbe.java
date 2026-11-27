/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The jar tools behind scripts/harvest-mcp-class-names.py. Run each with ASM on the class path:
 * {@code java -cp asm-9.11.1.jar scripts/McpClassNamesProbe.java <command> ...}
 * <ul>
 *   <li>{@code make <names.txt> <out.jar> <mc>}: a Forge mod for that version whose class {@code probe/Names} has one
 *   static field per listed class, typed with it. A 1.12.x version gets an mcmod.info, any other a mods.toml.</li>
 *   <li>{@code read <translated.jar> <names.txt>}: each name and the type its field has after a translation.</li>
 *   <li>{@code members <jar>[,<jar>...]}: every class with its superclass, interfaces and member names.</li>
 * </ul>
 */
public class McpClassNamesProbe {

    public static void main(String[] args) throws IOException {
        switch (args[0]) {
            case "make" -> make(Files.readAllLines(Path.of(args[1])), Path.of(args[2]), args[3]);
            case "read" -> read(Path.of(args[1]), Files.readAllLines(Path.of(args[2])));
            case "members" -> members(args[1].split(","));
            default -> throw new IllegalArgumentException("Unknown command " + args[0] + "; use make, read or members.");
        }
    }

    static void make(List<String> names, Path out, String mc) throws IOException {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, "probe/Names", null, "java/lang/Object", null);
        for (int i = 0; i < names.size(); i++) {
            cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "n" + i, "L" + names.get(i) + ";", null, null)
                    .visitEnd();
        }
        cw.visitEnd();
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(out), manifest)) {
            // A 1.12 Forge mod describes itself in mcmod.info; 1.13 and newer use mods.toml.
            if (mc.startsWith("1.12")) {
                put(jar, "mcmod.info", "[{\"modid\":\"probe\",\"name\":\"Probe\",\"version\":\"1.0\",\"mcversion\":\""
                        + mc + "\"}]");
            } else {
                put(jar, "META-INF/mods.toml", "modLoader=\"javafml\"\nloaderVersion=\"[31,)\"\nlicense=\"MIT\"\n"
                        + "[[mods]]\nmodId=\"probe\"\nversion=\"1.0\"\n[[dependencies.probe]]\nmodId=\"minecraft\"\n"
                        + "mandatory=true\nversionRange=\"[" + mc + "]\"\nordering=\"NONE\"\nside=\"BOTH\"\n");
            }
            jar.putNextEntry(new JarEntry("probe/Names.class"));
            jar.write(cw.toByteArray());
            jar.closeEntry();
        }
    }

    private static void put(JarOutputStream jar, String name, String text) throws IOException {
        jar.putNextEntry(new JarEntry(name));
        jar.write(text.getBytes(StandardCharsets.UTF_8));
        jar.closeEntry();
    }

    static void read(Path translated, List<String> names) throws IOException {
        Map<String, String> fieldTypes = new HashMap<>();
        try (JarFile jar = new JarFile(translated.toFile()); InputStream in = jar.getInputStream(
                jar.getJarEntry("probe/Names.class"))) {
            new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public FieldVisitor visitField(int access, String name, String desc, String sig, Object value) {
                    fieldTypes.put(name, desc.substring(1, desc.length() - 1));
                    return null;
                }
            }, ClassReader.SKIP_CODE);
        }
        for (int i = 0; i < names.size(); i++) {
            System.out.println(names.get(i) + "\t" + fieldTypes.getOrDefault("n" + i, "-"));
        }
    }

    static void members(String[] jars) throws IOException {
        PrintStream out = new PrintStream(new BufferedOutputStream(System.out), false, StandardCharsets.UTF_8);
        for (String path : jars) {
            try (ZipFile zip = new ZipFile(path)) {
                for (ZipEntry entry : Collections.list(zip.entries())) {
                    if (!entry.getName().endsWith(".class") || entry.getName().startsWith("META-INF/")) continue;
                    try (InputStream in = zip.getInputStream(entry)) {
                        printClass(new ClassReader(in), out);
                    } catch (RuntimeException unreadable) {
                        // A class ASM cannot read is left out of the dump.
                    }
                }
            }
        }
        out.flush();
    }

    private static void printClass(ClassReader reader, PrintStream out) {
        Set<String> members = new TreeSet<>();
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public FieldVisitor visitField(int access, String name, String desc, String sig, Object value) {
                members.add(name);
                return null;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                members.add(name);
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        String superName = reader.getSuperName() == null ? "" : reader.getSuperName();
        out.println(reader.getClassName() + "\t" + superName + "\t" + String.join(",", reader.getInterfaces())
                + "\t" + String.join(",", members));
    }
}
