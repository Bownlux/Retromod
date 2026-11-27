/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.neoforge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodTransformer.MethodKey;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.neoforge.guihost.DeltaTracker;
import com.retromod.shim.neoforge.guihost.Minecraft;
import com.retromod.shim.neoforge.guihost.RenderGuiEvent;
import com.retromod.shim.neoforge.guihost.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** A Forge 1.20.1 HUD handler's {@code event.getWindow()} and float partial tick on NeoForge 1.21 and newer (#410). */
class LegacyNeoForgeGuiEventBridgeTest {

    private static final String WINDOW = "com/mojang/blaze3d/platform/Window";
    private static final String NEO = LegacyNeoForgeGuiEventBridge.NEO_EVENT;
    private static final String FORGE = LegacyNeoForgeGuiEventBridge.FORGE_EVENT;

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("Only a host whose event lost getWindow() gets the bridge; offline, NeoForge from 1.21")
    void followsTheHost() {
        assertTrue(LegacyNeoForgeGuiEventBridge.hostDroppedWindow(event()), "NeoForge 1.21.1 and 26.x");
        ClassNode withWindow = event();
        MethodNode getter = new MethodNode(Opcodes.ACC_PUBLIC, "getWindow", "()L" + WINDOW + ";", null, null);
        withWindow.methods.add(getter);
        assertFalse(LegacyNeoForgeGuiEventBridge.hostDroppedWindow(withWindow), "NeoForge 1.20.4 kept Forge's getters");
        RetromodVersion.TARGET_MC_VERSION = "1.21.1";
        assertTrue(LegacyNeoForgeGuiEventBridge.hostDroppedWindow(null));
        RetromodVersion.TARGET_MC_VERSION = "1.20.4";
        assertFalse(LegacyNeoForgeGuiEventBridge.hostDroppedWindow(null));
    }

    @Test
    @DisplayName("An MCreator overlay's Pre handler calls the helper for the window and the partial tick")
    void rewritesTheOverlayHandler() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        transformer.registerClassRedirect(FORGE + "$Pre", NEO + "$Pre");
        LegacyNeoForgeGuiEventBridge.register(transformer, name -> name.equals(NEO) ? event() : null);
        assertNotNull(transformer.getMethodRedirects().get(new MethodKey(NEO + "$Pre", "getWindow", "()L" + WINDOW + ";")));
        assertNotNull(transformer.getMethodRedirects().get(new MethodKey(FORGE + "$Post", "getPartialTick", "()F")));

        ClassNode handler = new ClassNode();
        new ClassReader(transformer.transformClass(overlayHandler(), "test/mod/ChoiceOverlay.class")).accept(handler, 0);
        List<MethodInsnNode> calls = new ArrayList<>();
        for (AbstractInsnNode insn : handler.methods.stream().filter(m -> m.name.equals("eventHandler")).findFirst().orElseThrow().instructions) {
            if (insn instanceof MethodInsnNode call) calls.add(call);
        }
        assertEquals(List.of("getWindow", "getGuiScaledWidth", "getPartialTick"), calls.stream().map(c -> c.name).toList());
        assertEquals(Opcodes.INVOKESTATIC, calls.get(0).getOpcode());
        assertEquals(LegacyNeoForgeGuiEventBridge.HELPER, calls.get(0).owner);
        assertEquals(WINDOW, calls.get(1).owner, "the window's own calls are left alone");
        assertEquals(LegacyNeoForgeGuiEventBridge.HELPER, calls.get(2).owner);
        assertEquals("(L" + NEO + ";)F", calls.get(2).desc);
    }

    @Test
    @DisplayName("The helper answers the game's window and the tracker's partial tick")
    void helperReadsTheGame() throws Exception {
        ClassLoader host = host();
        Class<?> helper = host.loadClass(LegacyNeoForgeGuiEventBridge.HELPER.replace('/', '.'));
        Class<?> event = host.loadClass(NEO.replace('/', '.'));
        Object instance = event.getConstructor().newInstance();
        Object game = host.loadClass("net.minecraft.client.Minecraft").getField("INSTANCE").get(null);
        Object window = game.getClass().getField("window").get(game);

        assertSame(window, helper.getMethod("getWindow", Object.class).invoke(null, instance));
        assertEquals(1.0F, helper.getMethod("getPartialTick", event).invoke(null, instance),
                "the frame's tick, read as NeoForge's own layers read it");
    }

    private static ClassNode event() {
        ClassNode node = new ClassNode();
        node.name = NEO;
        node.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "getPartialTick", "()Lnet/minecraft/client/DeltaTracker;", null, null));
        return node;
    }

    /** A Forge 1.20.1 MCreator overlay: it reads the scaled width and the partial tick from the Pre event. */
    private static byte[] overlayHandler() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/mod/ChoiceOverlay", null, "java/lang/Object", null);
        MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "eventHandler", "(L" + FORGE + "$Pre;)F", null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FORGE + "$Pre", "getWindow", "()L" + WINDOW + ";", false);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, WINDOW, "getGuiScaledWidth", "()I", false);
        m.visitInsn(Opcodes.I2F);
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FORGE + "$Pre", "getPartialTick", "()F", false);
        m.visitInsn(Opcodes.FADD);
        m.visitInsn(Opcodes.FRETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** The stand-ins under the game's names and the generated helper, in their own loader. */
    private static ClassLoader host() throws Exception {
        Map<String, String> names = new HashMap<>();
        names.put(internal(Window.class), WINDOW);
        names.put(internal(Minecraft.class), "net/minecraft/client/Minecraft");
        names.put(internal(DeltaTracker.class), "net/minecraft/client/DeltaTracker");
        names.put(internal(RenderGuiEvent.class), NEO);
        SimpleRemapper remapper = new SimpleRemapper(names);
        Map<String, byte[]> classes = new HashMap<>();
        for (Class<?> type : List.of(Window.class, Minecraft.class, DeltaTracker.class, RenderGuiEvent.class)) {
            classes.put(names.get(internal(type)).replace('/', '.'), remap(type, remapper));
        }
        classes.put(LegacyNeoForgeGuiEventBridge.HELPER.replace('/', '.'), LegacyNeoForgeGuiEventBridge.generateHelper());
        return new ClassLoader(ClassLoader.getPlatformClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classes.get(name);
                if (bytes == null) throw new ClassNotFoundException(name);
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
    }

    private static byte[] remap(Class<?> type, SimpleRemapper names) throws Exception {
        try (InputStream in = type.getResourceAsStream(type.getSimpleName() + ".class")) {
            ClassWriter writer = new ClassWriter(0);
            new ClassReader(in.readAllBytes()).accept(new ClassRemapper(writer, names), 0);
            return writer.toByteArray();
        }
    }

    private static String internal(Class<?> type) {
        return type.getName().replace('.', '/');
    }
}
