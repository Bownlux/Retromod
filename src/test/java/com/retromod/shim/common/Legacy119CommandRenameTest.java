/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.forge.LegacyEntityTemplateAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** #412 (Ancient Revival, Forge 1.18.2 on Forge 1.20.1): an MCreator procedure runs a command through 1.18's performCommand. */
class Legacy119CommandRenameTest {

    private static final String COMMANDS = "net/minecraft/commands/Commands";
    private static final String RUN = "(Lnet/minecraft/commands/CommandSourceStack;Ljava/lang/String;)I";

    private RetromodTransformer transformer;
    private String host;

    @BeforeEach
    void setUp() {
        host = RetromodVersion.TARGET_MC_VERSION;
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
        RetromodVersion.TARGET_MC_VERSION = host;
    }

    @Test
    @DisplayName("performCommand(source, command) runs as 1.19's performPrefixedCommand on a host where it still returns the result")
    void performCommandIsRenamed() {
        RetromodVersion.TARGET_MC_VERSION = "1.20.1";
        LegacyMinecraft119Bridge.register(transformer);
        MethodInsnNode call = transformedCall();
        assertEquals(COMMANDS + ".performPrefixedCommand" + RUN, call.owner + "." + call.name + call.desc);
    }

    @Test
    @DisplayName("from 1.20.3 performPrefixedCommand returns nothing, so on 26.3 the call reaches the helper that answers 0")
    void performCommandReachesTheVoidHelperOn263() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        LegacyMinecraft119Bridge.register(transformer);
        // On 26.3 the 1.20 to 1.21 step registers the same helper for 1.20.1 mods; the two must not stack.
        LegacyEntityTemplateAdapter.registerCommandResults(transformer);
        MethodInsnNode call = transformedCall();
        assertEquals(INVOKESTATIC, call.getOpcode());
        assertEquals(LegacyEntityTemplateAdapter.COMMANDS_HELPER + ".performPrefixedCommand(L" + COMMANDS + ";"
                + RUN.substring(1), call.owner + "." + call.name + call.desc);
        assertTrue(transformer.getSyntheticClasses().containsKey(LegacyEntityTemplateAdapter.COMMANDS_HELPER));
    }

    @Test
    @DisplayName("Forge 1.20.3 to 1.20.6, which no later step covers, also get the helper")
    void performCommandReachesTheVoidHelperOn1203() {
        RetromodVersion.TARGET_MC_VERSION = "1.20.4";
        LegacyMinecraft119Bridge.register(transformer);
        assertEquals(LegacyEntityTemplateAdapter.COMMANDS_HELPER, transformedCall().owner);
    }

    /** The first call in a 1.18 MCreator procedure that runs a command, after transformation. */
    private MethodInsnNode transformedCall() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "test/ar/RevivalProcedure", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "run", "(L" + COMMANDS + ";)I", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitInsn(ACONST_NULL);
        mv.visitLdcInsn("summon minecraft:pig");
        mv.visitMethodInsn(INVOKEVIRTUAL, COMMANDS, "performCommand", RUN, false);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        ClassNode node = new ClassNode();
        new ClassReader(transformer.transformClass(cw.toByteArray(), "test/ar/RevivalProcedure")).accept(node, 0);
        return (MethodInsnNode) Arrays.stream(node.methods.get(0).instructions.toArray())
                .filter(MethodInsnNode.class::isInstance).findFirst().orElseThrow();
    }
}
