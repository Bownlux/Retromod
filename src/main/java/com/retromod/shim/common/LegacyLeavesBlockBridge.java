/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

/**
 * Keeps pre-1.21.5 leaves blocks constructible.
 *
 * <p>1.21.5 made {@code LeavesBlock} abstract and changed its only constructor from
 * {@code (Properties)} to {@code (float leafParticleChance, Properties)}. Vanilla leaves became
 * {@code TintedParticleLeavesBlock} or {@code UntintedParticleLeavesBlock}. An older mod that
 * extends {@code LeavesBlock} dies with {@code NoSuchMethodError} in its own constructor, and one
 * that writes {@code new LeavesBlock(props)} cannot instantiate an abstract class. Bountiful Fares'
 * fruit leaves hit the first case during block registration on 26.1.2.
 *
 * <p>The new parameter is prepended, so neither the append-only default insertion nor the generated
 * legacy base can supply it. This class generates a small base that extends
 * {@code TintedParticleLeavesBlock}, which is what oak, birch and the other tinted vanilla leaves
 * use, and passes vanilla's 0.01 particle chance. Subclasses are rebased onto it and direct
 * construction becomes a factory call. The modern {@code (float, Properties)} constructor passes
 * through unchanged, so a mod already built for 1.21.5 or newer keeps its own particle chance.
 *
 * <p>The limit: an old mod's leaves drop the tinted falling-leaf particle of oak leaves. A mod whose
 * leaves should use a different particle needs {@code UntintedParticleLeavesBlock}, which Retromod
 * cannot choose for it.
 */
public final class LegacyLeavesBlockBridge {

    private LegacyLeavesBlockBridge() {}

    public static final String INTERNAL = "com/retromod/generated/LegacyLeavesBlock";

    static final String LEAVES = "net/minecraft/world/level/block/LeavesBlock";
    static final String TINTED = "net/minecraft/world/level/block/TintedParticleLeavesBlock";
    static final String PROPS = "net/minecraft/world/level/block/state/BlockBehaviour$Properties";
    static final String LEGACY_CTOR = "(L" + PROPS + ";)V";
    static final String MODERN_CTOR = "(FL" + PROPS + ";)V";
    static final String FACTORY_DESC = "(L" + PROPS + ";)L" + LEAVES + ";";

    /** Vanilla's chance for tinted leaves such as oak, read from {@code Blocks} on 26.1.2. */
    private static final float VANILLA_TINTED_CHANCE = 0.01f;

    /** Registers the generated base, the subclass rebase, and the construction redirect. */
    public static void register(RetromodTransformer transformer) {
        if (transformer.getSyntheticClasses().containsKey(INTERNAL)) return;
        transformer.registerSyntheticClass(INTERNAL, generate());
        transformer.registerSuperclassRebase(LEAVES, INTERNAL);
        transformer.registerConstructorRedirect(LEAVES, LEGACY_CTOR, INTERNAL, "create",
                FACTORY_DESC);
    }

    public static byte[] generate() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, INTERNAL, null, TINTED, null);

        // LegacyLeavesBlock(Properties p) { super(0.01f, p); }
        MethodVisitor legacy = cw.visitMethod(ACC_PUBLIC, "<init>", LEGACY_CTOR, null, null);
        legacy.visitCode();
        legacy.visitVarInsn(ALOAD, 0);
        legacy.visitLdcInsn(VANILLA_TINTED_CHANCE);
        legacy.visitVarInsn(ALOAD, 1);
        legacy.visitMethodInsn(INVOKESPECIAL, TINTED, "<init>", MODERN_CTOR, false);
        legacy.visitInsn(RETURN);
        legacy.visitMaxs(0, 0);
        legacy.visitEnd();

        // LegacyLeavesBlock(float chance, Properties p) { super(chance, p); }
        MethodVisitor modern = cw.visitMethod(ACC_PUBLIC, "<init>", MODERN_CTOR, null, null);
        modern.visitCode();
        modern.visitVarInsn(ALOAD, 0);
        modern.visitVarInsn(FLOAD, 1);
        modern.visitVarInsn(ALOAD, 2);
        modern.visitMethodInsn(INVOKESPECIAL, TINTED, "<init>", MODERN_CTOR, false);
        modern.visitInsn(RETURN);
        modern.visitMaxs(0, 0);
        modern.visitEnd();

        // static LeavesBlock create(Properties p) { return new TintedParticleLeavesBlock(0.01f, p); }
        MethodVisitor create = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "create", FACTORY_DESC,
                null, null);
        create.visitCode();
        create.visitTypeInsn(NEW, TINTED);
        create.visitInsn(DUP);
        create.visitLdcInsn(VANILLA_TINTED_CHANCE);
        create.visitVarInsn(ALOAD, 0);
        create.visitMethodInsn(INVOKESPECIAL, TINTED, "<init>", MODERN_CTOR, false);
        create.visitInsn(ARETURN);
        create.visitMaxs(0, 0);
        create.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }
}
