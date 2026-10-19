/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.retromod.mapping.SrgToMojangMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * #283: the in-game Forge path reads the lower bound of the mod's minecraft range as its SRG
 * version. Scorched Guns declares {@code [1.20, 1.20.2)}, so the remap has to know 1.20's own
 * names: {@code m_9236_} is {@code level} there, as in 1.20.1, and only {@code getLevel} up to
 * 1.19.4.
 */
class ForgeSrgSourceVersionTest {

    private static final String ENTITY = "net/minecraft/world/entity/Entity";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();

    @BeforeEach
    void loadSrgNames() {
        transformer.clearRedirectsForTesting();
        SrgToMojangMapper.getInstance().applyTo(transformer);
    }

    @AfterEach
    void resetTransformer() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("a mod declaring [1.20, 1.20.2) calls level() and onGround(), not their 1.19.4 names")
    void minecraft120RangeKeepsThe120Names(@TempDir Path directory) throws Exception {
        Path input = directory.resolve("guns-fixture.jar");
        writeForgeMod(input, "[1.20, 1.20.2)");

        Path output = new ForgeModTransformer("1.21.1")
                .transformMod(input, Files.createDirectory(directory.resolve("out")));

        assertNotNull(output, "the fixture should be transformed");
        assertEquals(List.of("level", "onGround"), callNames(output),
                "1.20 names m_9236_ level and m_20096_ onGround, like 1.20.1");
    }

    private static void writeForgeMod(Path input, String minecraftRange) throws Exception {
        String metadata = """
                modLoader="javafml"
                loaderVersion="[46,)"
                license="MIT"
                [[mods]]
                modId="srg_source_version_test"
                version="1.0.0"
                [[dependencies.srg_source_version_test]]
                modId="forge"
                mandatory=true
                versionRange="[46,48)"
                ordering="NONE"
                side="BOTH"
                [[dependencies.srg_source_version_test]]
                modId="minecraft"
                mandatory=true
                versionRange="%s"
                ordering="NONE"
                side="BOTH"
                """.formatted(minecraftRange);
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(input))) {
            jar.putNextEntry(new JarEntry("test/Fixture.class"));
            jar.write(entityCalls());
            jar.closeEntry();
            jar.putNextEntry(new JarEntry("META-INF/mods.toml"));
            jar.write(metadata.getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
    }

    /** {@code test/Fixture.probe(Entity)}: {@code entity.m_9236_()} then {@code entity.m_20096_()}. */
    private static byte[] entityCalls() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/Fixture", null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "probe",
                "(L" + ENTITY + ";)V", null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ENTITY, "m_9236_",
                "()Lnet/minecraft/world/level/Level;", false);
        method.visitInsn(Opcodes.POP);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ENTITY, "m_20096_", "()Z", false);
        method.visitInsn(Opcodes.POP);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static List<String> callNames(Path jarPath) throws Exception {
        try (ZipFile jar = new ZipFile(jarPath.toFile());
             InputStream input = jar.getInputStream(jar.getEntry("test/Fixture.class"))) {
            ClassNode node = new ClassNode();
            new ClassReader(input.readAllBytes()).accept(node, 0);
            return node.methods.stream()
                    .flatMap(method -> Arrays.stream(method.instructions.toArray()))
                    .filter(MethodInsnNode.class::isInstance)
                    .map(instruction -> ((MethodInsnNode) instruction).name)
                    .toList();
        }
    }
}
