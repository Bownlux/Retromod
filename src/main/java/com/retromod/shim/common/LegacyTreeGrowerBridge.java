/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bridges the tree grower classes that 1.20.3 replaced with the {@code TreeGrower} value class.
 *
 * <p>A 1.20.1 mod declares a sapling's tree by subclassing {@code AbstractTreeGrower} (or
 * {@code AbstractMegaTreeGrower}) and returning a configured feature key from
 * {@code getConfiguredFeature(RandomSource, boolean)}, then passes an instance to
 * {@code new SaplingBlock(grower, properties)}. Both grower classes are gone, so the mod's block
 * registry fails with {@code NoClassDefFoundError} and nothing in the mod registers.
 *
 * <p>The grower classes are redirected to generated stand-ins with the same abstract methods. The
 * sapling constructor becomes a factory that asks the grower once for its normal, flowering and
 * mega tree keys, builds a {@code TreeGrower} from them, and creates the sapling through a
 * generated subclass, which can reach the {@code SaplingBlock} constructor even on a host that does
 * not make it public. A grower whose choice depends on the random source is sampled once with a
 * fixed seed, so it always grows the same tree. A mod subclass of {@code SaplingBlock} that passes
 * a legacy grower to {@code super} is not bridged.
 */
public final class LegacyTreeGrowerBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    static final String OLD_GROWER = "net/minecraft/world/level/block/grower/AbstractTreeGrower";
    static final String OLD_MEGA_GROWER = "net/minecraft/world/level/block/grower/AbstractMegaTreeGrower";
    static final String TREE_GROWER = "net/minecraft/world/level/block/grower/TreeGrower";
    static final String SAPLING = "net/minecraft/world/level/block/SaplingBlock";

    static final String GROWER = "com/retromod/generated/LegacyTreeGrower";
    static final String MEGA_GROWER = "com/retromod/generated/LegacyMegaTreeGrower";
    static final String SAPLING_FACTORY = "com/retromod/generated/LegacySaplingBlock";

    private static final String RANDOM = "net/minecraft/util/RandomSource";
    private static final String KEY = "net/minecraft/resources/ResourceKey";
    private static final String PROPERTIES = "net/minecraft/world/level/block/state/BlockBehaviour$Properties";
    private static final String OPTIONAL = "java/util/Optional";
    private static final String L_OPTIONAL = "Ljava/util/Optional;";
    private static final String L_TREE_GROWER = "L" + TREE_GROWER + ";";
    private static final String FEATURE_DESC = "(L" + RANDOM + ";Z)L" + KEY + ";";
    private static final String MEGA_FEATURE_DESC = "(L" + RANDOM + ";)L" + KEY + ";";
    private static final String GROW_TREE_DESC = "(Lnet/minecraft/server/level/ServerLevel;"
            + "Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/core/BlockPos;"
            + "Lnet/minecraft/world/level/block/state/BlockState;L" + RANDOM + ";)Z";

    private LegacyTreeGrowerBridge() {}

    /** Registers the bridge when the host has {@code TreeGrower} and no legacy grower. */
    public static void register(RetromodTransformer transformer) {
        if (!hostHasTreeGrower()) return;
        registerRedirects(transformer);
        LOGGER.info("Bridged the removed AbstractTreeGrower and SaplingBlock(AbstractTreeGrower, Properties)");
    }

    /**
     * The host's own classes decide. Offline, where Minecraft is not readable, the host version
     * does: 1.20.3 replaced the grower classes with {@code TreeGrower}.
     */
    static boolean hostHasTreeGrower() {
        if (ClassResourceInspector.read(SAPLING) == null && !ClassResourceInspector.exists(OLD_GROWER)) {
            return com.retromod.core.RetromodVersion.compareMcVersions(
                    com.retromod.core.RetromodVersion.TARGET_MC_VERSION, "1.20.3") >= 0;
        }
        return !ClassResourceInspector.exists(OLD_GROWER) && ClassResourceInspector.exists(TREE_GROWER)
                && ClassResourceInspector.exists(SAPLING);
    }

    /** Unconditional registration for transform-shape tests. */
    static void registerRedirects(RetromodTransformer transformer) {
        transformer.registerSyntheticClass(GROWER, generateGrower());
        transformer.registerSyntheticClass(MEGA_GROWER, generateMegaGrower());
        transformer.registerSyntheticClass(SAPLING_FACTORY, generateSaplingFactory());
        transformer.registerClassRedirect(OLD_GROWER, GROWER);
        transformer.registerClassRedirect(OLD_MEGA_GROWER, MEGA_GROWER);
        String factoryDesc = "(L" + GROWER + ";L" + PROPERTIES + ";)L" + SAPLING + ";";
        // The lookup happens after the class redirect, but a pre-remap key costs nothing.
        for (String grower : new String[] {GROWER, OLD_GROWER}) {
            transformer.registerConstructorRedirect(SAPLING, "(L" + grower + ";L" + PROPERTIES + ";)V",
                    SAPLING_FACTORY, "of", factoryDesc);
        }
    }

    static byte[] generateGrower() {
        ClassWriter cw = writer();
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_SUPER,
                GROWER, null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PRIVATE, "converted", L_TREE_GROWER, null, null).visitEnd();
        emitNoArgConstructor(cw, "java/lang/Object");
        cw.visitMethod(Opcodes.ACC_PROTECTED | Opcodes.ACC_ABSTRACT, "getConfiguredFeature",
                FEATURE_DESC, null, null).visitEnd();

        // growTree delegates to the converted grower, for mods that call it directly.
        MethodVisitor grow = cw.visitMethod(Opcodes.ACC_PUBLIC, "growTree", GROW_TREE_DESC, null, null);
        grow.visitCode();
        grow.visitVarInsn(Opcodes.ALOAD, 0);
        grow.visitMethodInsn(Opcodes.INVOKESTATIC, GROWER, "toTreeGrower",
                "(L" + GROWER + ";)" + L_TREE_GROWER, false);
        for (int slot = 1; slot <= 5; slot++) grow.visitVarInsn(Opcodes.ALOAD, slot);
        grow.visitMethodInsn(Opcodes.INVOKEVIRTUAL, TREE_GROWER, "growTree", GROW_TREE_DESC, false);
        grow.visitInsn(Opcodes.IRETURN);
        grow.visitMaxs(0, 0);
        grow.visitEnd();

        emitToTreeGrower(cw);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * {@code toTreeGrower(grower)}: built once per grower from its normal, flowering and mega keys.
     * The protected abstract methods are callable here because this class declares them.
     */
    private static void emitToTreeGrower(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "toTreeGrower",
                "(L" + GROWER + ";)" + L_TREE_GROWER, null, null);
        mv.visitCode();
        Label build = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, GROWER, "converted", L_TREE_GROWER);
        mv.visitJumpInsn(Opcodes.IFNULL, build);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, GROWER, "converted", L_TREE_GROWER);
        mv.visitInsn(Opcodes.ARETURN);

        mv.visitLabel(build);
        mv.visitLdcInsn(0L);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, RANDOM, "create", "(J)L" + RANDOM + ";", true);
        mv.visitVarInsn(Opcodes.ASTORE, 1);

        // mega key, or empty for a plain grower
        Label notMega = new Label();
        Label megaDone = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, MEGA_GROWER);
        mv.visitJumpInsn(Opcodes.IFEQ, notMega);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitTypeInsn(Opcodes.CHECKCAST, MEGA_GROWER);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MEGA_GROWER, "getConfiguredMegaFeature",
                MEGA_FEATURE_DESC, false);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, OPTIONAL, "ofNullable",
                "(Ljava/lang/Object;)" + L_OPTIONAL, false);
        mv.visitVarInsn(Opcodes.ASTORE, 2);
        mv.visitJumpInsn(Opcodes.GOTO, megaDone);
        mv.visitLabel(notMega);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, OPTIONAL, "empty", "()" + L_OPTIONAL, false);
        mv.visitVarInsn(Opcodes.ASTORE, 2);
        mv.visitLabel(megaDone);

        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitTypeInsn(Opcodes.NEW, TREE_GROWER);
        mv.visitInsn(Opcodes.DUP);
        // A unique name per grower class: TreeGrower registers itself by name for its codec.
        mv.visitLdcInsn("retromod_legacy/");
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getName", "()Ljava/lang/String;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "toLowerCase", "()Ljava/lang/String;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat",
                "(Ljava/lang/String;)Ljava/lang/String;", false);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        for (boolean flowers : new boolean[] {false, true}) {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitInsn(flowers ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, GROWER, "getConfiguredFeature", FEATURE_DESC, false);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, OPTIONAL, "ofNullable",
                    "(Ljava/lang/Object;)" + L_OPTIONAL, false);
        }
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, TREE_GROWER, "<init>",
                "(Ljava/lang/String;" + L_OPTIONAL + L_OPTIONAL + L_OPTIONAL + ")V", false);
        mv.visitFieldInsn(Opcodes.PUTFIELD, GROWER, "converted", L_TREE_GROWER);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, GROWER, "converted", L_TREE_GROWER);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    static byte[] generateMegaGrower() {
        ClassWriter cw = writer();
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_SUPER,
                MEGA_GROWER, null, GROWER, null);
        emitNoArgConstructor(cw, GROWER);
        cw.visitMethod(Opcodes.ACC_PROTECTED | Opcodes.ACC_ABSTRACT, "getConfiguredMegaFeature",
                MEGA_FEATURE_DESC, null, null).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A public subclass, so the factory does not depend on the host's constructor access. */
    static byte[] generateSaplingFactory() {
        ClassWriter cw = writer();
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, SAPLING_FACTORY, null, SAPLING, null);
        String ctorDesc = "(" + L_TREE_GROWER + "L" + PROPERTIES + ";)V";
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", ctorDesc, null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitVarInsn(Opcodes.ALOAD, 1);
        init.visitVarInsn(Opcodes.ALOAD, 2);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, SAPLING, "<init>", ctorDesc, false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        MethodVisitor of = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "of",
                "(L" + GROWER + ";L" + PROPERTIES + ";)L" + SAPLING + ";", null, null);
        of.visitCode();
        of.visitTypeInsn(Opcodes.NEW, SAPLING_FACTORY);
        of.visitInsn(Opcodes.DUP);
        of.visitVarInsn(Opcodes.ALOAD, 0);
        of.visitMethodInsn(Opcodes.INVOKESTATIC, GROWER, "toTreeGrower",
                "(L" + GROWER + ";)" + L_TREE_GROWER, false);
        of.visitVarInsn(Opcodes.ALOAD, 1);
        of.visitMethodInsn(Opcodes.INVOKESPECIAL, SAPLING_FACTORY, "<init>", ctorDesc, false);
        of.visitInsn(Opcodes.ARETURN);
        of.visitMaxs(0, 0);
        of.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void emitNoArgConstructor(ClassWriter cw, String superName) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static ClassWriter writer() {
        return new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                // Minecraft is not on Retromod's classpath when these classes are generated.
                return "java/lang/Object";
            }
        };
    }
}
