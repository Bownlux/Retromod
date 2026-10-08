/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** A Forge teleporter, as Wither Storm moves players into its bowels dimension, on a 1.21.1 host. */
class LegacyTeleporterBridgeTest {

    private static final String LEVEL = "Lnet/minecraft/server/level/ServerLevel;";
    private static final String ENTITY = "Lnet/minecraft/world/entity/Entity;";

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
    @DisplayName("The teleporter and its changeDimension call move onto the generated bridge")
    void teleporterUsesTheBridge() {
        LegacyTeleporterBridge.registerRedirects(transformer);

        ClassWriter teleporter = new ClassWriter(0);
        teleporter.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/BowelsTeleporter", null, "java/lang/Object",
                new String[] {LegacyTeleporterBridge.FORGE_TELEPORTER});
        teleporter.visitEnd();
        ClassNode teleporterOut = transform(teleporter.toByteArray(), "test/BowelsTeleporter");
        assertEquals(List.of(LegacyTeleporterBridge.TELEPORTER), teleporterOut.interfaces);

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/Bowels", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "send",
                "(" + ENTITY + LEVEL + "L" + LegacyTeleporterBridge.FORGE_TELEPORTER + ";)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/world/entity/Entity", "changeDimension",
                "(" + LEVEL + "L" + LegacyTeleporterBridge.FORGE_TELEPORTER + ";)" + ENTITY, false);
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        ClassNode out = transform(cw.toByteArray(), "test/Bowels");
        List<MethodInsnNode> calls = new ArrayList<>();
        out.methods.forEach(m -> m.instructions.forEach(insn -> {
            if (insn instanceof MethodInsnNode call) calls.add(call);
        }));
        MethodInsnNode call = calls.get(0);
        assertEquals(INVOKESTATIC, call.getOpcode());
        assertEquals(LegacyTeleporterBridge.CALLS, call.owner);
        assertEquals("(" + ENTITY + LEVEL + "L" + LegacyTeleporterBridge.TELEPORTER + ";)" + ENTITY, call.desc,
                "1.21 moves entities with a DimensionTransition, which the helper builds from the portal info");
    }

    @Test
    @DisplayName("The generated teleporter classes are well formed")
    void generatedClassesAreWellFormed() {
        for (byte[] bytes : List.of(LegacyTeleporterBridge.generatePortalInfo(),
                LegacyTeleporterBridge.generateTeleporter(), LegacyTeleporterBridge.generateCalls())) {
            assertDoesNotThrow(() -> new ClassReader(bytes).accept(
                    new CheckClassAdapter(new ClassWriter(0), false), 0));
        }
        ClassNode info = new ClassNode();
        new ClassReader(LegacyTeleporterBridge.generatePortalInfo()).accept(info, 0);
        assertEquals(List.of("pos", "speed", "yRot", "xRot"), info.fields.stream().map(f -> f.name).toList(),
                "a mod reads PortalInfo's fields directly, under their Mojang names");
    }

    private ClassNode transform(byte[] input, String name) {
        ClassNode node = new ClassNode();
        new ClassReader(transformer.transformClass(input, name)).accept(node, 0);
        return node;
    }
}
