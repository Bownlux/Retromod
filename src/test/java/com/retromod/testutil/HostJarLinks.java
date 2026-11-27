/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testutil;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Checks that the game members a generated class calls exist in real host jars, which tests skip when the jars are absent. */
public final class HostJarLinks {

    private static final Path LIBRARIES = Path.of(System.getProperty("user.home", ""),
            "Library/Application Support/PrismLauncher/libraries");

    private HostJarLinks() {}

    /** A jar under the Prism library folder, or null when this machine has none. */
    public static Path prism(String relative) {
        Path jar = LIBRARIES.resolve(relative);
        return Files.isRegularFile(jar) ? jar : null;
    }

    /** One class read from the jars, without code, or null when none holds it. */
    public static ClassNode read(List<Path> jars, String internalName) {
        for (Path jar : jars) {
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                ZipEntry entry = zip.getEntry(internalName + ".class");
                if (entry == null) continue;
                try (InputStream in = zip.getInputStream(entry)) {
                    ClassNode node = new ClassNode();
                    new ClassReader(in).accept(node, ClassReader.SKIP_CODE);
                    return node;
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return readJdk(internalName);
    }

    /** Each call or field access to a class under {@code prefixes} that the jars do not declare, as {@code owner.name desc}. */
    public static List<String> unresolved(byte[] classBytes, List<Path> jars, String... prefixes) {
        return unresolvedIn(classBytes, null, jars, prefixes);
    }

    /** The same, for the generated methods named {@code methodName} only, or every method for null. */
    public static List<String> unresolvedIn(byte[] classBytes, String methodName, List<Path> jars, String... prefixes) {
        ClassNode generated = new ClassNode();
        new ClassReader(classBytes).accept(generated, 0);
        List<String> missing = new ArrayList<>();
        for (MethodNode method : generated.methods) {
            if (methodName != null && !method.name.equals(methodName)) continue;
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && hostOwned(call.owner, prefixes)
                        && !declares(jars, call.owner, call.name, call.desc, true)) {
                    missing.add(call.owner + "." + call.name + call.desc);
                } else if (insn instanceof FieldInsnNode field && hostOwned(field.owner, prefixes)
                        && !declares(jars, field.owner, field.name, field.desc, false)) {
                    missing.add(field.owner + "." + field.name + ":" + field.desc);
                }
            }
        }
        return missing;
    }

    private static boolean hostOwned(String owner, String... prefixes) {
        for (String prefix : prefixes) {
            if (owner.startsWith(prefix)) return true;
        }
        return false;
    }

    // The member is found on the owner or a supertype, as the JVM resolves it.
    private static boolean declares(List<Path> jars, String owner, String name, String desc, boolean method) {
        ArrayDeque<String> pending = new ArrayDeque<>(List.of(owner));
        Set<String> seen = new HashSet<>();
        while (!pending.isEmpty()) {
            String type = pending.poll();
            if (!seen.add(type)) continue;
            ClassNode node = read(jars, type);
            if (node == null) continue;
            if (method && node.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(desc))) return true;
            if (!method && node.fields.stream().anyMatch((FieldNode f) -> f.name.equals(name) && f.desc.equals(desc))) {
                return true;
            }
            if (node.superName != null) pending.add(node.superName);
            pending.addAll(node.interfaces);
        }
        return false;
    }

    private static ClassNode readJdk(String internalName) {
        if (!internalName.startsWith("java/")) return null;
        try (InputStream in = Object.class.getResourceAsStream("/" + internalName + ".class")) {
            if (in == null) return null;
            ClassNode node = new ClassNode();
            new ClassReader(in).accept(node, ClassReader.SKIP_CODE);
            return node;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
