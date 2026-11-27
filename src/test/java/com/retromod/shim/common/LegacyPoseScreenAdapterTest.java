/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.retromod.shim.common.BridgeHarness.*;
import static org.junit.jupiter.api.Assertions.*;

/** A 1.19 mod screen's own {@code render(PoseStack, ...)}, which 26.1 and newer reach through {@code extractRenderState}. */
class LegacyPoseScreenAdapterTest {

    private static final String SCREENS = "net/minecraft/client/gui/screens/";
    private static final String GAME = "net/minecraft/client/Game";

    @TempDir
    static Path dir;

    private static Map<String, byte[]> mod;
    private static Map<String, byte[]> host;

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @BeforeAll
    static void compileShapes() throws Exception {
        Map<String, String> old = new HashMap<>();
        old.put("com/mojang/blaze3d/vertex/PoseStack.java", "package com.mojang.blaze3d.vertex; public class PoseStack {}");
        old.put(SCREENS + "Screen.java", "package net.minecraft.client.gui.screens; import com.mojang.blaze3d.vertex.PoseStack;"
                + " public class Screen { public void render(PoseStack p, int x, int y, float d) {}"
                + " public void renderBackground(PoseStack p) {} public void oldOnly() {}"
                + " public void renderTooltip(PoseStack p, net.minecraft.network.chat.Component c, int x, int y) {} }");
        old.put("net/minecraft/client/Gone.java", "package net.minecraft.client; public class Gone { public static void call() {} }");
        old.put("mod/BrokenScreen.java", "package mod; import com.mojang.blaze3d.vertex.PoseStack;\n"
                + "public class BrokenScreen extends net.minecraft.client.gui.screens.Screen {\n"
                + "    public String log = \"\";\n"
                + "    public void render(PoseStack pose, int x, int y, float d) {\n"
                + "        log += \"own;\";\n"
                + "        net.minecraft.client.Gone.call();\n"
                + "        super.render(pose, x, y, d);\n"
                + "    }\n"
                + "}");
        old.put("mod/StaleScreen.java", "package mod; import com.mojang.blaze3d.vertex.PoseStack;\n"
                + "public class StaleScreen extends net.minecraft.client.gui.screens.Screen {\n"
                + "    public String log = \"\";\n"
                + "    public void render(PoseStack pose, int x, int y, float d) { log += \"own;\"; step(); super.render(pose, x, y, d); }\n"
                + "    private void step() { oldOnly(); }\n"
                + "}");
        old.put(SCREENS + "OptionsSubScreen.java", "package net.minecraft.client.gui.screens; public class OptionsSubScreen extends Screen {}");
        old.put("net/minecraft/network/chat/Component.java", "package net.minecraft.network.chat; public interface Component {}");
        old.put("net/minecraft/client/gui/components/AbstractWidget.java", "package net.minecraft.client.gui.components;"
                + " public class AbstractWidget { public void render(com.mojang.blaze3d.vertex.PoseStack p, int x, int y, float d) {} }");
        old.put("mod/Widgets.java", "package mod; import com.mojang.blaze3d.vertex.PoseStack;\n"
                + "public class Widgets {\n"
                + "    public static void draw(net.minecraft.client.gui.components.AbstractWidget w, PoseStack p,\n"
                + "            net.minecraft.client.gui.screens.Screen s, net.minecraft.network.chat.Component c) {\n"
                + "        w.render(p, 1, 2, 0f);\n"
                + "        s.renderTooltip(p, c, 3, 4);\n"
                + "    }\n"
                + "}");
        old.put("mod/OldScreen.java", "package mod; import com.mojang.blaze3d.vertex.PoseStack;\n"
                + "public class OldScreen extends net.minecraft.client.gui.screens.Screen {\n"
                + "    public String log = \"\";\n"
                + "    public void render(PoseStack pose, int x, int y, float d) {\n"
                + "        renderBackground(pose);\n"
                + "        log += \"own \" + x + \",\" + y + \";\";\n"
                + "        super.render(pose, x, y, d);\n"
                + "    }\n"
                + "}");
        old.put("mod/ChildScreen.java", "package mod; import com.mojang.blaze3d.vertex.PoseStack;\n"
                + "public class ChildScreen extends OldScreen {\n"
                + "    public void render(PoseStack pose, int x, int y, float d) { log += \"child;\"; super.render(pose, x, y, d); }\n"
                + "}");
        old.put("mod/OptionsScreen.java", "package mod; import com.mojang.blaze3d.vertex.PoseStack;\n"
                + "public class OptionsScreen extends net.minecraft.client.gui.screens.OptionsSubScreen {\n"
                + "    public String log = \"\";\n"
                + "    public void render(PoseStack pose, int x, int y, float d) { log += \"options;\"; super.render(pose, x, y, d); }\n"
                + "}");
        old.put("mod/Frames.java", "package mod; public class Frames {\n"
                + "    public static String frame(String type) throws Exception {\n"
                + "        net.minecraft.client.gui.screens.Screen screen = (net.minecraft.client.gui.screens.Screen) Class.forName(type).getConstructor().newInstance();\n"
                + "        String drawn = net.minecraft.client.Game.frame(screen);\n"
                + "        return drawn + \" | \" + screen.getClass().getField(\"log\").get(screen);\n"
                + "    }\n"
                + "    public static String twice(String type) throws Exception {\n"
                + "        net.minecraft.client.gui.screens.Screen screen = (net.minecraft.client.gui.screens.Screen) Class.forName(type).getConstructor().newInstance();\n"
                + "        String first = net.minecraft.client.Game.frame(screen);\n"
                + "        String second = net.minecraft.client.Game.frame(screen);\n"
                + "        return first + \" | \" + second + \" | \" + screen.getClass().getField(\"log\").get(screen);\n"
                + "    }\n"
                + "}");
        old.put("net/minecraft/client/Game.java", "package net.minecraft.client; public class Game {"
                + " public static String frame(net.minecraft.client.gui.screens.Screen s) { return \"\"; } }");
        mod = only(compile(dir.resolve("old"), old), "mod/");

        Map<String, String> modern = new HashMap<>();
        modern.put("com/mojang/blaze3d/vertex/PoseStack.java", "package com.mojang.blaze3d.vertex; public class PoseStack {}");
        modern.put("net/minecraft/client/gui/GuiGraphicsExtractor.java",
                "package net.minecraft.client.gui; public class GuiGraphicsExtractor { public String log = \"\"; }");
        modern.put("net/minecraft/client/gui/components/Renderable.java", "package net.minecraft.client.gui.components;"
                + " public interface Renderable { void extractRenderState(net.minecraft.client.gui.GuiGraphicsExtractor g, int x, int y, float d); }");
        modern.put(SCREENS + "Screen.java", "package net.minecraft.client.gui.screens; import net.minecraft.client.gui.GuiGraphicsExtractor;\n"
                + "public class Screen implements net.minecraft.client.gui.components.Renderable {\n"
                + "    public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float d) { g.log += \"widgets;\"; }\n"
                + "    public void extractBackground(GuiGraphicsExtractor g, int x, int y, float d) { g.log += \"background;\"; }\n"
                + "    public final void extractRenderStateWithTooltipAndSubtitles(GuiGraphicsExtractor g, int x, int y, float d) {\n"
                + "        extractBackground(g, x, y, d);\n"
                + "        extractRenderState(g, x, y, d);\n"
                + "    }\n"
                + "}");
        modern.put(SCREENS + "OptionsSubScreen.java", "package net.minecraft.client.gui.screens; import net.minecraft.client.gui.GuiGraphicsExtractor;\n"
                + "public class OptionsSubScreen extends Screen {\n"
                + "    public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float d) { g.log += \"list;\"; super.extractRenderState(g, x, y, d); }\n"
                + "}");
        modern.put("net/minecraft/client/Game.java", "package net.minecraft.client; public class Game {\n"
                + "    public static String frame(net.minecraft.client.gui.screens.Screen s) {\n"
                + "        net.minecraft.client.gui.GuiGraphicsExtractor g = new net.minecraft.client.gui.GuiGraphicsExtractor();\n"
                + "        s.extractRenderStateWithTooltipAndSubtitles(g, 3, 4, 0f);\n"
                + "        return g.log;\n"
                + "    }\n"
                + "}");
        host = compile(dir.resolve("modern"), modern);
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("without the bridge the game never runs the old render, so the screen draws only its widgets")
    void oldRenderIsSkipped() throws Throwable {
        Run run = new Run(host, mod);
        assertEquals("background;widgets; | ", run.call("mod/Frames", "frame", "mod.OldScreen"));
    }

    @Test
    @DisplayName("on 26.x the game runs the old render once, and its super call draws the widgets into the same frame")
    void oldRenderRuns() throws Throwable {
        Run run = new Run(host, bridged());
        assertEquals("background;widgets; | own 3,4;", run.call("mod/Frames", "frame", "mod.OldScreen"),
                "the background is the game's own, so the old renderBackground draws nothing more");
        assertEquals("background;widgets; | child;own 3,4;", run.call("mod/Frames", "frame", "mod.ChildScreen"),
                "a subclass's render runs first and its super call reaches the base's, which ends in the host");
        assertEquals("background;list;widgets; | options;", run.call("mod/Frames", "frame", "mod.OptionsScreen"),
                "a super call to a vanilla screen reaches that screen's own extract");
    }

    @Test
    @DisplayName("an old render that fails on something the host lacks still leaves the frame its widgets, then stops running")
    void failingOldRenderFallsBackToTheWidgets() throws Throwable {
        Run run = new Run(host, bridged());
        assertEquals("background;widgets; | background;widgets; | own;", run.call("mod/Frames", "twice", "mod.BrokenScreen"),
                "the first frame falls back after the failure, and the next frame does not run the old render again");
    }

    @Test
    @DisplayName("an old render that the host proves reaches a missing member is left out of the frame")
    void renderReachingAMissingMemberIsLeftOut() throws Throwable {
        Map<String, byte[]> out = bridged();
        assertThrows(Exception.class, () -> method(out.get("mod/StaleScreen"), "extractRenderState"),
                "the screen keeps the host's own extract");
        assertEquals("background;widgets; | ", new Run(host, out).call("mod/Frames", "frame", "mod.StaleScreen"));
        assertEquals("mod/StaleScreen.oldOnly()V", LegacyPoseScreenAdapter.missingMember(node(mod.get("mod/StaleScreen")),
                name -> mod.containsKey(name) ? node(mod.get(name)) : null,
                name -> host.containsKey(name) ? node(host.get(name)) : ClassResourceInspector.read(name)),
                "the check follows the screen's own helper to the call the host lacks");
    }

    @Test
    @DisplayName("a super call that the jar's own base screen answers keeps calling that render")
    void baseScreenSuperCallIsKept() {
        Map<String, byte[]> out = bridged();
        assertTrue(calls(out.get("mod/ChildScreen"), "render").contains("mod/OldScreen.render(Lcom/mojang/blaze3d/vertex/PoseStack;IIF)V"));
        assertTrue(calls(out.get("mod/OldScreen"), "render").contains(SCREENS + "Screen.extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V"));
        assertTrue(calls(out.get("mod/OldScreen"), "render").contains(LegacyPoseScreenAdapter.HELPER
                + ".renderBackground(Ljava/lang/Object;Lcom/mojang/blaze3d/vertex/PoseStack;)V"));
    }

    @Test
    @DisplayName("a widget's old render and a screen's old tooltip reach the helpers that draw into the frame")
    void widgetAndTooltipCalls() {
        List<String> calls = calls(bridged().get("mod/Widgets"), "draw");
        String pose = "Lcom/mojang/blaze3d/vertex/PoseStack;";
        assertTrue(calls.contains(LegacyPoseScreenAdapter.HELPER + ".render(Lnet/minecraft/client/gui/components/Renderable;" + pose + "IIF)V"),
                calls.toString());
        assertTrue(calls.contains(LegacyPoseScreenAdapter.HELPER + ".renderTooltip(Ljava/lang/Object;" + pose
                + "Lnet/minecraft/network/chat/Component;II)V"), calls.toString());
    }

    @Test
    @DisplayName("a mixin into a screen gets no extract bridge, since its methods merge into a screen that has one")
    void mixinsAreLeftAlone() {
        org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
        writer.visit(org.objectweb.asm.Opcodes.V17, org.objectweb.asm.Opcodes.ACC_PUBLIC, "mod/TitleMixin", null,
                SCREENS + "Screen", null);
        writer.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false).visitEnd();
        org.objectweb.asm.MethodVisitor render = writer.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "render",
                LegacyPoseScreenAdapter.OLD_RENDER, null, null);
        render.visitCode();
        render.visitInsn(org.objectweb.asm.Opcodes.RETURN);
        render.visitMaxs(0, 5);
        render.visitEnd();
        writer.visitEnd();
        byte[] mixin = writer.toByteArray();
        assertSame(mixin, LegacyPoseScreenAdapter.apply(mixin));
    }

    @Test
    @DisplayName("an older target leaves the old render and its calls alone")
    void olderTargetUntouched() {
        Map<String, byte[]> out = transform("1.21.11", t -> {}, mod, only(host, SCREENS));
        assertTrue(calls(out.get("mod/OldScreen"), "render").contains(SCREENS + "Screen.render(Lcom/mojang/blaze3d/vertex/PoseStack;IIF)V"));
        assertThrows(Exception.class, () -> method(out.get("mod/OldScreen"), "extractRenderState"));
    }

    private Map<String, byte[]> bridged() {
        return transform("26.3", LegacyPoseGuiBridge::registerScreens, mod, host);
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }
}
