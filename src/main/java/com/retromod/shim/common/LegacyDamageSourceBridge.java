/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

/**
 * Keeps mods that build entity damage sources the 1.19.2 way running.
 *
 * <p>1.19.4 made every damage source point at a registered {@code DamageType}. A 1.19.2 mod wrote
 * {@code new EntityDamageSource("mod.slash", attacker).bypassArmor()}, and both the class and the
 * flag setters are gone. The source now comes from the attacker's own {@code DamageSources}: a
 * player attack for a player, a mob attack for any other living entity, and a thrown source for
 * the indirect form. The setters return the source unchanged.
 *
 * <p>What does not come back: the mod's own death message key and the flags. Both now live on a
 * registered damage type the old mod never shipped, so the hit is an ordinary attack, armor
 * applies, and the death message is the vanilla one.
 */
public final class LegacyDamageSourceBridge {

    static final String SOURCES = "com/retromod/generated/LegacyDamageSources";
    private static final String DAMAGE_SOURCE = "net/minecraft/world/damagesource/DamageSource";
    private static final String L_DAMAGE_SOURCE = "L" + DAMAGE_SOURCE + ";";
    private static final String DAMAGE_SOURCES = "net/minecraft/world/damagesource/DamageSources";
    private static final String ENTITY = "net/minecraft/world/entity/Entity";
    private static final String L_ENTITY = "L" + ENTITY + ";";
    private static final String LIVING = "net/minecraft/world/entity/LivingEntity";
    private static final String PLAYER = "net/minecraft/world/entity/player/Player";
    private static final String ENTITY_SOURCE = "net/minecraft/world/damagesource/EntityDamageSource";
    private static final String INDIRECT_SOURCE = "net/minecraft/world/damagesource/IndirectEntityDamageSource";

    /** 1.19.2 setters that marked a source; the marks are damage type tags now. */
    private static final String[] FLAG_SETTERS = {
        "bypassArmor", "bypassInvul", "bypassMagic", "bypassEnchantments", "setIsFire",
        "setProjectile", "setExplosion", "setMagic", "setNoAggro", "setScalesWithDifficulty",
        "damageHelmet", "setIsFall", "setThorns", "bypassCooldown"
    };

    private LegacyDamageSourceBridge() {}

    public static void register(RetromodTransformer transformer) {
        transformer.registerSyntheticClass(SOURCES, generate());

        String entityFactory = "(Ljava/lang/String;" + L_ENTITY + ")" + L_DAMAGE_SOURCE;
        String indirectFactory = "(Ljava/lang/String;" + L_ENTITY + L_ENTITY + ")" + L_DAMAGE_SOURCE;
        for (String owner : new String[]{ENTITY_SOURCE, DAMAGE_SOURCE}) {
            transformer.registerConstructorRedirect(owner, "(Ljava/lang/String;" + L_ENTITY + ")V",
                    SOURCES, "entity", entityFactory);
        }
        for (String owner : new String[]{INDIRECT_SOURCE, DAMAGE_SOURCE}) {
            transformer.registerConstructorRedirect(owner,
                    "(Ljava/lang/String;" + L_ENTITY + L_ENTITY + ")V", SOURCES, "indirect", indirectFactory);
        }
        transformer.registerClassRedirect(ENTITY_SOURCE, DAMAGE_SOURCE);
        transformer.registerClassRedirect(INDIRECT_SOURCE, DAMAGE_SOURCE);

        for (String setter : FLAG_SETTERS) {
            for (String owner : new String[]{DAMAGE_SOURCE, ENTITY_SOURCE, INDIRECT_SOURCE}) {
                transformer.registerMethodRedirect(owner, setter, "()" + L_DAMAGE_SOURCE,
                        SOURCES, "unchanged", "(" + L_DAMAGE_SOURCE + ")" + L_DAMAGE_SOURCE, true);
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
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, SOURCES, null, "java/lang/Object", null);
        emitEntity(cw);
        emitIndirect(cw);
        emitUnchanged(cw);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code entity(id, attacker)}: playerAttack, mobAttack, or generic from the attacker's sources. */
    private static void emitEntity(ClassWriter cw) {
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "entity",
                "(Ljava/lang/String;" + L_ENTITY + ")" + L_DAMAGE_SOURCE, null, null);
        m.visitCode();
        Label notPlayer = new Label();
        Label notLiving = new Label();
        m.visitVarInsn(ALOAD, 1);
        m.visitMethodInsn(INVOKEVIRTUAL, ENTITY, "damageSources", "()L" + DAMAGE_SOURCES + ";", false);
        m.visitVarInsn(ASTORE, 2);

        m.visitVarInsn(ALOAD, 1);
        m.visitTypeInsn(INSTANCEOF, PLAYER);
        m.visitJumpInsn(IFEQ, notPlayer);
        m.visitVarInsn(ALOAD, 2);
        m.visitVarInsn(ALOAD, 1);
        m.visitTypeInsn(CHECKCAST, PLAYER);
        m.visitMethodInsn(INVOKEVIRTUAL, DAMAGE_SOURCES, "playerAttack",
                "(L" + PLAYER + ";)" + L_DAMAGE_SOURCE, false);
        m.visitInsn(ARETURN);

        m.visitLabel(notPlayer);
        m.visitVarInsn(ALOAD, 1);
        m.visitTypeInsn(INSTANCEOF, LIVING);
        m.visitJumpInsn(IFEQ, notLiving);
        m.visitVarInsn(ALOAD, 2);
        m.visitVarInsn(ALOAD, 1);
        m.visitTypeInsn(CHECKCAST, LIVING);
        m.visitMethodInsn(INVOKEVIRTUAL, DAMAGE_SOURCES, "mobAttack",
                "(L" + LIVING + ";)" + L_DAMAGE_SOURCE, false);
        m.visitInsn(ARETURN);

        m.visitLabel(notLiving);
        m.visitVarInsn(ALOAD, 2);
        m.visitMethodInsn(INVOKEVIRTUAL, DAMAGE_SOURCES, "generic", "()" + L_DAMAGE_SOURCE, false);
        m.visitInsn(ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    /** {@code indirect(id, projectile, owner)}: a thrown source from the projectile's sources. */
    private static void emitIndirect(ClassWriter cw) {
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "indirect",
                "(Ljava/lang/String;" + L_ENTITY + L_ENTITY + ")" + L_DAMAGE_SOURCE, null, null);
        m.visitCode();
        m.visitVarInsn(ALOAD, 1);
        m.visitMethodInsn(INVOKEVIRTUAL, ENTITY, "damageSources", "()L" + DAMAGE_SOURCES + ";", false);
        m.visitVarInsn(ALOAD, 1);
        m.visitVarInsn(ALOAD, 2);
        m.visitMethodInsn(INVOKEVIRTUAL, DAMAGE_SOURCES, "thrown",
                "(" + L_ENTITY + L_ENTITY + ")" + L_DAMAGE_SOURCE, false);
        m.visitInsn(ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    /** {@code unchanged(source)}: the stand-in for every removed flag setter. */
    private static void emitUnchanged(ClassWriter cw) {
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "unchanged",
                "(" + L_DAMAGE_SOURCE + ")" + L_DAMAGE_SOURCE, null, null);
        m.visitCode();
        m.visitVarInsn(ALOAD, 0);
        m.visitInsn(ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }
}
