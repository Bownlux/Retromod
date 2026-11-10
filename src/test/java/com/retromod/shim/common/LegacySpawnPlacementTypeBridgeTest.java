/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 1.20.5 replaced the {@code SpawnPlacements.Type} enum with the {@code SpawnPlacementType}
 * interface. An old mob registering spawn rules reads {@code SpawnPlacements.Type.ON_GROUND}, and an
 * old spawn predicate calls the removed static {@code NaturalSpawner.isSpawnPositionOk}.
 */
class LegacySpawnPlacementTypeBridgeTest {

    private static final String SPAWNER = "net/minecraft/world/level/NaturalSpawner";
    private static final String OLD_CHECK = "(L" + LegacySpawnPlacementTypeBridge.OLD_TYPE
            + ";Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;"
            + "Lnet/minecraft/world/entity/EntityType;)Z";

    private final String previousHost = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void reset() {
        RetromodVersion.TARGET_MC_VERSION = previousHost;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    void readsTheConstantFromItsNewHolderAndCallsTheInterfaceThroughTheHelper() {
        RetromodVersion.TARGET_MC_VERSION = "1.21.1";
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacySpawnPlacementTypeBridge.register(transformer);

        MethodNode rules = method(transformer.transformClass(spawnRules(), "com/example/SpawnRules"));

        FieldInsnNode constant = first(rules, FieldInsnNode.class);
        assertEquals(LegacySpawnPlacementTypeBridge.CONSTANTS, constant.owner,
                "ON_GROUND lives on SpawnPlacementTypes from 1.20.5 on");
        assertEquals("L" + LegacySpawnPlacementTypeBridge.NEW_TYPE + ";", constant.desc);
        MethodInsnNode check = first(rules, MethodInsnNode.class);
        assertEquals(LegacySpawnPlacementTypeBridge.HELPER, check.owner,
                "the removed static check must go through the generated forwarder");
        assertTrue(transformer.getSyntheticClasses().containsKey(LegacySpawnPlacementTypeBridge.HELPER),
                "the forwarder must be registered so the embedder ships it");
    }

    @Test
    void registersNothingBefore1205() {
        RetromodVersion.TARGET_MC_VERSION = "1.20.4";
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacySpawnPlacementTypeBridge.register(transformer);
        assertFalse(transformer.getClassRedirects().containsKey(LegacySpawnPlacementTypeBridge.OLD_TYPE),
                "the enum still exists before 1.20.5");
    }

    @Test
    void fabricRegistersItOnceItsModsAreMojangNamed() {
        // Longwings 0.9.5 (Fabric 1.20.1) on 26.3: its entry point stopped at a biome spawn rule
        // with NoClassDefFoundError: SpawnPlacements$Type, because only NeoForge and Forge
        // registered the bridge.
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        new com.retromod.shim.fabric.Fabric_1_21_1_to_1_21_2().registerRedirects(transformer);
        assertEquals(LegacySpawnPlacementTypeBridge.NEW_TYPE,
                transformer.getClassRedirects().get(LegacySpawnPlacementTypeBridge.OLD_TYPE));

        // An intermediary host never sees the Mojang name, so the bridge stays off there.
        RetromodVersion.TARGET_MC_VERSION = "1.21.4";
        transformer.clearRedirectsForTesting();
        new com.retromod.shim.fabric.Fabric_1_21_1_to_1_21_2().registerRedirects(transformer);
        assertFalse(transformer.getClassRedirects().containsKey(LegacySpawnPlacementTypeBridge.OLD_TYPE));
    }

    @Test
    void helperForwardsToTheInterfaceMethod() {
        ClassNode helper = new ClassNode();
        new ClassReader(LegacySpawnPlacementTypeBridge.generateHelper()).accept(helper, 0);
        MethodInsnNode call = first(helper.methods.get(0), MethodInsnNode.class);
        assertEquals(Opcodes.INVOKEINTERFACE, call.getOpcode());
        assertEquals(LegacySpawnPlacementTypeBridge.NEW_TYPE, call.owner);
        assertEquals("isSpawnPositionOk", call.name);
    }

    @Test
    void forgeCreateAnswersAPlacementTypeThatAsksTheModsTest(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Throwable {
        // Alex's Mobs 1.20.1 builds its own placement type this way while its entities register.
        java.util.Map<String, String> old = new java.util.HashMap<>(placementTypes());
        old.put("net/minecraft/world/entity/SpawnPlacements.java", """
                package net.minecraft.world.entity;
                public class SpawnPlacements {
                    public enum Type {
                        ON_GROUND;
                        public static Type create(String name,
                                net.minecraftforge.common.util.TriPredicate<net.minecraft.world.level.LevelReader, net.minecraft.core.BlockPos, EntityType<?>> test) {
                            throw new IllegalStateException();
                        }
                    }
                }""");
        old.put("mod/Placements.java", """
                package mod;
                import net.minecraft.world.entity.SpawnPlacements;
                public final class Placements {
                    public static Object build() {
                        return SpawnPlacements.Type.create("UNDERWATER_ROCK", (level, pos, type) -> pos == null);
                    }
                }""");
        java.util.Map<String, String> host = new java.util.HashMap<>(placementTypes());
        host.put("net/minecraft/world/entity/SpawnPlacementType.java", """
                package net.minecraft.world.entity;
                public interface SpawnPlacementType {
                    boolean isSpawnPositionOk(net.minecraft.world.level.LevelReader level, net.minecraft.core.BlockPos pos, EntityType<?> type);
                }""");
        java.util.Map<String, byte[]> mod = BridgeHarness.transform("26.2", LegacySpawnPlacementTypeBridge::register,
                BridgeHarness.only(BridgeHarness.compile(dir.resolve("old"), old), "mod/"));
        BridgeHarness.Run run = new BridgeHarness.Run(BridgeHarness.compile(dir.resolve("host"), host), mod);

        Object type = run.call("mod/Placements", "build");
        Class<?> placement = run.loadClass("net.minecraft.world.entity.SpawnPlacementType");
        assertTrue(placement.isInstance(type), "the old create answers the interface 1.20.5 put in the enum's place");
        java.lang.reflect.Method check = placement.getMethods()[0];
        Object pos = run.loadClass("net.minecraft.core.BlockPos").getConstructor().newInstance();
        assertEquals(Boolean.TRUE, check.invoke(type, null, null, null), "the mod's test decides");
        assertEquals(Boolean.FALSE, check.invoke(type, null, pos, null));
    }

    /** Classes the old and new spawn placement shapes share. */
    private static java.util.Map<String, String> placementTypes() {
        return java.util.Map.of(
                "net/minecraft/world/level/LevelReader.java", "package net.minecraft.world.level; public interface LevelReader {}",
                "net/minecraft/core/BlockPos.java", "package net.minecraft.core; public class BlockPos {}",
                "net/minecraft/world/entity/EntityType.java", "package net.minecraft.world.entity; public class EntityType<T> {}",
                "net/minecraftforge/common/util/TriPredicate.java",
                "package net.minecraftforge.common.util; public interface TriPredicate<T, U, V> { boolean test(T t, U u, V v); }");
    }

    /** {@code boolean check(level, pos, type) { return NaturalSpawner.isSpawnPositionOk(Type.ON_GROUND, ...); }} */
    private static byte[] spawnRules() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/SpawnRules", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "check",
                "(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;"
                        + "Lnet/minecraft/world/entity/EntityType;)Z", null, null);
        mv.visitCode();
        mv.visitFieldInsn(Opcodes.GETSTATIC, LegacySpawnPlacementTypeBridge.OLD_TYPE, "ON_GROUND",
                "L" + LegacySpawnPlacementTypeBridge.OLD_TYPE + ";");
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, SPAWNER, "isSpawnPositionOk", OLD_CHECK, false);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static MethodNode method(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node.methods.stream().filter(m -> m.name.equals("check")).findFirst().orElseThrow();
    }

    private static <T extends AbstractInsnNode> T first(MethodNode method, Class<T> type) {
        for (AbstractInsnNode insn : method.instructions) {
            if (type.isInstance(insn)) return type.cast(insn);
        }
        throw new AssertionError("no " + type.getSimpleName() + " in " + method.name);
    }
}
