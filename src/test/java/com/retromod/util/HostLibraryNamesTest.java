/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The release jar relocates Gson, SLF4J and toml4j, and maven-shade rewrites any string constant
 * that names them. Tests run on the unshaded classes, so a literal such as
 * {@code "com/google/gson/Gson"} passes here and then ships naming Retromod's private copy (#294:
 * the reload-listener bridge failed with {@code VerifyError} in every published 1.3.x jar). This
 * scans the compiled classes for exactly the constants shade would rewrite.
 */
class HostLibraryNamesTest {

    /** The relocated patterns from pom.xml, in both separator forms. */
    private static final List<String> RELOCATED = List.of(
            "com/google/gson", "com.google.gson",
            "org/slf4j", "org.slf4j",
            "com/moandjiezana/toml", "com.moandjiezana.toml");

    @Test
    @DisplayName("the runtime-built names match the host packages")
    void namesAreTheHostPackages() {
        assertEquals("com/google/gson/Gson", HostLibraryNames.GSON);
        assertEquals("com.google.gson.JsonElement",
                HostLibraryNames.binaryName(HostLibraryNames.JSON_ELEMENT));
        assertEquals("org/slf4j", HostLibraryNames.SLF4J_PACKAGE);
    }

    @Test
    @DisplayName("no class carries a string constant that shading would rewrite")
    void noShadeVulnerableLiterals() throws Exception {
        Path classes = Path.of(HostLibraryNames.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        List<String> hits = new ArrayList<>();
        try (Stream<Path> files = Files.walk(classes)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".class")).toList()) {
                scan(classes.relativize(file).toString(), Files.readAllBytes(file), hits);
            }
        }
        assertTrue(hits.isEmpty(), "maven-shade rewrites these to com.retromod.shaded, so they "
                + "would name Retromod's own copy in the release jar. Build the name at runtime "
                + "through HostLibraryNames instead:\n" + String.join("\n", hits));
    }

    private static void scan(String file, byte[] bytes, List<String> hits) throws IOException {
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public FieldVisitor visitField(int access, String name, String desc, String sig,
                    Object value) {
                check(file + " field " + name, value, hits);
                return null;
            }

            @Override
            public org.objectweb.asm.AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                return annotationValues(file, hits);
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig,
                    String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitLdcInsn(Object value) {
                        check(file + " " + name, value, hits);
                    }

                    // "org/slf4j/" + x compiles to a StringConcatFactory recipe, which shade
                    // rewrites like any other constant.
                    @Override
                    public void visitInvokeDynamicInsn(String indyName, String desc,
                            org.objectweb.asm.Handle bootstrap, Object... bootstrapArgs) {
                        for (Object arg : bootstrapArgs) check(file + " " + name, arg, hits);
                    }

                    @Override
                    public org.objectweb.asm.AnnotationVisitor visitAnnotation(String desc,
                            boolean visible) {
                        return annotationValues(file + " " + name, hits);
                    }
                };
            }
        }, 0);
    }

    private static org.objectweb.asm.AnnotationVisitor annotationValues(String where,
            List<String> hits) {
        return new org.objectweb.asm.AnnotationVisitor(Opcodes.ASM9) {
            @Override
            public void visit(String name, Object value) {
                check(where + " @" + name, value, hits);
            }

            @Override
            public org.objectweb.asm.AnnotationVisitor visitArray(String name) {
                return this;
            }

            @Override
            public org.objectweb.asm.AnnotationVisitor visitAnnotation(String name, String desc) {
                return this;
            }
        };
    }

    private static void check(String where, Object value, List<String> hits) {
        if (!(value instanceof String text)) return;
        for (String pattern : RELOCATED) {
            if (text.contains(pattern)) {
                hits.add(where + ": \"" + text + "\"");
                return;
            }
        }
    }
}
