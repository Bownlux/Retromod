/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** Forge 26.2 replaced Forge 1.20.1's {@code Event.Result} with {@code common.util.Result}, whose constants run the other way. */
class LegacyEventResultRedirectTest {

    private static final String OLD_RESULT = LegacyEventBaseBridge.OLD_RESULT;
    private static final String RESULT = LegacyEventBaseBridge.RESULT;
    private static final String L_OLD = "L" + OLD_RESULT + ";";
    private static final String L_RESULT = "L" + RESULT + ";";
    private static final String MOB = "net/minecraft/world/entity/Mob";
    private static final String EVENT_FACTORY = "net/minecraftforge/event/ForgeEventFactory";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @BeforeEach
    void forge262() {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        McReflect.setForceNeoForge(false);
        McReflect.setForceForge(true);
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("GETSTATIC Event$Result.DENY becomes common/util/Result.DENY")
    void constantMovesToTheHostEnum() {
        new Forge_26_1_to_26_2().registerRedirects(transformer);
        byte[] out = transformer.transformClass(resultSwitch(), "mod/ResultSwitch.class");
        new ClassReader(out).accept(new CheckClassAdapter(new ClassWriter(0), true), 0);

        String text = new String(out, StandardCharsets.ISO_8859_1);
        assertFalse(text.contains(OLD_RESULT), "the old enum is still named");
        List<String> fields = fields(node(out));
        assertTrue(fields.contains(RESULT + ".DENY:" + L_RESULT), fields.toString());
        assertTrue(fields.contains(RESULT + ".ALLOW:" + L_RESULT), fields.toString());
    }

    @Test
    @DisplayName("A copied javac switch map runs correctly against a host enum in ALLOW, DEFAULT, DENY order")
    void switchMapFollowsTheHostOrder() throws Exception {
        new Forge_26_1_to_26_2().registerRedirects(transformer);
        byte[] translated = transformer.transformClass(resultSwitch(), "mod/ResultSwitch.class");

        // The compiled stub of Forge 26.2's Result has the host's constant order.
        byte[] hostResult = Files.readAllBytes(Path.of("target", "legacyapi-classes", RESULT + ".class"));
        ClassLoader loader = new LegacyEventRuntimeTest.Loader(getClass().getClassLoader(),
                Map.of("mod/ResultSwitch", translated, RESULT, hostResult));
        Class<?> result = Class.forName(RESULT.replace('/', '.'), true, loader);
        assertEquals("ALLOW", ((Enum<?>) result.getEnumConstants()[0]).name(), "the stand-in host enum starts with ALLOW");
        Class<?> mod = Class.forName("mod.ResultSwitch", true, loader);
        for (String constant : List.of("ALLOW", "DEFAULT", "DENY")) {
            Object value = result.getField(constant).get(null);
            assertEquals(constant.toLowerCase(), mod.getMethod("branch", result).invoke(null, value), constant);
        }
    }

    @Test
    @DisplayName("Nothing registers on a host that still has EventBus 6, or on NeoForge, where ForgeEventApiShim owns the name")
    void otherHostsKeepTheirResult() {
        RetromodVersion.TARGET_MC_VERSION = "1.21.1";
        LegacyEventBaseBridge.register(transformer);
        assertFalse(transformer.getClassRedirects().containsKey(OLD_RESULT), "Forge 1.21.1 still has Event.Result");
        assertEquals(OLD_RESULT, LegacyEventBaseBridge.resultEnum());

        RetromodVersion.TARGET_MC_VERSION = "26.2";
        McReflect.setForceNeoForge(true);
        new Forge_26_1_to_26_2().registerRedirects(transformer);
        LegacyEventBaseBridge.register(transformer);
        assertFalse(transformer.getClassRedirects().containsKey(OLD_RESULT), "NeoForge");

        McReflect.setForceNeoForge(false);
        McReflect.setForceForge(false);
        LegacyEventBaseBridge.register(transformer);
        assertFalse(transformer.getClassRedirects().containsKey(OLD_RESULT), "a target that is not Forge");
    }

    @Test
    @DisplayName("LegacyForge119Calls' despawn helper answers with the host Result on 26.2 and the old one before")
    void despawnHelperUsesTheHostResult() {
        LegacyForge119Calls.register(transformer);
        new Forge_26_1_to_26_2().registerRedirects(transformer);

        ClassNode helper = node(transformer.getSyntheticClasses().get(LegacyForge119Calls.SYNTH));
        MethodNode despawn = method(helper, "canEntityDespawn");
        assertEquals("(L" + MOB + ";)" + L_RESULT, despawn.desc);
        assertTrue(fields(helper).contains(RESULT + ".DEFAULT:" + L_RESULT), fields(helper).toString());
        assertTrue(calls(despawn).contains(EVENT_FACTORY + ".canEntityDespawn(L" + MOB
                + ";Lnet/minecraft/world/level/ServerLevelAccessor;)" + L_RESULT), "the host's two-argument factory");
        assertFalse(new String(transformer.getSyntheticClasses().get(LegacyForge119Calls.SYNTH),
                StandardCharsets.ISO_8859_1).contains(OLD_RESULT));

        // A 1.18 mod's one-argument call reaches the helper with the host type.
        byte[] out = transformer.transformClass(despawnCaller(), "mod/Despawn.class");
        assertTrue(calls(method(node(out), "check")).contains(LegacyForge119Calls.SYNTH + ".canEntityDespawn(L" + MOB
                + ";)" + L_RESULT), calls(method(node(out), "check")).toString());

        transformer.clearRedirectsForTesting();
        RetromodVersion.TARGET_MC_VERSION = "1.20.1";
        LegacyForge119Calls.register(transformer);
        assertEquals("(L" + MOB + ";)" + L_OLD,
                method(node(transformer.getSyntheticClasses().get(LegacyForge119Calls.SYNTH)), "canEntityDespawn").desc);
    }

    // ---- helpers ----

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }

    private static List<String> fields(ClassNode node) {
        List<String> fields = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof FieldInsnNode field) fields.add(field.owner + "." + field.name + ":" + field.desc);
            }
        }
        return fields;
    }

    private static List<String> calls(MethodNode method) {
        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name + call.desc);
        }
        return calls;
    }

    /** Zeta's shape: javac's switch map over {@code Event.Result}, and {@code branch} switching through it. */
    // The map is filled from ordinal() when the class loads.
    private static byte[] resultSwitch() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                return "java/lang/Object";
            }
        };
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "mod/ResultSwitch", null, "java/lang/Object", null);
        cw.visitField(ACC_STATIC | ACC_FINAL | ACC_SYNTHETIC, "MAP", "[I", null, null).visitEnd();

        MethodVisitor init = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        init.visitCode();
        init.visitMethodInsn(INVOKESTATIC, OLD_RESULT, "values", "()[" + L_OLD, false);
        init.visitInsn(ARRAYLENGTH);
        init.visitIntInsn(NEWARRAY, T_INT);
        init.visitFieldInsn(PUTSTATIC, "mod/ResultSwitch", "MAP", "[I");
        String[] cases = {"ALLOW", "DENY"};
        for (int i = 0; i < cases.length; i++) {
            init.visitFieldInsn(GETSTATIC, "mod/ResultSwitch", "MAP", "[I");
            init.visitFieldInsn(GETSTATIC, OLD_RESULT, cases[i], L_OLD);
            init.visitMethodInsn(INVOKEVIRTUAL, OLD_RESULT, "ordinal", "()I", false);
            init.visitLdcInsn(i + 1);
            init.visitInsn(IASTORE);
        }
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        MethodVisitor branch = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "branch", "(" + L_OLD + ")Ljava/lang/String;",
                null, null);
        branch.visitCode();
        Label allow = new Label();
        Label deny = new Label();
        Label other = new Label();
        branch.visitFieldInsn(GETSTATIC, "mod/ResultSwitch", "MAP", "[I");
        branch.visitVarInsn(ALOAD, 0);
        branch.visitMethodInsn(INVOKEVIRTUAL, OLD_RESULT, "ordinal", "()I", false);
        branch.visitInsn(IALOAD);
        branch.visitTableSwitchInsn(1, 2, other, allow, deny);
        branch.visitLabel(allow);
        branch.visitLdcInsn("allow");
        branch.visitInsn(ARETURN);
        branch.visitLabel(deny);
        branch.visitLdcInsn("deny");
        branch.visitInsn(ARETURN);
        branch.visitLabel(other);
        branch.visitLdcInsn("default");
        branch.visitInsn(ARETURN);
        branch.visitMaxs(0, 0);
        branch.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A 1.18 mob's despawn check: {@code ForgeEventFactory.canEntityDespawn(mob)}. */
    private static byte[] despawnCaller() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "mod/Despawn", null, "java/lang/Object", null);
        MethodVisitor check = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "check", "(L" + MOB + ";)Ljava/lang/Object;",
                null, null);
        check.visitCode();
        check.visitVarInsn(ALOAD, 0);
        check.visitMethodInsn(INVOKESTATIC, EVENT_FACTORY, "canEntityDespawn", "(L" + MOB + ";)" + L_OLD, false);
        check.visitInsn(ARETURN);
        check.visitMaxs(0, 0);
        check.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
