/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.function.Predicate;

/**
 * Bridges Forge's static hook classes onto NeoForge's for Forge 1.20.1 mods.
 *
 * <p>NeoForge renamed {@code ForgeHooks} to {@code CommonHooks} and kept most members with the same
 * shape, so the class is renamed whole. {@code ForgeEventFactory} is renamed by
 * {@link LegacyForgeEventFactoryBridge}, which also owns its changed hooks.
 * The damage hooks changed with NeoForge's damage pipeline and go through a generated helper:
 * <ul>
 *   <li>{@code onLivingAttack} posts {@code LivingIncomingDamageEvent} and answers whether the
 *       attack may continue, as before.</li>
 *   <li>{@code onLivingHurt} returns the amount unchanged. NeoForge already posted the incoming
 *       damage event, which is where Forge's hurt listeners now run, from {@code hurt}.</li>
 *   <li>{@code onLivingDamage} posts {@code LivingDamageEvent.Pre} and returns the new damage.</li>
 *   <li>{@code onLivingDrops} drops the looting level NeoForge no longer passes.</li>
 * </ul>
 * {@code TierSortingRegistry.isCorrectTierForDrops} becomes the 1.20.5 tag check: a tier may mine
 * a block unless the block is in the tier's incorrect-for-drops tag. Forge's custom tier ordering
 * is gone with it. {@code getLootingLevel} has no counterpart and stays unbridged.
 */
public final class LegacyForgeHooksBridge {

    static final String HELPER = "com/retromod/generated/LegacyForgeHooks";

    static final String FORGE_HOOKS = "net/minecraftforge/common/ForgeHooks";
    static final String COMMON_HOOKS = "net/neoforged/neoforge/common/CommonHooks";
    static final String TIER_SORTING = "net/minecraftforge/common/TierSortingRegistry";
    static final String DAMAGE_CONTAINER = "net/neoforged/neoforge/common/damagesource/DamageContainer";

    private static final String LIVING = "Lnet/minecraft/world/entity/LivingEntity;";
    private static final String SOURCE = "Lnet/minecraft/world/damagesource/DamageSource;";
    private static final String TIER = "net/minecraft/world/item/Tier";
    private static final String STATE = "net/minecraft/world/level/block/state/BlockState";
    private static final String STATE_BASE = "net/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase";
    private static final String TAG_KEY = "net/minecraft/tags/TagKey";

    static final String ATTACK_DESC = "(" + LIVING + SOURCE + "F)Z";
    static final String AMOUNT_DESC = "(" + LIVING + SOURCE + "F)F";
    static final String OLD_DROPS_DESC = "(" + LIVING + SOURCE + "Ljava/util/Collection;IZ)Z";
    static final String DROPS_DESC = "(" + LIVING + SOURCE + "Ljava/util/Collection;Z)Z";
    static final String TIER_DESC = "(L" + TIER + ";L" + STATE + ";)Z";

    private LegacyForgeHooksBridge() {}

    /** Registers what this host can take; each target class is checked first. */
    public static void register(RetromodTransformer transformer, Predicate<String> hostHasClass) {
        if (!hostHasClass.test(COMMON_HOOKS) || !hostHasClass.test(DAMAGE_CONTAINER)) return;
        // Tier itself was replaced by ToolMaterial in 1.21.2, so the tier check needs the 1.21.1 shape.
        registerRedirects(transformer, hostHasClass.test(TIER));
    }

    /** Unconditional registration for transform-shape tests. */
    static void registerRedirects(RetromodTransformer transformer, boolean tierCheck) {
        transformer.registerSyntheticClass(HELPER, generateHelper());
        // Class first, so the member redirects below also match the renamed owner.
        transformer.registerClassRedirect(FORGE_HOOKS, COMMON_HOOKS);
        transformer.registerMethodRedirect(FORGE_HOOKS, "onLivingAttack", ATTACK_DESC,
                HELPER, "onLivingAttack", ATTACK_DESC);
        transformer.registerMethodRedirect(FORGE_HOOKS, "onLivingHurt", AMOUNT_DESC,
                HELPER, "onLivingHurt", AMOUNT_DESC);
        transformer.registerMethodRedirect(FORGE_HOOKS, "onLivingDamage", AMOUNT_DESC,
                HELPER, "onLivingDamage", AMOUNT_DESC);
        transformer.registerMethodRedirect(FORGE_HOOKS, "onLivingDrops", OLD_DROPS_DESC,
                HELPER, "onLivingDrops", OLD_DROPS_DESC);
        if (tierCheck) {
            transformer.registerMethodRedirect(TIER_SORTING, "isCorrectTierForDrops", TIER_DESC,
                    HELPER, "isCorrectTierForDrops", TIER_DESC);
        }
    }

    static byte[] generateHelper() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                // Minecraft is not on Retromod's classpath when this class is generated.
                return "java/lang/Object";
            }
        };
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                HELPER, null, "java/lang/Object", null);

        MethodVisitor attack = begin(cw, "onLivingAttack", ATTACK_DESC);
        Label cancelled = new Label();
        attack.visitVarInsn(Opcodes.ALOAD, 0);
        newContainer(attack);
        attack.visitMethodInsn(Opcodes.INVOKESTATIC, COMMON_HOOKS, "onEntityIncomingDamage",
                "(" + LIVING + "L" + DAMAGE_CONTAINER + ";)Z", false);
        attack.visitJumpInsn(Opcodes.IFNE, cancelled);
        attack.visitInsn(Opcodes.ICONST_1);
        attack.visitInsn(Opcodes.IRETURN);
        attack.visitLabel(cancelled);
        attack.visitInsn(Opcodes.ICONST_0);
        attack.visitInsn(Opcodes.IRETURN);
        end(attack);

        MethodVisitor hurt = begin(cw, "onLivingHurt", AMOUNT_DESC);
        hurt.visitVarInsn(Opcodes.FLOAD, 2);
        hurt.visitInsn(Opcodes.FRETURN);
        end(hurt);

        MethodVisitor damage = begin(cw, "onLivingDamage", AMOUNT_DESC);
        damage.visitVarInsn(Opcodes.ALOAD, 0);
        newContainer(damage);
        damage.visitMethodInsn(Opcodes.INVOKESTATIC, COMMON_HOOKS, "onLivingDamagePre",
                "(" + LIVING + "L" + DAMAGE_CONTAINER + ";)F", false);
        damage.visitInsn(Opcodes.FRETURN);
        end(damage);

        MethodVisitor drops = begin(cw, "onLivingDrops", OLD_DROPS_DESC);
        drops.visitVarInsn(Opcodes.ALOAD, 0);
        drops.visitVarInsn(Opcodes.ALOAD, 1);
        drops.visitVarInsn(Opcodes.ALOAD, 2);
        drops.visitVarInsn(Opcodes.ILOAD, 4);
        drops.visitMethodInsn(Opcodes.INVOKESTATIC, COMMON_HOOKS, "onLivingDrops", DROPS_DESC, false);
        drops.visitInsn(Opcodes.IRETURN);
        end(drops);

        MethodVisitor tier = begin(cw, "isCorrectTierForDrops", TIER_DESC);
        Label incorrect = new Label();
        tier.visitVarInsn(Opcodes.ALOAD, 1);
        tier.visitVarInsn(Opcodes.ALOAD, 0);
        tier.visitMethodInsn(Opcodes.INVOKEINTERFACE, TIER, "getIncorrectBlocksForDrops",
                "()L" + TAG_KEY + ";", true);
        tier.visitMethodInsn(Opcodes.INVOKEVIRTUAL, STATE_BASE, "is", "(L" + TAG_KEY + ";)Z", false);
        tier.visitJumpInsn(Opcodes.IFNE, incorrect);
        tier.visitInsn(Opcodes.ICONST_1);
        tier.visitInsn(Opcodes.IRETURN);
        tier.visitLabel(incorrect);
        tier.visitInsn(Opcodes.ICONST_0);
        tier.visitInsn(Opcodes.IRETURN);
        end(tier);

        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A fresh container for (source, amount) in slots 1 and 2. */
    private static void newContainer(MethodVisitor mv) {
        mv.visitTypeInsn(Opcodes.NEW, DAMAGE_CONTAINER);
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.FLOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, DAMAGE_CONTAINER, "<init>", "(" + SOURCE + "F)V", false);
    }

    private static MethodVisitor begin(ClassWriter cw, String name, String desc) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, desc, null, null);
        mv.visitCode();
        return mv;
    }

    private static void end(MethodVisitor mv) {
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }
}
