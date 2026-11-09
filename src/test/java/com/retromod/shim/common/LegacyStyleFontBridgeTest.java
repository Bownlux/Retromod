/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.VersionShim;
import com.retromod.shim.fabric.Fabric_1_21_8_to_1_21_9;
import com.retromod.shim.forge.Forge_1_21_8_to_1_21_9;
import com.retromod.shim.neoforge.NeoForge_1_21_8_to_1_21_9;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static com.retromod.shim.common.BridgeHarness.*;
import static com.retromod.shim.common.LegacyStyleFontBridge.*;
import static org.junit.jupiter.api.Assertions.*;

/** A mod's {@code Style.withFont(id)} and {@code getFont()} where 1.21.9 made the font a {@code FontDescription}. */
class LegacyStyleFontBridgeTest {

    private static final String FONTS = "mod/Fonts";

    @TempDir
    static Path dir;

    private static Map<String, byte[]> mojangMod;
    private static Map<String, byte[]> intermediaryMod;
    private static Map<String, byte[]> mojangHost263;
    private static Map<String, byte[]> intermediaryHost12110;

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @BeforeAll
    static void compileShapes() throws Exception {
        mojangMod = only(compile(dir.resolve("mojang-old"), Map.of(
                "net/minecraft/resources/ResourceLocation.java", """
                        package net.minecraft.resources;
                        public class ResourceLocation { public ResourceLocation(String namespace, String path) {} }""",
                "net/minecraft/network/chat/Style.java", """
                        package net.minecraft.network.chat;
                        import net.minecraft.resources.ResourceLocation;
                        public class Style {
                            public static final Style EMPTY = null;
                            public static final Style SPRITE = null;
                            public Style withFont(ResourceLocation font) { return this; }
                            public ResourceLocation getFont() { return null; }
                        }""",
                "mod/Fonts.java", """
                        package mod;
                        import net.minecraft.network.chat.Style;
                        import net.minecraft.resources.ResourceLocation;
                        public class Fonts {
                            public static String runes() { return "" + Style.EMPTY.withFont(new ResourceLocation("mod", "runes")).getFont(); }
                            public static String cleared() { return "" + Style.EMPTY.withFont(null).getFont(); }
                            public static String sprite() { return "" + Style.SPRITE.getFont(); }
                        }""")), "mod/");
        mojangHost263 = compile(dir.resolve("mojang-host"), Map.of(
                "net/minecraft/resources/Identifier.java", identifier("net.minecraft.resources", "Identifier"),
                "net/minecraft/network/chat/FontDescription.java", """
                        package net.minecraft.network.chat;
                        import net.minecraft.resources.Identifier;
                        public interface FontDescription {
                            Resource DEFAULT = new Resource(new Identifier("minecraft", "default"));
                            record Resource(Identifier id) implements FontDescription {}
                            record AtlasSprite(String atlas) implements FontDescription {}
                        }""",
                "net/minecraft/network/chat/Style.java", style("net.minecraft.network.chat", "Style",
                        "FontDescription", "FontDescription.AtlasSprite", "withFont", "getFont", "DEFAULT")));
        intermediaryMod = only(compile(dir.resolve("intermediary-old"), Map.of(
                "net/minecraft/class_2960.java", """
                        package net.minecraft;
                        public class class_2960 { public class_2960(String namespace, String path) {} }""",
                "net/minecraft/class_2583.java", """
                        package net.minecraft;
                        public class class_2583 {
                            public static final class_2583 EMPTY = null;
                            public static final class_2583 SPRITE = null;
                            public class_2583 method_27704(class_2960 font) { return this; }
                            public class_2960 method_27708() { return null; }
                        }""",
                "mod/Fonts.java", """
                        package mod;
                        import net.minecraft.*;
                        public class Fonts {
                            public static String runes() { return "" + class_2583.EMPTY.method_27704(new class_2960("mod", "runes")).method_27708(); }
                            public static String cleared() { return "" + class_2583.EMPTY.method_27704(null).method_27708(); }
                            public static String sprite() { return "" + class_2583.SPRITE.method_27708(); }
                        }""")), "mod/");
        intermediaryHost12110 = compile(dir.resolve("intermediary-host"), Map.of(
                "net/minecraft/class_2960.java", identifier("net.minecraft", "class_2960"),
                "net/minecraft/class_11719.java", """
                        package net.minecraft;
                        public interface class_11719 {
                            class_11721 field_61972 = new class_11721(new class_2960("minecraft", "default"));
                            record class_11721(class_2960 comp_4590) implements class_11719 {}
                            record Sprite(String atlas) implements class_11719 {}
                        }""",
                "net/minecraft/class_2583.java", style("net.minecraft", "class_2583", "class_11719",
                        "class_11719.Sprite", "method_27704", "method_27708", "field_61972")));
    }

    /** An identifier stand-in that prints as {@code namespace:path}. */
    private static String identifier(String pkg, String name) {
        return "package " + pkg + ";\n"
                + "public final class " + name + " {\n"
                + "    private final String text;\n"
                + "    public " + name + "(String namespace, String path) { text = namespace + \":\" + path; }\n"
                + "    public String toString() { return text; }\n"
                + "}";
    }

    /** The 1.21.9 style: a font description, with an unset font read as the default. */
    private static String style(String pkg, String name, String description, String sprite, String with,
            String get, String defaultField) {
        return "package " + pkg + ";\n"
                + "public final class " + name + " {\n"
                + "    public static final " + name + " EMPTY = new " + name + "(null);\n"
                + "    public static final " + name + " SPRITE = new " + name + "(new " + sprite + "(\"gui\"));\n"
                + "    private final " + description + " font;\n"
                + "    private " + name + "(" + description + " font) { this.font = font; }\n"
                + "    public " + name + " " + with + "(" + description + " font) { return new " + name + "(font); }\n"
                + "    public " + description + " " + get + "() { return font != null ? font : " + description + "."
                + defaultField + "; }\n"
                + "}";
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("on 26.3 the Mojang calls fail as written and set and read the font file once bridged")
    void mojangCallsOn263() throws Throwable {
        assertOldCallsFail(mojangHost263, mojangMod);
        Consumer<RetromodTransformer> register = t -> {
            // The 1.21.11 shims rename the identifier class first.
            t.registerClassRedirect("net/minecraft/resources/ResourceLocation", IDENTIFIER);
            LegacyStyleFontBridge.register(t);
        };
        assertBridgedCallsRun(new Run(mojangHost263, transform("26.3", register, mojangMod)));
    }

    @Test
    @DisplayName("on a Fabric 1.21.10 host the intermediary calls set and read the font file once bridged")
    void intermediaryCallsOn12110() throws Throwable {
        assertOldCallsFail(intermediaryHost12110, intermediaryMod);
        Run bridged = new Run(intermediaryHost12110,
                transform("1.21.10", t -> new Fabric_1_21_8_to_1_21_9().registerRedirects(t), intermediaryMod));
        assertBridgedCallsRun(bridged);
    }

    @Test
    @DisplayName("each loader's 1.21.9 shim bridges the call, naming the identifier class its host has")
    void everyLoaderBridgesIt() {
        String onHost12110 = MOJANG_HELPER + ".withFont(L" + STYLE + ";L" + RESOURCE_LOCATION + ";)L" + STYLE + ";";
        for (VersionShim shim : List.of(new NeoForge_1_21_8_to_1_21_9(), new Forge_1_21_8_to_1_21_9())) {
            assertTrue(calls(transform("1.21.10", shim::registerRedirects, mojangMod).get(FONTS), "runes")
                    .contains(onHost12110), shim.getShimName());
        }
        String onHost263 = MOJANG_HELPER + ".withFont(L" + STYLE + ";L" + IDENTIFIER + ";)L" + STYLE + ";";
        Consumer<RetromodTransformer> fabric = t -> {
            t.registerClassRedirect("net/minecraft/resources/ResourceLocation", IDENTIFIER);
            new Fabric_1_21_8_to_1_21_9().registerRedirects(t);
        };
        assertTrue(calls(transform("26.3", fabric, mojangMod).get(FONTS), "runes").contains(onHost263),
                "Fabric on 26.x, after the remap");
    }

    @Test
    @DisplayName("a 1.21.8 host, whose style still takes an identifier, keeps the calls")
    void gatedOnTheHost() {
        List<String> calls = calls(transform("1.21.8", LegacyStyleFontBridge::register, mojangMod).get(FONTS), "runes");
        assertTrue(calls.contains(STYLE + ".withFont(L" + RESOURCE_LOCATION + ";)L" + STYLE + ";"), calls.toString());
        Names names = mojang("1.21.8");
        assertFalse(takesDescription(style(names.withFontOld()), names), "1.21.8");
        assertTrue(takesDescription(style(names.withFontNew()), names), "1.21.9");
    }

    private static final String MOJANG_HELPER = "com/retromod/generated/LegacyStyleFont";

    private static void assertOldCallsFail(Map<String, byte[]> host, Map<String, byte[]> mod) {
        Run old = new Run(host, mod);
        // NoSuchMethodError, or NoClassDefFoundError where the identifier class was renamed too.
        assertInstanceOf(LinkageError.class, assertThrows(Throwable.class, () -> old.call(FONTS, "runes")));
    }

    private static void assertBridgedCallsRun(Run run) throws Throwable {
        assertEquals("mod:runes", run.call(FONTS, "runes"), "withFont(id) sets that font file");
        assertEquals("minecraft:default", run.call(FONTS, "cleared"), "a cleared font reads as the default, as before");
        assertEquals("minecraft:default", run.call(FONTS, "sprite"), "a sprite font has no file, so the default");
    }

    private static ClassNode style(String withFontDesc) {
        ClassNode node = new ClassNode();
        node.methods.add(new MethodNode(1, "withFont", withFontDesc, null, null));
        return node;
    }
}
