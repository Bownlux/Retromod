/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

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

    /** The server's own rule getter, and the level data read that stands in for it where the getter is gone. */
    record ServerRead(List<String> servers, String getter, String worldDataGetter, String worldData,
            String worldDataRules) {}

    // 1.21.11 dropped MinecraftServer.getGameRules(), which 26.1 brought back. The level data keeps
    // the same rules the overworld reads. Checked with javap on NeoForge 21.11.42 and 26.1 to 26.3.
    static final ServerRead MOJANG_SERVER = new ServerRead(List.of("net/minecraft/server/MinecraftServer",
            "net/minecraft/server/dedicated/DedicatedServer", "net/minecraft/client/server/IntegratedServer"),
            "getGameRules", "getWorldData", "net/minecraft/world/level/storage/WorldData", "getGameRules");

    // The same read in Fabric 1.21.11's intermediary names, from the 1.21.11 intermediary table.
    static final ServerRead INTERMEDIARY_SERVER = new ServerRead(List.of("net/minecraft/server/MinecraftServer",
            "net/minecraft/class_3176", "net/minecraft/class_1132"),
            "method_3767", "method_27728", "net/minecraft/class_5219", "method_146");

    private LegacyGameRuleCallBridge() {}

    /** Registers under the old and the moved class names, since either can be current when the redirect is asked. */
    public static void register(RetromodTransformer t) {
        register(t, LegacyGameRuleValueBridge.hostShape());
    }

    static void register(RetromodTransformer t, LegacyGameRuleValueBridge.Shape shape) {
        ClassNode server = ClassResourceInspector.read(MOJANG_SERVER.servers().get(0));
        register(t, MOJANG, shape.radius(), hostDroppedServerRules(server) ? MOJANG_SERVER : null);
        // The old value objects from getRule, rebuilt as views over the same host rules.
        LegacyGameRuleValueBridge.register(t, shape);
    }

    /** The same reads in the intermediary names a Fabric 1.21.11 host runs. */
    public static void registerIntermediary(RetromodTransformer t) {
        // The old key type became the rule type, so the rule fields and the mod's own locals take it.
        t.registerClassRedirect(INTERMEDIARY.oldKey(), INTERMEDIARY.rule());
        // Only 1.21.11 runs intermediary names with the new rules, and its server has no rule getter.
        register(t, INTERMEDIARY, false, INTERMEDIARY_SERVER);
    }

    /** Whether the host's server lost its rule getter; offline, the 1.21.11 line before 26.1 lost it. */
    static boolean hostDroppedServerRules(ClassNode server) {
        if (server == null) return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "26.1") < 0;
        String desc = "()L" + MOJANG.rules() + ";";
        for (MethodNode method : server.methods) {
            if (method.name.equals(MOJANG_SERVER.getter()) && method.desc.equals(desc)) return false;
        }
        return true;
    }

    private static void register(RetromodTransformer t, Names names, boolean radius, ServerRead serverRead) {
        t.registerSyntheticClass(names.helper(), generate(names, radius, serverRead));
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
        if (serverRead != null) {
            // A server's own rule read becomes its level data's, under either return spelling.
            String serverHelper = "(L" + serverRead.servers().get(0) + ";)" + rules;
            for (String owner : serverRead.servers()) {
                for (String returned : List.of(rules, "L" + names.oldRules() + ";")) {
                    t.registerMethodRedirect(owner, serverRead.getter(), "()" + returned, names.helper(),
                            "serverGameRules", serverHelper);
                }
            }
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
        return generate(names, false, null);
    }

    /**
     * With {@code radius}, the old fire tick switch, renamed to the fire spread radius, reads "on" while the radius is not 0.
     * With {@code serverRead}, a server's rules are read from its level data.
     */
    static byte[] generate(Names names, boolean radius, ServerRead serverRead) {
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
        if (serverRead != null) serverRules(cw, rulesDesc, serverRead);

        // Typed reads unbox the rule's value.
        unboxedRead(cw, names, "getBoolean", "Z", "java/lang/Boolean", "booleanValue", names.negatedRules(), radius);
        unboxedRead(cw, names, "getInt", "I", "java/lang/Integer", "intValue", List.of(), false);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code serverGameRules(server)}: {@code server.getWorldData().getGameRules()}. */
    private static void serverRules(ClassWriter cw, String rulesDesc, ServerRead read) {
        String server = read.servers().get(0);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "serverGameRules", "(L" + server + ";)" + rulesDesc,
                null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, server, read.worldDataGetter(), "()L" + read.worldData() + ";", false);
        mv.visitMethodInsn(INVOKEINTERFACE, read.worldData(), read.worldDataRules(), "()" + rulesDesc, true);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void unboxedRead(ClassWriter cw, Names names, String name, String primitive, String box,
            String unbox, List<String> negated, boolean radius) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name,
                "(L" + names.rules() + ";L" + names.rule() + ";)" + primitive, null, null);
        mv.visitCode();
        if (radius) {
            Label notRadius = new Label();
            mv.visitVarInsn(ALOAD, 1);
            mv.visitFieldInsn(GETSTATIC, names.rules(), LegacyGameRuleValueBridge.RADIUS_RULE, "L" + names.rule() + ";");
            mv.visitJumpInsn(IF_ACMPNE, notRadius);
            Label stopped = new Label();
            emitGet(mv, names, "I", "java/lang/Integer", "intValue");
            mv.visitJumpInsn(IFEQ, stopped);
            mv.visitInsn(ICONST_1);
            mv.visitInsn(IRETURN);
            mv.visitLabel(stopped);
            mv.visitFrame(F_SAME, 0, null, 0, null);
            mv.visitInsn(ICONST_0);
            mv.visitInsn(IRETURN);
            mv.visitLabel(notRadius);
            mv.visitFrame(F_SAME, 0, null, 0, null);
        }
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
