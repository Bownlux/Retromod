/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards against a loader entry point dragging in another loader's classes.
 *
 * <p>This has bitten twice: {@code Retromod implements ModInitializer} (Fabric)
 * crashed Forge reading {@code TARGET_MC_VERSION} (fixed by {@link RetromodVersion}),
 * and beta.7 had {@code RetromodNeoForge}/{@code RetromodForge} call a helper on
 * {@code RetromodPreLaunch}, which {@code implements PreLaunchEntrypoint} (Fabric) -
 * so NeoForge/Forge crashed at load with `NoClassDefFoundError` even with no mods
 * (issue #40).
 *
 * <p>The check scans the compiled class's constant pool (where type references are
 * stored as {@code a/b/C} UTF-8 strings) for foreign-loader package names. ASCII
 * names survive byte-for-byte, so a substring search over the raw bytes is reliable.
 */
class LoaderIsolationTest {

    private static String constantPool(String internalName) throws IOException {
        try (InputStream in = LoaderIsolationTest.class
                .getResourceAsStream("/" + internalName + ".class")) {
            assertNotNull(in, "class not found on the test classpath: " + internalName);
            return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }

    private static ClassNode classNode(String internalName) throws IOException {
        try (InputStream in = LoaderIsolationTest.class
                .getResourceAsStream("/" + internalName + ".class")) {
            assertNotNull(in, "class not found on the test classpath: " + internalName);
            ClassNode node = new ClassNode();
            new ClassReader(in).accept(node, 0);
            return node;
        }
    }

    @Test
    @DisplayName("#40: NeoForge/Forge entry points reference no Fabric class or RetromodPreLaunch")
    void neoForgeForgeEntryPointsAreLoaderClean() throws IOException {
        for (String cls : new String[]{
                "com/retromod/core/RetromodNeoForge",
                "com/retromod/core/RetromodForge"}) {
            String cp = constantPool(cls);
            assertFalse(cp.contains("net/fabricmc/"),
                    cls + " must not reference any net.fabricmc class (loads on a non-Fabric loader)");
            assertFalse(cp.contains("RetromodPreLaunch"),
                    cls + " must not reference RetromodPreLaunch - it implements the Fabric-only "
                            + "PreLaunchEntrypoint, so loading it on NeoForge/Forge crashes (#40)");
        }
    }

    @Test
    @DisplayName("RetromodVersion carries no loader-specific reference (safe from any entry point)")
    void retromodVersionIsLoaderNeutral() throws IOException {
        String cp = constantPool("com/retromod/core/RetromodVersion");
        assertFalse(cp.contains("net/fabricmc/"), "RetromodVersion must stay Fabric-free");
        assertFalse(cp.contains("net/neoforged/"), "RetromodVersion must stay NeoForge-free");
        assertFalse(cp.contains("net/minecraftforge/"), "RetromodVersion must stay Forge-free");
    }

    @Test
    @DisplayName("Shared GUI code does not resolve the Fabric entry point")
    void sharedGuiDoesNotReferenceFabricEntrypoint() throws IOException {
        String fabricEntrypoint = "com/retromod/core/Retromod";
        for (String className : new String[] {
                "com/retromod/gui/RetromodGui",
                "com/retromod/gui/RetromodScreen",
                "com/retromod/gui/ModCompatibilityChecker"}) {
            ClassNode node = classNode(className);
            for (var method : node.methods) {
                for (var instruction : method.instructions) {
                    String owner = null;
                    if (instruction instanceof FieldInsnNode field) {
                        owner = field.owner;
                    } else if (instruction instanceof MethodInsnNode call) {
                        owner = call.owner;
                    } else if (instruction instanceof TypeInsnNode type) {
                        owner = type.desc;
                    }
                    assertFalse(fabricEntrypoint.equals(owner),
                            className + " must use RetromodVersion instead of the Fabric entry point");
                }
            }
        }
    }

    @Test
    @DisplayName("Forge entry points publish their own loader side to EnvironmentDetector")
    void forgeEntryPointsOwnLogicalSideDetection() throws IOException {
        String neoForge = constantPool("com/retromod/core/RetromodNeoForge");
        assertTrue(neoForge.contains("net.neoforged.fml.loading.FMLEnvironment"));
        assertTrue(neoForge.contains("setLoaderEnvironment"));
        assertFalse(neoForge.contains("net.minecraftforge.fml.loading.FMLEnvironment"));

        String forge = constantPool("com/retromod/core/RetromodForge");
        assertTrue(forge.contains("net.minecraftforge.fml.loading.FMLEnvironment"));
        assertTrue(forge.contains("setLoaderEnvironment"));
        assertFalse(forge.contains("net.neoforged.fml.loading.FMLEnvironment"));
    }

    @Test
    @DisplayName("Every loader entry point processes staged resource and data packs")
    void everyLoaderProcessesStagedPacks() throws IOException {
        for (String className : new String[] {
                "com/retromod/core/Retromod",
                "com/retromod/core/RetromodForge",
                "com/retromod/core/RetromodNeoForge"}) {
            String cp = constantPool(className);
            assertTrue(cp.contains("com/retromod/resources/ResourceManager"),
                    className + " must use the loader-neutral resource manager");
            assertTrue(cp.contains("processStagedPacks"),
                    className + " must process staged packs during startup");
        }
    }
}
