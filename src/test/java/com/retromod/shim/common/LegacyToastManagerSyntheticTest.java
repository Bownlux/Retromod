/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * Runs the generated {@code Minecraft.getToastManager()} forwarder against small stand-ins for
 * the 26.2 client classes, so the early-init behavior is checked by execution rather than by
 * reading the bytecode.
 */
class LegacyToastManagerSyntheticTest {

    private static final String MC = "net/minecraft/client/Minecraft";
    private static final String GUI = "net/minecraft/client/gui/Gui";
    private static final String OPTIONS = "net/minecraft/client/Options";
    private static final String TOASTS = "net/minecraft/client/gui/components/toasts/ToastManager";

    @Test
    @DisplayName("Once the GUI exists, the forwarder returns the GUI's toast manager")
    void readsTheGuiManager() throws Exception {
        Host host = new Host();
        Object client = host.newInstance(MC);
        Object gui = host.newInstance(GUI);
        Object guiManager = host.newToastManager(client);
        gui.getClass().getField("manager").set(gui, guiManager);
        client.getClass().getField("gui").set(client, gui);

        assertSame(guiManager, host.getToastManager(client));
    }

    @Test
    @DisplayName("Before the GUI exists, the forwarder returns one early manager instead of throwing")
    void returnsOneEarlyManagerBeforeTheGui() throws Exception {
        Host host = new Host();
        Object client = host.newInstance(MC);
        client.getClass().getField("options").set(client, host.newInstance(OPTIONS));

        Object first = host.getToastManager(client);
        assertNotNull(first, "a client entrypoint that runs before the GUI must still get a manager");
        assertSame(first, host.getToastManager(client), "repeated early calls must share one manager");
    }

    @Test
    @DisplayName("Before options exist, the forwarder returns null, as 26.1 did at that point")
    void returnsNullBeforeOptions() throws Exception {
        Host host = new Host();
        assertNull(host.getToastManager(host.newInstance(MC)));
    }

    /** Defines the forwarder next to minimal stand-ins for the client classes it reaches. */
    private static final class Host extends ClassLoader {
        private final Map<String, byte[]> classes = new HashMap<>();

        Host() {
            super(LegacyToastManagerSyntheticTest.class.getClassLoader());
            classes.put(OPTIONS, plainClass(OPTIONS));
            classes.put(MC, classWithFields(MC, new String[][]{
                    {"gui", "L" + GUI + ";"}, {"options", "L" + OPTIONS + ";"}}));
            classes.put(GUI, guiClass());
            classes.put(TOASTS, toastManagerClass());
            classes.put(LegacyToastManagerSynthetic.INTERNAL, LegacyToastManagerSynthetic.generate());
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = classes.get(name.replace('.', '/'));
            if (bytes == null) throw new ClassNotFoundException(name);
            return defineClass(name, bytes, 0, bytes.length);
        }

        Object newInstance(String internalName) throws Exception {
            return loadClass(internalName.replace('/', '.')).getConstructor().newInstance();
        }

        Object newToastManager(Object client) throws Exception {
            Class<?> type = loadClass(TOASTS.replace('/', '.'));
            return type.getConstructor(loadClass(MC.replace('/', '.')), loadClass(OPTIONS.replace('/', '.')))
                    .newInstance(client, null);
        }

        Object getToastManager(Object client) throws Exception {
            Class<?> forwarder = loadClass(LegacyToastManagerSynthetic.INTERNAL.replace('/', '.'));
            Method method = forwarder.getMethod("getToastManager", loadClass(MC.replace('/', '.')));
            return method.invoke(null, client);
        }
    }

    private static byte[] plainClass(String name) {
        return classWithFields(name, new String[0][]);
    }

    private static byte[] classWithFields(String name, String[][] fields) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, name, null, "java/lang/Object", null);
        for (String[] field : fields) {
            cw.visitField(ACC_PUBLIC, field[0], field[1], null, null).visitEnd();
        }
        defaultConstructor(cw);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] guiClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, GUI, null, "java/lang/Object", null);
        cw.visitField(ACC_PUBLIC, "manager", "L" + TOASTS + ";", null, null).visitEnd();
        defaultConstructor(cw);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "toastManager", "()L" + TOASTS + ";", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, GUI, "manager", "L" + TOASTS + ";");
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] toastManagerClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, TOASTS, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", "(L" + MC + ";L" + OPTIONS + ";)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void defaultConstructor(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }
}
