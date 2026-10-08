/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

/**
 * Keeps calls that 1.18.2 mobs make on every spawn, despawn check, and menu open linking on a
 * Forge 1.19 or newer host.
 *
 * <p>1.19 folded {@code ClientboundAddMobPacket} into {@code ClientboundAddEntityPacket}, whose
 * constructor takes any entity, so a mob's spawn packet failed the first time a player came near.
 * Forge renamed {@code NetworkHooks.openGui} to {@code openScreen} and gave
 * {@code canEntityDespawn} a level argument. {@code IntProvider.sample} and the loot builder stopped
 * taking a {@code java.util.Random}. A sample now draws from a source seeded by the mod's random,
 * and the loot builder's random is dropped, because 1.20 seeds loot from the level instead.
 */
public final class LegacyForge119Calls {

    static final String SYNTH = "com/retromod/generated/LegacyForge119Calls";
    private static final String LIVING = "Lnet/minecraft/world/entity/LivingEntity;";
    private static final String MOB = "net/minecraft/world/entity/Mob";
    private static final String ADD_MOB = "net/minecraft/network/protocol/game/ClientboundAddMobPacket";
    private static final String ADD_ENTITY = "net/minecraft/network/protocol/game/ClientboundAddEntityPacket";
    private static final String INT_PROVIDER = "net/minecraft/util/valueproviders/IntProvider";
    private static final String RANDOM_SOURCE = "net/minecraft/util/RandomSource";
    private static final String LOOT_BUILDER = "net/minecraft/world/level/storage/loot/LootParams$Builder";
    private static final String EVENT_FACTORY = "net/minecraftforge/event/ForgeEventFactory";
    private static final String RESULT = "Lnet/minecraftforge/eventbus/api/Event$Result;";
    private static final String SERVER_LEVEL_ACCESSOR = "net/minecraft/world/level/ServerLevelAccessor";
    private static final String RANDOM = "Ljava/util/Random;";

    private LegacyForge119Calls() {
    }

    public static void register(RetromodTransformer transformer) {
        transformer.registerSyntheticClass(SYNTH, generate());

        // 1.20.5 gave the spawn packet a ServerEntity argument, so the factory stops there.
        if (RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "1.20.5") < 0) {
            String factory = "(" + LIVING + ")L" + ADD_ENTITY + ";";
            for (String owner : new String[]{ADD_MOB, ADD_ENTITY}) {
                transformer.registerConstructorRedirect(owner, "(" + LIVING + ")V", SYNTH, "addMob", factory);
            }
        }

        String hooks = "net/minecraftforge/network/NetworkHooks";
        String player = "Lnet/minecraft/server/level/ServerPlayer;";
        String provider = "Lnet/minecraft/world/MenuProvider;";
        for (String extra : new String[]{"", "Lnet/minecraft/core/BlockPos;", "Ljava/util/function/Consumer;"}) {
            String desc = "(" + player + provider + extra + ")V";
            transformer.registerMethodRedirect(hooks, "openGui", desc, hooks, "openScreen", desc);
        }

        transformer.registerMethodRedirect(EVENT_FACTORY, "canEntityDespawn", "(L" + MOB + ";)" + RESULT,
                SYNTH, "canEntityDespawn", "(L" + MOB + ";)" + RESULT);

        // UniformInt, ConstantInt and the rest are called through their own class name.
        String sampleDesc = "(L" + INT_PROVIDER + ";" + RANDOM + ")I";
        for (String provided : new String[]{"IntProvider", "UniformInt", "ConstantInt", "BiasedToBottomInt",
                "ClampedInt", "ClampedNormalInt", "WeightedListInt"}) {
            transformer.registerMethodRedirect("net/minecraft/util/valueproviders/" + provided, "sample",
                    "(" + RANDOM + ")I", SYNTH, "sample", sampleDesc, true);
        }
        String lootDesc = "(L" + LOOT_BUILDER + ";" + RANDOM + ")L" + LOOT_BUILDER + ";";
        transformer.registerMethodRedirect(LOOT_BUILDER, "withRandom", "(" + RANDOM + ")L" + LOOT_BUILDER + ";",
                SYNTH, "withRandom", lootDesc, true);
    }

    static byte[] generate() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                return "java/lang/Object";
            }
        };
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, SYNTH, null, "java/lang/Object", null);

        MethodVisitor packet = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "addMob",
                "(" + LIVING + ")L" + ADD_ENTITY + ";", null, null);
        packet.visitCode();
        packet.visitTypeInsn(NEW, ADD_ENTITY);
        packet.visitInsn(DUP);
        packet.visitVarInsn(ALOAD, 0);
        packet.visitMethodInsn(INVOKESPECIAL, ADD_ENTITY, "<init>",
                "(Lnet/minecraft/world/entity/Entity;)V", false);
        packet.visitInsn(ARETURN);
        packet.visitMaxs(0, 0);
        packet.visitEnd();

        // Despawn checks only run on the server, where the level is always a ServerLevelAccessor.
        MethodVisitor despawn = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "canEntityDespawn",
                "(L" + MOB + ";)" + RESULT, null, null);
        despawn.visitCode();
        Label notServer = new Label();
        despawn.visitVarInsn(ALOAD, 0);
        despawn.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/world/entity/Entity", "level",
                "()Lnet/minecraft/world/level/Level;", false);
        despawn.visitInsn(DUP);
        despawn.visitTypeInsn(INSTANCEOF, SERVER_LEVEL_ACCESSOR);
        despawn.visitJumpInsn(IFEQ, notServer);
        despawn.visitTypeInsn(CHECKCAST, SERVER_LEVEL_ACCESSOR);
        despawn.visitVarInsn(ASTORE, 1);
        despawn.visitVarInsn(ALOAD, 0);
        despawn.visitVarInsn(ALOAD, 1);
        despawn.visitMethodInsn(INVOKESTATIC, EVENT_FACTORY, "canEntityDespawn",
                "(L" + MOB + ";L" + SERVER_LEVEL_ACCESSOR + ";)" + RESULT, false);
        despawn.visitInsn(ARETURN);
        despawn.visitLabel(notServer);
        despawn.visitInsn(POP);
        despawn.visitFieldInsn(GETSTATIC, "net/minecraftforge/eventbus/api/Event$Result", "DEFAULT", RESULT);
        despawn.visitInsn(ARETURN);
        despawn.visitMaxs(0, 0);
        despawn.visitEnd();

        MethodVisitor sample = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "sample",
                "(L" + INT_PROVIDER + ";" + RANDOM + ")I", null, null);
        sample.visitCode();
        sample.visitVarInsn(ALOAD, 0);
        sample.visitVarInsn(ALOAD, 1);
        sample.visitMethodInsn(INVOKEVIRTUAL, "java/util/Random", "nextLong", "()J", false);
        sample.visitMethodInsn(INVOKESTATIC, RANDOM_SOURCE, "create", "(J)L" + RANDOM_SOURCE + ";", true);
        sample.visitMethodInsn(INVOKEVIRTUAL, INT_PROVIDER, "sample", "(L" + RANDOM_SOURCE + ";)I", false);
        sample.visitInsn(IRETURN);
        sample.visitMaxs(0, 0);
        sample.visitEnd();

        MethodVisitor loot = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "withRandom",
                "(L" + LOOT_BUILDER + ";" + RANDOM + ")L" + LOOT_BUILDER + ";", null, null);
        loot.visitCode();
        loot.visitVarInsn(ALOAD, 0);
        loot.visitInsn(ARETURN);
        loot.visitMaxs(0, 0);
        loot.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }
}
