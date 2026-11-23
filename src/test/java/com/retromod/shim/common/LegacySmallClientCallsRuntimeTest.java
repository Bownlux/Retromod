/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.shim.common.embedded.LegacyLog;
import com.retromod.testsupport.fakemc.commands.FakeChatCommandPacket;
import com.retromod.testsupport.fakemc.commands.FakeDispatcher;
import com.retromod.testsupport.fakemc.commands.FakePacket;
import com.retromod.testsupport.fakemc.commands.FakePacketListener;
import com.retromod.testsupport.fakemc.commands.FakeParseResults;
import com.retromod.testsupport.fakemc.commands.FakeSignableCommand;
import com.retromod.testsupport.fakemc.commands.FakeSuggestionProvider;
import com.retromod.testsupport.fakemc.keys.FakeInputConstantsSdl;
import com.retromod.testsupport.fakemc.keys.FakeKey;
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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The rebuilt unsigned command send and select-key check, run against stand-ins for a 26.3 client. */
class LegacySmallClientCallsRuntimeTest {

    private static final Path COMPILED = Path.of("target", "legacyapi-classes");
    private static final String CALLS = "com/retromod/legacyapi/minecraft/client/LegacySmallClientCalls";
    private static final Map<Class<?>, String> NAMES = Map.ofEntries(
            Map.entry(FakePacketListener.class, "net/minecraft/client/multiplayer/ClientPacketListener"),
            Map.entry(FakeSuggestionProvider.class, "net/minecraft/client/multiplayer/ClientSuggestionProvider"),
            Map.entry(FakeDispatcher.class, "com/mojang/brigadier/CommandDispatcher"),
            Map.entry(FakeParseResults.class, "com/mojang/brigadier/ParseResults"),
            Map.entry(FakeSignableCommand.class, "net/minecraft/network/chat/SignableCommand"),
            Map.entry(FakePacket.class, "net/minecraft/network/protocol/Packet"),
            Map.entry(FakeChatCommandPacket.class, "net/minecraft/network/protocol/game/ServerboundChatCommandPacket"),
            Map.entry(FakeInputConstantsSdl.class, "com/mojang/blaze3d/platform/InputConstants"),
            Map.entry(FakeKey.class, "com/mojang/blaze3d/platform/InputConstants$Key"));

    @Test
    @DisplayName("a command with a signable argument is refused and nothing is sent; a plain one goes out unsigned")
    void signableCommandIsRefused() throws Exception {
        ClassLoader game = game();
        Object listener = type(game, "net/minecraft/client/multiplayer/ClientPacketListener").getConstructor().newInstance();
        List<?> sent = (List<?>) listener.getClass().getField("SENT").get(null);

        assertFalse(sendUnsigned(game, listener, "msg Steve hello"), "a signable command answers false");
        assertTrue(sent.isEmpty(), "and sends nothing: " + sent);

        assertTrue(sendUnsigned(game, listener, "time set day"));
        assertEquals(1, sent.size());
        assertEquals("net.minecraft.network.protocol.game.ServerboundChatCommandPacket", sent.get(0).getClass().getName());
        assertEquals("time set day", sent.get(0).toString());
    }

    @Test
    @DisplayName("on 26.3 Enter, Space and keypad Enter are SDL keys 40, 44 and 88; the old GLFW numbers select nothing")
    void selectKeysUseTheHostNumbers() throws Exception {
        ClassLoader game = game();
        for (int key : new int[]{40, 44, 88}) assertTrue(selected(game, key), "key " + key);
        for (int key : new int[]{257, 32, 335}) assertFalse(selected(game, key), "GLFW key " + key + " is not a select key on 26.3");
    }

    private static boolean sendUnsigned(ClassLoader game, Object listener, String command) throws Exception {
        return (Boolean) type(game, CALLS).getMethod("sendUnsignedCommand", listener.getClass(), String.class)
                .invoke(null, listener, command);
    }

    private static boolean selected(ClassLoader game, int key) throws Exception {
        return (Boolean) type(game, CALLS).getMethod("selected", int.class).invoke(null, key);
    }

    private static Class<?> type(ClassLoader game, String internalName) throws ClassNotFoundException {
        return Class.forName(internalName.replace('/', '.'), true, game);
    }

    /** The stand-ins under their game names and the compiled calls in one loader; other game types are empty. */
    private static ClassLoader game() throws IOException {
        Map<String, String> rename = new HashMap<>();
        NAMES.forEach((fake, name) -> rename.put(internal(fake), name));
        SimpleRemapper remapper = new SimpleRemapper(rename);
        Map<String, byte[]> classes = new HashMap<>();
        for (Map.Entry<Class<?>, String> fake : NAMES.entrySet()) {
            classes.put(fake.getValue().replace('/', '.'), remap(bytesOf(fake.getKey()), remapper));
        }
        for (String own : new String[]{CALLS, CALLS + "$SelectKeys"}) {
            classes.put(own.replace('/', '.'), Files.readAllBytes(COMPILED.resolve(own + ".class")));
        }
        classes.put(LegacyLog.class.getName(), bytesOf(LegacyLog.class));
        return new ClassLoader(ClassLoader.getPlatformClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classes.get(name);
                // The verifier loads other types the class names; an empty interface passes its assignment checks.
                if (bytes == null && (name.startsWith("net.minecraft.") || name.startsWith("com.mojang."))) {
                    bytes = empty(name.replace('.', '/'));
                }
                if (bytes == null) throw new ClassNotFoundException(name);
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
    }

    private static byte[] empty(String name) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, name, null, "java/lang/Object", null);
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
