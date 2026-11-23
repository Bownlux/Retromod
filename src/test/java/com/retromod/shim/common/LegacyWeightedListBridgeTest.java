/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;

import static com.retromod.shim.common.LegacyWeightedListBridge.*;
import static org.junit.jupiter.api.Assertions.*;

/** A 1.21.1 mod's empty(), single() and getRandomValue() on the old weighted list reach WeightedList's of() and getRandom(). */
class LegacyWeightedListBridgeTest {

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final RetromodTransformer transformer = RetromodTransformer.getInstance();

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        transformer.setModSourceVersion(null);
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("Old factory and lookup calls name WeightedList's of and getRandom, on the old or the moved owner")
    void oldCallsReachTheNewNames() {
        register("1.21.1");
        String list = "L" + NEW + ";";
        assertEquals(List.of(NEW + ".of()" + list), BridgeHarness.calls(caller(OLD), "empty"));
        assertEquals(List.of(NEW + ".of(Ljava/lang/Object;)" + list), BridgeHarness.calls(caller(NEW), "single"));
        assertEquals(List.of(NEW + ".getRandom(" + RANDOM + ")Ljava/util/Optional;"), BridgeHarness.calls(caller(OLD), "pick"));
    }

    @Test
    @DisplayName("Mods built for 1.21.5 or later keep their calls, and the bridge registers only where the host replaced the list")
    void followsTheModAndTheHost() {
        register("1.21.5");
        assertEquals(List.of(NEW + ".empty()L" + NEW + ";"), BridgeHarness.calls(caller(NEW), "empty"));
        ClassNode old = new ClassNode();
        old.methods.add(new MethodNode(9, "empty", "()L" + NEW + ";", null, null));
        assertFalse(hostReplacedIt(name -> old));
        RetromodVersion.TARGET_MC_VERSION = "1.21.4";
        assertFalse(hostReplacedIt(name -> null));
        RetromodVersion.TARGET_MC_VERSION = "1.21.5";
        assertTrue(hostReplacedIt(name -> null));
    }

    private void register(String modVersion) {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        transformer.clearRedirectsForTesting();
        LegacyWeightedListBridge.register(transformer, name -> null);
        transformer.setModSourceVersion(modVersion);
    }

    /** {@code mod/Spawns} with empty(), single(x) and pick(list, random) written against {@code owner}. */
    private byte[] caller(String owner) {
        String list = "L" + owner + ";";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "mod/Spawns", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "empty", "()" + list, null, null);
        mv.visitCode();
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "empty", "()" + list, false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "single", "(Ljava/lang/Object;)" + list, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "single", "(Ljava/lang/Object;)" + list, false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "pick", "(" + list + RANDOM + ")Ljava/util/Optional;", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner, "getRandomValue", "(" + RANDOM + ")Ljava/util/Optional;", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return transformer.transformClass(cw.toByteArray(), "mod/Spawns");
    }
}
