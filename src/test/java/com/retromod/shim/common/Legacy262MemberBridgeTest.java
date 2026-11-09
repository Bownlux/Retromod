/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.VersionShim;
import com.retromod.shim.fabric.Fabric_26_1_to_26_2;
import com.retromod.shim.forge.Forge_26_1_to_26_2;
import com.retromod.shim.neoforge.NeoForge_26_1_to_26_2;
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

import static com.retromod.shim.common.BridgeHarness.*;
import static org.junit.jupiter.api.Assertions.*;

/** A tooltip mod's {@code I18n.exists(key)} on 26.2, which removed it, for every loader. */
class Legacy262MemberBridgeTest {

    private static final String MOD = "mod/Tooltips";
    private static final String EXISTS = Legacy262MemberBridge.I18N + ".exists(Ljava/lang/String;)Z";
    private static final String BRIDGED = Legacy262MemberBridge.HELPER + ".exists(Ljava/lang/String;)Z";

    @TempDir
    static Path dir;

    private static Map<String, byte[]> mod;
    /** 26.2's shape: I18n without exists, and a language that knows only block names. */
    private static Map<String, byte[]> host262;

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @BeforeAll
    static void compileShapes() throws Exception {
        mod = only(compile(dir.resolve("old"), Map.of(
                "net/minecraft/client/resources/language/I18n.java", """
                        package net.minecraft.client.resources.language;
                        public class I18n { public static boolean exists(String key) { return false; } }""",
                "mod/Tooltips.java", """
                        package mod;
                        import net.minecraft.client.resources.language.I18n;
                        public class Tooltips {
                            public static String label(String key) { return I18n.exists(key) ? key : "missing"; }
                        }""")), "mod/");
        host262 = compile(dir.resolve("host"), Map.of(
                "net/minecraft/client/resources/language/I18n.java", """
                        package net.minecraft.client.resources.language;
                        public class I18n { public static String get(String key, Object... args) { return key; } }""",
                "net/minecraft/locale/Language.java", """
                        package net.minecraft.locale;
                        public abstract class Language {
                            private static final Language INSTANCE = new Language() {
                                public boolean has(String key) { return key.startsWith("block."); }
                            };
                            public static Language getInstance() { return INSTANCE; }
                            public abstract boolean has(String key);
                        }"""));
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("on 26.2 the call fails as written and answers from the language once bridged")
    void bridgedCallAnswersFromTheLanguage() throws Throwable {
        Run old = new Run(host262, mod);
        assertInstanceOf(NoSuchMethodError.class, assertThrows(Throwable.class,
                () -> old.call(MOD, "label", "block.stone")), "26.2 has no I18n.exists");

        Run bridged = new Run(host262, transform("26.2", Legacy262MemberBridge::register, mod));
        assertEquals("block.stone", bridged.call(MOD, "label", "block.stone"), "a key the language has");
        assertEquals("missing", bridged.call(MOD, "label", "item.unknown"), "a key it lacks, as I18n answered");
    }

    @Test
    @DisplayName("each loader's 26.1 to 26.2 shim bridges the call")
    void everyLoaderBridgesIt() {
        for (VersionShim shim : List.of(new NeoForge_26_1_to_26_2(), new Forge_26_1_to_26_2(),
                new Fabric_26_1_to_26_2())) {
            Map<String, byte[]> out = transform("26.3", shim::registerRedirects, mod);
            assertTrue(calls(out.get(MOD), "label").contains(BRIDGED), shim.getShimName());
        }
    }

    @Test
    @DisplayName("a 26.1 host, which still has I18n.exists, keeps the call")
    void gatedOnTheHost() {
        assertTrue(calls(transform("26.1", Legacy262MemberBridge::register, mod).get(MOD), "label").contains(EXISTS));
        ClassNode i18n = new ClassNode();
        i18n.methods.add(new MethodNode(0x9, "exists", Legacy262MemberBridge.EXISTS_DESC, null, null));
        assertFalse(Legacy262MemberBridge.lostI18nExists(i18n), "26.1.2 declares exists(String)");
        assertTrue(Legacy262MemberBridge.lostI18nExists(new ClassNode()), "26.2 does not");
    }
}
