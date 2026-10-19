/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Helpers that translated mod bytecode calls stay callable by jars an older build translated. */
class LoaderApiHelperAbiTest {

    private static final Path CLASSES = Path.of("target/classes");
    private static final Path ROOT = CLASSES.resolve("com/retromod/loaderapi");
    private static final String ABI = "/loaderapi/helper-abi.txt";

    // Within the 26.x line helpers are append-only, and a changed signature gets a new name.
    @Test
    @DisplayName("Helper methods and fields named from mod bytecode are append-only")
    void helperAbiIsAppendOnly() throws IOException {
        Set<String> published = new TreeSet<>();
        try (InputStream in = getClass().getResourceAsStream(ABI)) {
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (!line.isBlank() && !line.startsWith("#")) published.add(line.trim());
            }
        }
        Set<String> current = new TreeSet<>();
        for (String name : runtimeClasses()) {
            if (!isHelper(name)) continue;
            ClassNode node = new ClassNode();
            new ClassReader(Files.readAllBytes(CLASSES.resolve(name + ".class")))
                    .accept(node, ClassReader.SKIP_CODE);
            // Mod bytecode can only name a public class, so package-private plumbing is free to change.
            if ((node.access & Opcodes.ACC_PUBLIC) == 0) continue;
            for (MethodNode method : node.methods) {
                if ((method.access & Opcodes.ACC_PUBLIC) != 0 && !method.name.startsWith("<")) {
                    current.add(name + "." + method.name + method.desc);
                }
            }
            // A redirected field read, such as an old EVENT, names the helper's field for good too.
            for (FieldNode field : node.fields) {
                if ((field.access & Opcodes.ACC_PUBLIC) != 0) current.add(name + "." + field.name + ":" + field.desc);
            }
        }
        Set<String> removed = new TreeSet<>(published);
        removed.removeAll(current);
        assertTrue(removed.isEmpty(), "published helper methods or fields were removed or changed; give a "
                + "changed signature a new name instead: " + removed);
        Set<String> added = new TreeSet<>(current);
        added.removeAll(published);
        assertEquals(Set.of(), added, "new helper methods and fields must be listed in " + ABI);
    }

    /** Helpers live in a batch package or a shared package; the provider's own classes do not. */
    private static boolean isHelper(String name) {
        if (LoaderApiRuntime.isSharedTypeName(name)) return true;
        String prefix = "com/retromod/loaderapi/fabric/v26/";
        if (!name.startsWith(prefix)) return false;
        String rest = name.substring(prefix.length());
        return rest.contains("/") && !rest.startsWith("synth/");
    }

    private static List<String> runtimeClasses() throws IOException {
        try (Stream<Path> files = Files.walk(ROOT)) {
            return files.filter(path -> path.toString().endsWith(".class"))
                    .map(path -> CLASSES.relativize(path).toString().replace(File.separatorChar, '/'))
                    .map(path -> path.substring(0, path.length() - ".class".length()))
                    .sorted().toList();
        }
    }
}
