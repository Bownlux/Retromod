/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.VersionShim;
import com.retromod.shim.fabric.Fabric_26_1_to_26_2;
import com.retromod.shim.forge.Forge_26_1_to_26_2;
import com.retromod.shim.neoforge.NeoForge_26_1_to_26_2;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.util.ArrayList;
import java.util.List;

import static com.retromod.shim.common.LegacyGamePacketListenerBridge.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** A mod's fake player connection built with the 1.20.1 listener constructor, on every loader's 26.2 shim. */
class LegacyGamePacketListenerBridgeTest {

    private static final String MOD = "mod/FakeConnection";
    private static final String CREATE_INITIAL = COOKIE + ".createInitial(L" + PROFILE + ";Z)L" + COOKIE + ";";
    private static final String GET_PROFILE = SERVER_PLAYER + ".getGameProfile()L" + PROFILE + ";";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        transformer.clearRedirectsForTesting();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"forge", "neoforge", "fabric"})
    @DisplayName("The super call and a direct construction both get a cookie from the player")
    void everyLoaderAddsTheCookie(String loader) throws Exception {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        McReflect.setForceForge(loader.equals("forge"));
        McReflect.setForceNeoForge(loader.equals("neoforge"));
        transformer.clearRedirectsForTesting();
        VersionShim shim = switch (loader) {
            case "forge" -> new Forge_26_1_to_26_2();
            case "neoforge" -> new NeoForge_26_1_to_26_2();
            default -> new Fabric_26_1_to_26_2();
        };
        shim.registerRedirects(transformer);

        ClassNode connection = node(transformer.transformClass(oldConnection(), MOD + ".class"));
        List<String> expected = List.of(GET_PROFILE, CREATE_INITIAL, LISTENER + ".<init>" + CURRENT);
        assertEquals(expected, calls(method(connection, "<init>")), "super(server, connection, player)");
        assertEquals(expected, calls(method(connection, "open")), "new ServerGamePacketListenerImpl(server, connection, player)");
        for (MethodNode method : connection.methods) new Analyzer<>(new BasicVerifier()).analyze(connection.name, method);
    }

    @Test
    @DisplayName("Before 1.20.2 the host still has the old constructor, so nothing is registered")
    void olderHostKeepsTheOldConstructor() {
        RetromodVersion.TARGET_MC_VERSION = "1.20.1";
        assertFalse(hostLacksOldConstructor());
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        assertTrue(hostLacksOldConstructor());
    }

    /**
     * {@code class FakeConnection extends ServerGamePacketListenerImpl}, whose constructor calls
     * {@code super(server, connection, player)}, plus {@code open} building the host class directly.
     */
    private static byte[] oldConnection() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, MOD, null, LISTENER, null);
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", OLD, null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 1);
        init.visitVarInsn(ALOAD, 2);
        init.visitVarInsn(ALOAD, 3);
        init.visitMethodInsn(INVOKESPECIAL, LISTENER, "<init>", OLD, false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        MethodVisitor open = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "open",
                OLD.substring(0, OLD.length() - 1) + "Ljava/lang/Object;", null, null);
        open.visitCode();
        open.visitTypeInsn(NEW, LISTENER);
        open.visitInsn(DUP);
        open.visitVarInsn(ALOAD, 0);
        open.visitVarInsn(ALOAD, 1);
        open.visitVarInsn(ALOAD, 2);
        open.visitMethodInsn(INVOKESPECIAL, LISTENER, "<init>", OLD, false);
        open.visitInsn(ARETURN);
        open.visitMaxs(0, 0);
        open.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }

    private static List<String> calls(MethodNode method) {
        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name + call.desc);
        }
        return calls;
    }
}
