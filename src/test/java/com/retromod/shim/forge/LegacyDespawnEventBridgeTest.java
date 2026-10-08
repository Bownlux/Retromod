/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.shim.forge.embedded.LegacyDespawnResults;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** A Forge despawn listener, as Wither Storm keeps sickened mobs from despawning, on NeoForge. */
class LegacyDespawnEventBridgeTest {

    private static final String FORGE_RESULT = "net/minecraftforge/eventbus/api/Event$Result";

    private RetromodTransformer transformer;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("The listener takes NeoForge's despawn event and sets its result by name")
    void listenerMovesToTheDespawnEvent() {
        assertTrue(LegacyDespawnEventBridge.register(transformer, Set.of(LegacyDespawnEventBridge.NEO)::contains));
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/SicknessEvents", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "onCheckDespawn",
                "(L" + LegacyDespawnEventBridge.FORGE + ";)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETSTATIC, FORGE_RESULT, "DENY", "L" + FORGE_RESULT + ";");
        mv.visitMethodInsn(INVOKEVIRTUAL, LegacyDespawnEventBridge.FORGE, "setResult", "(L" + FORGE_RESULT + ";)V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        ClassNode out = new ClassNode();
        new ClassReader(transformer.transformClass(cw.toByteArray(), "test/SicknessEvents")).accept(out, 0);
        assertEquals("(L" + LegacyDespawnEventBridge.NEO + ";)V", out.methods.get(0).desc,
                "the bus resolves the listener's parameter, so it must name a class NeoForge has");
        MethodInsnNode call = (MethodInsnNode) java.util.Arrays.stream(out.methods.get(0).instructions.toArray())
                .filter(MethodInsnNode.class::isInstance).findFirst().orElseThrow();
        assertEquals(LegacyDespawnEventBridge.HELPER, call.owner);
        assertEquals(INVOKESTATIC, call.getOpcode());
    }

    enum Result { ALLOW, DEFAULT, DENY }

    /** Shaped like MobDespawnEvent, with a result enum of the same names. */
    public static final class FakeDespawnEvent {
        Object result;
        public void setResult(Result result) { this.result = result; }
    }

    @Test
    @DisplayName("A result the host cannot find is reported with its name")
    void missingHostEnumIsReported() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> LegacyDespawnResults.setResult(new FakeDespawnEvent(), Result.DENY));
        assertTrue(failure.getMessage().contains("DENY"), failure.getMessage());
    }
}
