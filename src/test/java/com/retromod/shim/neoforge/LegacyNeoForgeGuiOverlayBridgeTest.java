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
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** A Forge 1.20.1 mod's overlay registration, overlay events and ForgeGui casts reach NeoForge's GUI layers. */
class LegacyNeoForgeGuiOverlayBridgeTest {

    private static final String OLD_OVERLAY = LegacyNeoForgeGuiOverlayBridge.OLD_OVERLAY;
    private static final String OLD_EVENT = LegacyNeoForgeGuiOverlayBridge.OLD_EVENT;
    private static final String LAYERS = LegacyNeoForgeGuiOverlayBridge.LAYERS;

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("registration, the overlay getters and the ForgeGui cast go to the rebuilt helpers on NeoForge 26.3")
    void overlayCallsReachTheLayers() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        LegacyNeoForgeGuiOverlayBridge.register(t, name -> name.equals(LegacyNeoForgeGuiOverlayBridge.REGISTER) ? layersHost()
                : name.equals(LegacyNeoForgeGuiOverlayBridge.GUI_LAYER) ? guiLayer("net/minecraft/client/gui/GuiGraphicsExtractor") : null);

        ClassNode out = read(t.transformClass(oldOverlayMod(), "test/hud/OldOverlays"));

        List<String> calls = calls(out);
        String rebuilt = LegacyNeoForgeGuiOverlayBridge.ROOT;
        assertTrue(calls.contains(LAYERS + ".registerAbove(L" + LegacyNeoForgeGuiOverlayBridge.REGISTER
                + ";Lnet/minecraft/resources/Identifier;Ljava/lang/String;L" + rebuilt + "IGuiOverlay;)V"), calls.toString());
        assertTrue(calls.contains(LAYERS + ".getOverlay(L" + LegacyNeoForgeGuiOverlayBridge.RENDER + ";)L" + rebuilt
                + "NamedGuiOverlay;"), calls.toString());
        assertTrue(calls.contains(LAYERS + ".getPartialTick(L" + LegacyNeoForgeGuiOverlayBridge.RENDER + ";)F"), calls.toString());
        assertTrue(calls.contains(rebuilt + "VanillaGuiOverlay.type()L" + rebuilt + "NamedGuiOverlay;"), calls.toString());
        assertTrue(calls.contains(LegacyNeoForgeGuiOverlayBridge.FORGE_GUI + ".cast(Ljava/lang/Object;)L"
                + LegacyNeoForgeGuiOverlayBridge.FORGE_GUI + ";"), "the Minecraft.gui cast: " + calls);
        boolean plainCast = out.methods.stream().flatMap(m -> java.util.stream.StreamSupport.stream(m.instructions.spliterator(), false))
                .anyMatch(insn -> insn instanceof TypeInsnNode type && type.getOpcode() == Opcodes.CHECKCAST
                        && type.desc.equals(LegacyNeoForgeGuiOverlayBridge.FORGE_GUI));
        assertFalse(plainCast, "a plain cast of Minecraft.gui to the rebuilt ForgeGui would fail");
        assertEquals(LegacyNeoForgeGuiOverlayBridge.RENDER + "$Pre", t.getClassRedirects().get(OLD_EVENT + "RenderGuiOverlayEvent$Pre"));
    }

    @Test
    @DisplayName("NeoForge 1.21.1, whose layers take a ResourceLocation and a LayeredDraw layer, gets no bridge")
    void olderNeoForgeKeepsItsOwn() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        ClassNode old = new ClassNode();
        old.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "registerAbove", "(Lnet/minecraft/resources/ResourceLocation;"
                + "Lnet/minecraft/resources/ResourceLocation;Lnet/minecraft/client/gui/LayeredDraw$Layer;)V", null, null));
        LegacyNeoForgeGuiOverlayBridge.register(t, name -> old);

        assertNull(t.getClassRedirects().get(OLD_OVERLAY + "VanillaGuiOverlay"));
    }

    @Test
    @DisplayName("NeoForge 1.21.11, whose GUI layers draw into GuiGraphics, gets no bridge")
    void neoForge12111KeepsItsOwn() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        LegacyNeoForgeGuiOverlayBridge.register(t, name -> name.equals(LegacyNeoForgeGuiOverlayBridge.REGISTER) ? layersHost()
                : name.equals(LegacyNeoForgeGuiOverlayBridge.GUI_LAYER) ? guiLayer("net/minecraft/client/gui/GuiGraphics") : null);

        assertNull(t.getClassRedirects().get(OLD_OVERLAY + "VanillaGuiOverlay"));
    }

    private static ClassNode guiLayer(String graphics) {
        ClassNode node = new ClassNode();
        node.name = LegacyNeoForgeGuiOverlayBridge.GUI_LAYER;
        node.methods.add(new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "render", "(L" + graphics
                + ";Lnet/minecraft/client/DeltaTracker;)V", null, null));
        return node;
    }

    private static ClassNode layersHost() {
        ClassNode node = new ClassNode();
        node.name = LegacyNeoForgeGuiOverlayBridge.REGISTER;
        node.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "registerAbove", "(Lnet/minecraft/resources/Identifier;"
                + "Lnet/minecraft/resources/Identifier;L" + LegacyNeoForgeGuiOverlayBridge.GUI_LAYER + ";)V", null, null));
        return node;
    }

    // An old mod's overlay listener and registration, as Forge 1.20.1 compiled them.
    private static byte[] oldOverlayMod() {
        String pre = OLD_EVENT + "RenderGuiOverlayEvent$Pre";
        String named = "L" + OLD_OVERLAY + "NamedGuiOverlay;";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/hud/OldOverlays", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "onOverlay", "(L" + pre + ";)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, pre, "getOverlay", "()" + named, false);
        mv.visitFieldInsn(Opcodes.GETSTATIC, OLD_OVERLAY + "VanillaGuiOverlay", "FOOD_LEVEL", "L" + OLD_OVERLAY + "VanillaGuiOverlay;");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, OLD_OVERLAY + "VanillaGuiOverlay", "type", "()" + named, false);
        mv.visitInsn(Opcodes.POP2);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, pre, "getPartialTick", "()F", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitTypeInsn(Opcodes.CHECKCAST, OLD_OVERLAY + "ForgeGui");
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        String register = OLD_EVENT + "RegisterGuiOverlaysEvent";
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "onRegister", "(L" + register + ";)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitLdcInsn("compass");
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, register, "registerAbove", "(Lnet/minecraft/resources/ResourceLocation;"
                + "Ljava/lang/String;L" + OLD_OVERLAY + "IGuiOverlay;)V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static List<String> calls(ClassNode node) {
        List<String> calls = new ArrayList<>();
        node.methods.forEach(m -> m.instructions.forEach(insn -> {
            if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name + call.desc);
        }));
        return calls;
    }
}
