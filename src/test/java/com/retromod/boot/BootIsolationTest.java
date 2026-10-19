/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.boot;

import com.retromod.boot.forge.ForgeBootLocator;
import com.retromod.boot.neoforge.LegacyNeoForgeBootLocator;
import com.retromod.boot.neoforge.NeoForgeBootLocator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The bootstrap classes run in the loader's service layer or early-service class loader,
 * before Minecraft or any mod is loaded. Three rules keep them safe there:
 *
 * <ul>
 *   <li>They reference only the JDK and their own generation's loader SPI. Any other Retromod
 *       package would split a package with the runtime jar on modlauncher hosts, and on
 *       NeoForge 21.9 and newer {@code ClassLoadingGuardian} rejects game-content packages
 *       loaded by a parent of the game class loader.</li>
 *   <li>They never name a Minecraft class, even reflectively (pitfall 14: probing a Minecraft
 *       class at discovery time initializes it too early).</li>
 *   <li>They never ship in the runtime jar, and the runtime jar never declares a loader
 *       service (#90, #102).</li>
 * </ul>
 *
 * These checks read the compiled {@code target/boot-classes}, the exact files build-all.sh
 * packages.
 */
class BootIsolationTest {

    private static final Path BOOT_CLASSES = Path.of("target", "boot-classes");
    private static final Path RUNTIME_CLASSES = Path.of("target", "classes");

    private static List<Path> bootClassFiles() throws IOException {
        Path root = BOOT_CLASSES.resolve("com/retromod/boot");
        assertTrue(Files.isDirectory(root),
                "target/boot-classes is missing; the boot-compile execution in pom.xml must run "
                        + "before tests");
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(f -> f.toString().endsWith(".class")).sorted().toList();
        }
    }

    private static String internalName(Path classFile) {
        String relative = BOOT_CLASSES.relativize(classFile).toString().replace('\\', '/');
        return relative.substring(0, relative.length() - ".class".length());
    }

    /** Every type a class names in its signatures, members, instructions, and constants. */
    private static Set<String> referencedTypes(byte[] bytes) {
        Set<String> types = new TreeSet<>();
        Remapper recorder = new Remapper() {
            @Override
            public String map(String internalName) {
                types.add(internalName);
                return internalName;
            }
        };
        new ClassReader(bytes).accept(new ClassRemapper(new ClassVisitor(Opcodes.ASM9) { },
                recorder), 0);
        return types;
    }

    private static List<String> stringConstants(byte[] bytes) {
        List<String> constants = new ArrayList<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitLdcInsn(Object value) {
                        if (value instanceof String text) constants.add(text);
                    }
                };
            }
        }, 0);
        return constants;
    }

    private static boolean isAllowedJdkType(String type) {
        if (type.startsWith("java/awt/") || type.startsWith("javax/swing/")
                || type.startsWith("java/applet/")) {
            return false;
        }
        return type.startsWith("java/") || type.startsWith("javax/");
    }

    @Test
    @DisplayName("bootstrap classes reference only the JDK and their own generation's SPI")
    void bootClassesStayJdkAndSpiOnly() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : bootClassFiles()) {
            String owner = internalName(file);
            String allowedSpi;
            if (owner.startsWith("com/retromod/boot/forge/")) {
                allowedSpi = "net/minecraftforge/forgespi/";
            } else if (owner.startsWith("com/retromod/boot/neoforge/")) {
                allowedSpi = "net/neoforged/neoforgespi/";
            } else {
                allowedSpi = null;
            }
            for (String type : referencedTypes(Files.readAllBytes(file))) {
                boolean allowed = isAllowedJdkType(type)
                        || type.startsWith("com/retromod/boot/")
                        || (allowedSpi != null && type.startsWith(allowedSpi));
                if (!allowed) violations.add(owner + " -> " + type);
            }
        }
        assertEquals(List.of(), violations,
                "bootstrap code may use only the JDK and its generation's loader SPI");
    }

    @Test
    @DisplayName("shared bootstrap code names no loader type at all")
    void sharedBootCodeIsJdkOnly() throws IOException {
        for (Path file : bootClassFiles()) {
            String owner = internalName(file);
            if (owner.substring("com/retromod/boot/".length()).contains("/")) continue;
            for (String type : referencedTypes(Files.readAllBytes(file))) {
                assertTrue(isAllowedJdkType(type) || type.startsWith("com/retromod/boot/"),
                        owner + " must stay JDK-only but references " + type);
            }
        }
    }

    @Test
    @DisplayName("bootstrap code never names a Minecraft class, even as a reflective string")
    void bootClassesNeverNameMinecraftClasses() throws IOException {
        for (Path file : bootClassFiles()) {
            for (String constant : stringConstants(Files.readAllBytes(file))) {
                // A bare package prefix such as "net.minecraft." names no class. The engine
                // class loader uses those to refuse game packages outright.
                if (constant.endsWith(".")) continue;
                assertFalse(constant.startsWith("net.minecraft.")
                                || constant.startsWith("net/minecraft/")
                                || constant.startsWith("com.mojang.")
                                || constant.startsWith("com/mojang/"),
                        internalName(file) + " names " + constant);
            }
        }
    }

    @Test
    @DisplayName("the runtime jar never carries bootstrap classes or a loader service")
    void runtimeOutputHasNoBootstrapOrLoaderService() throws IOException {
        assertFalse(Files.exists(RUNTIME_CLASSES.resolve("com/retromod/boot")),
                "com.retromod.boot must never compile into the runtime jar");
        Path services = RUNTIME_CLASSES.resolve("META-INF/services");
        if (!Files.isDirectory(services)) return;
        Set<String> loaderServices = Set.of(
                "cpw.mods.modlauncher.api.ITransformationService",
                "net.minecraftforge.forgespi.locating.IModLocator",
                "net.minecraftforge.forgespi.locating.IDependencyLocator",
                "net.neoforged.neoforgespi.locating.IModLocator",
                "net.neoforged.neoforgespi.locating.IDependencyLocator",
                "net.neoforged.neoforgespi.locating.IModFileCandidateLocator",
                "net.neoforged.neoforgespi.locating.IModFileReader");
        try (Stream<Path> files = Files.list(services)) {
            for (Path service : files.toList()) {
                assertFalse(loaderServices.contains(service.getFileName().toString()),
                        "the runtime jar declares " + service.getFileName()
                                + ", which hides RetromodForge and RetromodNeoForge (#90, #102)");
            }
        }
    }

    @Test
    @DisplayName("the packaging script registers each locator for the generation it checks")
    void packagingScriptMatchesLocatorGenerations() throws IOException {
        String script = Files.readString(Path.of("scripts", "loader_bootstrap.py"),
                StandardCharsets.UTF_8).replaceAll("\\s+", " ");
        assertTrue(script.contains("\"" + ForgeBootLocator.GENERATION + "\": ( "
                + "\"net.minecraftforge.forgespi.locating.IModLocator\", "
                + "\"" + ForgeBootLocator.class.getName() + "\","));
        assertTrue(script.contains("\"" + LegacyNeoForgeBootLocator.GENERATION + "\": ( "
                + "\"net.neoforged.neoforgespi.locating.IModLocator\", "
                + "\"" + LegacyNeoForgeBootLocator.class.getName() + "\","));
        assertTrue(script.contains("\"" + NeoForgeBootLocator.GENERATION + "\": ( "
                + "\"net.neoforged.neoforgespi.locating.IModFileCandidateLocator\", "
                + "\"" + NeoForgeBootLocator.class.getName() + "\","));
    }
}
