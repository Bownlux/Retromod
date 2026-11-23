/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.testsupport.fakemc.keys.FakeInputConstantsGlfw;
import com.retromod.testsupport.fakemc.keys.FakeInputConstantsSdl;
import com.retromod.testsupport.fakemc.keys.FakeInputQuirks;
import com.retromod.testsupport.fakemc.keys.FakeKey;
import com.retromod.testsupport.fakemc.keys.FakeMinecraft;
import com.retromod.testsupport.fakemc.keys.FakeWindow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The old copy, cut, paste and select-all checks, run against stand-ins for a GLFW host and 26.3's SDL host. */
class LegacyScreenKeysRuntimeTest {

    private static final Path COMPILED = Path.of("target", "legacyapi-classes");
    private static final String CALLS = "com/retromod/legacyapi/minecraft/client/LegacyScreenCalls";
    private static final String INPUT = "com/mojang/blaze3d/platform/InputConstants";

    @Test
    @DisplayName("on 26.3 Ctrl+C is SDL key 6, and the old GLFW numbers (F10) or letter codes are not a copy")
    void sdlHostUsesItsOwnNumbers() throws Exception {
        Host host = new Host(FakeInputConstantsSdl.class);
        host.modifiers(true, false, false);

        assertTrue(host.check("isCopy", 6));
        assertTrue(host.check("isSelectAll", 4));
        assertFalse(host.check("isCopy", 67), "67 is F10 on 26.3");
        assertFalse(host.check("isCopy", 'c'), "99 is a keypad key on 26.3");
        assertFalse(host.check("isPaste", 6));
    }

    @Test
    @DisplayName("on a GLFW host the checks keep the old numbers, and shift or alt still cancels them")
    void glfwHostKeepsTheOldNumbers() throws Exception {
        Host host = new Host(FakeInputConstantsGlfw.class);
        host.modifiers(true, false, false);
        assertTrue(host.check("isCut", 88));
        assertTrue(host.check("isPaste", 86));

        host.modifiers(true, true, false);
        assertFalse(host.check("isPaste", 86), "Ctrl+Shift+V is not a paste");
        host.modifiers(false, false, false);
        assertFalse(host.check("isPaste", 86), "V alone is not a paste");
    }

    @Test
    @DisplayName("on macOS Command is the shortcut key, through each host's own key check")
    void macUsesCommand() throws Exception {
        for (Class<?> input : new Class<?>[]{FakeInputConstantsSdl.class, FakeInputConstantsGlfw.class}) {
            Host host = new Host(input);
            host.macOs();
            int c = input == FakeInputConstantsSdl.class ? 6 : 67;
            int leftCommand = input == FakeInputConstantsSdl.class ? 227 : 343;

            host.modifiers(true, false, false);
            assertFalse(host.check("isCopy", c), "Control is not the shortcut key on macOS: " + input.getSimpleName());
            host.modifiers(false, false, false);
            host.press(leftCommand);
            assertTrue(host.check("isCopy", c), "Command+C copies: " + input.getSimpleName());
        }
    }

    /** One host's classes in their own loader, with the rebuilt calls compiled against the stubs. */
    private static final class Host {
        private final ClassLoader loader;
        private final String input;

        Host(Class<?> inputConstants) throws IOException {
            Map<String, String> names = new HashMap<>();
            names.put(internal(inputConstants), INPUT);
            names.put(internal(FakeKey.class), INPUT + "$Key");
            names.put(internal(FakeWindow.class), "com/mojang/blaze3d/platform/Window");
            names.put(internal(FakeMinecraft.class), "net/minecraft/client/Minecraft");
            names.put(internal(FakeInputQuirks.class), "net/minecraft/client/input/InputQuirks");
            SimpleRemapper remapper = new SimpleRemapper(names);
            Map<String, byte[]> classes = new HashMap<>();
            for (Class<?> fake : new Class<?>[]{inputConstants, FakeKey.class, FakeWindow.class, FakeMinecraft.class, FakeInputQuirks.class}) {
                classes.put(names.get(internal(fake)).replace('/', '.'), remap(bytesOf(fake), remapper));
            }
            for (String own : new String[]{CALLS, CALLS + "$Keys"}) {
                classes.put(own.replace('/', '.'), Files.readAllBytes(COMPILED.resolve(own + ".class")));
            }
            input = INPUT.replace('/', '.');
            loader = new ClassLoader(ClassLoader.getPlatformClassLoader()) {
                @Override
                protected Class<?> findClass(String name) throws ClassNotFoundException {
                    byte[] bytes = classes.get(name);
                    // The verifier loads the text and model types the other calls name; empty ones are enough here.
                    if (bytes == null && name.startsWith("net.minecraft.")) bytes = empty(name.replace('.', '/'));
                    if (bytes == null) throw new ClassNotFoundException(name);
                    return defineClass(name, bytes, 0, bytes.length);
                }
            };
        }

        void modifiers(boolean control, boolean shift, boolean alt) throws Exception {
            Class<?> minecraft = type("net.minecraft.client.Minecraft");
            minecraft.getField("control").setBoolean(null, control);
            minecraft.getField("shift").setBoolean(null, shift);
            minecraft.getField("alt").setBoolean(null, alt);
        }

        void macOs() throws Exception {
            type("net.minecraft.client.input.InputQuirks").getField("REPLACE_CTRL_KEY_WITH_CMD_KEY").setBoolean(null, true);
        }

        @SuppressWarnings("unchecked")
        void press(int key) throws Exception {
            ((Set<Integer>) type(input).getField("DOWN").get(null)).add(key);
        }

        boolean check(String name, int key) throws Exception {
            return (Boolean) type(CALLS.replace('/', '.')).getMethod(name, int.class).invoke(null, key);
        }

        private Class<?> type(String name) throws ClassNotFoundException {
            return Class.forName(name, true, loader);
        }
    }

    private static byte[] empty(String name) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static String internal(Class<?> type) {
        return type.getName().replace('.', '/');
    }

    private static byte[] bytesOf(Class<?> type) throws IOException {
        try (InputStream in = type.getClassLoader().getResourceAsStream(internal(type) + ".class")) {
            return in.readAllBytes();
        }
    }

    private static byte[] remap(byte[] bytes, SimpleRemapper remapper) {
        ClassWriter cw = new ClassWriter(0);
        new ClassReader(bytes).accept(new ClassRemapper(cw, remapper), 0);
        return cw.toByteArray();
    }
}
