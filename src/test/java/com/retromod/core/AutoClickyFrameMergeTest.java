/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end guard for #180 (AutoClicky). The mod branches between two of its own screens and hands
 * the result to {@code Minecraft.setScreen(Screen)}. If frame computation types that merge as
 * {@code Object}, the JVM rejects the method with a {@code VerifyError} and the game dies at startup.
 *
 * <p>The hierarchy is {@code NewCombat extends OldCombat extends Screen}, so the shared type is
 * reachable from the mod's own jar without resolving any Minecraft class.
 */
class AutoClickyFrameMergeTest {

    private static final Path FIXTURE =
            Path.of("test-jars-mixin/autoclicky-1.2.1+mc1.20.5-1.21.1.jar");
    private static final String OWNER = "com/breelock/autoclicky/AutoClicky";
    private static final String LAMBDA = "lambda$onInitialize$0";

    private String savedVersion;

    @AfterEach
    void restore() {
        if (savedVersion != null) RetromodVersion.TARGET_MC_VERSION = savedVersion;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
        RetromodTransformer.getInstance().clearJarClassBytesProvider();
    }

    private static byte[] entry(Path jar, String name) throws Exception {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry e = zip.getEntry(name);
            assertNotNull(e, "missing " + name);
            try (InputStream in = zip.getInputStream(e)) {
                return in.readAllBytes();
            }
        }
    }

    /** Every reference type that appears on the operand stack of the lambda's frames. */
    private static List<String> stackTypes(byte[] classBytes, String methodName) {
        ClassNode cn = new ClassNode();
        new ClassReader(classBytes).accept(cn, ClassReader.EXPAND_FRAMES);
        List<String> types = new ArrayList<>();
        for (MethodNode m : cn.methods) {
            if (!methodName.equals(m.name)) continue;
            for (var insn : m.instructions.toArray()) {
                if (insn instanceof FrameNode f && f.stack != null) {
                    for (Object o : f.stack) {
                        if (o instanceof String s) types.add(s);
                    }
                }
            }
        }
        return types;
    }

    /** Unpacks the fixture's classes the way every loader path does before transforming them. */
    private static void unpack(Path jar, Path dir) throws Exception {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (ZipEntry e : java.util.Collections.list(zip.entries())) {
                if (!e.getName().endsWith(".class")) continue;
                Path out = dir.resolve(e.getName());
                Files.createDirectories(out.getParent());
                try (InputStream in = zip.getInputStream(e)) {
                    Files.write(out, in.readAllBytes());
                }
            }
        }
    }

    @Test
    @DisplayName("#180: wrapping the entrypoint must not widen a merge of the mod's own screens")
    void entrypointWrapKeepsTheSharedType(@TempDir Path dir) throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(FIXTURE), "autoclicky fixture present");
        savedVersion = RetromodVersion.TARGET_MC_VERSION;
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        unpack(FIXTURE, dir);

        // This is the pass that actually rebuilt the frame in the reported crash: it wraps
        // onInitialize in a try-catch and re-emits the class with recomputed frames.
        new FabricModTransformer("26.2").wrapEntrypoints(dir);

        List<String> after = stackTypes(Files.readAllBytes(dir.resolve(OWNER + ".class")), LAMBDA);
        assertFalse(after.contains("java/lang/Object"),
                "the entrypoint wrapper widened the merge to Object, which the verifier rejects "
                        + "when the value reaches setScreen(Screen). Stack types: " + after);
    }

    @Test
    @DisplayName("#180: two of a mod's own screens must not merge to Object")
    void modOwnedScreensKeepTheirSharedType(@TempDir Path dir) throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(FIXTURE), "autoclicky fixture present");
        savedVersion = RetromodVersion.TARGET_MC_VERSION;
        RetromodVersion.TARGET_MC_VERSION = "26.2";

        // The original jar carries the type javac computed, which proves the information exists.
        List<String> before = stackTypes(entry(FIXTURE, OWNER + ".class"), LAMBDA);
        assertFalse(before.contains("java/lang/Object"),
                "the mod as shipped does not merge to Object: " + before);

        // Unpack so the transformer can read the mod's own hierarchy, exactly as the loader paths do.
        try (ZipFile zip = new ZipFile(FIXTURE.toFile())) {
            for (ZipEntry e : java.util.Collections.list(zip.entries())) {
                if (!e.getName().endsWith(".class")) continue;
                Path out = dir.resolve(e.getName());
                Files.createDirectories(out.getParent());
                try (InputStream in = zip.getInputStream(e)) {
                    Files.write(out, in.readAllBytes());
                }
            }
        }

        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.setJarClassBytesProvider(name -> {
            try {
                Path cf = dir.resolve(name + ".class");
                return Files.exists(cf) ? Files.readAllBytes(cf) : null;
            } catch (Exception e) {
                return null;
            }
        });
        // One redirect is enough to make the transformer actually re-emit the class.
        transformer.registerClassRedirect("net/minecraft/class_437",
                "net/minecraft/client/gui/screens/Screen");

        byte[] out = transformer.transformClass(Files.readAllBytes(dir.resolve(OWNER + ".class")), OWNER);
        assertNotNull(out, "the class should be re-emitted");

        List<String> after = stackTypes(out, LAMBDA);
        assertFalse(after.contains("java/lang/Object"),
                "frame merge widened to Object, which the verifier rejects when the value is "
                        + "passed to setScreen(Screen). Stack types: " + after);
    }
}
