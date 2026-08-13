/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exact-jar regression for ENGRAM's inherited Entity world calls (#179). */
class EngramInheritedWorldCallExactJarTest {

    private static final String MOD_SHA256 =
            "d202ed0b210717dc8f0dd6a0870f60d027aa1d497025462bb086e73fe8662f66";
    private static final String BLUEICE = "horror/blueice129/entity/Blueice129Entity";
    private static final String ENTITY = "net/minecraft/class_1297";
    private static final String WORLD_DESC = "()Lnet/minecraft/class_1937;";
    private static final String OLD_NAME = "method_37908";
    private static final String NEW_NAME = "method_73183";

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
        RetromodTransformer.getInstance().clearJarClassBytesProvider();
    }

    @Test
    @DisplayName("#179 exact jar: every inherited getWorld call uses the 1.21.11 Entity alias")
    void exactEngramEntityUsesCurrentWorldMethod() throws Exception {
        Path modJar = findModFixture();
        Path hostJar = findHostFixture();
        Assumptions.assumeTrue(modJar != null, "exact ENGRAM 0.8.0-beta fixture present");
        Assumptions.assumeTrue(hostJar != null, "Fabric 1.21.11 intermediary client jar present");
        assertEquals(MOD_SHA256, sha256(modJar), "fixture must be the reported ENGRAM build");

        RetromodVersion.TARGET_MC_VERSION = "1.21.11";
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        new Fabric_1_21_8_to_1_21_9().registerRedirects(transformer);

        try (ZipFile mod = new ZipFile(modJar.toFile());
             ZipFile host = new ZipFile(hostJar.toFile())) {
            byte[] original = readRequired(mod, BLUEICE + ".class");
            List<MethodCall> before = calls(original, OLD_NAME);
            assertEquals(10, before.size(),
                    "the exact class must retain all reported legacy calls");
            assertTrue(before.stream().allMatch(call -> BLUEICE.equals(call.owner())
                    && WORLD_DESC.equals(call.descriptor())),
                    "the reported bug requires subclass-owned calls with the exact legacy descriptor");

            try (var hierarchy = transformer.pushJarClassBytesProvider(name -> {
                byte[] modClass = readOptional(mod, name + ".class");
                return modClass != null ? modClass : readOptional(host, name + ".class");
            })) {
                byte[] output = transformer.transformClass(original, BLUEICE);
                List<MethodCall> stale = calls(output, OLD_NAME);
                assertTrue(stale.isEmpty(),
                        "no inherited method_37908 call may survive the transform: " + stale);
                List<MethodCall> repaired = calls(output, NEW_NAME);
                assertEquals(before.size(), repaired.size(),
                        "each reported legacy call must be repaired exactly once");
                assertTrue(repaired.stream().allMatch(call -> ENTITY.equals(call.owner())
                                && WORLD_DESC.equals(call.descriptor())),
                        "the repaired calls must link through Entity.method_73183");
            }
        }
    }

    private static List<MethodCall> calls(byte[] classBytes, String calledName) {
        List<MethodCall> calls = new ArrayList<>();
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName,
                            String methodDescriptor, boolean isInterface) {
                        if (calledName.equals(methodName)) {
                            calls.add(new MethodCall(owner, methodDescriptor));
                        }
                    }
                };
            }
        }, 0);
        return calls;
    }

    private static Path findModFixture() throws Exception {
        String home = System.getProperty("user.home", "");
        for (Path candidate : List.of(
                Path.of("test-jars-mixin/ENGRAM-0.8.0-beta.jar"),
                Path.of("/private/tmp/ENGRAM-0.8.0-beta.jar"),
                Path.of("/tmp/ENGRAM-0.8.0-beta.jar"),
                Path.of(home, "Library/Application Support/PrismLauncher/instances/1.21.11 Fabric",
                        "minecraft/retromod-backups/ENGRAM-0.8.0-beta-original.jar"))) {
            if (Files.isRegularFile(candidate) && MOD_SHA256.equals(sha256(candidate))) {
                return candidate;
            }
        }
        return null;
    }

    private static Path findHostFixture() {
        String home = System.getProperty("user.home", "");
        for (Path candidate : List.of(
                Path.of("test-jars-mixin/minecraft-1.21.11-client-intermediary.jar"),
                Path.of(home, "Library/Application Support/PrismLauncher/instances/1.21.11 Fabric",
                        "minecraft/.fabric/remappedJars/minecraft-1.21.11-0.19.2/client-intermediary.jar"))) {
            if (Files.isRegularFile(candidate)) return candidate;
        }
        return null;
    }

    private static byte[] readRequired(ZipFile zip, String name) throws IOException {
        byte[] bytes = readOptional(zip, name);
        if (bytes == null) throw new IOException("missing jar entry " + name);
        return bytes;
    }

    private static byte[] readOptional(ZipFile zip, String name) {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) return null;
        try (InputStream input = zip.getInputStream(entry)) {
            return input.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            for (int read; (read = input.read(buffer)) >= 0;) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private record MethodCall(String owner, String descriptor) {}
}
