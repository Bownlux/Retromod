/*
 * Retromod test suite. Copyright (c) 2026 Bownlux. MIT License.
 */
package com.retromod.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Clients get Retromod's title-screen button, and no entry point opens a Swing window. */
class ClientInterfaceStartupTest {

    private static final List<String> ENTRY_POINTS = List.of("com/retromod/core/Retromod",
            "com/retromod/core/RetromodClient", "com/retromod/core/RetromodServer",
            "com/retromod/core/RetromodPreLaunch", "com/retromod/core/RetromodForge",
            "com/retromod/core/RetromodNeoForge");

    @Test
    @DisplayName("both Forge-family entry points register the title-screen button")
    void forgeFamilyEntryPointsRegisterTheButton() throws IOException {
        for (String entryPoint : List.of("com/retromod/core/RetromodForge",
                "com/retromod/core/RetromodNeoForge")) {
            assertTrue(calls(entryPoint).stream().anyMatch(call -> call.owner.equals(
                    "com/retromod/gui/TitleScreenButtonInjector") && call.name.equals("register")),
                    entryPoint + " registers the title-screen button");
        }
    }

    @Test
    @DisplayName("no entry point opens a Swing window")
    void noEntryPointOpensSwing() throws IOException {
        // Minecraft's client Main sets java.awt.headless=true before any mod is constructed,
        // from 1.20.1 to 26.3, so a Swing window started from an entry point never opens.
        for (String entryPoint : ENTRY_POINTS) {
            String constants = constantPool(entryPoint);
            assertFalse(constants.contains("javax/swing/"), entryPoint + " must not start Swing");
            assertFalse(constants.contains("com/retromod/gui/RetromodGui"),
                    entryPoint + " must not reach the removed Swing setup");
        }
    }

    private static List<MethodInsnNode> calls(String internalName) throws IOException {
        ClassNode node = new ClassNode();
        new ClassReader(bytes(internalName)).accept(node, 0);
        List<MethodInsnNode> calls = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call) calls.add(call);
            }
        }
        return calls;
    }

    private static String constantPool(String internalName) throws IOException {
        // Class and descriptor names sit in the constant pool as plain UTF-8.
        return new String(bytes(internalName), StandardCharsets.ISO_8859_1);
    }

    private static byte[] bytes(String internalName) throws IOException {
        try (InputStream in = ClientInterfaceStartupTest.class.getResourceAsStream(
                "/" + internalName + ".class")) {
            assertNotNull(in, "class not found on the test classpath: " + internalName);
            return in.readAllBytes();
        }
    }
}
