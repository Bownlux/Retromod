/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;

import static com.retromod.testutil.RelocatedLibraryFixture.*;
import static org.junit.jupiter.api.Assertions.*;

/** The legacy command copies Retromod's shim classes into a mod naming the game's Gson and SLF4J. */
class LegacyShimCopyTest {

    /** Each Retromod class the command reads comes back as a helper naming the relocated libraries. */
    private static final Function<String, byte[]> RELOCATED_JAR = resource ->
            resource.startsWith("com/retromod/") && resource.endsWith(".class")
                    ? helper(resource.substring(0, resource.length() - ".class".length()), SHADED_SLF4J, SHADED_GSON)
                    : null;

    @Test
    @DisplayName("The shim classes added to a 1.12 mod name the game's libraries")
    void legacyTransformCopiesTheGamesNames(@TempDir Path directory) throws Exception {
        Path source = directory.resolve("slimy-1.0-1.12.2.jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(source))) {
            jar.putNextEntry(new JarEntry("mcmod.info"));
            jar.write("[{\"modid\":\"slimy\",\"version\":\"1.0\",\"mcversion\":\"1.12.2\"}]"
                    .getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        LegacyModSupport support = (LegacyModSupport) subclassServing(LegacyModSupport.class,
                "(Ljava/nio/file/Path;Ljava/lang/String;)V", RELOCATED_JAR)
                .getConstructor(Path.class, String.class).newInstance(directory, "26.3");

        Path output = support.transformMod(source, support.analyzeMod(source));

        assertCopiesNameTheGamesLibraries(Files.readAllBytes(output));
    }

    @Test
    @DisplayName("The virtual loader classes added to a 1.12 mod name the game's libraries")
    void virtualLoaderCopiesTheGamesNames() throws Exception {
        VirtualModLoader loader = (VirtualModLoader) subclassServing(VirtualModLoader.class, "()V", RELOCATED_JAR)
                .getConstructor().newInstance();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream out = new JarOutputStream(bytes)) {
            loader.embedComponents(out, LegacyModSupport.ModLoaderType.FORGE_LEGACY,
                    LegacyModSupport.Epoch.LEGACY_1_8_TO_1_12);
        }

        assertCopiesNameTheGamesLibraries(bytes.toByteArray());
    }

    private static void assertCopiesNameTheGamesLibraries(byte[] jarBytes) throws Exception {
        List<String> copies = new ArrayList<>();
        try (JarInputStream in = new JarInputStream(new ByteArrayInputStream(jarBytes))) {
            for (JarEntry entry; (entry = in.getNextJarEntry()) != null; ) {
                if (!entry.getName().startsWith("com/retromod/") || !entry.getName().endsWith(".class")) continue;
                copies.add(entry.getName());
                assertFalse(namesShadedLibrary(in.readAllBytes()),
                        entry.getName() + " still names Retromod's relocated libraries");
            }
        }
        assertFalse(copies.isEmpty(), "the command must copy at least one Retromod class");
    }
}
