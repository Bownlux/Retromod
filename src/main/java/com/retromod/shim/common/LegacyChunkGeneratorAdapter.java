/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.nio.charset.StandardCharsets;
import java.util.function.Function;

/** Gives a mod's own chunk generator the steps the host calls now, each running the steps the mod wrote for an older one. */
public final class LegacyChunkGeneratorAdapter {

    static final String GENERATOR = "net/minecraft/world/level/chunk/ChunkGenerator";
    // The base Retromod rebuilds under a 1.20.1 generator whose codec() returned a plain codec.
    static final String REBUILT_BASE = "com/retromod/generated/LegacyChunkGenerator";
    private static final String CHUNK = "Lnet/minecraft/world/level/chunk/ChunkAccess;";
    private static final String BLENDER = "Lnet/minecraft/world/level/levelgen/blending/Blender;";
    private static final String RANDOM = "Lnet/minecraft/world/level/levelgen/RandomState;";
    private static final String STRUCTURES = "Lnet/minecraft/world/level/StructureManager;";
    private static final String BIOMES = "Lnet/minecraft/world/level/biome/BiomeManager;";
    private static final String REGION = "Lnet/minecraft/server/level/WorldGenRegion;";
    private static final String FUTURE = "Ljava/util/concurrent/CompletableFuture;";
    private static final String DEBUG_ARGS = "Ljava/util/List;" + RANDOM + "Lnet/minecraft/core/BlockPos;";

    // 1.20.1 passed an executor to the noise step; 1.21 to 26.2 did not.
    static final String NOISE_1201 = "(Ljava/util/concurrent/Executor;" + BLENDER + RANDOM + STRUCTURES + CHUNK + ")" + FUTURE;
    static final String NOISE = "(" + BLENDER + RANDOM + STRUCTURES + CHUNK + ")" + FUTURE;
    static final String SURFACE = "(" + REGION + STRUCTURES + RANDOM + CHUNK + ")V";
    static final String CARVERS = "(" + REGION + "J" + RANDOM + BIOMES + STRUCTURES + CHUNK + ")V";
    // 26.3 runs noise, surface and carvers as one terrain step.
    static final String TERRAIN = "(" + CHUNK + BLENDER + RANDOM + STRUCTURES + BIOMES + REGION + "Ljava/util/Set;)" + FUTURE;
    static final String OLD_DEBUG = "(" + DEBUG_ARGS + ")V";
    static final String DEBUG = "(" + DEBUG_ARGS + "Lnet/minecraft/world/level/levelgen/densityfunction/SamplerContext;)V";

    /** The steps the host's generator asks for. */
    enum HostShape { NONE, SEPARATE_STEPS, TERRAIN_STEP }

    private static volatile String checkedTarget;
    private static volatile HostShape checkedShape;

    private LegacyChunkGeneratorAdapter() {}

    /** The post-remap pass over one mod class; the same array when nothing changes. */
    public static byte[] apply(byte[] classBytes) {
        if (classBytes == null || !new String(classBytes, StandardCharsets.ISO_8859_1).contains("fillFromNoise")) return classBytes;
        HostShape shape = hostShape();
        if (shape == HostShape.NONE) return classBytes;
        return apply(classBytes, RetromodTransformer::readCurrentJarClassForAdapter, shape);
    }

    /** The host's generator shape; offline, 26.3 targets take the terrain step and 1.21 to 26.2 the separate ones. */
    static HostShape hostShape() {
        String target = RetromodVersion.TARGET_MC_VERSION;
        if (target != null && target.equals(checkedTarget)) return checkedShape;
        ClassNode host = ClassResourceInspector.read(GENERATOR);
        if (host == null) {
            if (RetromodVersion.compareMcVersions(target, "26.3") >= 0) return HostShape.TERRAIN_STEP;
            return RetromodVersion.compareMcVersions(target, "1.21") >= 0 ? HostShape.SEPARATE_STEPS : HostShape.NONE;
        }
        HostShape shape = declares(host, "buildTerrain", TERRAIN) ? HostShape.TERRAIN_STEP
                : declares(host, "fillFromNoise", NOISE) ? HostShape.SEPARATE_STEPS : HostShape.NONE;
        checkedShape = shape;
        checkedTarget = target;
        return shape;
    }

    static byte[] apply(byte[] classBytes, Function<String, byte[]> jarClasses, HostShape shape) {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        if ((node.access & Opcodes.ACC_INTERFACE) != 0 || node.name.startsWith("net/minecraft/")
                || StaticFieldIdStamper.isMixin(node)) {
            return classBytes;
        }
        // Only a generator built straight on the abstract base lacks the host's step; one built on a vanilla generator
        // such as the noise or flat one inherits it.
        String base = firstHostAncestor(node.superName, jarClasses);
        if (!GENERATOR.equals(base) && !REBUILT_BASE.equals(base)) return classBytes;
        boolean oldNoise = overrides(node, NOISE_1201);
        boolean noise = overrides(node, NOISE);
        if (!oldNoise && !noise) return classBytes;
        int added = 0;
        if (shape == HostShape.SEPARATE_STEPS) {
            // The host calls the four-argument noise step; a 1.20.1 generator gets it, with a pool to run its work on.
            if (oldNoise && !noise) added += add(node, noiseFromOld(node));
            // 1.20.1's carvers took a carving step whose enum is gone, so its override cannot be called; the host's
            // abstract form gets an empty one, as a generator without carvers had.
            if (oldNoise && !declares(node, "applyCarvers", CARVERS) && declaresName(node, "applyCarvers")) {
                added += add(node, noCarvers());
            }
        } else {
            if (!declares(node, "buildTerrain", TERRAIN)) added += add(node, terrain(node, noise));
            if (overrides(node, OLD_DEBUG) && !declares(node, "addDebugScreenInfo", DEBUG)) added += add(node, debug(node));
        }
        if (added == 0) return classBytes;
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    /** The first ancestor that is not one of the jar's own classes, read through the jar's superclass chain. */
    static String firstHostAncestor(String superName, Function<String, byte[]> jarClasses) {
        String current = superName;
        for (int depth = 0; current != null && depth < 32; depth++) {
            byte[] bytes = REBUILT_BASE.equals(current) ? null : jarClasses.apply(current);
            if (bytes == null) return current;
            current = new ClassReader(bytes).getSuperName();
        }
        return current;
    }

    /** {@code fillFromNoise(blender, random, structures, chunk)}: the 1.20.1 step on the common pool. */
    private static MethodNode noiseFromOld(ClassNode node) {
        MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC, "fillFromNoise", NOISE, null, null);
        m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        m.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/concurrent/ForkJoinPool", "commonPool",
                "()Ljava/util/concurrent/ForkJoinPool;", false));
        for (int slot = 1; slot <= 4; slot++) m.instructions.add(new VarInsnNode(Opcodes.ALOAD, slot));
        m.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, "fillFromNoise", NOISE_1201, false));
        m.instructions.add(new InsnNode(Opcodes.ARETURN));
        return m;
    }

    /**
     * {@code buildTerrain(chunk, blender, random, structures, biomes, region, biomeSet)}: the mod's noise step, then its
     * surface and carvers when it declares them, as the host ran the three in order before 26.3.
     */
    private static MethodNode terrain(ClassNode node, boolean noise) {
        MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC, "buildTerrain", TERRAIN, null, null);
        InsnList code = m.instructions;
        // Slots: 1 chunk, 2 blender, 3 random, 4 structures, 5 biomes, 6 region, 7 biome set.
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        if (!noise) {
            code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/concurrent/ForkJoinPool", "commonPool",
                    "()Ljava/util/concurrent/ForkJoinPool;", false));
        }
        for (int slot : new int[]{2, 3, 4, 1}) code.add(new VarInsnNode(Opcodes.ALOAD, slot));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, "fillFromNoise", noise ? NOISE : NOISE_1201, false));
        // The later steps need the filled chunk, so the noise step is waited for here.
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/concurrent/CompletableFuture", "join", "()Ljava/lang/Object;", false));
        code.add(new TypeInsnNode(Opcodes.CHECKCAST, CHUNK.substring(1, CHUNK.length() - 1)));
        code.add(new VarInsnNode(Opcodes.ASTORE, 1));
        if (overrides(node, SURFACE)) {
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            for (int slot : new int[]{6, 4, 3, 1}) code.add(new VarInsnNode(Opcodes.ALOAD, slot));
            code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, "buildSurface", SURFACE, false));
        }
        if (overrides(node, CARVERS)) {
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new VarInsnNode(Opcodes.ALOAD, 6));
            code.add(new VarInsnNode(Opcodes.ALOAD, 6));
            code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, REGION.substring(1, REGION.length() - 1), "getSeed", "()J", false));
            for (int slot : new int[]{3, 5, 4, 1}) code.add(new VarInsnNode(Opcodes.ALOAD, slot));
            code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, "applyCarvers", CARVERS, false));
        }
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/concurrent/CompletableFuture", "completedFuture",
                "(Ljava/lang/Object;)" + FUTURE, false));
        code.add(new InsnNode(Opcodes.ARETURN));
        return m;
    }

    /** {@code addDebugScreenInfo(lines, random, pos, context)}: the mod's three-argument lines. */
    private static MethodNode debug(ClassNode node) {
        MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC, "addDebugScreenInfo", DEBUG, null, null);
        m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        for (int slot = 1; slot <= 3; slot++) m.instructions.add(new VarInsnNode(Opcodes.ALOAD, slot));
        m.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, "addDebugScreenInfo", OLD_DEBUG, false));
        m.instructions.add(new InsnNode(Opcodes.RETURN));
        return m;
    }

    private static MethodNode noCarvers() {
        MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC, "applyCarvers", CARVERS, null, null);
        m.instructions.add(new InsnNode(Opcodes.RETURN));
        return m;
    }

    private static boolean declaresName(ClassNode node, String name) {
        return node.methods.stream().anyMatch(m -> m.name.equals(name));
    }

    private static int add(ClassNode node, MethodNode method) {
        node.methods.add(method);
        return 1;
    }

    private static boolean overrides(ClassNode node, String desc) {
        String name = desc.equals(SURFACE) ? "buildSurface" : desc.equals(CARVERS) ? "applyCarvers"
                : desc.equals(OLD_DEBUG) ? "addDebugScreenInfo" : "fillFromNoise";
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)
                    && (method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_PRIVATE)) == 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean declares(ClassNode node, String name, String desc) {
        return node.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(desc));
    }
}
