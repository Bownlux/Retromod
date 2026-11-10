/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.VersionShim;
import com.retromod.shim.fabric.Fabric_1_21_10_to_1_21_11;
import com.retromod.shim.forge.Forge_1_21_10_to_1_21_11;
import com.retromod.shim.neoforge.NeoForge_1_21_10_to_1_21_11;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static com.retromod.shim.common.BridgeHarness.*;
import static org.junit.jupiter.api.Assertions.*;

/** A mod screen's {@code resize(Minecraft, int, int)} override on 1.21.11, which dropped the Minecraft argument. */
class LegacyScreenResizeTest {

    private static final String RESIZER = "mod/Resizer";

    @TempDir
    static Path dir;

    private static Map<String, byte[]> mojangMod;
    private static Map<String, byte[]> intermediaryMod;
    private static Map<String, byte[]> mojangHost;
    private static Map<String, byte[]> intermediaryHost;

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @BeforeAll
    static void compileShapes() throws Exception {
        mojangMod = mod(dir.resolve("mojang-old"), "net.minecraft.client", "Minecraft", "getInstance",
                "net.minecraft.client.gui.screens", "Screen", "resize");
        intermediaryMod = mod(dir.resolve("intermediary-old"), "net.minecraft", "class_310", "method_1551",
                "net.minecraft", "class_437", "method_25410");
        mojangHost = host(dir.resolve("mojang-host"), "net.minecraft.client", "Minecraft", "getInstance",
                "net.minecraft.client.gui.screens", "Screen", "resize");
        intermediaryHost = host(dir.resolve("intermediary-host"), "net.minecraft", "class_310", "method_1551",
                "net.minecraft", "class_437", "method_25410");
    }

    /** A screen that overrides the old resize and ends with super, a subclass of it, and a plain caller. */
    private static Map<String, byte[]> mod(Path at, String mcPkg, String mc, String getInstance, String screenPkg,
            String screen, String resize) throws Exception {
        String minecraft = mcPkg + "." + mc;
        String base = screenPkg + "." + screen;
        return only(compile(at, Map.of(
                path(minecraft), "package " + mcPkg + "; public class " + mc + " { public static " + mc + " "
                        + getInstance + "() { return null; } }",
                path(base), "package " + screenPkg + "; public class " + screen + " { public int width;"
                        + " public void " + resize + "(" + minecraft + " mc, int w, int h) {} }",
                "mod/ModScreen.java", "package mod; public class ModScreen extends " + base + " {\n"
                        + "    public String log = \"\";\n"
                        + "    public void " + resize + "(" + minecraft + " mc, int w, int h) {\n"
                        + "        log += \"mod \" + w + \"x\" + h + (mc == " + minecraft + "." + getInstance + "() ? \"\" : \" other\") + \";\";\n"
                        + "        super." + resize + "(mc, w, h);\n"
                        + "    }\n"
                        + "}",
                "mod/PlainScreen.java", "package mod; public class PlainScreen extends " + base + " {}",
                "mod/ChildScreen.java", "package mod; public class ChildScreen extends ModScreen {\n"
                        + "    public void " + resize + "(" + minecraft + " mc, int w, int h) {\n"
                        + "        log += \"child;\";\n"
                        + "        super." + resize + "(mc, w, h);\n"
                        + "    }\n"
                        + "}",
                "mod/Resizer.java", "package mod; public class Resizer {\n"
                        + "    public static String game(String type, int w, int h) throws Exception {\n"
                        + "        ModScreen screen = (ModScreen) Class.forName(type).getConstructor().newInstance();\n"
                        + "        " + screenPkg + ".Game.resizeDisplay(screen, w, h);\n"
                        + "        return screen.log + screen.width;\n"
                        + "    }\n"
                        + "    public static String plain(int w, int h) {\n"
                        + "        PlainScreen screen = new PlainScreen();\n"
                        + "        screen." + resize + "(" + minecraft + "." + getInstance + "(), w, h);\n"
                        + "        return \"\" + screen.width;\n"
                        + "    }\n"
                        + "    public static String anvil(int w, int h) {\n"
                        + "        " + screenPkg + ".AnvilScreen screen = new AnvilLike();\n"
                        + "        screen." + resize + "(" + minecraft + "." + getInstance + "(), w, h);\n"
                        + "        return ((AnvilLike) screen).log + screen.width;\n"
                        + "    }\n"
                        + "    public static String direct(int w, int h) {\n"
                        + "        ModScreen screen = new ChildScreen();\n"
                        + "        ((" + base + ") screen)." + resize + "(" + minecraft + "." + getInstance + "(), w, h);\n"
                        + "        return screen.log + screen.width;\n"
                        + "    }\n"
                        + "}",
                screenPkg.replace('.', '/') + "/Game.java", "package " + screenPkg + "; public class Game {"
                        + " public static void resizeDisplay(" + screen + " s, int w, int h) {} }",
                screenPkg.replace('.', '/') + "/AnvilScreen.java", "package " + screenPkg
                        + "; public class AnvilScreen extends " + screen + " {}",
                "mod/AnvilLike.java", "package mod; public class AnvilLike extends " + screenPkg + ".AnvilScreen {\n"
                        + "    public String log = \"\";\n"
                        + "    public void " + resize + "(" + minecraft + " mc, int w, int h) {\n"
                        + "        log += \"anvil \" + w + \"x\" + h + \";\";\n"
                        + "        super." + resize + "(mc, w, h);\n"
                        + "    }\n"
                        + "    public static String game(int w, int h) {\n"
                        + "        AnvilLike screen = new AnvilLike();\n"
                        + "        " + screenPkg + ".Game.resizeDisplay(screen, w, h);\n"
                        + "        return screen.log + screen.width;\n"
                        + "    }\n"
                        + "}")), "mod/");
    }

    /** The 1.21.11 shape: resize(int, int), which the game calls when the window changes size. */
    private static Map<String, byte[]> host(Path at, String mcPkg, String mc, String getInstance, String screenPkg,
            String screen, String resize) throws Exception {
        String minecraft = mcPkg + "." + mc;
        return compile(at, Map.of(
                path(minecraft), "package " + mcPkg + "; public class " + mc + " { private static final " + mc
                        + " INSTANCE = new " + mc + "(); public static " + mc + " " + getInstance
                        + "() { return INSTANCE; } }",
                path(screenPkg + "." + screen), "package " + screenPkg + "; public class " + screen
                        + " { public int width; public void " + resize + "(int w, int h) { width = w; } }",
                screenPkg.replace('.', '/') + "/Game.java", "package " + screenPkg + "; public class Game {"
                        + " public static void resizeDisplay(" + screen + " s, int w, int h) { s." + resize + "(w, h); } }",
                screenPkg.replace('.', '/') + "/AnvilScreen.java", "package " + screenPkg
                        + "; public class AnvilScreen extends " + screen + " { public String kept = \"\";"
                        + " public void " + resize + "(int w, int h) { kept = \"name kept\"; super." + resize + "(w, h); } }"));
    }

    private static String path(String className) {
        return className.replace('.', '/') + ".java";
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("on 1.21.11 the game skips the old override and the call fails as written")
    void oldCodeIsSkippedOrFails() {
        Run old = new Run(mojangHost, mojangMod);
        assertDoesNotThrow(() -> assertEquals("100", old.call(RESIZER, "game", "mod.ModScreen", 100, 50)),
                "the game's resize(w, h) never reaches the mod's override");
        assertInstanceOf(NoSuchMethodError.class, assertThrows(Throwable.class, () -> old.call(RESIZER, "direct", 1, 2)));
    }

    @Test
    @DisplayName("on a NeoForge 1.21.11 host the game runs the mod's override chain once bridged")
    void mojangOverridesRun() throws Throwable {
        assertOverridesRun(new Run(mojangHost, transform("1.21.11",
                t -> new NeoForge_1_21_10_to_1_21_11().registerRedirects(t), mojangMod)));
    }

    @Test
    @DisplayName("on a Fabric 1.21.11 host the intermediary override chain runs once bridged")
    void intermediaryOverridesRun() throws Throwable {
        assertOverridesRun(new Run(intermediaryHost, transform("1.21.11",
                t -> new Fabric_1_21_10_to_1_21_11().registerRedirects(t), intermediaryMod)));
    }

    @Test
    @DisplayName("a screen on a vanilla screen, proven through the host's classes, runs its override and the host's")
    void overrideOnAVanillaScreenRuns() throws Throwable {
        Map<String, byte[]> hostScreens = only(mojangHost, "net/minecraft/client/gui/screens/");
        Run run = new Run(mojangHost, transform("1.21.11",
                t -> new NeoForge_1_21_10_to_1_21_11().registerRedirects(t), mojangMod, hostScreens));
        assertEquals("anvil 40x10;40", run.call("mod/AnvilLike", "game", 40, 10));
        assertEquals("anvil 9x3;9", run.call(RESIZER, "anvil", 9, 3), "a call typed by the vanilla screen");
        Run intermediary = new Run(intermediaryHost, transform("1.21.11",
                t -> new Fabric_1_21_10_to_1_21_11().registerRedirects(t), intermediaryMod,
                only(intermediaryHost, "net/minecraft/")));
        assertEquals("anvil 40x10;40", intermediary.call("mod/AnvilLike", "game", 40, 10));
    }

    @Test
    @DisplayName("each loader's 1.21.11 shim bridges the call, Fabric on 26.x after the remap")
    void everyLoaderBridgesTheCall() {
        String helper = LegacyGuiOverrideAdapter.MOJANG_RESIZE.helper() + ".resize"
                + LegacyGuiOverrideAdapter.MOJANG_RESIZE.helperDesc();
        Map<VersionShim, String> shims = Map.of(new NeoForge_1_21_10_to_1_21_11(), "1.21.11",
                new Forge_1_21_10_to_1_21_11(), "1.21.11", new Fabric_1_21_10_to_1_21_11(), "26.3");
        shims.forEach((shim, target) -> {
            Consumer<RetromodTransformer> register = shim::registerRedirects;
            assertTrue(calls(transform(target, register, mojangMod).get(RESIZER), "direct").contains(helper),
                    shim.getShimName());
        });
    }

    @Test
    @DisplayName("a 1.21.10 host, which still passes Minecraft, keeps the override and the call")
    void gatedOnTheHost() {
        Map<String, byte[]> out = transform("1.21.10", LegacyGuiOverrideAdapter::registerResizeCalls, mojangMod);
        assertTrue(calls(out.get(RESIZER), "direct").contains("net/minecraft/client/gui/screens/Screen.resize"
                + LegacyGuiOverrideAdapter.MOJANG_RESIZE.oldDesc()));
        assertEquals(List.of(LegacyGuiOverrideAdapter.MOJANG_RESIZE.oldDesc()), resizeDescs(out.get("mod/ModScreen")),
                "no resize(int, int) bridge on 1.21.10");
    }

    private static List<String> resizeDescs(byte[] bytes) {
        org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
        new org.objectweb.asm.ClassReader(bytes).accept(node, 0);
        return node.methods.stream().filter(m -> m.name.equals("resize")).map(m -> m.desc).toList();
    }

    private static void assertOverridesRun(Run run) throws Throwable {
        assertEquals("mod 100x50;100", run.call(RESIZER, "game", "mod.ModScreen", 100, 50),
                "the game's resize(w, h) runs the mod's override with the game's Minecraft");
        assertEquals("child;mod 30x20;30", run.call(RESIZER, "game", "mod.ChildScreen", 30, 20),
                "a subclass's super call reaches its parent's override once, then the host");
        assertEquals("child;mod 7x8;7", run.call(RESIZER, "direct", 7, 8), "a call through Screen does the same");
        assertEquals("5", run.call(RESIZER, "plain", 5, 6), "a call typed by a mod screen without its own resize");
    }
}
