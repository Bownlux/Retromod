/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.common.embedded.LegacySpawns;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;

import static org.objectweb.asm.Opcodes.*;

/**
 * Keeps an old biome's mob spawns. {@code SpawnerData} lost its weight to a weighted list and now
 * takes its group size as an {@code IntProvider}, and {@code addSpawn(category, data)} became
 * {@code addSpawn(type, category, weight, counts)}. A biome built in code failed with
 * {@code NoSuchMethodError}, and an unbuilt biome left the registry unbound, which stopped the world
 * from loading. Each old entry keeps its type, weight, and counts.
 */
public final class LegacyBiomeSpawnsBridge {

    static final String CALLS = "com/retromod/generated/LegacySpawnCalls";
    static final String HELPER = "com/retromod/shim/common/embedded/LegacySpawns";
    private static final String DATA = "net/minecraft/world/level/biome/MobSpawnSettings$SpawnerData";
    private static final String BUILDER = "net/minecraft/world/level/biome/MobSpawnSettings$Builder";
    private static final String TYPE = "Lnet/minecraft/world/entity/EntityType;";
    private static final String CATEGORY = "Lnet/minecraft/world/entity/MobCategory;";
    private static final String OLD_DATA = "(" + TYPE + "III)V";
    private static final String OBJECT = "Ljava/lang/Object;";

    private LegacyBiomeSpawnsBridge() {}

    /** Only where the entry takes an {@code IntProvider} and the old constructor is gone. */
    public static void register(RetromodTransformer transformer) {
        ClassNode data = ClassResourceInspector.read(DATA);
        if (data == null || data.methods.stream().anyMatch(m -> m.name.equals("<init>") && m.desc.equals(OLD_DATA))
                || data.methods.stream().noneMatch(m -> m.name.equals("<init>")
                        && m.desc.equals("(" + TYPE + "Lnet/minecraft/util/valueproviders/IntProvider;)V"))) {
            return;
        }
        registerRedirects(transformer);
    }

    static void registerRedirects(RetromodTransformer transformer) {
        SyntheticEmbedder.registerClassResource(transformer, HELPER, LegacySpawns.class);
        transformer.registerSyntheticClass(CALLS, generateCalls());
        transformer.registerConstructorRedirect(DATA, OLD_DATA, CALLS, "spawnerData", "(" + TYPE + "III)L" + DATA + ";");
        transformer.registerMethodRedirect(BUILDER, "addSpawn", "(" + CATEGORY + "L" + DATA + ";)L" + BUILDER + ";",
                CALLS, "addSpawn", "(L" + BUILDER + ";" + CATEGORY + "L" + DATA + ";)L" + BUILDER + ";", true);
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
