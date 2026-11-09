/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.cli;

import com.retromod.core.ModVersionDetector;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.loaderapi.CliHarness;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code transform} keeps a Forge mod that builds a loot condition type with its old serializer. */
class CliTransformLootStandInTest {

    private static final String LOOT = "net/minecraft/world/level/storage/loot/";
    private static final String SERIALIZER = LOOT + "Serializer";
    private static final String CONDITION_TYPE = LOOT + "predicates/LootItemConditionType";
    private static final String MOD_CLASS = "testmod/ModLoot";
    // The factory that replaces the old constructor builds both wrapper types, which 26.1 deleted.
    private static final String TYPES = "com/retromod/generated/LegacyLootTypes";
    private static final String FUNCTION_STAND_IN = "com/retromod/shim/common/embedded/LootItemFunctionType";
    private static final String CONDITION_STAND_IN = "com/retromod/shim/common/embedded/LootItemConditionType";

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({"forge, 26.2", "forge, 26.3", "neoforge, 26.3"})
    @DisplayName("A Forge 1.20.1 mod that makes only loot conditions carries both loot type stand-ins")
    void conditionOnlyModCarriesBothStandIns(String loader, String target, @TempDir Path dir)
            throws Exception {
        Path input = dir.resolve("lootmod.jar");
        writeModJar(input);
        Path output = dir.resolve("out.jar");

        transform(input, output, target, loader);

        assertTrue(Files.isRegularFile(output), "transform must keep the output jar");
        Map<String, byte[]> classes = classes(output);
        String base = SyntheticEmbedder.embeddedBase(input.getFileName().toString());
        for (String standIn : new String[]{TYPES, FUNCTION_STAND_IN, CONDITION_STAND_IN}) {
            assertTrue(classes.containsKey(base + standIn), base + standIn + " must be embedded");
        }
        assertTrue(referencedNames(classes.get(base + TYPES)).contains(base + FUNCTION_STAND_IN),
                "the embedded factory must build the embedded function type");
        for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
            Set<String> names = referencedNames(entry.getValue());
            for (String shared : new String[]{TYPES, FUNCTION_STAND_IN, CONDITION_STAND_IN}) {
                assertFalse(names.contains(shared), entry.getKey() + " still names " + shared);
            }
        }
    }

    /** Runs {@code transform} as {@code main} would with {@code --target-loader <loader>}. */
    private static void transform(Path input, Path output, String target, String loader)
            throws Exception {
        McReflect.setForceNeoForge("neoforge".equals(loader));
        McReflect.setForceForge("forge".equals(loader));
        ModVersionDetector.setDefaultPreferredLoader(loader);
        try {
            CliHarness.transform(input, output, target);
        } finally {
            McReflect.setForceNeoForge(false);
            McReflect.setForceForge(false);
            ModVersionDetector.setDefaultPreferredLoader(null);
        }
    }

    /** A Forge 1.20.1 jar whose one class builds a condition type around its own serializer. */
    private static void writeModJar(Path jar) throws Exception {
        String toml = "modLoader=\"javafml\"\nloaderVersion=\"[47,)\"\nlicense=\"MIT\"\n"
                + "[[mods]]\nmodId=\"lootmod\"\nversion=\"1.0.0\"\n"
                + "[[dependencies.lootmod]]\nmodId=\"forge\"\nmandatory=true\nversionRange=\"[47,)\"\n"
                + "[[dependencies.lootmod]]\nmodId=\"minecraft\"\nmandatory=true\n"
                + "versionRange=\"[1.20.1,1.21)\"\n";
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("META-INF/mods.toml"));
            out.write(toml.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new JarEntry(MOD_CLASS + ".class"));
            out.write(conditionTypeFactory());
            out.closeEntry();
        }
    }

    /** {@code static LootItemConditionType count(Serializer s) { return new LootItemConditionType(s); }} */
    private static byte[] conditionTypeFactory() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, MOD_CLASS, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "count",
                "(L" + SERIALIZER + ";)L" + CONDITION_TYPE + ";", null, null);
        method.visitCode();
        method.visitTypeInsn(Opcodes.NEW, CONDITION_TYPE);
        method.visitInsn(Opcodes.DUP);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, CONDITION_TYPE, "<init>",
                "(L" + SERIALIZER + ";)V", false);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static Map<String, byte[]> classes(Path jar) throws Exception {
        Map<String, byte[]> classes = new LinkedHashMap<>();
        try (JarFile file = new JarFile(jar.toFile())) {
            for (JarEntry entry : java.util.Collections.list(file.entries())) {
                String name = entry.getName();
                if (name.endsWith(".class")) {
                    classes.put(name.substring(0, name.length() - ".class".length()),
                            file.getInputStream(entry).readAllBytes());
                }
            }
        }
        return classes;
    }

    private static Set<String> referencedNames(byte[] bytes) {
        Set<String> names = new HashSet<>();
        new ClassReader(bytes).accept(new ClassRemapper(new ClassWriter(0), new Remapper() {
            @Override
            public String map(String internalName) {
                names.add(internalName);
                return internalName;
            }
        }), 0);
        return names;
    }
}
