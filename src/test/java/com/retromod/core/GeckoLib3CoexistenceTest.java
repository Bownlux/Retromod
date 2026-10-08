/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.core;

import com.retromod.polyfill.thirdparty.GeckoLibBridgePolyfill;
import com.retromod.shim.api.common.GeckoLibApiShim;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A GeckoLib 3 mod keeps its own library, which has to load beside GeckoLib 4 (#311). */
class GeckoLib3CoexistenceTest {

    private static final String GECKOLIB3_TOML = """
            modLoader="javafml"
            loaderVersion="[40,)"
            [[mods]]
            modId="geckolib3"
            [[dependencies.geckolib3]]
                modId="minecraft"
                versionRange="[1.18, 1.19)"
            """;

    private static final String DEPENDENT_TOML = """
            [[mods]]
            modId="isleofberk"
            [[dependencies.isleofberk]]
            modId="geckolib3"
            versionRange="[3.0.40,)"
            """;

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void reset() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    void movesTheDemoPackageThatSplitsWithGeckoLib4(@TempDir Path jar) throws IOException {
        writeToml(jar, GECKOLIB3_TOML);
        writeClass(jar, "software/bernie/example/GeckoLibMod",
                classCalling("software/bernie/example/GeckoLibMod",
                        "software/bernie/example/registry/ItemRegistry"));
        writeClass(jar, "software/bernie/example/registry/ItemRegistry",
                classCalling("software/bernie/example/registry/ItemRegistry", "java/lang/Object"));
        Files.writeString(jar.resolve("software/bernie/example/GeckoLibMod.java"), "source");
        writeClass(jar, "software/bernie/geckolib3/GeckoLib",
                classCalling("software/bernie/geckolib3/GeckoLib", "java/lang/Object"));

        assertEquals(2, GeckoLib3Coexistence.relocateDemoPackage(jar),
                "both demo classes should move");

        assertFalse(Files.exists(jar.resolve("software/bernie/example")),
                "no entry may keep the package GeckoLib 4 also ships");
        Path moved = jar.resolve("software/bernie/geckolib3/example/GeckoLibMod.class");
        assertTrue(Files.isRegularFile(moved), "the @Mod entry point must survive the move");
        assertTrue(Files.isRegularFile(
                jar.resolve("software/bernie/geckolib3/example/GeckoLibMod.java")),
                "non-class files move with the package");
        List<String> refs = classReferences(Files.readAllBytes(moved));
        assertTrue(refs.contains("software/bernie/geckolib3/example/GeckoLibMod"),
                "the class must be renamed to its new package, saw " + refs);
        assertTrue(refs.contains("software/bernie/geckolib3/example/registry/ItemRegistry"),
                "references between demo classes must follow the move, saw " + refs);
        assertTrue(Files.isRegularFile(jar.resolve("software/bernie/geckolib3/GeckoLib.class")),
                "library classes stay where they are");
    }

    @Test
    void leavesModsThatOnlyDependOnGeckoLib3Alone(@TempDir Path jar) throws IOException {
        writeToml(jar, DEPENDENT_TOML);
        writeClass(jar, "software/bernie/example/Shaded",
                classCalling("software/bernie/example/Shaded", "java/lang/Object"));

        assertEquals(0, GeckoLib3Coexistence.relocateDemoPackage(jar),
                "a dependency block naming geckolib3 does not make the jar GeckoLib 3");
        assertTrue(Files.isRegularFile(jar.resolve("software/bernie/example/Shaded.class")));
        assertFalse(GeckoLib3Coexistence.isGeckoLib3ModsBlock(DEPENDENT_TOML));
        assertTrue(GeckoLib3Coexistence.isGeckoLib3ModsBlock(GECKOLIB3_TOML));
    }

    @Test
    void geckoLib3ReferencesAreNotRewrittenOntoGeckoLib4() {
        RetromodVersion.TARGET_MC_VERSION = "1.20.1";
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        new GeckoLibApiShim().registerRedirects(transformer);
        new GeckoLibBridgePolyfill().registerPolyfills(transformer);

        String dragon = "com/example/Dragon";
        byte[] transformed = transformer.transformClass(
                classCalling(dragon, "software/bernie/geckolib3/core/IAnimatable"), dragon);

        List<String> refs = classReferences(transformed);
        assertTrue(refs.contains("software/bernie/geckolib3/core/IAnimatable"),
                "a 3.x mod must keep calling the GeckoLib 3 it ships with, saw " + refs);
        assertFalse(refs.stream().anyMatch(ref -> ref.startsWith("software/bernie/geckolib/")),
                "nothing may be pointed at GeckoLib 4's different API, saw " + refs);
    }

    private static void writeToml(Path jar, String toml) throws IOException {
        Files.createDirectories(jar.resolve("META-INF"));
        Files.writeString(jar.resolve("META-INF/mods.toml"), toml, StandardCharsets.UTF_8);
    }

    private static void writeClass(Path jar, String name, byte[] bytes) throws IOException {
        Path file = jar.resolve(name + ".class");
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
    }

    /** A class with one static method that calls a static method on {@code target}. */
    private static byte[] classCalling(String name, String target) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "probe", "()V", null, null);
        method.visitCode();
        method.visitMethodInsn(Opcodes.INVOKESTATIC, target, "touch", "()V", false);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static List<String> classReferences(byte[] bytes) {
        List<String> refs = new ArrayList<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visit(int version, int access, String name, String signature,
                    String superName, String[] interfaces) {
                refs.add(name);
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String called,
                            String desc, boolean itf) {
                        refs.add(owner);
                    }
                };
            }
        }, 0);
        return refs;
    }
}
