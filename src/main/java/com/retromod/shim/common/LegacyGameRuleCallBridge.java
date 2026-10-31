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

/** Keeps {@code level.getGameRules().getBoolean(rule)} and {@code getInt(rule)} working where only a server level has game rules. */
public final class LegacyGameRuleCallBridge {

    static final String CALLS = "com/retromod/generated/LegacyGameRuleCalls";
    private static final String LEVEL = "net/minecraft/world/level/Level";
    private static final String SERVER_LEVEL = "net/minecraft/server/level/ServerLevel";
    private static final String RULES = "net/minecraft/world/level/gamerules/GameRules";
    private static final String RULE = "net/minecraft/world/level/gamerules/GameRule";
    private static final String OLD_RULES = "net/minecraft/world/level/GameRules";
    private static final String OLD_KEY = "net/minecraft/world/level/GameRules$Key";

    private LegacyGameRuleCallBridge() {}

    /** Registers under the old and the moved class names, since either can be current when the redirect is asked. */
    public static void register(RetromodTransformer t) {
        t.registerSyntheticClass(CALLS, generate());
        String rules = "L" + RULES + ";";
        String levelHelper = "(L" + LEVEL + ";)" + rules;
        t.registerMethodRedirect(LEVEL, "getGameRules", "()" + rules, CALLS, "gameRules", levelHelper);
        t.registerMethodRedirect(LEVEL, "getGameRules", "()L" + OLD_RULES + ";", CALLS, "gameRules", levelHelper);
        for (String[] read : new String[][]{{"getBoolean", "Z"}, {"getInt", "I"}}) {
            String helper = "(" + rules + "L" + RULE + ";)" + read[1];
            t.registerMethodRedirect(RULES, read[0], "(L" + RULE + ";)" + read[1], CALLS, read[0], helper);
            t.registerMethodRedirect(OLD_RULES, read[0], "(L" + OLD_KEY + ";)" + read[1], CALLS, read[0], helper);
        }
    }

    static byte[] generate() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, CALLS, null, "java/lang/Object", null);
        // A server level answers with its rules; a client level, which has none now, with the defaults.
        MethodVisitor rules = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "gameRules", "(L" + LEVEL + ";)L" + RULES + ";", null, null);
        rules.visitCode();
        Label client = new Label();
        rules.visitVarInsn(ALOAD, 0);
        rules.visitTypeInsn(INSTANCEOF, SERVER_LEVEL);
        rules.visitJumpInsn(IFEQ, client);
        rules.visitVarInsn(ALOAD, 0);
        rules.visitTypeInsn(CHECKCAST, SERVER_LEVEL);
        rules.visitMethodInsn(INVOKEVIRTUAL, SERVER_LEVEL, "getGameRules", "()L" + RULES + ";", false);
        rules.visitInsn(ARETURN);
        rules.visitLabel(client);
        rules.visitFrame(F_SAME, 0, null, 0, null);
        rules.visitTypeInsn(NEW, RULES);
        rules.visitInsn(DUP);
        rules.visitVarInsn(ALOAD, 0);
        rules.visitMethodInsn(INVOKEVIRTUAL, LEVEL, "enabledFeatures", "()Lnet/minecraft/world/flag/FeatureFlagSet;", false);
        rules.visitMethodInsn(INVOKESPECIAL, RULES, "<init>", "(Lnet/minecraft/world/flag/FeatureFlagSet;)V", false);
        rules.visitInsn(ARETURN);
        rules.visitMaxs(0, 0);
        rules.visitEnd();

        // Typed reads unbox the rule's value.
        unboxedRead(cw, "getBoolean", "Z", "java/lang/Boolean", "booleanValue");
        unboxedRead(cw, "getInt", "I", "java/lang/Integer", "intValue");
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void unboxedRead(ClassWriter cw, String name, String primitive, String box, String unbox) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name, "(L" + RULES + ";L" + RULE + ";)" + primitive, null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, RULES, "get", "(L" + RULE + ";)Ljava/lang/Object;", false);
        mv.visitTypeInsn(CHECKCAST, box);
        mv.visitMethodInsn(INVOKEVIRTUAL, box, unbox, "()" + primitive, false);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }
}
