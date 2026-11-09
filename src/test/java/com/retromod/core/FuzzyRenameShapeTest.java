/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** A short name a few edits from another member's name is not auto-applied as its rename. */
class FuzzyRenameShapeTest {

    private static final String BLOCKS = "net/minecraft/world/level/block/Blocks";
    private static final String BLOCK = "Lnet/minecraft/world/level/block/Block;";
    private static final String ENTITY = "net/minecraft/world/entity/Entity";
    private static final String EFFECTS = "net/minecraft/world/level/biome/BiomeSpecialEffects";
    private static final String OPTIONAL = "()Ljava/util/Optional;";
    private static final String HEIGHT = "net/minecraft/world/level/LevelHeightAccessor";
    private static final String LEVEL = "net/minecraft/class_1937";

    @TempDir
    Path dir;

    private Path report;
    private FuzzyMethodResolver resolver;

    @BeforeEach
    void indexHost() throws Exception {
        report = dir.resolve("fuzzy-report.tsv");
        FuzzyMatchReport.setOutputFile(report);
        resolver = new FuzzyMethodResolver();
        resolver.indexJar(hostJar());
    }

    @AfterEach
    void parkReport() {
        FuzzyMatchReport.setOutputFile(dir.resolve("parked.tsv"));
    }

    @Test
    @DisplayName("Blocks.CHAIN becomes IRON_CHAIN, not AIR or RAIL three edits away")
    void chainIsNotAir() {
        FuzzyMethodResolver.FieldInfo match = resolver.resolveField(BLOCKS, "CHAIN", BLOCK);
        assertNotNull(match, "26.3 renamed the chain block to IRON_CHAIN");
        assertEquals("IRON_CHAIN", match.name(), "a chain must not turn into air");
    }

    @Test
    @DisplayName("Entity.kill() stays unresolved and is reported as a near miss for kill(ServerLevel)")
    void killIsNotTick() throws Exception {
        assertNull(resolver.resolveMethod(ENTITY, "kill", "()V"), "kill() must never run tick()");
        String rows = Files.readString(report);
        assertTrue(rows.contains("METHOD\tNEAR\t75\t" + ENTITY + "\tkill\t()V\t" + ENTITY + "\tkill\t"),
                "the report must name kill(ServerLevel) as the near miss:\n" + rows);
    }

    @Test
    @DisplayName("getFoliageColorOverride becomes foliageColorOverride, not dryFoliageColorOverride")
    void foliageIsNotDryFoliage() {
        FuzzyMethodResolver.MethodInfo match =
                resolver.resolveMethod(EFFECTS, "getFoliageColorOverride", OPTIONAL);
        assertNotNull(match);
        assertEquals("foliageColorOverride", match.name());
    }

    @Test
    @DisplayName("getMaxSection becomes getMaxSectionY, not getMinSectionY")
    void maxSectionIsNotMinSection() {
        FuzzyMethodResolver.MethodInfo match = resolver.resolveMethod(HEIGHT, "getMaxSection", "()I");
        assertNotNull(match);
        assertEquals("getMaxSectionY", match.name());
    }

    @Test
    @DisplayName("Two intermediary ids a few digits apart are different members")
    void intermediaryIdsAreNotRenames() {
        assertNull(resolver.resolveMethod(LEVEL, "method_8322", "()I"),
                "method_8322 must not become method_8615");
    }

    /** A host jar shaped like 26.3, with members declared in the host's own order. */
    private Path hostJar() throws Exception {
        Path jar = dir.resolve("host.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            ClassWriter blocks = open(BLOCKS, 0);
            for (String name : new String[]{"AIR", "RAIL", "CAKE", "IRON_CHAIN", "CHAIN_COMMAND_BLOCK"}) {
                blocks.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                        name, BLOCK, null, null).visitEnd();
            }
            write(out, BLOCKS, blocks);

            ClassWriter entity = open(ENTITY, 0);
            method(entity, "kill", "(Lnet/minecraft/server/level/ServerLevel;)V");
            method(entity, "tick", "()V");
            write(out, ENTITY, entity);

            ClassWriter effects = open(EFFECTS, 0);
            method(effects, "foliageColorOverride", OPTIONAL);
            method(effects, "dryFoliageColorOverride", OPTIONAL);
            write(out, EFFECTS, effects);

            ClassWriter height = open(HEIGHT, Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT);
            method(height, "getSectionsCount", "()I");
            method(height, "getMinSectionY", "()I");
            method(height, "getMaxSectionY", "()I");
            write(out, HEIGHT, height);

            ClassWriter level = open(LEVEL, 0);
            method(level, "method_8615", "()I");
            write(out, LEVEL, level);
        }
        return jar;
    }

    private static ClassWriter open(String name, int extraAccess) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | extraAccess, name, null, "java/lang/Object", null);
        return cw;
    }

    private static void method(ClassWriter cw, String name, String descriptor) {
        cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, name, descriptor, null, null).visitEnd();
    }

    private static void write(JarOutputStream out, String name, ClassWriter cw) throws Exception {
        cw.visitEnd();
        out.putNextEntry(new JarEntry(name + ".class"));
        out.write(cw.toByteArray());
        out.closeEntry();
    }
}
