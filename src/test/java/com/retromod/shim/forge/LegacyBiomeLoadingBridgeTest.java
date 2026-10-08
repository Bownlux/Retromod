/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.objectweb.asm.Opcodes.*;

/** A 1.18 mod's BiomeLoadingEvent handler is posted again through a Forge biome modifier. */
class LegacyBiomeLoadingBridgeTest {

    private static final String MOD_CLASS = "com/example/dragons/Dragons";
    private static final String HANDLER = "com/example/dragons/SpawnRegistration";

    @TempDir
    Path modRoot;

    @Test
    void subscribingModRegistersItsModifierAndNamesItInData() throws Exception {
        write(MOD_CLASS, modClass("dragons"));
        write(HANDLER, handler());

        assertEquals(1, LegacyBiomeLoadingBridge.wire(modRoot));

        MethodNode constructor = constructor(Files.readAllBytes(modRoot.resolve(MOD_CLASS + ".class")));
        MethodInsnNode first = (MethodInsnNode) constructor.instructions.getFirst();
        assertEquals(LegacyBiomeLoadingBridge.MODIFIER, first.owner);
        assertEquals("registerSerializer", first.name,
                "the serializer is registered while the mod's own bus and namespace are active");
        Path entry = modRoot.resolve("data/dragons/forge/biome_modifier/retromod_biome_loading.json");
        assertTrue(Files.readString(entry).contains("\"type\": \"dragons:retromod_biome_loading\""),
                "the data entry names the serializer in the mod's own namespace");
    }

    @Test
    void hostWithoutTheModifierLeavesTheModAlone() throws Exception {
        byte[] original = modClass("dragons");
        write(MOD_CLASS, original);
        write(HANDLER, handler());
        com.retromod.core.RetromodTransformer transformer = com.retromod.core.RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();

        assertEquals(0, LegacyBiomeLoadingBridge.wire(modRoot, transformer),
                "a call to an unregistered modifier would fail the mod's constructor");
        assertTrue(Arrays.equals(original, Files.readAllBytes(modRoot.resolve(MOD_CLASS + ".class"))));
    }

    @Test
    void modWithoutTheOldEventIsLeftAlone() throws Exception {
        byte[] original = modClass("plain");
        write(MOD_CLASS, original);

        assertEquals(0, LegacyBiomeLoadingBridge.wire(modRoot));
        assertTrue(Arrays.equals(original, Files.readAllBytes(modRoot.resolve(MOD_CLASS + ".class"))));
        assertFalse(Files.exists(modRoot.resolve("data")), "no biome modifier entry is written");
    }

    @Test
    void generatedClassesAreValid() {
        byte[] event = LegacyBiomeLoadingBridge.generateEvent();
        byte[] modifier = LegacyBiomeLoadingBridge.generateModifier();
        new ClassReader(event).accept(new CheckClassAdapter(new ClassWriter(0), false), 0);
        new ClassReader(modifier).accept(new CheckClassAdapter(new ClassWriter(0), false), 0);

        ClassNode eventNode = node(event);
        List<String> getters = eventNode.methods.stream().map(m -> m.name + m.desc).toList();
        assertTrue(getters.contains("getName()Lnet/minecraft/resources/ResourceLocation;"));
        assertTrue(getters.contains("getSpawns()Lnet/minecraftforge/common/world/MobSpawnSettingsBuilder;"));
        assertTrue(getters.contains(
                "getGeneration()Lnet/minecraftforge/common/world/BiomeGenerationSettingsBuilder;"));
        assertTrue(node(modifier).interfaces.contains("net/minecraftforge/common/world/BiomeModifier"));
    }

    private void write(String name, byte[] bytes) throws Exception {
        Path file = modRoot.resolve(name + ".class");
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
    }

    private static byte[] modClass(String modId) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, MOD_CLASS, null, "java/lang/Object", null);
        AnnotationVisitor mod = cw.visitAnnotation("Lnet/minecraftforge/fml/common/Mod;", true);
        mod.visit("value", modId);
        mod.visitEnd();
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A handler after the transform, already pointing at the stand-in event. */
    private static byte[] handler() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, HANDLER, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "onBiomeLoading",
                "(L" + LegacyBiomeLoadingBridge.EVENT + ";)V", null, null);
        mv.visitCode();
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static MethodNode constructor(byte[] bytes) {
        return node(bytes).methods.stream().filter(m -> m.name.equals("<init>")).findFirst().orElseThrow();
    }
}
