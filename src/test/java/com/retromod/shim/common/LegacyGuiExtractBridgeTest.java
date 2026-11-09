/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Old {@code GuiGraphics}, render type and key calls reach the 26.x shapes or the rebuilt helpers. */
class LegacyGuiExtractBridgeTest {

    private static final String GUI = "net/minecraft/client/gui/GuiGraphicsExtractor";
    private static final String DRAWS = LegacyGuiExtractBridge.DRAWS;
    private static final String GUI_ARG = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;";
    private static final String TYPE = "Lnet/minecraft/client/renderer/rendertype/RenderType;";
    private static final String FONT = "Lnet/minecraft/client/gui/Font;";
    private static final String STACK = "Lnet/minecraft/world/item/ItemStack;";

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedNeoForge = McReflect.isForceNeoForge();
    private final boolean savedForge = McReflect.isForceForge();

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceNeoForge(savedNeoForge);
        McReflect.setForceForge(savedForge);
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("A pose stack passed on gets the rebuilt one; the immediate push, move and pop use the 2D stack itself")
    void poseStack(@TempDir Path dir) throws Exception {
        byte[] calls = translate(dir).get("mod/Calls");
        assertEquals(List.of(DRAWS + ".pose(" + GUI_ARG + ")Lcom/mojang/blaze3d/vertex/PoseStack;",
                "mod/Calls.shift(Lcom/mojang/blaze3d/vertex/PoseStack;)V"), BridgeHarness.calls(calls, "passOn"));
        List<String> inline = BridgeHarness.calls(calls, "inline");
        assertEquals(List.of(GUI + ".pose()Lorg/joml/Matrix3x2fStack;", "org/joml/Matrix3x2fStack.pushMatrix()Lorg/joml/Matrix3x2fStack;",
                GUI + ".pose()Lorg/joml/Matrix3x2fStack;", "org/joml/Matrix3x2fStack.popMatrix()Lorg/joml/Matrix3x2fStack;"), inline);
    }

    @Test
    @DisplayName("Fills, lines and gradients with a render type or a depth reach the helper; render type factories hand back nothing")
    void fills(@TempDir Path dir) throws Exception {
        List<String> calls = BridgeHarness.calls(translate(dir).get("mod/Calls"), "fills");
        assertEquals(List.of(DRAWS + ".guiRenderType()" + TYPE, DRAWS + ".fill(" + GUI_ARG + TYPE + "IIIII)V",
                DRAWS + ".guiRenderType()" + TYPE, DRAWS + ".fill(" + GUI_ARG + TYPE + "IIIIII)V",
                DRAWS + ".fill(" + GUI_ARG + "IIIIII)V",
                DRAWS + ".fillGradient(" + GUI_ARG + "IIIIIII)V",
                DRAWS + ".guiRenderType()" + TYPE, DRAWS + ".fillGradient(" + GUI_ARG + TYPE + "IIIIIII)V",
                DRAWS + ".hLine(" + GUI_ARG + "IIII)V", DRAWS + ".vLine(" + GUI_ARG + "IIII)V",
                DRAWS + ".guiRenderType()" + TYPE, DRAWS + ".hLine(" + GUI_ARG + TYPE + "IIII)V",
                // The selection fill keeps its blend: its render type came straight from guiTextHighlight().
                DRAWS + ".guiTextHighlightRenderType()" + TYPE, DRAWS + ".fillTextHighlight(" + GUI_ARG + TYPE + "IIIII)V"), calls);
    }

    @Test
    @DisplayName("Fractional text, word wrap, managed draws, items, sprites and atlas blits reach the helper")
    void textSpritesAndItems(@TempDir Path dir) throws Exception {
        List<String> calls = BridgeHarness.calls(translate(dir).get("mod/Calls"), "draws");
        String id = "Lnet/minecraft/resources/Identifier;";
        String sprite = "Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;";
        assertEquals(List.of(DRAWS + ".drawString(" + GUI_ARG + FONT + "Ljava/lang/String;FFIZ)I",
                DRAWS + ".drawWordWrap(" + GUI_ARG + FONT + "Lnet/minecraft/network/chat/FormattedText;IIII)V",
                DRAWS + ".drawManaged(" + GUI_ARG + "Ljava/lang/Runnable;)V",
                DRAWS + ".renderItem(" + GUI_ARG + STACK + "IIII)V",
                DRAWS + ".blitSprite(" + GUI_ARG + id + "IIIII)V",
                DRAWS + ".blitSprite(" + GUI_ARG + id + "IIIIIIII)V",
                DRAWS + ".blit(" + GUI_ARG + "IIIII" + sprite + ")V",
                DRAWS + ".blit(" + GUI_ARG + "IIIII" + sprite + "FFFF)V"), calls);
    }

    @Test
    @DisplayName("Tooltips take the 26.x names, and the positioned one gets the helper")
    void vanillaTooltips(@TempDir Path dir) throws Exception {
        List<String> calls = calls(translate(dir).get("mod/Calls"), "tooltips");
        assertEquals(List.of(GUI + ".setTooltipForNextFrame(" + FONT + STACK + "II)V",
                GUI + ".setTooltipForNextFrame(" + FONT + "Ljava/util/List;Ljava/util/Optional;II)V",
                GUI + ".setTooltipForNextFrame(" + FONT + "Ljava/util/List;II)V",
                GUI + ".setComponentTooltipForNextFrame(" + FONT + "Ljava/util/List;II)V",
                DRAWS + ".renderTooltip(" + GUI_ARG + FONT
                        + "Ljava/util/List;Lnet/minecraft/client/gui/screens/inventory/tooltip/ClientTooltipPositioner;II)V"), calls);
    }

    @Test
    @DisplayName("NeoForge's stack-aware tooltips take NeoForge's 26.x names; on Forge only the one Forge renamed moves")
    void loaderTooltips(@TempDir Path dir) throws Exception {
        McReflect.setForceNeoForge(true);
        assertEquals(List.of(GUI + ".setTooltipForNextFrame(" + FONT + "Ljava/util/List;Ljava/util/Optional;" + STACK + "II)V",
                GUI + ".setComponentTooltipForNextFrame(" + FONT + "Ljava/util/List;II" + STACK + ")V",
                GUI + ".setComponentTooltipFromElementsForNextFrame(" + FONT + "Ljava/util/List;II" + STACK + ")V"),
                calls(translate(dir).get("mod/Calls"), "stackTooltips"));
        McReflect.setForceNeoForge(false);
        McReflect.setForceForge(true);
        List<String> forge = calls(translate(dir).get("mod/Calls"), "stackTooltips");
        assertEquals(GUI + ".setTooltipForNextFrame(" + FONT + "Ljava/util/List;Ljava/util/Optional;" + STACK + "II)V", forge.get(0));
        assertTrue(forge.stream().noneMatch(call -> call.contains("ForNextFrame(" + FONT + "Ljava/util/List;II")), String.valueOf(forge));
    }

    @Test
    @DisplayName("On Forge, tooltip calls Forge's client still declares keep their names instead of a guessed extract name")
    void forgeKeepsItsOwnTooltips(@TempDir Path dir) throws Exception {
        McReflect.setForceForge(true);
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        Map<String, byte[]> mod = BridgeHarness.only(BridgeHarness.compile(dir.resolve("mod"), OldHost.sources()), "mod/");
        // A transformer of its own, so the host index it loads stays out of the other tests.
        java.lang.reflect.Constructor<RetromodTransformer> constructor = RetromodTransformer.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        RetromodTransformer transformer = constructor.newInstance();
        moves(transformer);
        LegacyGuiExtractBridge.register(transformer);
        transformer.initFuzzyResolver(forgeHost(dir.resolve("forge-client.jar")));
        String list = FONT + "Ljava/util/List;II" + STACK + ")V";
        assertEquals(List.of(GUI + ".setTooltipForNextFrame(" + FONT + "Ljava/util/List;Ljava/util/Optional;" + STACK + "II)V",
                GUI + ".renderComponentTooltip(" + list, GUI + ".renderComponentTooltipFromElements(" + list),
                calls(transformer.transformClass(mod.get("mod/Calls"), "mod/Calls"), "stackTooltips"));
    }

    // The members Forge 26.2's patched client declares on GuiGraphicsExtractor, which vanilla does not.
    private static Path forgeHost(Path jar) throws Exception {
        org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(0);
        cw.visit(org.objectweb.asm.Opcodes.V17, org.objectweb.asm.Opcodes.ACC_PUBLIC, GUI, null, "java/lang/Object", null);
        String list = "(" + FONT + "Ljava/util/List;II" + STACK + ")V";
        for (String[] member : new String[][]{{"renderComponentTooltip", list}, {"renderComponentTooltipFromElements", list},
                {"setTooltipForNextFrame", "(" + FONT + "Ljava/util/List;Ljava/util/Optional;" + STACK + "II)V"}}) {
            cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_ABSTRACT, member[0], member[1], null, null)
                    .visitEnd();
        }
        cw.visitEnd();
        try (var out = new java.util.jar.JarOutputStream(java.nio.file.Files.newOutputStream(jar))) {
            out.putNextEntry(new java.util.jar.JarEntry(GUI + ".class"));
            out.write(cw.toByteArray());
            out.closeEntry();
        }
        return jar;
    }

    @Test
    @DisplayName("Key checks by raw codes and the string category reach RetroKeyMapping")
    void keyChecks(@TempDir Path dir) throws Exception {
        String keys = "com/retromod/polyfill/minecraft/RetroKeyMapping";
        assertEquals(List.of(keys + ".matches(Ljava/lang/Object;II)Z", keys + ".matchesMouse(Ljava/lang/Object;I)Z",
                keys + ".categoryName(Ljava/lang/Object;)Ljava/lang/String;"),
                BridgeHarness.calls(translate(dir).get("mod/Calls"), "keys"));
    }

    @Test
    @DisplayName("The key checks rebuild the 26.x event, and the category reads back as the string the mod gave")
    void keyChecksRun(@TempDir Path dir) throws Throwable {
        translate(dir.resolve("mod"));
        BridgeHarness.Run run = new BridgeHarness.Run(BridgeHarness.compile(dir.resolve("host"), keyHost()), Map.of());
        String keys = "com/retromod/polyfill/minecraft/RetroKeyMapping";
        Class<?> category = run.loadClass("net.minecraft.client.KeyMapping$Category");
        Class<?> keyMapping = run.loadClass("net.minecraft.client.KeyMapping");
        Object jump = keyMapping.getConstructor(String.class, int.class, category).newInstance("jump", 74, category.getField("MISC").get(null));
        assertEquals(true, run.call(keys, "matches", jump, 74, 5));
        assertEquals(false, run.call(keys, "matches", jump, 75, 5));
        assertEquals(true, run.call(keys, "matchesMouse", jump, 74));
        assertEquals("key.categories.misc", run.call(keys, "categoryName", jump));
        // A category the mod named itself comes back as written, one from elsewhere as its translation key.
        Object own = run.call(keys, "createDefault", "own", 76, "key.categories.mymod");
        assertEquals("key.categories.mymod", run.call(keys, "categoryName", own));
        Object id = run.loadClass("net.minecraft.resources.Identifier").getConstructor(String.class, String.class).newInstance("other", "cat");
        Object foreign = keyMapping.getConstructor(String.class, int.class, category).newInstance("x", 1,
                category.getConstructor(id.getClass()).newInstance(id));
        assertEquals("key.category.other.cat", run.call(keys, "categoryName", foreign));
    }

    // 26.x-shaped key classes: a mapping matches its own key or mouse button.
    private static Map<String, String> keyHost() {
        return Map.of(
                "net/minecraft/resources/Identifier.java", """
                        package net.minecraft.resources;
                        public record Identifier(String namespace, String path) {
                            public static Identifier fromNamespaceAndPath(String namespace, String path) { return new Identifier(namespace, path); }
                            public String toLanguageKey(String prefix) { return prefix + "." + namespace + "." + path; }
                        }
                        """,
                "net/minecraft/client/input/KeyEvent.java",
                "package net.minecraft.client.input; public record KeyEvent(int key, int keycode, int modifiers) {}",
                "net/minecraft/client/input/MouseButtonInfo.java",
                "package net.minecraft.client.input; public record MouseButtonInfo(int button, int modifiers) {}",
                "net/minecraft/client/input/MouseButtonEvent.java",
                "package net.minecraft.client.input; public record MouseButtonEvent(double x, double y, MouseButtonInfo buttonInfo) {}",
                "net/minecraft/client/KeyMapping.java", """
                        package net.minecraft.client;
                        import net.minecraft.client.input.*;
                        import net.minecraft.resources.Identifier;
                        public class KeyMapping {
                            public record Category(Identifier id) {
                                public static final Category MISC = new Category(Identifier.fromNamespaceAndPath("minecraft", "misc"));
                                public static Category register(Identifier id) { return new Category(id); }
                            }
                            private final int key;
                            private final Category category;
                            public KeyMapping(String name, int key, Category category) { this.key = key; this.category = category; }
                            public boolean matches(KeyEvent event) { return event.key() == key; }
                            public boolean matchesMouse(MouseButtonEvent event) { return event.buttonInfo().button() == key; }
                            public Category getCategory() { return category; }
                        }
                        """);
    }

    @Test
    @DisplayName("Without the bridge, the same calls keep their 1.21.1 shapes")
    void withoutTheBridgeNothingMoves(@TempDir Path dir) throws Exception {
        Map<String, byte[]> mod = BridgeHarness.only(BridgeHarness.compile(dir, OldHost.sources()), "mod/");
        byte[] calls = BridgeHarness.transform("26.3", LegacyGuiExtractBridgeTest::moves, mod).get("mod/Calls");
        assertTrue(BridgeHarness.calls(calls, "passOn").contains(GUI + ".pose()Lcom/mojang/blaze3d/vertex/PoseStack;"));
        assertTrue(BridgeHarness.calls(calls, "keys").contains("net/minecraft/client/KeyMapping.matches(II)Z"));
    }

    // The calls a method makes, without the JDK's.
    private static List<String> calls(byte[] bytes, String method) {
        return BridgeHarness.calls(bytes, method).stream().filter(call -> !call.startsWith("java/")).toList();
    }

    private static Map<String, byte[]> translate(Path dir) throws Exception {
        Map<String, byte[]> mod = BridgeHarness.only(BridgeHarness.compile(dir, OldHost.sources()), "mod/");
        Consumer<RetromodTransformer> register = t -> {
            moves(t);
            LegacyGuiExtractBridge.register(t);
        };
        return BridgeHarness.transform("26.3", register, mod);
    }

    // The class moves the 26.x shims make before this bridge is reached.
    static void moves(RetromodTransformer t) {
        t.registerClassRedirect("net/minecraft/client/gui/GuiGraphics", GUI);
        t.registerClassRedirect("net/minecraft/client/renderer/RenderType", "net/minecraft/client/renderer/rendertype/RenderType");
        t.registerClassRedirect("net/minecraft/resources/ResourceLocation", "net/minecraft/resources/Identifier");
    }

    /** 1.21.1-shaped stand-ins the old mod compiles against, and the mod's calls. */
    static final class OldHost {
        static Map<String, String> sources() {
            return Map.ofEntries(
                    Map.entry("net/minecraft/client/gui/GuiGraphics.java", """
                            package net.minecraft.client.gui;
                            import com.mojang.blaze3d.vertex.PoseStack;
                            import net.minecraft.client.renderer.RenderType;
                            import net.minecraft.client.renderer.texture.TextureAtlasSprite;
                            import net.minecraft.resources.ResourceLocation;
                            import net.minecraft.world.item.ItemStack;
                            import net.minecraft.network.chat.*;
                            import net.minecraft.util.FormattedCharSequence;
                            import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
                            import java.util.*;
                            public class GuiGraphics {
                                public PoseStack pose() { return null; }
                                public void fill(RenderType t, int a, int b, int c, int d, int e) {}
                                public void fill(RenderType t, int a, int b, int c, int d, int e, int f) {}
                                public void fill(int a, int b, int c, int d, int e, int f) {}
                                public void fillGradient(int a, int b, int c, int d, int e, int f, int g) {}
                                public void fillGradient(RenderType t, int a, int b, int c, int d, int e, int f, int g) {}
                                public void hLine(int a, int b, int c, int d) {}
                                public void hLine(RenderType t, int a, int b, int c, int d) {}
                                public void vLine(int a, int b, int c, int d) {}
                                public int drawString(Font f, String s, float x, float y, int c, boolean d) { return 0; }
                                public void drawWordWrap(Font f, FormattedText s, int a, int b, int c, int d) {}
                                public void drawManaged(Runnable r) {}
                                public void renderItem(ItemStack s, int a, int b, int c, int d) {}
                                public void blitSprite(ResourceLocation s, int a, int b, int c, int d, int e) {}
                                public void blitSprite(ResourceLocation s, int a, int b, int c, int d, int e, int f, int g, int h) {}
                                public void blit(int a, int b, int c, int d, int e, TextureAtlasSprite s) {}
                                public void blit(int a, int b, int c, int d, int e, TextureAtlasSprite s, float r, float g, float b2, float a2) {}
                                public void renderTooltip(Font f, ItemStack s, int x, int y) {}
                                public void renderTooltip(Font f, List<Component> l, Optional<Object> o, int x, int y) {}
                                public void renderTooltip(Font f, List<? extends FormattedCharSequence> l, int x, int y) {}
                                public void renderComponentTooltip(Font f, List<Component> l, int x, int y) {}
                                public void renderTooltip(Font f, List<FormattedCharSequence> l, ClientTooltipPositioner p, int x, int y) {}
                                public void renderTooltip(Font f, List<Component> l, Optional<Object> o, ItemStack s, int x, int y) {}
                                public void renderComponentTooltip(Font f, List<? extends FormattedText> l, int x, int y, ItemStack s) {}
                                public void renderComponentTooltipFromElements(Font f, List<Object> l, int x, int y, ItemStack s) {}
                            }
                            """),
                    Map.entry("net/minecraft/client/gui/Font.java", "package net.minecraft.client.gui; public class Font {}"),
                    Map.entry("net/minecraft/client/renderer/RenderType.java", """
                            package net.minecraft.client.renderer;
                            public class RenderType {
                                public static RenderType gui() { return null; }
                                public static RenderType guiTextHighlight() { return null; }
                            }
                            """),
                    Map.entry("net/minecraft/client/renderer/texture/TextureAtlasSprite.java",
                            "package net.minecraft.client.renderer.texture; public class TextureAtlasSprite {}"),
                    Map.entry("net/minecraft/resources/ResourceLocation.java", "package net.minecraft.resources; public class ResourceLocation {}"),
                    Map.entry("net/minecraft/world/item/ItemStack.java", "package net.minecraft.world.item; public class ItemStack {}"),
                    Map.entry("net/minecraft/network/chat/FormattedText.java", "package net.minecraft.network.chat; public interface FormattedText {}"),
                    Map.entry("net/minecraft/network/chat/Component.java",
                            "package net.minecraft.network.chat; public interface Component extends FormattedText {}"),
                    Map.entry("net/minecraft/util/FormattedCharSequence.java", "package net.minecraft.util; public interface FormattedCharSequence {}"),
                    Map.entry("net/minecraft/client/gui/screens/inventory/tooltip/ClientTooltipPositioner.java",
                            "package net.minecraft.client.gui.screens.inventory.tooltip; public interface ClientTooltipPositioner {}"),
                    Map.entry("com/mojang/blaze3d/vertex/PoseStack.java", """
                            package com.mojang.blaze3d.vertex;
                            public class PoseStack {
                                public void pushPose() {}
                                public void popPose() {}
                                public void translate(float x, float y, float z) {}
                            }
                            """),
                    Map.entry("net/minecraft/client/KeyMapping.java", """
                            package net.minecraft.client;
                            public class KeyMapping {
                                public boolean matches(int key, int scanCode) { return false; }
                                public boolean matchesMouse(int button) { return false; }
                                public String getCategory() { return null; }
                            }
                            """),
                    Map.entry("mod/Calls.java", """
                            package mod;
                            import com.mojang.blaze3d.vertex.PoseStack;
                            import net.minecraft.client.KeyMapping;
                            import net.minecraft.client.gui.*;
                            import net.minecraft.client.renderer.RenderType;
                            import net.minecraft.client.renderer.texture.TextureAtlasSprite;
                            import net.minecraft.resources.ResourceLocation;
                            import net.minecraft.world.item.ItemStack;
                            import net.minecraft.network.chat.*;
                            import net.minecraft.util.FormattedCharSequence;
                            import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
                            import java.util.*;
                            public class Calls {
                                public static void passOn(GuiGraphics g) { shift(g.pose()); }
                                static void shift(PoseStack pose) { pose.translate(1, 2, 3); }
                                public static void inline(GuiGraphics g) { g.pose().pushPose(); g.pose().popPose(); }
                                public static void fills(GuiGraphics g) {
                                    g.fill(RenderType.gui(), 1, 2, 3, 4, 5);
                                    g.fill(RenderType.gui(), 1, 2, 3, 4, 5, 6);
                                    g.fill(1, 2, 3, 4, 5, 6);
                                    g.fillGradient(1, 2, 3, 4, 5, 6, 7);
                                    g.fillGradient(RenderType.gui(), 1, 2, 3, 4, 5, 6, 7);
                                    g.hLine(1, 2, 3, 4);
                                    g.vLine(1, 2, 3, 4);
                                    g.hLine(RenderType.gui(), 1, 2, 3, 4);
                                    g.fill(RenderType.guiTextHighlight(), 1, 2, 3, 4, 0xFF0000FF);
                                }
                                public static void draws(GuiGraphics g, Font f, FormattedText t, ItemStack s, ResourceLocation id, TextureAtlasSprite a) {
                                    g.drawString(f, "x", 1.5F, 2.5F, 3, true);
                                    g.drawWordWrap(f, t, 1, 2, 3, 4);
                                    g.drawManaged(null);
                                    g.renderItem(s, 1, 2, 3, 4);
                                    g.blitSprite(id, 1, 2, 3, 4, 5);
                                    g.blitSprite(id, 1, 2, 3, 4, 5, 6, 7, 8);
                                    g.blit(1, 2, 3, 4, 5, a);
                                    g.blit(1, 2, 3, 4, 5, a, 1F, 1F, 1F, 1F);
                                }
                                public static void tooltips(GuiGraphics g, Font f, ItemStack s, List<Component> c, List<FormattedCharSequence> l,
                                        ClientTooltipPositioner p) {
                                    g.renderTooltip(f, s, 1, 2);
                                    g.renderTooltip(f, c, Optional.empty(), 1, 2);
                                    g.renderTooltip(f, (List<? extends FormattedCharSequence>) l, 1, 2);
                                    g.renderComponentTooltip(f, c, 1, 2);
                                    g.renderTooltip(f, l, p, 1, 2);
                                }
                                public static void stackTooltips(GuiGraphics g, Font f, ItemStack s, List<Component> c, List<Object> e) {
                                    g.renderTooltip(f, c, Optional.empty(), s, 1, 2);
                                    g.renderComponentTooltip(f, (List<? extends FormattedText>) c, 1, 2, s);
                                    g.renderComponentTooltipFromElements(f, e, 1, 2, s);
                                }
                                public static void keys(KeyMapping k) {
                                    k.matches(1, 2);
                                    k.matchesMouse(0);
                                    k.getCategory();
                                }
                            }
                            """));
        }
    }
}
