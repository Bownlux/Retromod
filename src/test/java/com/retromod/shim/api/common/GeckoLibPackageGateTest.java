/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.api.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.SyntheticLibraries;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** The GeckoLib 4 to com.geckolib moves follow the GeckoLib a mod will run with, not the Minecraft version alone. */
class GeckoLibPackageGateTest {

    private static final String GEO_ITEM = "software/bernie/geckolib/animatable/GeoItem";
    private static final String PROBE_PROPERTY = "retromod.test.geckolib.initialized";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @BeforeEach
    void clean() {
        transformer.clearRedirectsForTesting();
        System.clearProperty(PROBE_PROPERTY);
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        transformer.clearLinkedLibraries();
        transformer.clearRedirectsForTesting();
        System.clearProperty(PROBE_PROPERTY);
    }

    @Test
    @DisplayName("GeckoLib 5.4.5 on NeoForge 1.21.11 keeps software.bernie, so nothing moves")
    void geckoLib5WithTheOldPackageKeepsTheNames() {
        Set<String> geckoLib545 = Set.of(GeckoLib5ApiShim.OLD_MAIN_CLASS);
        assertEquals(0, GeckoLib5ApiShim.registerIfInstalledMoved(transformer, geckoLib545::contains, "1.21.11"));
        assertNull(transformer.getClassRedirects().get(GEO_ITEM), "SoniCraft and Bloodbathen crashed on this rename");
    }

    @Test
    @DisplayName("The installed GeckoLib beats the version table in both directions")
    void installedLibraryDecides() {
        Set<String> oldPackage = Set.of(GeckoLib5ApiShim.OLD_MAIN_CLASS);
        assertEquals(0, GeckoLib5ApiShim.registerIfInstalledMoved(transformer, oldPackage::contains, "26.3"),
                "a 26.3 game running a GeckoLib that still has software.bernie keeps those names");

        Set<String> newPackage = Set.of(GeckoLib5ApiShim.NEW_MAIN_CLASS);
        assertTrue(GeckoLib5ApiShim.registerIfInstalledMoved(transformer, newPackage::contains, "1.21.11") > 100,
                "a GeckoLib with only com.geckolib gets the moves whatever the table says");
        assertEquals("com/geckolib/animatable/GeoItem", transformer.getClassRedirects().get(GEO_ITEM));
    }

    @Test
    @DisplayName("A GeckoLib that still has the old package is not moved, even beside com.geckolib")
    void bothPackagesKeepTheNames() {
        Set<String> both = Set.of(GeckoLib5ApiShim.OLD_MAIN_CLASS, GeckoLib5ApiShim.NEW_MAIN_CLASS);
        assertEquals(0, GeckoLib5ApiShim.registerIfInstalledMoved(transformer, both::contains, "26.3"));
    }

    @ParameterizedTest(name = "no GeckoLib readable on {0}: moved")
    @ValueSource(strings = {"26.1", "26.1.2", "26.2", "26.3", "26.3.1"})
    @DisplayName("With no GeckoLib readable, the hosts whose published GeckoLib uses com.geckolib get the moves")
    void tableMovesForComGeckolibHosts(String host) {
        assertTrue(GeckoLib5ApiShim.registerIfInstalledMoved(transformer, name -> false, host) > 100, host);
    }

    @ParameterizedTest(name = "no GeckoLib readable on {0}: kept")
    @ValueSource(strings = {"1.21.5", "1.21.8", "1.21.10", "1.21.11", "26.4", "27.1", "", "unknown"})
    @DisplayName("With no GeckoLib readable, a software.bernie host or an unknown host keeps the names")
    void tableKeepsNamesElsewhere(String host) {
        assertEquals(0, GeckoLib5ApiShim.registerIfInstalledMoved(transformer, name -> false, host), host);
        assertFalse(GeckoLib5ApiShim.hostShipsNewPackage(null));
    }

    @Test
    @DisplayName("A mod built for GeckoLib 5 (Minecraft 1.21.5 or newer) keeps its software.bernie names")
    void geckoLib5ModsAreNeverMoved() {
        GeckoLib5ApiShim.registerIfInstalledMoved(transformer, name -> false, "26.3");
        try {
            transformer.setModSourceVersion("1.21.11");
            assertEquals(GEO_ITEM, transformer.classRedirectForCurrentMod(GEO_ITEM), "a GeckoLib 5.4 mod");
            transformer.setModSourceVersion("1.21.5");
            assertEquals(GEO_ITEM, transformer.classRedirectForCurrentMod(GEO_ITEM), "a GeckoLib 5.0 mod");
            transformer.setModSourceVersion("1.20.1");
            assertEquals("com/geckolib/animatable/GeoItem", transformer.classRedirectForCurrentMod(GEO_ITEM),
                    "a GeckoLib 4 mod");
            transformer.setModSourceVersion(null);
            assertEquals("com/geckolib/animatable/GeoItem", transformer.classRedirectForCurrentMod(GEO_ITEM),
                    "a mod of unknown version keeps the redirect, as it keeps every shim for its host");
        } finally {
            transformer.setModSourceVersion(null);
        }
    }

    @Test
    @DisplayName("The game's class loader is read without loading or initializing GeckoLib")
    void runtimeProbeReadsTheClassLoader(@TempDir Path dir) throws Exception {
        Path geckoLib = geckoLibJar(dir.resolve("geckolib-5.4.5.jar"), GeckoLib5ApiShim.OLD_MAIN_CLASS);
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        withContextLoader(geckoLib, () -> new GeckoLib5ApiShim().registerRedirects(transformer));

        assertNull(transformer.getClassRedirects().get(GEO_ITEM),
                "the GeckoLib on the class loader still has software.bernie, so the 26.3 table answer is overruled");
        assertNull(System.getProperty(PROBE_PROPERTY), "the probe must not run GeckoLib's static initializer");

        Path movedGeckoLib = geckoLibJar(dir.resolve("geckolib-5.5.7.jar"), GeckoLib5ApiShim.NEW_MAIN_CLASS);
        RetromodVersion.TARGET_MC_VERSION = "1.21.11";
        withContextLoader(movedGeckoLib, () -> new GeckoLib5ApiShim().registerRedirects(transformer));
        assertEquals("com/geckolib/animatable/GeoItem", transformer.getClassRedirects().get(GEO_ITEM));
        assertNull(System.getProperty(PROBE_PROPERTY), "the probe must not run GeckoLib's static initializer");
    }

    @Test
    @DisplayName("Offline, a GeckoLib among the linked libraries decides")
    void offlineProbeReadsLinkedLibraries(@TempDir Path dir) throws Exception {
        SyntheticLibraries libraries = SyntheticLibraries.create();
        libraries.add(geckoLibJar(dir.resolve("geckolib-neoforge-1.21.11-5.4.5.jar"), GeckoLib5ApiShim.OLD_MAIN_CLASS));
        transformer.setLinkedLibraries(libraries);
        RetromodVersion.TARGET_MC_VERSION = "26.3";

        new GeckoLib5ApiShim().registerRedirects(transformer);

        assertNull(transformer.getClassRedirects().get(GEO_ITEM), "the batch's own GeckoLib still has software.bernie");
    }

    @Test
    @DisplayName("Moves the version table chose yield to a GeckoLib 4 the mod is translated with")
    void tableMovesYieldToAGeckoLibInThePass(@TempDir Path dir) throws Exception {
        GeckoLib5ApiShim.registerIfInstalledMoved(transformer, name -> false, "26.3");
        SyntheticLibraries libraries = SyntheticLibraries.create();
        libraries.add(geckoLibJar(dir.resolve("geckolib-forge-1.20.1-4.4.9.jar"), GeckoLib5ApiShim.OLD_MAIN_CLASS));

        transformer.setModSourceVersion("1.20.1");
        assertEquals("com/geckolib/animatable/GeoItem", transformer.classRedirectForCurrentMod(GEO_ITEM),
                "a GeckoLib 4 mod translated alone follows the 26.3 table");
        transformer.setLinkedLibraries(libraries);
        assertEquals(GEO_ITEM, transformer.classRedirectForCurrentMod(GEO_ITEM),
                "Bloodbathen translated after GeckoLib 4 keeps the names GeckoLib 4 still has");
        transformer.setModSourceVersion(null);
    }

    @Test
    @DisplayName("Moves an installed com.geckolib GeckoLib chose do not yield to a GeckoLib 4 in the pass")
    void installedMovesDoNotYield(@TempDir Path dir) throws Exception {
        Set<String> newPackage = Set.of(GeckoLib5ApiShim.NEW_MAIN_CLASS);
        GeckoLib5ApiShim.registerIfInstalledMoved(transformer, newPackage::contains, "26.3");
        SyntheticLibraries libraries = SyntheticLibraries.create();
        libraries.add(geckoLibJar(dir.resolve("geckolib-forge-1.20.1-4.4.9.jar"), GeckoLib5ApiShim.OLD_MAIN_CLASS));

        transformer.setModSourceVersion("1.20.1");
        transformer.setLinkedLibraries(libraries);
        assertEquals("com/geckolib/animatable/GeoItem", transformer.classRedirectForCurrentMod(GEO_ITEM),
                "the game runs the GeckoLib 5.5 it loaded, so the mod follows it");
        transformer.setModSourceVersion(null);
    }

    /** A one-class jar standing in for GeckoLib, whose static initializer marks a system property. */
    static Path geckoLibJar(Path jar, String mainClass) throws Exception {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, mainClass, null, "java/lang/Object", null);
        MethodVisitor init = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        init.visitCode();
        init.visitLdcInsn(PROBE_PROPERTY);
        init.visitLdcInsn("yes");
        init.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "setProperty",
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", false);
        init.visitInsn(Opcodes.POP);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        writer.visitEnd();
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry(mainClass + ".class"));
            out.write(writer.toByteArray());
            out.closeEntry();
        }
        return jar;
    }

    private static void withContextLoader(Path jar, Runnable action) throws Exception {
        Thread thread = Thread.currentThread();
        ClassLoader saved = thread.getContextClassLoader();
        try (URLClassLoader loader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, saved)) {
            thread.setContextClassLoader(loader);
            action.run();
        } finally {
            thread.setContextClassLoader(saved);
        }
    }
}
