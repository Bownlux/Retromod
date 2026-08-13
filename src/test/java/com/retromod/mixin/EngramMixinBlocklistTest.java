/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mixin;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exact and synthetic coverage for ENGRAM's delayed screenshot crash (#179). */
class EngramMixinBlocklistTest {

    private static final String ACCESSOR =
            "horror/blueice129/mixin/client/GameRendererAccessor";
    private static final String RENDER_MIXIN =
            "horror/blueice129/mixin/client/GameRendererMixin";
    private static final String EXACT_SHA256 =
            "d202ed0b210717dc8f0dd6a0870f60d027aa1d497025462bb086e73fe8662f66";

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        MixinBlocklist.resetForTesting();
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("#179: ENGRAM screenshot mixins are disabled only on the affected 1.21.11 host")
    void bundledRulesAreTargetScoped() {
        RetromodVersion.TARGET_MC_VERSION = "1.21.11";
        MixinBlocklist.resetForTesting();
        assertTrue(MixinBlocklist.isFullStrip(ACCESSOR));
        assertTrue(MixinBlocklist.isFullStrip(RENDER_MIXIN));

        RetromodVersion.TARGET_MC_VERSION = "1.21.10";
        assertFalse(MixinBlocklist.isFullStrip(ACCESSOR));
        assertFalse(MixinBlocklist.isFullStrip(RENDER_MIXIN));
        assertNull(MixinBlocklist.methodsToStrip(ACCESSOR));

        RetromodVersion.TARGET_MC_VERSION = "26.1";
        assertFalse(MixinBlocklist.isFullStrip(ACCESSOR));
        assertFalse(MixinBlocklist.isFullStrip(RENDER_MIXIN));
    }

    @Test
    @DisplayName("#179 exact jar: both ENGRAM screenshot mixins are neutralized consistently")
    void exactEngramJarNeutralizesScreenshotPair() throws Exception {
        Path jar = findExactFixture();
        Assumptions.assumeTrue(jar != null, "exact ENGRAM 0.8.0-beta fixture present");
        assertEquals(EXACT_SHA256, sha256(jar), "fixture must be the reported ENGRAM build");

        RetromodVersion.TARGET_MC_VERSION = "1.21.11";
        MixinBlocklist.resetForTesting();
        MixinCompatibilityTransformer transformer =
                new MixinCompatibilityTransformer(RetromodTransformer.getInstance());

        for (String mixin : List.of(ACCESSOR, RENDER_MIXIN)) {
            byte[] output = transformer.transformMixinClass(readEntry(jar, mixin + ".class"));
            ClassNode node = new ClassNode();
            new ClassReader(output).accept(node, 0);
            AnnotationNode annotation = mixinAnnotation(node);
            assertFalse(hasValue(annotation, "value"), mixin + " kept its original class target");
            assertEquals(List.of("retromod/stripped/" + simpleName(mixin)),
                    value(annotation, "targets"), mixin + " was not neutralized");
        }
    }

    private static Path findExactFixture() throws Exception {
        String home = System.getProperty("user.home", "");
        List<Path> candidates = List.of(
                Path.of("test-jars-mixin/ENGRAM-0.8.0-beta.jar"),
                Path.of("/private/tmp/ENGRAM-0.8.0-beta.jar"),
                Path.of("/tmp/ENGRAM-0.8.0-beta.jar"),
                Path.of(home, "Library/Application Support/PrismLauncher/instances/1.21.11 Fabric",
                        "minecraft/retromod-backups/ENGRAM-0.8.0-beta-original.jar"));
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate) && EXACT_SHA256.equals(sha256(candidate))) {
                return candidate;
            }
        }
        return null;
    }

    private static byte[] readEntry(Path jar, String name) throws Exception {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry(name);
            assertTrue(entry != null, "missing exact-jar class " + name);
            try (InputStream input = zip.getInputStream(entry)) {
                return input.readAllBytes();
            }
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

    private static AnnotationNode mixinAnnotation(ClassNode node) {
        for (List<AnnotationNode> annotations : List.of(
                node.visibleAnnotations != null ? node.visibleAnnotations : List.<AnnotationNode>of(),
                node.invisibleAnnotations != null ? node.invisibleAnnotations : List.<AnnotationNode>of())) {
            for (AnnotationNode annotation : annotations) {
                if ("Lorg/spongepowered/asm/mixin/Mixin;".equals(annotation.desc)) {
                    return annotation;
                }
            }
        }
        throw new AssertionError("class has no @Mixin annotation: " + node.name);
    }

    private static boolean hasValue(AnnotationNode annotation, String key) {
        if (annotation.values == null) return false;
        for (int i = 0; i < annotation.values.size(); i += 2) {
            if (key.equals(annotation.values.get(i))) return true;
        }
        return false;
    }

    private static Object value(AnnotationNode annotation, String key) {
        if (annotation.values == null) return null;
        for (int i = 0; i < annotation.values.size(); i += 2) {
            if (key.equals(annotation.values.get(i))) return annotation.values.get(i + 1);
        }
        return null;
    }

    private static String simpleName(String internalName) {
        return internalName.substring(internalName.lastIndexOf('/') + 1);
    }
}
