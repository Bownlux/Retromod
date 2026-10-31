/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.neoforge;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** #366: ProjectE's transmutation overlay implements {@code LayeredDraw.Layer}, which 1.21.6 removed. */
class LegacyHudLayerRedirectTest {

    private static final String GUI_LAYER = "net/neoforged/neoforge/client/gui/GuiLayer";

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("A HUD layer implements NeoForge's GuiLayer and keeps its render method")
    void hudLayerImplementsGuiLayer() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        new NeoForge_1_21_5_to_1_21_6().registerRedirects(transformer);

        ClassNode overlay = new ClassNode();
        new ClassReader(transformer.transformClass(overlay(), "mod/Overlay.class")).accept(overlay, 0);

        assertEquals(List.of(GUI_LAYER), overlay.interfaces);
        assertTrue(overlay.methods.stream().anyMatch(m -> m.name.equals("render")
                && m.desc.equals("(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V")));
    }

    private static byte[] overlay() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC | ACC_SUPER, "mod/Overlay", null, "java/lang/Object",
                new String[]{"net/minecraft/client/gui/LayeredDraw$Layer"});
        MethodVisitor render = cw.visitMethod(ACC_PUBLIC, "render",
                "(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V", null, null);
        render.visitCode();
        render.visitInsn(RETURN);
        render.visitMaxs(0, 0);
        render.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
