/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

/** Sends the four-argument {@code Scoreboard.addObjective} that 1.20.3 replaced to the six-argument one, as the scoreboard command adds. */
public final class LegacyScoreboardBridge {

    static final String HELPER = "com/retromod/generated/LegacyScoreboard";
    static final String SCOREBOARD = "net/minecraft/world/scores/Scoreboard";
    private static final String OBJECTIVE = "Lnet/minecraft/world/scores/Objective;";
    private static final String CRITERIA = "Lnet/minecraft/world/scores/criteria/ObjectiveCriteria;";
    private static final String COMPONENT = "Lnet/minecraft/network/chat/Component;";
    private static final String RENDER_TYPE = "Lnet/minecraft/world/scores/criteria/ObjectiveCriteria$RenderType;";
    static final String OLD_ADD = "(Ljava/lang/String;" + CRITERIA + COMPONENT + RENDER_TYPE + ")" + OBJECTIVE;
    static final String NEW_ADD = "(Ljava/lang/String;" + CRITERIA + COMPONENT + RENDER_TYPE + "ZLnet/minecraft/network/chat/numbers/NumberFormat;)" + OBJECTIVE;

    private LegacyScoreboardBridge() {}

    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector.read(SCOREBOARD));
    }

    static void register(RetromodTransformer t, ClassNode host) {
        if (!lacksOldAdd(host) || t.getSyntheticClasses().containsKey(HELPER)) return;
        t.registerSyntheticClass(HELPER, generateHelper());
        // MCreator procedures call it on the level's scoreboard, which a server's own subclass answers.
        t.registerInheritedMethodRedirect(SCOREBOARD, "addObjective", OLD_ADD, HELPER, "addObjective",
                "(L" + SCOREBOARD + ";" + OLD_ADD.substring(1));
    }

    /** Whether the host has only the 1.20.3 form; unreadable, a host from 1.20.3 does. */
    static boolean lacksOldAdd(ClassNode host) {
        if (host == null) return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "1.20.3") >= 0;
        boolean hasOld = host.methods.stream().anyMatch(m -> m.name.equals("addObjective") && m.desc.equals(OLD_ADD));
        boolean hasNew = host.methods.stream().anyMatch(m -> m.name.equals("addObjective") && m.desc.equals(NEW_ADD));
        return !hasOld && hasNew;
    }

    /** {@code addObjective(scoreboard, name, criteria, title, renderType)}: no automatic display update and the default number format. */
    static byte[] generateHelper() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, HELPER, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "addObjective",
                "(L" + SCOREBOARD + ";" + OLD_ADD.substring(1), null, null);
        mv.visitCode();
        for (int slot = 0; slot <= 4; slot++) mv.visitVarInsn(Opcodes.ALOAD, slot);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, SCOREBOARD, "addObjective", NEW_ADD, false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
