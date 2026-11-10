/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.retromod.loaderapi.CliHarness;
import com.retromod.loaderapi.LoaderApiFixtures;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** The jars the corpus showed defining a class Retromod moves, through the CLI with the real registrations. */
class OwnLibraryCasesPathTest {

    private static final String USER = "lib/User";
    private static final String DEPENDENT = "dep/User";

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @TempDir
    Path dir;

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        ModVersionDetector.setDefaultPreferredLoader(null);
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            // Third-party names: the library keeps its own classes, its users still follow the move.
            "GeckoLib 4.4.9, forge-1.20.1, neoforge, 26.3, software/bernie/geckolib/animatable/GeoItem, kept",
            // EMI and Trinkets left this table: no EMI or Trinkets 3 release moved EmiRender or SlotType.
            "A Create 0.5.0 copy, forge-1.20.1, forge, 26.2, com/simibubi/create/foundation/tileEntity/SmartTileEntity, kept",
            "Sodium 0.5.11, fabric-1.21.1, fabric, 26.3, "
                    + "me/jellysquid/mods/sodium/client/render/chunk/compile/buffers/ChunkModelBuilder, kept",
            "Forge Config API Port 21.1.6 for Fabric, fabric-1.21.1, fabric, 26.3, "
                    + "fuzs/forgeconfigapiport/fabric/api/neoforge/v4/NeoForgeModConfigEvents, kept",
            "Replay Mod's own javax.annotation, fabric-1.21.1, fabric, 26.3, javax/annotation/Nullable, kept",
            // Host names: a port or a bundled copy follows its users, so its API keeps linking.
            "Forge Config API Port 21.1.6 for NeoForge, neoforge-1.21.1, neoforge, 26.3, "
                    + "net/minecraftforge/common/ForgeConfigSpec, moved",
            "Forgified Fabric API in a Forge mod, forge-1.20.1, neoforge, 26.3, "
                    + "net/fabricmc/fabric/api/object/builder/v1/block/FabricBlockSettings, moved"})
    @DisplayName("A jar that defines a moved class keeps it unless the name is the host's")
    void definingJarKeepsThirdPartyNames(String jarName, String metadata, String loader, String host,
            String definedClass, String expected) throws Exception {
        McReflect.setForceForge(loader.equals("forge"));
        McReflect.setForceNeoForge(loader.equals("neoforge"));
        ModVersionDetector.setDefaultPreferredLoader(loader.equals("fabric") ? null : loader);
        Path library = jar("library.jar", metadata, Map.of(definedClass, plainClass(definedClass),
                USER, user(USER, definedClass)));
        Path dependent = jar("dependent.jar", metadata, Map.of(DEPENDENT, user(DEPENDENT, definedClass)));

        Path libraryOut = dir.resolve("out/library.jar");
        Path dependentOut = dir.resolve("out/dependent.jar");
        Files.createDirectories(libraryOut.getParent());
        CliHarness.transform(library, libraryOut, host);
        CliHarness.transform(dependent, dependentOut, host);

        assertNotEquals("L" + definedClass + ";", fieldType(dependentOut, DEPENDENT),
                "a jar that only uses " + definedClass + " follows the move, so the move is active on " + host);
        if (expected.equals("kept")) {
            assertEquals("L" + definedClass + ";", fieldType(libraryOut, USER), jarName + " keeps its own class");
            assertEquals(definedClass, read(libraryOut, definedClass).name, jarName + "'s class file keeps its name");
        } else {
            assertEquals(fieldType(dependentOut, DEPENDENT), fieldType(libraryOut, USER),
                    jarName + " follows its users, so calls between them link");
        }
    }

    private Path jar(String name, String metadata, Map<String, byte[]> classes) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        String[] parts = metadata.split("-");
        String id = name.replace(".jar", "");
        if (parts[0].equals("fabric")) {
            entries.put("fabric.mod.json", ("{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"1.0.0\","
                    + "\"depends\":{\"minecraft\":\"" + parts[1] + "\",\"fabricloader\":\">=0.15.0\"}}")
                    .getBytes(StandardCharsets.UTF_8));
        } else {
            // A Forge mod ships mods.toml; a NeoForge mod from 1.20.5 on ships neoforge.mods.toml.
            String toml = parts[0].equals("neoforge") ? "META-INF/neoforge.mods.toml" : "META-INF/mods.toml";
            entries.put(toml, ("modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\nlicense=\"MIT\"\n"
                    + "[[mods]]\nmodId=\"" + id + "\"\nversion=\"1.0.0\"\n[[dependencies." + id + "]]\n"
                    + "modId=\"minecraft\"\ntype=\"required\"\nmandatory=true\nversionRange=\"[" + parts[1]
                    + ",)\"\nordering=\"NONE\"\nside=\"BOTH\"\n").getBytes(StandardCharsets.UTF_8));
        }
        classes.forEach((className, bytes) -> entries.put(className + ".class", bytes));
        return Files.write(dir.resolve(name), LoaderApiFixtures.jar(entries));
    }

    private static byte[] plainClass(String name) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", null);
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** A class holding the defined type in a static field, the plainest reference a move rewrites. */
    private static byte[] user(String name, String type) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", null);
        writer.visitField(ACC_PUBLIC | ACC_STATIC, "value", "L" + type + ";", null, null).visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static String fieldType(Path jar, String className) throws Exception {
        return read(jar, className).fields.get(0).desc;
    }

    private static ClassNode read(Path jar, String className) throws Exception {
        try (JarFile file = new JarFile(jar.toFile())) {
            var entry = file.getJarEntry(className + ".class");
            assertNotNull(entry, className + " is in " + jar.getFileName());
            ClassNode node = new ClassNode();
            new ClassReader(file.getInputStream(entry).readAllBytes()).accept(node, 0);
            return node;
        }
    }
}
