/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * The Forge test mod's packet round trip on NeoForge 26.3 failed in its own handler: it logged the
 * sender with {@code GameProfile.getName()}, which authlib 7 (Minecraft 1.21.9) renamed to
 * {@code name()} when the profile became a record.
 */
class LegacyGameProfileBridgeTest {

    private final String previousHost = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void tearDown() {
        RetromodVersion.TARGET_MC_VERSION = previousHost;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("on a 1.21.9 or newer host, profile.getName() becomes profile.name()")
    void getNameBecomesName() {
        Assumptions.assumeTrue(ClassResourceInspector.read("com/mojang/authlib/GameProfile") == null,
                "decided by the host's own GameProfile when authlib is on the classpath");
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyGameProfileBridge.register(transformer);

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "test/Who", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "name",
                "(Lcom/mojang/authlib/GameProfile;)Ljava/lang/String;", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, "com/mojang/authlib/GameProfile", "getName",
                "()Ljava/lang/String;", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        ClassNode out = new ClassNode();
        new ClassReader(transformer.transformClass(cw.toByteArray(), "test/Who")).accept(out, 0);
        MethodInsnNode call = (MethodInsnNode) java.util.Arrays.stream(out.methods.get(0).instructions.toArray())
                .filter(MethodInsnNode.class::isInstance).findFirst().orElseThrow();
        assertEquals("name", call.name);
    }

    @Test
    @DisplayName("a host before 1.21.9 keeps getName")
    void olderHostKeepsGetName() {
        Assumptions.assumeTrue(ClassResourceInspector.read("com/mojang/authlib/GameProfile") == null);
        RetromodVersion.TARGET_MC_VERSION = "1.21.1";
        assertFalse(LegacyGameProfileBridge.hostProfileIsRecord());
    }
}
