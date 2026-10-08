/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Bridges the 1.20.5 replacement of the {@code SpawnPlacements.Type} enum.
 *
 * <p>The enum became the {@code SpawnPlacementType} interface, and its four constants moved to
 * {@code SpawnPlacementTypes} with unchanged names. The static
 * {@code NaturalSpawner.isSpawnPositionOk(Type, LevelReader, BlockPos, EntityType)} became the
 * instance method {@code SpawnPlacementType.isSpawnPositionOk(LevelReader, BlockPos, EntityType)}.
 * A class rename covers descriptors (including spawn registration calls), two field redirects
 * cover the constants, and a generated static helper forwards the old static call to the new
 * instance method, because a redirect cannot turn a static call into an interface call.
 *
 * <p>Mojang names only: it serves NeoForge, Forge 1.20.5 and newer, and Fabric on 26.1 and newer
 * after the intermediary remap.
 */
public final class LegacySpawnPlacementTypeBridge {

    static final String OLD_TYPE = "net/minecraft/world/entity/SpawnPlacements$Type";
    static final String NEW_TYPE = "net/minecraft/world/entity/SpawnPlacementType";
    static final String CONSTANTS = "net/minecraft/world/entity/SpawnPlacementTypes";
    static final String HELPER = "com/retromod/generated/LegacySpawnPositionCheck";

    private static final String NATURAL_SPAWNER = "net/minecraft/world/level/NaturalSpawner";
    private static final String CHECK_ARGS = "Lnet/minecraft/world/level/LevelReader;"
            + "Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/EntityType;";
    private static final String OLD_CHECK_DESC = "(L" + OLD_TYPE + ";" + CHECK_ARGS + ")Z";
    private static final String NEW_STATIC_CHECK_DESC = "(L" + NEW_TYPE + ";" + CHECK_ARGS + ")Z";
    private static final String[] CONSTANT_NAMES = {"ON_GROUND", "IN_WATER", "NO_RESTRICTIONS", "IN_LAVA"};

    private LegacySpawnPlacementTypeBridge() {}

    /** Registers the bridge on hosts at or past 1.20.5. */
    public static void register(RetromodTransformer transformer) {
        if (RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "1.20.5") < 0) return;

        transformer.registerSyntheticClass(HELPER, generateHelper());
        transformer.registerClassRedirect(OLD_TYPE, NEW_TYPE);
        for (String constant : CONSTANT_NAMES) {
            // Field keys are matched both before and after the class rename, depending on the pass.
            for (String owner : new String[]{OLD_TYPE, NEW_TYPE}) {
                transformer.registerFieldRedirect(owner, constant, "L" + owner + ";",
                        CONSTANTS, constant, "L" + NEW_TYPE + ";");
            }
        }
        transformer.registerMethodRedirect(NATURAL_SPAWNER, "isSpawnPositionOk", OLD_CHECK_DESC,
                HELPER, "isSpawnPositionOk", NEW_STATIC_CHECK_DESC);
    }

    /** {@code static boolean isSpawnPositionOk(type, level, pos, entityType)}: calls the interface. */
    static byte[] generateHelper() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                HELPER, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "isSpawnPositionOk", NEW_STATIC_CHECK_DESC, null, null);
        mv.visitCode();
        for (int slot = 0; slot < 4; slot++) mv.visitVarInsn(Opcodes.ALOAD, slot);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, NEW_TYPE, "isSpawnPositionOk",
                "(" + CHECK_ARGS + ")Z", true);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
