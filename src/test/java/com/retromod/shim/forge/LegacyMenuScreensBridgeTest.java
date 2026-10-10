/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.shim.forge.embedded.LegacyMenuScreens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * Grids Are Dumb registers its menu screen with {@code MenuScreens.register} from client setup.
 * Forge's access transformer opens that method and NeoForge's does not, so on NeoForge the client
 * stopped with {@code IllegalAccessError} while loading.
 */
class LegacyMenuScreensBridgeTest {

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("a mod's MenuScreens.register call goes through the embedded helper")
    void registerCallIsRedirected() {
        transformer.clearRedirectsForTesting();
        LegacyMenuScreensBridge.registerRedirect(transformer);

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "test/GadClient", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "clientSetup",
                "(Ljava/lang/Object;Ljava/lang/Object;)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitTypeInsn(CHECKCAST, "net/minecraft/world/inventory/MenuType");
        mv.visitVarInsn(ALOAD, 1);
        mv.visitTypeInsn(CHECKCAST, "net/minecraft/client/gui/screens/MenuScreens$ScreenConstructor");
        mv.visitMethodInsn(INVOKESTATIC, LegacyMenuScreensBridge.SCREENS, "register",
                LegacyMenuScreensBridge.REGISTER_DESC, false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        ClassNode out = new ClassNode();
        new ClassReader(transformer.transformClass(cw.toByteArray(), "test/GadClient")).accept(out, 0);
        MethodNode setup = out.methods.stream().filter(m -> m.name.equals("clientSetup")).findFirst().orElseThrow();
        MethodInsnNode call = Arrays.stream(setup.instructions.toArray())
                .filter(MethodInsnNode.class::isInstance).map(MethodInsnNode.class::cast)
                .findFirst().orElseThrow();
        assertTrue(call.owner.endsWith("LegacyMenuScreens"), call.owner);
        assertEquals("(Ljava/lang/Object;Ljava/lang/Object;)V", call.desc);
    }

    @Test
    @DisplayName("the probe needs the host's register method; a server without MenuScreens gets nothing")
    void probeReadsTheHostMethod() {
        assertTrue(LegacyMenuScreensBridge.hostKeepsRegisterPrivate(screens(ACC_PRIVATE | ACC_STATIC)));
        assertFalse(LegacyMenuScreensBridge.hostKeepsRegisterPrivate(screens(ACC_PUBLIC | ACC_STATIC)));
        assertFalse(LegacyMenuScreensBridge.hostKeepsRegisterPrivate(null));
    }

    @Test
    @DisplayName("without Minecraft the helper fails with a message naming the menu")
    void helperErrorIsActionable() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> LegacyMenuScreens.register("gad:grid_menu", null));
        assertTrue(failure.getMessage().contains("gad:grid_menu"), failure.getMessage());
    }

    private static ClassNode screens(int access) {
        ClassNode node = new ClassNode();
        node.name = LegacyMenuScreensBridge.SCREENS;
        node.methods.add(new MethodNode(access, "register", LegacyMenuScreensBridge.REGISTER_DESC, null, null));
        return node;
    }
}
