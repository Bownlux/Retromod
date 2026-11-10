/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static com.retromod.shim.common.BridgeHarness.*;
import static org.junit.jupiter.api.Assertions.*;

/** A mod's own KeyMapping subclass, like Carry On's, whose super(...) call names the old String category. */
class KeyMappingSubclassCategoryTest {

    private static final String KEY = "mod/CarryKey";

    @TempDir
    static Path dir;

    private static Map<String, byte[]> mod;
    private static Map<String, byte[]> host;

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @BeforeAll
    static void compileShapes() throws Exception {
        Map<String, String> old = new HashMap<>(shared());
        old.put("net/minecraft/client/KeyMapping.java", """
                package net.minecraft.client;
                public class KeyMapping {
                    public KeyMapping(String name, int code, String category) {}
                    public KeyMapping(String name, com.mojang.blaze3d.platform.InputConstants.Type type, int code, String category) {}
                }""");
        old.put("mod/CarryKey.java", """
                package mod;
                import com.mojang.blaze3d.platform.InputConstants;
                public class CarryKey extends net.minecraft.client.KeyMapping {
                    public CarryKey(String name, int code, String category) { super(name, code, category); }
                    public CarryKey(String name, InputConstants.Type type, int code, String category) { super(name, type, code, category); }
                    public static Object plain(String category) { return new CarryKey("key.carry.desc", 340, category); }
                    public static Object mouse() { return new CarryKey("key.carry.desc", InputConstants.Type.MOUSE, 2, "key.categories.misc"); }
                }""");
        mod = only(compile(dir.resolve("old"), old), "mod/");
        Map<String, String> current = new HashMap<>(shared());
        current.put("net/minecraft/client/KeyMapping.java", """
                package net.minecraft.client;
                import com.mojang.blaze3d.platform.InputConstants;
                import net.minecraft.resources.Identifier;
                public class KeyMapping {
                    private final String text;
                    public KeyMapping(String name, int code, Category category) { this(name, InputConstants.Type.KEYSYM, code, category); }
                    public KeyMapping(String name, InputConstants.Type type, int code, Category category) {
                        text = name + " " + type + " " + code + " " + category.id();
                    }
                    @Override public String toString() { return text; }
                    public record Category(Identifier id) {
                        public static final Category MISC = new Category(Identifier.fromNamespaceAndPath("minecraft", "misc"));
                        private static final java.util.Set<Identifier> KNOWN = new java.util.HashSet<>();
                        public static Category register(Identifier id) {
                            if (!KNOWN.add(id)) throw new IllegalArgumentException("already registered: " + id);
                            return new Category(id);
                        }
                    }
                }""");
        host = compile(dir.resolve("host"), current);
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("The subclass's old super calls fail as written and build a key with a Category once adapted")
    void subclassGetsACategory() throws Throwable {
        Run old = new Run(host, mod);
        assertInstanceOf(NoSuchMethodError.class, assertThrows(Throwable.class, () -> old.call(KEY, "plain", "key.carry.category")));

        Run game = new Run(host, transform("26.2", Common_1_21_11_to_26_1_ClassMoves::registerStringCategoryKeyMappings, mod));
        assertEquals("key.carry.desc KEYSYM 340 retromod_compat:key_carry_category", game.call(KEY, "plain", "key.carry.category").toString(),
                "a mod's own category string becomes a registered category");
        assertEquals("key.carry.desc KEYSYM 340 minecraft:misc", game.call(KEY, "plain", "key.categories.misc").toString(),
                "a vanilla category string becomes the vanilla category");
        assertEquals("key.carry.desc MOUSE 2 minecraft:misc", game.call(KEY, "mouse").toString(),
                "the four-argument form keeps its input type and code");
    }

    @Test
    @DisplayName("The 26.1 class moves, which every loader's 26.1 shim runs, adapt the subclass")
    void commonMovesAdaptIt() {
        String plain = "net/minecraft/client/KeyMapping.<init>(Ljava/lang/String;ILnet/minecraft/client/KeyMapping$Category;)V";
        assertTrue(calls(transform("26.2", Common_1_21_11_to_26_1_ClassMoves::register, mod).get(KEY), "<init>").contains(plain));
    }

    private static Map<String, String> shared() {
        Map<String, String> sources = new HashMap<>();
        sources.put("com/mojang/blaze3d/platform/InputConstants.java", """
                package com.mojang.blaze3d.platform;
                public class InputConstants { public enum Type { KEYSYM, SCANCODE, MOUSE } }""");
        sources.put("net/minecraft/resources/Identifier.java", """
                package net.minecraft.resources;
                public record Identifier(String namespace, String path) {
                    public static Identifier fromNamespaceAndPath(String namespace, String path) { return new Identifier(namespace, path); }
                    @Override public String toString() { return namespace + ":" + path; }
                }""");
        return sources;
    }
}
