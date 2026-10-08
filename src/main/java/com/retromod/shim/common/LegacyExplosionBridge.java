/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import static org.objectweb.asm.Opcodes.*;

/**
 * Keeps 1.19.2 {@code Level.explode} calls working on 1.19.3 and newer.
 *
 * <p>1.19.2 took an {@code Explosion.BlockInteraction} of {@code NONE}, {@code BREAK} or
 * {@code DESTROY}. 1.19.3 changed every {@code explode} overload to take a
 * {@code Level.ExplosionInteraction}, and renamed the block interactions to {@code KEEP},
 * {@code DESTROY} and {@code DESTROY_WITH_DECAY}. An old mod's explosion failed with
 * {@code NoSuchFieldError} on the constant or {@code NoSuchMethodError} on the call.
 *
 * <p>The removed constants move to their survivors, {@code NONE} to {@code KEEP} and {@code BREAK}
 * to {@code DESTROY}, and each old overload calls the new one. {@code KEEP} becomes an explosion
 * that breaks nothing. Every breaking interaction becomes a block explosion, so the
 * {@code blockExplosionDropDecay} game rule decides how many blocks drop. An old {@code BREAK},
 * which always dropped every block, now follows that rule too.
 */
public final class LegacyExplosionBridge {

    static final String GENERATED = "com/retromod/generated/LegacyExplosions";
    private static final String LEVEL = "net/minecraft/world/level/Level";
    private static final String SERVER_LEVEL = "net/minecraft/server/level/ServerLevel";
    private static final String BLOCK_INTERACTION = "net/minecraft/world/level/Explosion$BlockInteraction";
    private static final String EXPLOSION_INTERACTION = "net/minecraft/world/level/Level$ExplosionInteraction";
    private static final String L_BLOCK_INTERACTION = "L" + BLOCK_INTERACTION + ";";
    private static final String L_EXPLOSION_INTERACTION = "L" + EXPLOSION_INTERACTION + ";";
    private static final String L_EXPLOSION = "Lnet/minecraft/world/level/Explosion;";
    private static final String L_ENTITY = "Lnet/minecraft/world/entity/Entity;";
    private static final String L_DAMAGE_SOURCE = "Lnet/minecraft/world/damagesource/DamageSource;";
    private static final String L_CALCULATOR = "Lnet/minecraft/world/level/ExplosionDamageCalculator;";

    /** The 1.19.2 {@code explode} parameter lists, each ending in the block interaction. */
    private static final String[] OLD_PARAMETERS = {
        L_ENTITY + "DDDF",
        L_ENTITY + "DDDFZ",
        L_ENTITY + L_DAMAGE_SOURCE + L_CALCULATOR + "DDDFZ",
    };

    private LegacyExplosionBridge() {}

    public static void register(RetromodTransformer transformer) {
        transformer.registerSyntheticClass(GENERATED, generate());
        transformer.registerFieldRedirect(BLOCK_INTERACTION, "NONE", BLOCK_INTERACTION, "KEEP");
        transformer.registerFieldRedirect(BLOCK_INTERACTION, "BREAK", BLOCK_INTERACTION, "DESTROY");
        for (String parameters : OLD_PARAMETERS) {
            String oldDesc = "(" + parameters + L_BLOCK_INTERACTION + ")" + L_EXPLOSION;
            String bridgeDesc = "(L" + LEVEL + ";" + parameters + L_BLOCK_INTERACTION + ")" + L_EXPLOSION;
            for (String owner : new String[]{LEVEL, SERVER_LEVEL}) {
                transformer.registerMethodRedirect(owner, "explode", oldDesc,
                        GENERATED, "explode", bridgeDesc, true);
            }
        }
    }

    static byte[] generate() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                return "java/lang/Object";
            }
        };
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, GENERATED, null, "java/lang/Object", null);
        emitInteraction(cw);
        for (String parameters : OLD_PARAMETERS) {
            emitExplode(cw, parameters);
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code interaction(old)}: {@code NONE} for {@code KEEP}, otherwise {@code BLOCK}. */
    private static void emitInteraction(ClassWriter cw) {
        MethodVisitor m = cw.visitMethod(ACC_PRIVATE | ACC_STATIC, "interaction",
                "(" + L_BLOCK_INTERACTION + ")" + L_EXPLOSION_INTERACTION, null, null);
        m.visitCode();
        Label breaks = new Label();
        m.visitVarInsn(ALOAD, 0);
        m.visitFieldInsn(GETSTATIC, BLOCK_INTERACTION, "KEEP", L_BLOCK_INTERACTION);
        m.visitJumpInsn(IF_ACMPNE, breaks);
        m.visitFieldInsn(GETSTATIC, EXPLOSION_INTERACTION, "NONE", L_EXPLOSION_INTERACTION);
        m.visitInsn(ARETURN);
        m.visitLabel(breaks);
        m.visitFieldInsn(GETSTATIC, EXPLOSION_INTERACTION, "BLOCK", L_EXPLOSION_INTERACTION);
        m.visitInsn(ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    /** {@code explode(level, ..., old)}: the same call with the converted interaction. */
    private static void emitExplode(ClassWriter cw, String parameters) {
        String bridgeDesc = "(L" + LEVEL + ";" + parameters + L_BLOCK_INTERACTION + ")" + L_EXPLOSION;
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "explode", bridgeDesc, null, null);
        m.visitCode();
        Type[] arguments = Type.getArgumentTypes(bridgeDesc);
        int slot = 0;
        for (int i = 0; i < arguments.length - 1; i++) {
            m.visitVarInsn(arguments[i].getOpcode(ILOAD), slot);
            slot += arguments[i].getSize();
        }
        m.visitVarInsn(ALOAD, slot);
        m.visitMethodInsn(INVOKESTATIC, GENERATED, "interaction",
                "(" + L_BLOCK_INTERACTION + ")" + L_EXPLOSION_INTERACTION, false);
        m.visitMethodInsn(INVOKEVIRTUAL, LEVEL, "explode",
                "(" + parameters + L_EXPLOSION_INTERACTION + ")" + L_EXPLOSION, false);
        m.visitInsn(ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }
}
