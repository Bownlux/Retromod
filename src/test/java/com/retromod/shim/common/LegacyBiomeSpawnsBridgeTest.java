/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.VersionShim;
import com.retromod.shim.forge.Forge_1_21_4_to_1_21_5;
import com.retromod.shim.neoforge.NeoForge_1_21_4_to_1_21_5;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** A biome modifier that adds spawns through getSpawner reaches the weighted builder on 1.21.5 and newer. */
class LegacyBiomeSpawnsBridgeTest {

    private static final String DATA = "net/minecraft/world/level/biome/MobSpawnSettings$SpawnerData";
    private static final String TYPE = "Lnet/minecraft/world/entity/EntityType;";
    private static final String CATEGORY = "Lnet/minecraft/world/entity/MobCategory;";
    private static final String VANILLA_BUILDER = "net/minecraft/world/level/biome/MobSpawnSettings$Builder";
    private static final String HELPER = LegacyBiomeSpawnsBridge.HELPER;
    private static final String CALLS = LegacyBiomeSpawnsBridge.CALLS;

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("a Cataclysm-shaped biome modifier is rebuilt for NeoForge's and Forge's builder")
    void biomeModifierIsBridgedForBothLoaders() {
        for (String owner : LegacyBiomeSpawnsBridge.LOADER_BUILDERS) {
            RetromodTransformer transformer = RetromodTransformer.getInstance();
            transformer.clearRedirectsForTesting();
            LegacyBiomeSpawnsBridge.registerRedirects(transformer);
            LegacyBiomeSpawnsBridge.registerSpawnerListRedirect(transformer, owner);
            List<String> out = trace(transformer.transformClass(biomeModifier(owner), "mod/Spawns"));

            assertTrue(out.contains("static " + HELPER + ".spawnerList"), owner + ": " + out);
            assertTrue(out.contains("static " + CALLS + ".spawnerData"), "the four-int entry is gone: " + out);
            assertTrue(out.contains("interface java/util/List.add"), "the mod's own add stays: " + out);
            assertTrue(out.contains("static " + CALLS + ".addSpawn"), owner + " addSpawn(category, entry): " + out);
            assertTrue(out.contains("virtual " + owner + ".getSpawner"),
                    "a 1.21.5 call that returns the weighted builder is left alone: " + out);
            assertTrue(out.contains("virtual " + DATA + ".type"), "the private record field is read through type(): " + out);
            assertFalse(out.contains("new " + DATA), out.toString());
        }
    }

    @Test
    @DisplayName("the bridge applies to the 1.21.5 and 26.3 entry shapes, not to the weighted one")
    void appliesToReadsTheEntryShape() {
        assertTrue(LegacyBiomeSpawnsBridge.appliesTo(data("(" + TYPE + "II)V")), "1.21.5 to 26.2");
        assertTrue(LegacyBiomeSpawnsBridge.appliesTo(
                data("(" + TYPE + "Lnet/minecraft/util/valueproviders/IntProvider;)V")), "26.3");
        assertFalse(LegacyBiomeSpawnsBridge.appliesTo(data("(" + TYPE + "III)V", "(" + TYPE + "II)V")),
                "a host that still takes a weight keeps the old constructor");
    }

    @Test
    @DisplayName("the 1.21.5 loader shims register the bridge on a NeoForge 21.11-shaped host")
    void loaderShimsRegisterOnA1215Host() {
        String neoBuilder = LegacyBiomeSpawnsBridge.LOADER_BUILDERS[0];
        String neoOverrides = LegacyBiomeSpawnsBridge.STRUCTURE_OVERRIDES[0];
        Map<String, byte[]> host = Map.of(
                DATA, classWith(DATA, "<init>", "(" + TYPE + "II)V"),
                neoBuilder, classWith(neoBuilder, "getSpawner",
                        "(" + CATEGORY + ")Lnet/minecraft/util/random/WeightedList$Builder;"),
                neoOverrides, classWith(neoOverrides, "addSpawn", "(L" + DATA + ";I)V"));
        for (VersionShim shim : List.of(new NeoForge_1_21_4_to_1_21_5(), new Forge_1_21_4_to_1_21_5())) {
            RetromodTransformer transformer = RetromodTransformer.getInstance();
            transformer.clearRedirectsForTesting();
            withHost(host, () -> shim.registerRedirects(transformer));
            List<String> out = trace(transformer.transformClass(biomeModifier(neoBuilder), "mod/Spawns"));
            String name = shim.getShimName();

            assertTrue(out.contains("static " + HELPER + ".spawnerList"), name + ": " + out);
            assertTrue(out.contains("static " + CALLS + ".spawnerData"), name + ": " + out);
            assertTrue(out.contains("static " + HELPER + ".addStructureSpawn"),
                    name + ": NeoForge 21.11 dropped the structure override's addSpawn(entry): " + out);
        }
    }

    @Test
    @DisplayName("a host that keeps the list getter and the one-argument structure addSpawn is left alone")
    void keptLoaderMembersAreLeftAlone() {
        String forgeBuilder = LegacyBiomeSpawnsBridge.LOADER_BUILDERS[1];
        String forgeOverrides = LegacyBiomeSpawnsBridge.STRUCTURE_OVERRIDES[1];
        Map<String, byte[]> host = Map.of(
                DATA, classWith(DATA, "<init>", "(" + TYPE + "II)V"),
                forgeBuilder, classWith(forgeBuilder, "getSpawner", LegacyBiomeSpawnsBridge.OLD_GET_SPAWNER),
                forgeOverrides, classWith(forgeOverrides, "addSpawn", LegacyBiomeSpawnsBridge.OLD_STRUCTURE_ADD));
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        withHost(host, () -> LegacyBiomeSpawnsBridge.register(transformer));
        List<String> out = trace(transformer.transformClass(biomeModifier(forgeBuilder), "mod/Spawns"));

        assertTrue(out.contains("virtual " + forgeBuilder + ".getSpawner"), out.toString());
        assertTrue(out.contains("virtual " + forgeOverrides + ".addSpawn"), "Forge 26.2 kept addSpawn(entry): " + out);
        assertTrue(out.contains("static " + CALLS + ".spawnerData"), "the entry itself is still rebuilt: " + out);
    }

    @Test
    @DisplayName("offline, a Forge 26.2 target gets the spawn bridge and the list getSpawner, though no host class can be read")
    void offlineForgeTargetGetsTheSpawnerList() {
        String forgeBuilder = LegacyBiomeSpawnsBridge.LOADER_BUILDERS[1];
        String neoBuilder = LegacyBiomeSpawnsBridge.LOADER_BUILDERS[0];
        String savedTarget = RetromodVersion.TARGET_MC_VERSION;
        boolean savedForge = McReflect.isForceForge();
        try {
            RetromodVersion.TARGET_MC_VERSION = "26.2";
            McReflect.setForceForge(true);
            RetromodTransformer transformer = RetromodTransformer.getInstance();
            // The CLI's class reads see neither the vanilla entry nor the loader builders.
            withHost(Map.of(), () -> LegacyBiomeSpawnsBridge.register(transformer));
            List<String> out = trace(transformer.transformClass(biomeModifier(forgeBuilder), "mod/Spawns"));
            assertTrue(out.contains("static " + HELPER + ".spawnerList"), out.toString());
            assertTrue(out.contains("static " + CALLS + ".spawnerData"), "the weighted entry is rebuilt too: " + out);
            assertFalse(LegacyBiomeSpawnsBridge.needsSpawnerList(neoBuilder, null), "NeoForge's builder is not assumed");

            RetromodVersion.TARGET_MC_VERSION = "1.21.5";
            assertFalse(LegacyBiomeSpawnsBridge.offlineForgeTarget(), "Forge before 26.2 is not checked");
            assertFalse(LegacyBiomeSpawnsBridge.needsSpawnerList(forgeBuilder, null), "Forge before 26.2 is not checked");
            RetromodVersion.TARGET_MC_VERSION = "26.2";
            McReflect.setForceForge(false);
            assertFalse(LegacyBiomeSpawnsBridge.offlineForgeTarget(), "not a Forge target");
            assertFalse(LegacyBiomeSpawnsBridge.needsSpawnerList(forgeBuilder, null), "not a Forge target");
        } finally {
            RetromodVersion.TARGET_MC_VERSION = savedTarget;
            McReflect.setForceForge(savedForge);
        }
    }

    /** Cataclysm's biome modifier: getSpawner(category).add(new SpawnerData(...)), plus its siblings. */
    private static byte[] biomeModifier(String owner) {
        String overrides = owner.startsWith("net/neoforged")
                ? LegacyBiomeSpawnsBridge.STRUCTURE_OVERRIDES[0] : LegacyBiomeSpawnsBridge.STRUCTURE_OVERRIDES[1];
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "mod/Spawns", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "modify",
                "(L" + owner + ";" + CATEGORY + TYPE + "L" + overrides + ";)V", null, null);
        mv.visitCode();
        // builder.getSpawner(category).add(new SpawnerData(type, 10, 1, 2));
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, owner, "getSpawner", LegacyBiomeSpawnsBridge.OLD_GET_SPAWNER, false);
        mv.visitTypeInsn(NEW, DATA);
        mv.visitInsn(DUP);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitIntInsn(BIPUSH, 10);
        mv.visitInsn(ICONST_1);
        mv.visitInsn(ICONST_2);
        mv.visitMethodInsn(INVOKESPECIAL, DATA, "<init>", "(" + TYPE + "III)V", false);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true);
        mv.visitInsn(POP);
        // builder.addSpawn(category, entry), with the loader's builder as the owner.
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitInsn(ACONST_NULL);
        mv.visitTypeInsn(CHECKCAST, DATA);
        mv.visitMethodInsn(INVOKEVIRTUAL, owner, "addSpawn",
                "(" + CATEGORY + "L" + DATA + ";)L" + VANILLA_BUILDER + ";", false);
        mv.visitInsn(POP);
        // A 1.21.5 mod's own call, which returns the weighted builder.
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, owner, "getSpawner",
                "(" + CATEGORY + ")Lnet/minecraft/util/random/WeightedList$Builder;", false);
        mv.visitInsn(POP);
        // A removeIf predicate's entry.type read, and a structure override add.
        mv.visitInsn(ACONST_NULL);
        mv.visitTypeInsn(CHECKCAST, DATA);
        mv.visitFieldInsn(GETFIELD, DATA, "type", TYPE);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitInsn(ACONST_NULL);
        mv.visitTypeInsn(CHECKCAST, DATA);
        mv.visitMethodInsn(INVOKEVIRTUAL, overrides, "addSpawn", LegacyBiomeSpawnsBridge.OLD_STRUCTURE_ADD, false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ClassNode data(String... constructors) {
        ClassNode node = new ClassNode();
        new ClassReader(classWith(DATA, "<init>", constructors)).accept(node, 0);
        return node;
    }

    private static byte[] classWith(String name, String method, String... descriptors) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC, name, null, "java/lang/Object", null);
        for (String descriptor : descriptors) {
            cw.visitMethod(ACC_PUBLIC, method, descriptor, null, null).visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Runs a registration while the context loader serves only the given host classes. */
    private static void withHost(Map<String, byte[]> classes, Runnable registration) {
        ClassLoader host = new ClassLoader(null) {
            @Override
            public InputStream getResourceAsStream(String name) {
                String internal = name.endsWith(".class") ? name.substring(0, name.length() - 6) : name;
                byte[] bytes = classes.get(internal);
                return bytes == null ? null : new ByteArrayInputStream(bytes);
            }
        };
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(host);
        try {
            registration.run();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    private static List<String> trace(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        List<String> out = new ArrayList<>();
        for (MethodNode m : cn.methods) {
            for (AbstractInsnNode insn : m.instructions) {
                if (insn instanceof MethodInsnNode c) {
                    String kind = switch (c.getOpcode()) {
                        case INVOKESTATIC -> "static ";
                        case INVOKEINTERFACE -> "interface ";
                        default -> "virtual ";
                    };
                    out.add(kind + c.owner + "." + c.name);
                }
                if (insn instanceof TypeInsnNode t && t.getOpcode() == NEW) out.add("new " + t.desc);
            }
        }
        return out;
    }
}
