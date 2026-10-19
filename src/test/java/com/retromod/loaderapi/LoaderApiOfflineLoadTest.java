/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every runtime class loads with Fabric API and Minecraft absent, as in the CLI. */
class LoaderApiOfflineLoadTest {

    private static final Path CLASSES = Path.of("target/classes");
    private static final Path ROOT = CLASSES.resolve("com/retromod/loaderapi");

    // Otherwise the offline phantom sweep would drop the redirects to it.
    @Test
    @DisplayName("Every runtime class loads with Fabric API and Minecraft absent")
    void everyClassLoadsOffline() throws Exception {
        List<URL> urls = new ArrayList<>();
        urls.add(CLASSES.toUri().toURL());
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            if (entry.contains("/org/ow2/asm/") || entry.contains("/org/slf4j/")
                    || entry.contains("/com/google/code/gson/")) {
                urls.add(Path.of(entry).toUri().toURL());
            }
        }
        List<String> failures = new ArrayList<>();
        try (URLClassLoader offline = new URLClassLoader(urls.toArray(new URL[0]),
                ClassLoader.getPlatformClassLoader())) {
            for (String name : runtimeClasses()) {
                try {
                    Class.forName(name.replace('/', '.'), false, offline);
                } catch (ClassNotFoundException | LinkageError e) {
                    failures.add(name + ": " + e);
                }
            }
        }
        assertTrue(failures.isEmpty(), "classes that need Fabric API or Minecraft to load: " + failures);
    }

    @Test
    @DisplayName("Shared types extend and implement only JDK or Retromod types")
    void sharedTypesStayNeutral() throws IOException {
        for (String name : runtimeClasses()) {
            if (!LoaderApiRuntime.isSharedTypeName(name)) continue;
            ClassNode node = new ClassNode();
            new ClassReader(Files.readAllBytes(CLASSES.resolve(name + ".class")))
                    .accept(node, ClassReader.SKIP_CODE);
            List<String> supertypes = new ArrayList<>(node.interfaces);
            if (node.superName != null) supertypes.add(node.superName);
            for (String supertype : supertypes) {
                assertTrue(supertype.startsWith("java/") || supertype.startsWith("com/retromod/"),
                        name + " may not extend " + supertype);
            }
        }
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
