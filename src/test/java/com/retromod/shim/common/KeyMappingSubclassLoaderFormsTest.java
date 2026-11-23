/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.fabric.Fabric_1_21_8_to_1_21_9;
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

/** KeyMapping subclasses whose super(...) passes a String category to a loader's own constructor (#425). */
class KeyMappingSubclassLoaderFormsTest {

    private static final String NEO_KEY = "mod/NeoKey";
    private static final String FORGE_KEY = "mod/ForgeKey";
    private static final String FABRIC_KEY = "mod/FabricKey";

    @TempDir
    static Path dir;

    private static Map<String, byte[]> neoMod;
    private static Map<String, byte[]> neoHost;
    private static Map<String, byte[]> forgeMod;
    private static Map<String, byte[]> forgeHost;
    private static Map<String, byte[]> fabricMod;

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @BeforeAll
    static void compileShapes() throws Exception {
        neoMod = only(compile(dir.resolve("neo-old"), oldShapes("net.neoforged.neoforge.client.settings", NEO_KEY)), "mod/");
        neoHost = compile(dir.resolve("neo-host"), currentShapes("net.neoforged.neoforge.client.settings", ""));
        forgeMod = only(compile(dir.resolve("forge-old"), oldShapes("net.minecraftforge.client.settings", FORGE_KEY)), "mod/");
        forgeHost = compile(dir.resolve("forge-host"), currentShapes("net.minecraftforge.client.settings", ", int order"));

        // A distributed Fabric mod names KeyBinding and its input type by intermediary.
        Map<String, String> fabric = new HashMap<>();
        fabric.put("net/minecraft/class_3675.java", """
                package net.minecraft;
                public class class_3675 { public enum class_307 { field_1668, field_1672 } }""");
        fabric.put("net/minecraft/class_304.java", """
                package net.minecraft;
                public class class_304 {
                    public class_304(String name, int code, String category) {}
                    public class_304(String name, class_3675.class_307 type, int code, String category) {}
                }""");
        fabric.put("mod/FabricKey.java", """
                package mod;
                public class FabricKey extends net.minecraft.class_304 {
                    public FabricKey(String name, int code) { super(name, code, "key.categories.misc"); }
                    public FabricKey(String name, net.minecraft.class_3675.class_307 type, int code) { super(name, type, code, "key.categories.misc"); }
                }""");
        fabricMod = only(compile(dir.resolve("fabric-old"), fabric), "mod/");
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("A NeoForge conflict-context subclass builds its key with a Category")
    void neoForgeConflictForms() throws Throwable {
        Run old = new Run(neoHost, neoMod);
        assertInstanceOf(NoSuchMethodError.class, assertThrows(Throwable.class, () -> old.call(NEO_KEY, "typed")));

        Run game = new Run(neoHost, transform("26.2", Common_1_21_11_to_26_1_ClassMoves::registerStringCategoryKeyMappings, neoMod));
        assertEquals("ctx MOUSE 2 minecraft:misc", game.call(NEO_KEY, "typed").toString(),
                "the five-argument form keeps its context, input type, and code");
        assertEquals("ctx key retromod_compat:key_neo_category", game.call(NEO_KEY, "keyed").toString(),
                "the key form converts only the category");
        assertEquals("ctx mod MOUSE 2 minecraft:misc", game.call(NEO_KEY, "modifiedTyped").toString());
        assertEquals("ctx mod key minecraft:misc", game.call(NEO_KEY, "modifiedKeyed").toString());
    }

    @Test
    @DisplayName("A Forge conflict-context subclass also passes the sort order the Forge constructor added")
    void forgeConflictFormsAddTheOrder() throws Throwable {
        Run game = new Run(forgeHost, transform("26.2", Common_1_21_11_to_26_1_ClassMoves::registerStringCategoryKeyMappings, forgeMod));
        assertEquals("ctx MOUSE 2 minecraft:misc 0", game.call(FORGE_KEY, "typed").toString());
        assertEquals("ctx key retromod_compat:key_neo_category 0", game.call(FORGE_KEY, "keyed").toString());
        assertEquals("ctx mod MOUSE 2 minecraft:misc 0", game.call(FORGE_KEY, "modifiedTyped").toString());
        assertEquals("ctx mod key minecraft:misc 0", game.call(FORGE_KEY, "modifiedKeyed").toString());
    }

    @Test
    @DisplayName("Fabric 1.21.9 to 1.21.11 adapt an intermediary subclass, and 26.x leaves it to the full remap")
    void fabricIntermediarySubclass() {
        String category = "Lnet/minecraft/class_304$class_11900;";
        String typed = "net/minecraft/class_304.<init>(Ljava/lang/String;Lnet/minecraft/class_3675$class_307;I" + category + ")V";
        String plain = "net/minecraft/class_304.<init>(Ljava/lang/String;I" + category + ")V";
        Map<String, byte[]> adapted = transform("1.21.11", t -> new Fabric_1_21_8_to_1_21_9().registerRedirects(t), fabricMod);
        assertTrue(constructorCalls(adapted.get(FABRIC_KEY)).containsAll(java.util.List.of(typed, plain)),
                "both old super calls name the Category constructors: " + constructorCalls(adapted.get(FABRIC_KEY)));

        Map<String, byte[]> modern = transform("26.2", t -> new Fabric_1_21_8_to_1_21_9().registerRedirects(t), fabricMod);
        assertFalse(constructorCalls(modern.get(FABRIC_KEY)).contains(plain), "the intermediary adapter is off on 26.x hosts");
    }

    /** Each call in every constructor of the class, as {@code owner.name(desc)}. */
    private static java.util.List<String> constructorCalls(byte[] bytes) {
        org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
        new org.objectweb.asm.ClassReader(bytes).accept(node, 0);
        java.util.List<String> calls = new java.util.ArrayList<>();
        for (org.objectweb.asm.tree.MethodNode method : node.methods) {
            if (!method.name.equals("<init>")) continue;
            for (org.objectweb.asm.tree.AbstractInsnNode insn : method.instructions) {
                if (insn instanceof org.objectweb.asm.tree.MethodInsnNode call) calls.add(call.owner + "." + call.name + call.desc);
            }
        }
        return calls;
    }

    /** A mod's subclass over every old conflict-context constructor of one loader's settings package. */
    private static Map<String, String> oldShapes(String settings, String keyClass) {
        Map<String, String> sources = new HashMap<>(shared(settings));
        sources.put("net/minecraft/client/KeyMapping.java", """
                package net.minecraft.client;
                import com.mojang.blaze3d.platform.InputConstants;
                import %1$s.*;
                public class KeyMapping {
                    public KeyMapping(String name, IKeyConflictContext context, InputConstants.Type type, int code, String category) {}
                    public KeyMapping(String name, IKeyConflictContext context, InputConstants.Key key, String category) {}
                    public KeyMapping(String name, IKeyConflictContext context, KeyModifier modifier, InputConstants.Type type, int code, String category) {}
                    public KeyMapping(String name, IKeyConflictContext context, KeyModifier modifier, InputConstants.Key key, String category) {}
                }""".formatted(settings));
        String simple = keyClass.substring(keyClass.lastIndexOf('/') + 1);
        sources.put(keyClass + ".java", """
                package mod;
                import com.mojang.blaze3d.platform.InputConstants;
                import %1$s.*;
                public class %2$s extends net.minecraft.client.KeyMapping {
                    %2$s(IKeyConflictContext c, InputConstants.Type t, int code, String cat) { super("k", c, t, code, cat); }
                    %2$s(IKeyConflictContext c, InputConstants.Key key, String cat) { super("k", c, key, cat); }
                    %2$s(IKeyConflictContext c, KeyModifier m, InputConstants.Type t, int code, String cat) { super("k", c, m, t, code, cat); }
                    %2$s(IKeyConflictContext c, KeyModifier m, InputConstants.Key key, String cat) { super("k", c, m, key, cat); }
                    public static Object typed() { return new %2$s(new IKeyConflictContext() {}, InputConstants.Type.MOUSE, 2, "key.categories.misc"); }
                    public static Object keyed() { return new %2$s(new IKeyConflictContext() {}, new InputConstants.Key(), "key.neo.category"); }
                    public static Object modifiedTyped() { return new %2$s(new IKeyConflictContext() {}, KeyModifier.CONTROL, InputConstants.Type.MOUSE, 2, "key.categories.misc"); }
                    public static Object modifiedKeyed() { return new %2$s(new IKeyConflictContext() {}, KeyModifier.CONTROL, new InputConstants.Key(), "key.categories.misc"); }
                }""".formatted(settings, simple));
        return sources;
    }

    /** The host's KeyMapping, whose conflict constructors take a Category and, on Forge, an order after it. */
    private static Map<String, String> currentShapes(String settings, String order) {
        Map<String, String> sources = new HashMap<>(shared(settings));
        String orderText = order.isEmpty() ? "\"\"" : "\" \" + order";
        sources.put("net/minecraft/client/KeyMapping.java", """
                package net.minecraft.client;
                import com.mojang.blaze3d.platform.InputConstants;
                import net.minecraft.resources.Identifier;
                import %1$s.*;
                public class KeyMapping {
                    private final String text;
                    public KeyMapping(String name, IKeyConflictContext context, InputConstants.Type type, int code, Category category%2$s) {
                        text = "ctx " + type + " " + code + " " + category.id() + %3$s;
                    }
                    public KeyMapping(String name, IKeyConflictContext context, InputConstants.Key key, Category category%2$s) {
                        text = "ctx key " + category.id() + %3$s;
                    }
                    public KeyMapping(String name, IKeyConflictContext context, KeyModifier modifier, InputConstants.Type type, int code, Category category%2$s) {
                        text = "ctx mod " + type + " " + code + " " + category.id() + %3$s;
                    }
                    public KeyMapping(String name, IKeyConflictContext context, KeyModifier modifier, InputConstants.Key key, Category category%2$s) {
                        text = "ctx mod key " + category.id() + %3$s;
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
                }""".formatted(settings, order, orderText));
        return sources;
    }

    private static Map<String, String> shared(String settings) {
        Map<String, String> sources = new HashMap<>();
        String path = settings.replace('.', '/');
        sources.put("com/mojang/blaze3d/platform/InputConstants.java", """
                package com.mojang.blaze3d.platform;
                public class InputConstants {
                    public enum Type { KEYSYM, SCANCODE, MOUSE }
                    public static final class Key {}
                }""");
        sources.put("net/minecraft/resources/Identifier.java", """
                package net.minecraft.resources;
                public record Identifier(String namespace, String path) {
                    public static Identifier fromNamespaceAndPath(String namespace, String path) { return new Identifier(namespace, path); }
                    @Override public String toString() { return namespace + ":" + path; }
                }""");
        sources.put(path + "/IKeyConflictContext.java", "package " + settings + "; public interface IKeyConflictContext {}");
        sources.put(path + "/KeyModifier.java", "package " + settings + "; public enum KeyModifier { CONTROL }");
        return sources;
    }
}
