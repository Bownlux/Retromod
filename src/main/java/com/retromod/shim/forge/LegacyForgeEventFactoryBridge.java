/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

import java.util.List;
import java.util.function.Predicate;

import static org.objectweb.asm.Opcodes.*;

/** Keeps Forge 1.20.1 {@code ForgeEventFactory} calls working on NeoForge's {@code EventHooks} and on Forge 26.2. */
final class LegacyForgeEventFactoryBridge {

    static final String FORGE = "net/minecraftforge/event/ForgeEventFactory";
    static final String NEO = "net/neoforged/neoforge/event/EventHooks";

    private static final String LEVEL = "Lnet/minecraft/world/level/Level;";
    private static final String ENTITY = "Lnet/minecraft/world/entity/Entity;";
    private static final String SPAWN_ARGS = "Lnet/minecraft/world/entity/Mob;"
            + "Lnet/minecraft/world/level/ServerLevelAccessor;Lnet/minecraft/world/DifficultyInstance;"
            + "Lnet/minecraft/world/entity/MobSpawnType;Lnet/minecraft/world/entity/SpawnGroupData;";
    private static final String SPAWN_DATA = "Lnet/minecraft/world/entity/SpawnGroupData;";

    static final String GRIEFING_HELPER = "com/retromod/generated/LegacyMobGriefing";
    static final String GRIEFING_EVENT = "net/minecraftforge/event/entity/EntityMobGriefingEvent";
    static final String OLD_GRIEF = "(" + LEVEL + ENTITY + ")Z";
    private static final String SERVER_LEVEL = "net/minecraft/server/level/ServerLevel";
    private static final String RESULT = "net/minecraftforge/common/util/Result";
    private static final String EVENT_BUS = "net/minecraftforge/eventbus/api/bus/EventBus";

    private LegacyForgeEventFactoryBridge() {}

    /**
     * Forge 26.2 takes a {@code ServerLevel} in {@code getMobGriefingEvent}, so the old {@code Level}
     * form, which mobs also call on the client, gets a helper. Offline, a Forge target from 26.2.
     */
    static void registerForge(RetromodTransformer transformer) {
        if (!ForgeHostShape.matches(List.of(GRIEFING_EVENT, RESULT), List.of())) return;
        if (!ForgeHostMembers.lacksMethod(FORGE, "getMobGriefingEvent", OLD_GRIEF, ForgeHostShape.FIRST_CHECKED_VERSION)) return;
        transformer.registerSyntheticClass(GRIEFING_HELPER, generateGriefing());
        transformer.registerMethodRedirect(FORGE, "getMobGriefingEvent", OLD_GRIEF,
                GRIEFING_HELPER, "getMobGriefingEvent", OLD_GRIEF);
    }

    /**
     * A server level asks the host, which reads the game rule and posts the event. Anywhere else
     * the event is posted and griefing is allowed unless a listener denies it, since a client level
     * no longer carries the rule.
     */
    static byte[] generateGriefing() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                return "java/lang/Object";
            }
        };
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, GRIEFING_HELPER, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "getMobGriefingEvent", OLD_GRIEF, null, null);
        mv.visitCode();
        Label notServer = new Label();
        Label withEntity = new Label();
        Label denied = new Label();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitTypeInsn(INSTANCEOF, SERVER_LEVEL);
        mv.visitJumpInsn(IFEQ, notServer);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitTypeInsn(CHECKCAST, SERVER_LEVEL);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKESTATIC, FORGE, "getMobGriefingEvent", "(L" + SERVER_LEVEL + ";" + ENTITY + ")Z", false);
        mv.visitInsn(IRETURN);

        // Without an entity there is no event to post, and no rule to read.
        mv.visitLabel(notServer);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitJumpInsn(IFNONNULL, withEntity);
        mv.visitInsn(ICONST_1);
        mv.visitInsn(IRETURN);

        // EntityMobGriefingEvent.BUS.post(event); return event.getResult() != Result.DENY;
        mv.visitLabel(withEntity);
        mv.visitTypeInsn(NEW, GRIEFING_EVENT);
        mv.visitInsn(DUP);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKESPECIAL, GRIEFING_EVENT, "<init>", "(" + ENTITY + ")V", false);
        mv.visitVarInsn(ASTORE, 2);
        mv.visitFieldInsn(GETSTATIC, GRIEFING_EVENT, "BUS", "L" + EVENT_BUS + ";");
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKEINTERFACE, EVENT_BUS, "post", "(Lnet/minecraftforge/eventbus/internal/Event;)Z", true);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKEVIRTUAL, GRIEFING_EVENT, "getResult", "()L" + RESULT + ";", false);
        mv.visitFieldInsn(GETSTATIC, RESULT, "DENY", "L" + RESULT + ";");
        mv.visitJumpInsn(IF_ACMPEQ, denied);
        mv.visitInsn(ICONST_1);
        mv.visitInsn(IRETURN);
        mv.visitLabel(denied);
        mv.visitInsn(ICONST_0);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * NeoForge renamed the class and kept most hooks with the same name and descriptor, such as
     * {@code onExplosionStart}, {@code onAnimalTame} and {@code getExperienceDrop}. Two hooks a mob
     * mod commonly calls changed: the griefing check is now {@code canEntityGrief}, and the
     * finalize-spawn hook is {@code finalizeMobSpawn} without the NBT argument 1.20.5 removed. A hook
     * NeoForge removed still fails when it is called.
     */
    static boolean register(RetromodTransformer transformer, Predicate<String> hostHasClass) {
        if (hostHasClass.test(FORGE) || !hostHasClass.test(NEO)) return false;
        transformer.registerClassRedirect(FORGE, NEO);
        String grief = "(" + LEVEL + ENTITY + ")Z";
        transformer.registerMethodRedirect(FORGE, "getMobGriefingEvent", grief, NEO, "canEntityGrief", grief);
        // Arg-drop redirects have no owner alias, and the class rename reaches the call first.
        for (String owner : new String[] {FORGE, NEO}) {
            transformer.registerArgDropMethodRedirect(owner, "onFinalizeSpawn",
                    "(" + SPAWN_ARGS + "Lnet/minecraft/nbt/CompoundTag;)" + SPAWN_DATA,
                    NEO, "finalizeMobSpawn", "(" + SPAWN_ARGS + ")" + SPAWN_DATA);
        }
        return true;
    }
}
