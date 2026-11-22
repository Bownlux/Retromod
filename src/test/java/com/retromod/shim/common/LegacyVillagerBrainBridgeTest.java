/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * Moonlight adds a memory to {@code Villager.MEMORY_TYPES} for Supplementaries' scared villagers. 26.1 folded that list
 * into the villager's brain provider, so NeoForge 26.3 stopped in common setup with {@code NoSuchFieldError}.
 */
class LegacyVillagerBrainBridgeTest {

    private static final Path COMPILED = Path.of("target", "legacyapi-classes");
    private static final String VILLAGER = LegacyVillagerBrainBridge.VILLAGER;
    private static final String PROVIDER = "net/minecraft/world/entity/ai/Brain$Provider";
    private static final String LIST = "Lcom/google/common/collect/ImmutableList;";

    private final String previousHost = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void reset() {
        RetromodVersion.TARGET_MC_VERSION = previousHost;
        RetromodTransformer.getInstance().setModSourceVersion(null);
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("a memory added to the old list reaches the brain provider every new villager is made from")
    void addedMemoryReachesTheProvider() throws Exception {
        HostStubs host = new HostStubs().add(provider()).add(villager())
                .add(LegacyVillagerBrainBridge.BRAIN, Files.readAllBytes(COMPILED.resolve(LegacyVillagerBrainBridge.BRAIN + ".class")));
        Class<?> brain = host.load(LegacyVillagerBrainBridge.BRAIN);

        Collection<?> before = (Collection<?>) brain.getMethod("memoryTypes").invoke(null);
        List<Object> added = new ArrayList<>(before);
        added.add("scared");
        brain.getMethod("setMemoryTypes", Collection.class).invoke(null, added);

        assertTrue(before.isEmpty());
        assertEquals(List.of("scared"), List.copyOf((Collection<?>) brain.getMethod("memoryTypes").invoke(null)));
        assertEquals(List.of("hearing"), List.copyOf((Collection<?>) brain.getMethod("sensorTypes").invoke(null)),
                "the sensors are left as they were");
    }

    @Test
    @DisplayName("an old mod's read and write of MEMORY_TYPES go through the rebuilt holder on a 26.3 host")
    void oldFieldAccessIsRedirected() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyVillagerBrainBridge.register(transformer);
        transformer.setModSourceVersion("1.21.1");

        ClassNode fixed = read(transformer.transformClass(registerMemory(), "mod/VillagerHooks"));

        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode insn : fixed.methods.get(0).instructions) {
            if (insn instanceof MethodInsnNode call && call.owner.equals(LegacyVillagerBrainBridge.BRAIN)) calls.add(call.name);
            if (insn instanceof FieldInsnNode field) fail("the old field is still accessed: " + field.name);
        }
        assertEquals(List.of("memoryTypes", "setMemoryTypes"), calls);
    }

    @Test
    @DisplayName("a 1.21.11 host, which still has the list, keeps the field")
    void hostWithTheListIsLeftAlone() {
        RetromodVersion.TARGET_MC_VERSION = "1.21.11";
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyVillagerBrainBridge.register(transformer);

        ClassNode kept = read(transformer.transformClass(registerMemory(), "mod/VillagerHooks"));

        assertTrue(kept.methods.get(0).instructions.size() > 0);
        boolean field = false;
        for (AbstractInsnNode insn : kept.methods.get(0).instructions) field |= insn instanceof FieldInsnNode;
        assertTrue(field);
    }

    /** {@code static void add(Object memory) { MEMORY_TYPES = ImmutableList.builder().addAll(MEMORY_TYPES).add(memory).build(); }} */
    private static byte[] registerMemory() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC, "mod/VillagerHooks", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "add", "(Ljava/lang/Object;)V", null, null);
        mv.visitCode();
        String builder = "com/google/common/collect/ImmutableList$Builder";
        mv.visitMethodInsn(INVOKESTATIC, "com/google/common/collect/ImmutableList", "builder", "()L" + builder + ";", false);
        mv.visitFieldInsn(GETSTATIC, VILLAGER, "MEMORY_TYPES", LIST);
        mv.visitMethodInsn(INVOKEVIRTUAL, builder, "addAll", "(Ljava/lang/Iterable;)L" + builder + ";", false);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, builder, "add", "(Ljava/lang/Object;)L" + builder + ";", false);
        mv.visitMethodInsn(INVOKEVIRTUAL, builder, "build", "()" + LIST, false);
        mv.visitFieldInsn(PUTSTATIC, VILLAGER, "MEMORY_TYPES", LIST);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code final class Brain$Provider { private final Collection memoryTypes = List.of(), sensorTypes = List.of("hearing"); }} */
    private static byte[] provider() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL, PROVIDER, null, "java/lang/Object", null);
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "memoryTypes", "Ljava/util/Collection;", null, null).visitEnd();
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "sensorTypes", "Ljava/util/Collection;", null, null).visitEnd();
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        // Like the real host, the villager's provider lists no memories of its own; mods' additions are all it holds.
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESTATIC, "java/util/List", "of", "()Ljava/util/List;", true);
        init.visitFieldInsn(PUTFIELD, PROVIDER, "memoryTypes", "Ljava/util/Collection;");
        init.visitVarInsn(ALOAD, 0);
        init.visitLdcInsn("hearing");
        init.visitMethodInsn(INVOKESTATIC, "java/util/List", "of", "(Ljava/lang/Object;)Ljava/util/List;", true);
        init.visitFieldInsn(PUTFIELD, PROVIDER, "sensorTypes", "Ljava/util/Collection;");
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code class Villager { private static final Brain.Provider BRAIN_PROVIDER = new Brain.Provider(); }} */
    private static byte[] villager() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, VILLAGER, null, "java/lang/Object", null);
        cw.visitField(ACC_PRIVATE | ACC_STATIC | ACC_FINAL, "BRAIN_PROVIDER", "L" + PROVIDER + ";", null, null).visitEnd();
        MethodVisitor clinit = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitTypeInsn(NEW, PROVIDER);
        clinit.visitInsn(DUP);
        clinit.visitMethodInsn(INVOKESPECIAL, PROVIDER, "<init>", "()V", false);
        clinit.visitFieldInsn(PUTSTATIC, VILLAGER, "BRAIN_PROVIDER", "L" + PROVIDER + ";");
        clinit.visitInsn(RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }
}
