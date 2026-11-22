/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The rebuilt User.getType, run against a stand-in of the host's User with and without an Xbox user id. */
class LegacyUserTypesRuntimeTest {

    private static final Path COMPILED = Path.of("target", "legacyapi-classes");
    private static final String TYPES = "com/retromod/legacyapi/minecraft/client/LegacyUserTypes";

    @Test
    @DisplayName("a login with an Xbox user id is a Microsoft account, and one without is an offline account")
    void typeFollowsTheXuid(@TempDir Path dir) throws Exception {
        Map<String, byte[]> classes = new HashMap<>(BridgeHarness.compile(dir, Map.of("net/minecraft/client/User.java", """
                package net.minecraft.client;
                public class User {
                    private final java.util.Optional<String> xuid;
                    public User(java.util.Optional<String> xuid) { this.xuid = xuid; }
                    public java.util.Optional<String> getXuid() { return xuid; }
                }""")));
        classes.put(TYPES, Files.readAllBytes(COMPILED.resolve(TYPES + ".class")));
        ClassLoader game = new ClassLoader(LegacyUserTypesRuntimeTest.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                synchronized (getClassLoadingLock(name)) {
                    byte[] bytes = classes.get(name.replace('.', '/'));
                    if (bytes == null) return super.loadClass(name, resolve);
                    Class<?> loaded = findLoadedClass(name);
                    return loaded != null ? loaded : defineClass(name, bytes, 0, bytes.length);
                }
            }
        };
        Class<?> user = game.loadClass("net.minecraft.client.User");
        var getType = game.loadClass(TYPES.replace('/', '.')).getMethod("getType", user);

        Object online = user.getConstructor(java.util.Optional.class).newInstance(java.util.Optional.of("2535400000000000"));
        Object offline = user.getConstructor(java.util.Optional.class).newInstance(java.util.Optional.empty());
        assertEquals("MSA", String.valueOf(getType.invoke(null, online)));
        assertEquals("LEGACY", String.valueOf(getType.invoke(null, offline)), "old launches defaulted to the legacy type");
    }
}
