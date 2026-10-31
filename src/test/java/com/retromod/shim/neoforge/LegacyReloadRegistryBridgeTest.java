/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.neoforge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** #366: ProjectE keeps {@code AddServerReloadListenersEvent.getRegistryAccess()}, which NeoForge 26.3 removed. */
class LegacyReloadRegistryBridgeTest {

    private final String previousHost = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void reset() {
        RetromodVersion.TARGET_MC_VERSION = previousHost;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("The removed accessor goes to the helper, its result cast back")
    void registryAccessGoesToTheHelper() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyReloadRegistryBridge.register(transformer);

        ClassNode node = new ClassNode();
        new ClassReader(transformer.transformClass(listener(), "mod/Listener.class")).accept(node, 0);
        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode insn : node.methods.get(0).instructions) {
            if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name);
        }
        assertEquals(List.of(LegacyReloadRegistryBridge.SUPPORT + ".registryAccess"), calls);
        assertTrue(transformer.getSyntheticClasses().containsKey(LegacyReloadRegistryBridge.SUPPORT));
    }

    @Test
    @DisplayName("NeoForge 26.2 keeps the call")
    void olderHostsKeepTheCall() {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        assertFalse(LegacyReloadRegistryBridge.hostLostRegistryAccess());
    }

    private static byte[] listener() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC | ACC_SUPER, "mod/Listener", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "access",
                "(L" + LegacyReloadRegistryBridge.EVENT + ";)" + LegacyReloadRegistryBridge.ACCESS, null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, LegacyReloadRegistryBridge.EVENT, "getRegistryAccess", "()" + LegacyReloadRegistryBridge.ACCESS, false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
