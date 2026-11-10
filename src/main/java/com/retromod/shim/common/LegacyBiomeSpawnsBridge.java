/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.common.embedded.LegacySpawns;
import com.retromod.shim.forge.ForgeHostShape;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;

import java.util.List;

import static org.objectweb.asm.Opcodes.*;

/** Keeps an old biome's mob spawns, with their weights, on hosts that moved the weight to a weighted list. */
public final class LegacyBiomeSpawnsBridge {

    static final String CALLS = "com/retromod/generated/LegacySpawnCalls";
    static final String HELPER = "com/retromod/shim/common/embedded/LegacySpawns";
    private static final String DATA = "net/minecraft/world/level/biome/MobSpawnSettings$SpawnerData";
    private static final String BUILDER = "net/minecraft/world/level/biome/MobSpawnSettings$Builder";
    private static final String TYPE = "Lnet/minecraft/world/entity/EntityType;";
    private static final String CATEGORY = "Lnet/minecraft/world/entity/MobCategory;";
    private static final String OLD_DATA = "(" + TYPE + "III)V";
    private static final String OBJECT = "Ljava/lang/Object;";
    /** The loaders' biome modifier builders, which mods name as the owner of their spawn calls. */
    static final String[] LOADER_BUILDERS = {
        "net/neoforged/neoforge/common/world/MobSpawnSettingsBuilder",
        "net/minecraftforge/common/world/MobSpawnSettingsBuilder"};
    /** The loaders' structure spawn override builders. */
    static final String[] STRUCTURE_OVERRIDES = {
        "net/neoforged/neoforge/common/world/StructureSettingsBuilder$StructureSpawnOverrideBuilder",
        "net/minecraftforge/common/world/StructureSettingsBuilder$StructureSpawnOverrideBuilder"};
    static final String OLD_GET_SPAWNER = "(" + CATEGORY + ")Ljava/util/List;";
    static final String OLD_STRUCTURE_ADD = "(L" + DATA + ";)V";

    private LegacyBiomeSpawnsBridge() {}

    /** Registers where the host's spawn entry has lost its weight. */
    public static void register(RetromodTransformer transformer) {
        // SpawnerData lost its weight in 1.21.5, and 26.3 made its group size an IntProvider. A biome
        // built in code then failed with NoSuchMethodError, and the unbuilt biome left the registry
        // unbound, which stopped the world from loading.
        ClassNode data = ClassResourceInspector.read(DATA);
        if (data != null ? !appliesTo(data) : !offlineForgeTarget()) return;
        registerRedirects(transformer);
        // A loader builder whose getSpawner no longer returns a list gets one that writes through.
        for (String owner : LOADER_BUILDERS) {
            if (needsSpawnerList(owner, ClassResourceInspector.read(owner))) {
                registerSpawnerListRedirect(transformer, owner);
            }
        }
        // NeoForge 21.11 and 26.3 dropped the structure override's addSpawn(entry); Forge 26.2 kept it.
        for (String owner : STRUCTURE_OVERRIDES) {
            ClassNode overrides = ClassResourceInspector.read(owner);
            if (overrides != null && !hasMethod(overrides, "addSpawn", OLD_STRUCTURE_ADD)) {
                registerStructureSpawnRedirect(transformer, owner);
            }
        }
    }

    /** True when the entry lacks the weighted constructor and has the 1.21.5 or 26.3 one. */
    static boolean appliesTo(ClassNode data) {
        if (hasMethod(data, "<init>", OLD_DATA)) return false;
        return hasMethod(data, "<init>", "(" + TYPE + "II)V")
                || hasMethod(data, "<init>", "(" + TYPE + "Lnet/minecraft/util/valueproviders/IntProvider;)V");
    }

    /** Whether the builder's getSpawner lost its list; {@code builder} is null when the host's class cannot be read. */
    static boolean needsSpawnerList(String owner, ClassNode builder) {
        if (builder != null) return !hasMethod(builder, "getSpawner", OLD_GET_SPAWNER);
        // Forge's builder is checked to return a weighted list on 26.2 and 26.3; NeoForge's is left as it was.
        return owner.startsWith("net/minecraftforge/") && ForgeHostShape.matches(List.of(owner), List.of());
    }

    /**
     * Offline the CLI cannot read the host's classes. Forge 26.2 and 26.3 are checked to have the
     * weightless entry, so a Forge target from 26.2 takes the bridge; other offline targets do not.
     */
    static boolean offlineForgeTarget() {
        return ForgeHostShape.matches(List.of(), List.of());
    }

    static void registerRedirects(RetromodTransformer transformer) {
        SyntheticEmbedder.registerClassResource(transformer, HELPER, LegacySpawns.class);
        transformer.registerSyntheticClass(CALLS, generateCalls());
        transformer.registerConstructorRedirect(DATA, OLD_DATA, CALLS, "spawnerData", "(" + TYPE + "III)L" + DATA + ";");
        // javac names the loader's builder as the owner when the mod holds one, as biome modifiers do.
        String addDesc = "(" + CATEGORY + "L" + DATA + ";)L" + BUILDER + ";";
        String callsDesc = "(L" + BUILDER + ";" + CATEGORY + "L" + DATA + ";)L" + BUILDER + ";";
        transformer.registerMethodRedirect(BUILDER, "addSpawn", addDesc, CALLS, "addSpawn", callsDesc, true);
        for (String owner : LOADER_BUILDERS) {
            transformer.registerMethodRedirect(owner, "addSpawn", addDesc, CALLS, "addSpawn", callsDesc, true);
        }
        // The record's type field is private from 1.21.5; an old removeIf predicate read it directly.
        transformer.registerFieldAccessorRedirect(DATA, "type", "type", "()" + TYPE, null, null);
    }

    static void registerSpawnerListRedirect(RetromodTransformer transformer, String owner) {
        SyntheticEmbedder.registerClassResource(transformer, HELPER, LegacySpawns.class);
        transformer.registerMethodRedirect(owner, "getSpawner", OLD_GET_SPAWNER, HELPER, "spawnerList",
                "(" + OBJECT + OBJECT + ")Ljava/util/List;", true);
    }

    static void registerStructureSpawnRedirect(RetromodTransformer transformer, String owner) {
        SyntheticEmbedder.registerClassResource(transformer, HELPER, LegacySpawns.class);
        transformer.registerMethodRedirect(owner, "addSpawn", OLD_STRUCTURE_ADD, HELPER, "addStructureSpawn",
                "(" + OBJECT + OBJECT + ")V", true);
    }

    private static boolean hasMethod(ClassNode node, String name, String desc) {
        return node.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(desc));
    }

    /** Typed wrappers over the compiled helper, which can only name {@code Object}. */
    static byte[] generateCalls() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, CALLS, null, "java/lang/Object", null);
        MethodVisitor data = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "spawnerData", "(" + TYPE + "III)L" + DATA + ";", null, null);
        data.visitCode();
        data.visitVarInsn(ALOAD, 0);
        data.visitVarInsn(ILOAD, 1);
        data.visitVarInsn(ILOAD, 2);
        data.visitVarInsn(ILOAD, 3);
        data.visitMethodInsn(INVOKESTATIC, HELPER, "spawnerData", "(" + OBJECT + "III)" + OBJECT, false);
        data.visitTypeInsn(CHECKCAST, DATA);
        data.visitInsn(ARETURN);
        data.visitMaxs(0, 0);
        data.visitEnd();
        MethodVisitor add = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "addSpawn",
                "(L" + BUILDER + ";" + CATEGORY + "L" + DATA + ";)L" + BUILDER + ";", null, null);
        add.visitCode();
        add.visitVarInsn(ALOAD, 0);
        add.visitVarInsn(ALOAD, 1);
        add.visitVarInsn(ALOAD, 2);
        add.visitMethodInsn(INVOKESTATIC, HELPER, "addSpawn", "(" + OBJECT + OBJECT + OBJECT + ")" + OBJECT, false);
        add.visitTypeInsn(CHECKCAST, BUILDER);
        add.visitInsn(ARETURN);
        add.visitMaxs(0, 0);
        add.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
