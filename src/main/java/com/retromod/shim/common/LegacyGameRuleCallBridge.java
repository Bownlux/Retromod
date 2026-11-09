/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

import java.util.List;

import static org.objectweb.asm.Opcodes.*;

/** Keeps {@code level.getGameRules().getBoolean(rule)} and {@code getInt(rule)} working where only a server level has game rules. */
public final class LegacyGameRuleCallBridge {

    static final String CALLS = "com/retromod/generated/LegacyGameRuleCalls";

    /** The names one helper is written in: Mojang names, or a Fabric 1.21.11 host's intermediary. */
    record Names(String helper, String level, String serverLevel, String clientLevel, String rules, String rule,
            String oldRules, String oldKey, String levelGetter, String serverGetter, String booleanRead,
            String intRead, String enabledFeatures, String featureSet, String get, List<String> negatedRules) {}

    // 1.21.11 turned three negative rules positive, such as DISABLE_RAIDS into RAIDS. A Fabric mod
    // remapped on 26.x reaches the positive name through the rule's unchanged intermediary id.
    static final Names MOJANG = new Names(CALLS, "net/minecraft/world/level/Level",
            "net/minecraft/server/level/ServerLevel", "net/minecraft/client/multiplayer/ClientLevel",
            "net/minecraft/world/level/gamerules/GameRules", "net/minecraft/world/level/gamerules/GameRule",
            "net/minecraft/world/level/GameRules", "net/minecraft/world/level/GameRules$Key",
            "getGameRules", "getGameRules", "getBoolean", "getInt", "enabledFeatures",
            "net/minecraft/world/flag/FeatureFlagSet", "get",
            List.of("ELYTRA_MOVEMENT_CHECK", "PLAYER_MOVEMENT_CHECK", "RAIDS"));

    // A Fabric 1.21.11 host runs intermediary names. The rule fields kept their ids but took the new
    // rule type, the typed reads became get, and only the server level kept a getter, under a new
    // id. Checked against the 1.21.11 intermediary table and the remapped 1.21.11 game jar.
    static final Names INTERMEDIARY = new Names("com/retromod/generated/LegacyIntermediaryGameRuleCalls",
            "net/minecraft/class_1937", "net/minecraft/class_3218", "net/minecraft/class_638",
            "net/minecraft/class_1928", "net/minecraft/class_12279", "net/minecraft/class_1928",
            "net/minecraft/class_1928$class_4313", "method_8450", "method_64395", "method_8355", "method_8356",
            "method_45162", "net/minecraft/class_7699", "method_76185",
            List.of("field_19404", "field_54909", "field_19422"));

    private LegacyGameRuleCallBridge() {}

    /** Registers under the old and the moved class names, since either can be current when the redirect is asked. */
    public static void register(RetromodTransformer t) {
        register(t, MOJANG);
    }

    /** The same reads in the intermediary names a Fabric 1.21.11 host runs. */
    public static void registerIntermediary(RetromodTransformer t) {
        // The old key type became the rule type, so the rule fields and the mod's own locals take it.
        t.registerClassRedirect(INTERMEDIARY.oldKey(), INTERMEDIARY.rule());
        register(t, INTERMEDIARY);
    }

    private static void register(RetromodTransformer t, Names names) {
        t.registerSyntheticClass(names.helper(), generate(names));
        String rules = "L" + names.rules() + ";";
        String levelHelper = "(L" + names.level() + ";)" + rules;
        // javac names the receiver's own type as the owner, so a client level's call is keyed too.
        for (String owner : List.of(names.level(), names.clientLevel())) {
            t.registerMethodRedirect(owner, names.levelGetter(), "()" + rules, names.helper(), "gameRules", levelHelper);
            t.registerMethodRedirect(owner, names.levelGetter(), "()L" + names.oldRules() + ";", names.helper(),
                    "gameRules", levelHelper);
        }
        // A server level kept its getter, which only needs renaming where its id changed.
        if (!names.serverGetter().equals(names.levelGetter())) {
            t.registerMethodRedirect(names.serverLevel(), names.levelGetter(), "()" + rules,
                    names.serverLevel(), names.serverGetter(), "()" + rules);
        }
        String[][] reads = {{names.booleanRead(), "getBoolean", "Z"}, {names.intRead(), "getInt", "I"}};
        for (String[] read : reads) {
            String helper = "(" + rules + "L" + names.rule() + ";)" + read[2];
            t.registerMethodRedirect(names.rules(), read[0], "(L" + names.rule() + ";)" + read[2], names.helper(),
                    read[1], helper);
            t.registerMethodRedirect(names.oldRules(), read[0], "(L" + names.oldKey() + ";)" + read[2], names.helper(),
                    read[1], helper);
        }
    }

    static byte[] generate() {
        return generate(MOJANG);
    }

    static byte[] generate(Names names) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, names.helper(), null, "java/lang/Object", null);
        String rulesDesc = "L" + names.rules() + ";";
        // A server level answers with its rules; a client level, which has none now, with the defaults.
        MethodVisitor rules = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "gameRules", "(L" + names.level() + ";)" + rulesDesc,
                null, null);
        rules.visitCode();
        Label client = new Label();
        rules.visitVarInsn(ALOAD, 0);
        rules.visitTypeInsn(INSTANCEOF, names.serverLevel());
        rules.visitJumpInsn(IFEQ, client);
        rules.visitVarInsn(ALOAD, 0);
        rules.visitTypeInsn(CHECKCAST, names.serverLevel());
        rules.visitMethodInsn(INVOKEVIRTUAL, names.serverLevel(), names.serverGetter(), "()" + rulesDesc, false);
        rules.visitInsn(ARETURN);
        rules.visitLabel(client);
        rules.visitFrame(F_SAME, 0, null, 0, null);
        rules.visitTypeInsn(NEW, names.rules());
        rules.visitInsn(DUP);
        rules.visitVarInsn(ALOAD, 0);
        rules.visitMethodInsn(INVOKEVIRTUAL, names.level(), names.enabledFeatures(), "()L" + names.featureSet() + ";", false);
        rules.visitMethodInsn(INVOKESPECIAL, names.rules(), "<init>", "(L" + names.featureSet() + ";)V", false);
        rules.visitInsn(ARETURN);
        rules.visitMaxs(0, 0);
        rules.visitEnd();

        // Typed reads unbox the rule's value.
        unboxedRead(cw, names, "getBoolean", "Z", "java/lang/Boolean", "booleanValue", names.negatedRules());
        unboxedRead(cw, names, "getInt", "I", "java/lang/Integer", "intValue", List.of());
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void unboxedRead(ClassWriter cw, Names names, String name, String primitive, String box,
            String unbox, List<String> negated) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name,
                "(L" + names.rules() + ";L" + names.rule() + ";)" + primitive, null, null);
        mv.visitCode();
        // Only an old mod reads through here, so a rule that became positive is read negated.
        Label negate = new Label();
        for (String field : negated) {
            mv.visitVarInsn(ALOAD, 1);
            mv.visitFieldInsn(GETSTATIC, names.rules(), field, "L" + names.rule() + ";");
            mv.visitJumpInsn(IF_ACMPEQ, negate);
        }
        emitGet(mv, names, primitive, box, unbox);
        mv.visitInsn(IRETURN);
        if (!negated.isEmpty()) {
            mv.visitLabel(negate);
            mv.visitFrame(F_SAME, 0, null, 0, null);
            emitGet(mv, names, primitive, box, unbox);
            mv.visitInsn(ICONST_1);
            mv.visitInsn(IXOR);
            mv.visitInsn(IRETURN);
        }
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** Pushes {@code ((Box) rules.get(rule)).unbox()}. */
    private static void emitGet(MethodVisitor mv, Names names, String primitive, String box, String unbox) {
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, names.rules(), names.get(), "(L" + names.rule() + ";)Ljava/lang/Object;", false);
        mv.visitTypeInsn(CHECKCAST, box);
        mv.visitMethodInsn(INVOKEVIRTUAL, box, unbox, "()" + primitive, false);
    }
}
