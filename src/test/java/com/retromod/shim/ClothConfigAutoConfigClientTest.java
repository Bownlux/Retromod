/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.api.fabric.ClothConfigApiShim;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cloth Config's 26.1 build moved {@code getConfigScreen} and {@code getGuiRegistry} off
 * {@code AutoConfig} onto {@code AutoConfigClient}. A mod built against an older Cloth still
 * calls the old owner, so its config screen dies with {@code NoSuchMethodError} while the rest
 * of the mod runs (#181, Double Hotbar).
 */
class ClothConfigAutoConfigClientTest {

    private static final String AUTO_CONFIG = "me/shedaniel/autoconfig/AutoConfig";
    private static final String AUTO_CONFIG_CLIENT = "me/shedaniel/autoconfig/AutoConfigClient";
    private static final String SCREEN = "Lnet/minecraft/client/gui/screens/Screen;";
    private static final String SCREEN_DESC = "(Ljava/lang/Class;" + SCREEN + ")Ljava/util/function/Supplier;";
    private static final String GUI_REGISTRY_DESC =
            "(Ljava/lang/Class;)Lme/shedaniel/autoconfig/gui/registry/GuiRegistry;";

    private String savedVersion;

    @AfterEach
    void restore() {
        if (savedVersion != null) RetromodVersion.TARGET_MC_VERSION = savedVersion;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    /** A mod's Mod Menu integration, calling the helpers where they used to live. */
    private static byte[] callerClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "mod/MenuIntegration", null, "java/lang/Object", null);

        MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "screen", "(Ljava/lang/Class;" + SCREEN + ")Ljava/util/function/Supplier;", null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitVarInsn(Opcodes.ALOAD, 1);
        m.visitMethodInsn(Opcodes.INVOKESTATIC, AUTO_CONFIG, "getConfigScreen", SCREEN_DESC, false);
        m.visitInsn(Opcodes.ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();

        MethodVisitor r = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "registry", GUI_REGISTRY_DESC, null, null);
        r.visitCode();
        r.visitVarInsn(Opcodes.ALOAD, 0);
        r.visitMethodInsn(Opcodes.INVOKESTATIC, AUTO_CONFIG, "getGuiRegistry", GUI_REGISTRY_DESC, false);
        r.visitInsn(Opcodes.ARETURN);
        r.visitMaxs(0, 0);
        r.visitEnd();

        // register did not move, so it has to be left alone.
        MethodVisitor k = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "keep", "(Ljava/lang/Class;)Lme/shedaniel/autoconfig/ConfigHolder;", null, null);
        k.visitCode();
        k.visitVarInsn(Opcodes.ALOAD, 0);
        k.visitMethodInsn(Opcodes.INVOKESTATIC, AUTO_CONFIG, "getConfigHolder",
                "(Ljava/lang/Class;)Lme/shedaniel/autoconfig/ConfigHolder;", false);
        k.visitInsn(Opcodes.ARETURN);
        k.visitMaxs(0, 0);
        k.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] transformWithHost(String hostVersion) {
        RetromodVersion.TARGET_MC_VERSION = hostVersion;
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        new ClothConfigApiShim().registerRedirects(transformer);
        byte[] out = transformer.transformClass(callerClass(), "mod/MenuIntegration");
        return out != null ? out : callerClass();
    }

    private static boolean calls(byte[] classBytes, String owner, String name) {
        ClassNode cn = new ClassNode();
        new ClassReader(classBytes).accept(cn, 0);
        for (MethodNode m : cn.methods) {
            for (var insn : m.instructions.toArray()) {
                if (insn instanceof MethodInsnNode call
                        && owner.equals(call.owner) && name.equals(call.name)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Test
    @DisplayName("#181: the moved client helpers are redirected to AutoConfigClient on a 26.x host")
    void redirectsMovedHelpers() {
        savedVersion = RetromodVersion.TARGET_MC_VERSION;
        byte[] out = transformWithHost("26.2");

        assertTrue(calls(out, AUTO_CONFIG_CLIENT, "getConfigScreen"),
                "getConfigScreen must move to AutoConfigClient");
        assertTrue(calls(out, AUTO_CONFIG_CLIENT, "getGuiRegistry"),
                "getGuiRegistry must move to AutoConfigClient");
        assertFalse(calls(out, AUTO_CONFIG, "getConfigScreen"),
                "the old owner no longer has it, so no call may be left behind");
    }

    @Test
    @DisplayName("#181: the helpers that did not move are left on AutoConfig")
    void leavesUnmovedHelpersAlone() {
        savedVersion = RetromodVersion.TARGET_MC_VERSION;
        byte[] out = transformWithHost("26.2");

        assertTrue(calls(out, AUTO_CONFIG, "getConfigHolder"),
                "getConfigHolder stayed on AutoConfig and must not be redirected");
        assertFalse(calls(out, AUTO_CONFIG_CLIENT, "getConfigHolder"));
    }

    @Test
    @DisplayName("#181: an older host still has the helpers on AutoConfig, so nothing is redirected")
    void gatedOffBeforeTheMove() {
        savedVersion = RetromodVersion.TARGET_MC_VERSION;
        byte[] out = transformWithHost("1.21.1");

        assertTrue(calls(out, AUTO_CONFIG, "getConfigScreen"),
                "redirecting here would break a mod that works today");
        assertFalse(calls(out, AUTO_CONFIG_CLIENT, "getConfigScreen"));
        assertFalse(Arrays.equals(new byte[0], out));
    }
}
