/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.polyfill.minecraft;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * 26.3 moved input from GLFW to SDL and renumbered every key and mouse button. Values below are
 * the real ones from the 26.2 and 26.3 client jars' {@code InputConstants}.
 */
class RetroKeyMappingSdlTest {

    /** A slice of 26.3's SDL-numbered constants. */
    private static final Map<String, Integer> SDL_HOST = Map.of(
            "KEY_R", 21,
            "KEY_A", 4,
            "KEY_LSHIFT", 225,
            "KEY_ESCAPE", 41,
            "KEY_LGUI", 227,
            "MOUSE_BUTTON_LEFT", 1,
            "MOUSE_BUTTON_RIGHT", 3);

    @Test
    @DisplayName("a GLFW key code becomes the host's code for the same key")
    void keysTranslateByName() {
        assertEquals(21, RetroKeyMapping.translate(82, false, SDL_HOST), "R");
        assertEquals(225, RetroKeyMapping.translate(340, false, SDL_HOST), "left shift");
        assertEquals(41, RetroKeyMapping.translate(256, false, SDL_HOST), "escape");
    }

    @Test
    @DisplayName("mouse buttons use their own table")
    void mouseButtonsTranslate() {
        assertEquals(1, RetroKeyMapping.translate(0, true, SDL_HOST), "left button was 0");
        assertEquals(3, RetroKeyMapping.translate(1, true, SDL_HOST), "right button was 1");
    }

    @Test
    @DisplayName("26.3 renamed the super keys, and the translation follows the rename")
    void renamedKeysFollow() {
        assertEquals(227, RetroKeyMapping.translate(343, false, SDL_HOST), "KEY_LSUPER is KEY_LGUI");
    }

    @Test
    @DisplayName("a key 26.3 dropped is left unbound instead of landing on another key")
    void droppedKeysAreUnbound() {
        assertEquals(-1, RetroKeyMapping.translate(314, false, SDL_HOST), "KEY_F25 is gone");
        assertEquals(-1, RetroKeyMapping.translate(9999, false, SDL_HOST), "not a GLFW constant");
    }

    @Test
    @DisplayName("a GLFW host and the unbound code are left alone")
    void glfwHostIsUntouched() {
        assertEquals(82, RetroKeyMapping.translate(82, false, null));
        assertEquals(-1, RetroKeyMapping.translate(-1, false, SDL_HOST));
    }

    @Test
    @DisplayName("without Minecraft on the classpath nothing is translated")
    void noHostMeansNoTranslation() {
        assertEquals(82, RetroKeyMapping.hostKeyCode(82));
    }

    @Test
    @DisplayName("a GLFW mouse poll reads the button Minecraft tracks under the host's number")
    void mouseButtonPollsReadTheHostButton() {
        assertEquals("isLeftPressed", RetroKeyMapping.mouseButtonQuery(1, SDL_HOST));
        assertEquals("isRightPressed", RetroKeyMapping.mouseButtonQuery(3, SDL_HOST));
        assertEquals("isLeftPressed", RetroKeyMapping.mouseButtonQuery(
                RetroKeyMapping.translate(0, true, SDL_HOST), SDL_HOST),
                "GLFW's literal left button is host button 1");
        assertNull(RetroKeyMapping.mouseButtonQuery(8, SDL_HOST),
                "Minecraft keeps no state for extra buttons, so they read as released");
    }

    @Test
    @DisplayName("with no host constants the button keeps GLFW's numbering")
    void mouseButtonPollsWithoutHostConstants() {
        assertEquals("isLeftPressed", RetroKeyMapping.mouseButtonQuery(0, null));
        assertEquals("isRightPressed", RetroKeyMapping.mouseButtonQuery(1, null));
        assertEquals("isMiddlePressed", RetroKeyMapping.mouseButtonQuery(2, null));
    }

    @Test
    @DisplayName("GLFW polls before the client exists read as released and leave the cursor at zero")
    void glfwPollsWithoutAClient() {
        assertEquals(0, RetroKeyMapping.glfwGetKey(1L, 82));
        assertEquals(0, RetroKeyMapping.glfwGetKeyUntranslated(1L, -1));
        assertEquals(0, RetroKeyMapping.glfwGetMouseButton(1L, 0));
        double[] x = {5.0};
        double[] y = {7.0};
        RetroKeyMapping.glfwGetCursorPos(1L, x, y);
        assertEquals(0.0, x[0]);
        assertEquals(0.0, y[0]);
    }

    @Test
    @DisplayName("mouse polls read the live client state on every call")
    void mousePollsReadTheClientEachTime() throws Exception {
        ClientStubs client = new ClientStubs();
        Class<?> keys = client.loadClass(RetroKeyMapping.class.getName());
        Method button = keys.getMethod("glfwGetMouseButtonUntranslated", long.class, int.class);
        Method cursor = keys.getMethod("glfwGetCursorPos", long.class, double[].class, double[].class);

        assertEquals(0, button.invoke(null, 1L, 0), "no client yet");
        Object mouse = client.startClient();
        mouse.getClass().getField("left").setBoolean(mouse, true);
        mouse.getClass().getField("x").setDouble(mouse, 12.5);
        assertEquals(1, button.invoke(null, 1L, 0), "GLFW's left button is held");
        double[] x = new double[1];
        cursor.invoke(null, 1L, x, new double[1]);
        assertEquals(12.5, x[0]);

        mouse.getClass().getField("left").setBoolean(mouse, false);
        mouse.getClass().getField("x").setDouble(mouse, 3.0);
        assertEquals(0, button.invoke(null, 1L, 0), "the looked-up members still read the live state");
        cursor.invoke(null, 1L, x, new double[1]);
        assertEquals(3.0, x[0]);
    }

    /**
     * A loader holding its own copy of {@code RetroKeyMapping} and stand-ins for the client's
     * {@code Minecraft} and {@code MouseHandler}, so the test classpath never has a Minecraft class.
     */
    private static final class ClientStubs extends ClassLoader {
        private static final String MINECRAFT = "net/minecraft/client/Minecraft";
        private static final String MOUSE = "net/minecraft/client/MouseHandler";
        private final Map<String, byte[]> classes = new HashMap<>();
        private final Map<String, Class<?>> defined = new HashMap<>();

        ClientStubs() throws Exception {
            super(RetroKeyMappingSdlTest.class.getClassLoader());
            String keys = RetroKeyMapping.class.getName();
            try (InputStream in = getParent().getResourceAsStream(keys.replace('.', '/') + ".class")) {
                classes.put(keys, in.readAllBytes());
            }
            classes.put(MINECRAFT.replace('/', '.'), minecraft());
            classes.put(MOUSE.replace('/', '.'), mouseHandler());
        }

        /** Creates the client singleton and returns its mouse handler. */
        Object startClient() throws Exception {
            Class<?> minecraft = loadClass(MINECRAFT.replace('/', '.'));
            Object client = minecraft.getConstructor().newInstance();
            minecraft.getField("instance").set(null, client);
            return minecraft.getField("mouseHandler").get(client);
        }

        @Override
        protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            byte[] bytes = classes.get(name);
            if (bytes == null) return super.loadClass(name, resolve);
            Class<?> type = defined.get(name);
            if (type == null) {
                type = defineClass(name, bytes, 0, bytes.length);
                defined.put(name, type);
            }
            return type;
        }

        /** {@code Minecraft} with a settable {@code instance} and a public {@code mouseHandler}. */
        private static byte[] minecraft() {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(V17, ACC_PUBLIC, MINECRAFT, null, "java/lang/Object", null);
            cw.visitField(ACC_PUBLIC | ACC_STATIC, "instance", "L" + MINECRAFT + ";", null, null).visitEnd();
            cw.visitField(ACC_PUBLIC | ACC_FINAL, "mouseHandler", "L" + MOUSE + ";", null, null).visitEnd();
            MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
            init.visitCode();
            init.visitVarInsn(ALOAD, 0);
            init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            init.visitVarInsn(ALOAD, 0);
            init.visitTypeInsn(NEW, MOUSE);
            init.visitInsn(DUP);
            init.visitMethodInsn(INVOKESPECIAL, MOUSE, "<init>", "()V", false);
            init.visitFieldInsn(PUTFIELD, MINECRAFT, "mouseHandler", "L" + MOUSE + ";");
            init.visitInsn(RETURN);
            init.visitMaxs(0, 0);
            init.visitEnd();
            MethodVisitor get = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "getInstance", "()L" + MINECRAFT + ";",
                    null, null);
            get.visitCode();
            get.visitFieldInsn(GETSTATIC, MINECRAFT, "instance", "L" + MINECRAFT + ";");
            get.visitInsn(ARETURN);
            get.visitMaxs(0, 0);
            get.visitEnd();
            cw.visitEnd();
            return cw.toByteArray();
        }

        /** {@code MouseHandler} reporting its public {@code left} and {@code x} fields. */
        private static byte[] mouseHandler() {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(V17, ACC_PUBLIC, MOUSE, null, "java/lang/Object", null);
            cw.visitField(ACC_PUBLIC, "left", "Z", null, null).visitEnd();
            cw.visitField(ACC_PUBLIC, "x", "D", null, null).visitEnd();
            MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
            init.visitCode();
            init.visitVarInsn(ALOAD, 0);
            init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            init.visitInsn(RETURN);
            init.visitMaxs(0, 0);
            init.visitEnd();
            getter(cw, "isLeftPressed", "Z", "left");
            getter(cw, "xpos", "D", "x");
            cw.visitEnd();
            return cw.toByteArray();
        }

        private static void getter(ClassWriter cw, String name, String type, String field) {
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, name, "()" + type, null, null);
            mv.visitCode();
            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(GETFIELD, MOUSE, field, type);
            mv.visitInsn(type.equals("D") ? DRETURN : IRETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
    }
}
