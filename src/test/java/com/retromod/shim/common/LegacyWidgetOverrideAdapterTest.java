/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Old list entries, renderables and buttons answer the methods a 26.3-shaped host calls. */
class LegacyWidgetOverrideAdapterTest {

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("A list entry's old render runs from extractContent, after its background, with the entry's content bounds")
    void listEntry(@TempDir Path dir) throws Throwable {
        Game game = new Game(dir);
        Object row = game.make("mod.Row");
        Object graphics = game.make("net.minecraft.client.gui.GuiGraphicsExtractor");
        game.invoke(row, "extractContent", graphics, 5, 6, true, 0.5F);
        // index 0, then top, left, width and height from the host's content bounds.
        assertEquals("back;row 0 20 10 30 40 5 6 true 0.5", game.log());
    }

    @Test
    @DisplayName("A background a mod base row declares runs for its subclasses, and a subclass's super call still reaches it")
    void inheritedBackground(@TempDir Path dir) throws Throwable {
        Game game = new Game(dir);
        Object graphics = game.make("net.minecraft.client.gui.GuiGraphicsExtractor");
        game.invoke(game.make("mod.PlainRow"), "extractContent", graphics, 1, 2, false, 0F);
        game.invoke(game.make("mod.LayeredRow"), "extractContent", graphics, 1, 2, false, 0F);
        assertEquals("base back;plain row;base back;layered back;layered row", game.log());
    }

    @Test
    @DisplayName("A mod's own Renderable is drawn through extractRenderState, also when old code calls render on it")
    void ownRenderable(@TempDir Path dir) throws Throwable {
        Game game = new Game(dir);
        Object badge = game.make("mod.Badge");
        Object graphics = game.make("net.minecraft.client.gui.GuiGraphicsExtractor");
        game.invoke(badge, "extractRenderState", graphics, 1, 2, 3F);
        game.call("mod.Clicker", "draw", badge, graphics);
        // As Cloth Config's dropdown: a Renderable over a Minecraft class that draws nothing itself.
        game.invoke(game.make("mod.Dropdown"), "extractRenderState", graphics, 4, 5, 6F);
        assertEquals("badge 1 2 3.0;badge 7 8 9.0;dropdown 4 5 6.0", game.log());
    }

    @Test
    @DisplayName("A widget draw call is renamed even when the mod's widget base keeps an old render(PoseStack, ...) overload")
    void widgetDrawPastPoseOverload(@TempDir Path dir) throws Throwable {
        Game game = new Game(dir);
        game.call("mod.Clicker", "drawWidget", game.make("mod.PoseWidget"), game.make("net.minecraft.client.gui.GuiGraphicsExtractor"));
        assertEquals("widget draw", game.log());
    }

    @Test
    @DisplayName("Old button presses run from the 26.x press, and pressing or super-pressing a Minecraft button reaches its callback")
    void buttons(@TempDir Path dir) throws Throwable {
        Game game = new Game(dir);
        Object click = game.make("net.minecraft.client.input.MouseButtonInfo", 0, 0);
        game.invoke(game.make("mod.Toggle"), "onPress", click);
        game.invoke(game.make("mod.Confirm"), "onPress", click);
        game.call("mod.Clicker", "click", game.make("net.minecraft.client.gui.components.Button"));
        assertEquals("toggle;confirm;host press;host press", game.log());
    }

    @Test
    @DisplayName("A class that already has the 26.x method, or a mixin, is left as it was")
    void declines(@TempDir Path dir) throws Throwable {
        Map<String, byte[]> mod = BridgeHarness.only(BridgeHarness.compile(dir.resolve("old"), Game.oldSources()), "mod/");
        Map<String, byte[]> out = Game.translate(mod);
        assertEquals(1, countMethods(out.get("mod/Current"), "extractContent"));
        assertEquals(0, countMethods(out.get("mod/RowMixin"), "extractContent"));
    }

    private static int countMethods(byte[] bytes, String name) {
        org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
        new org.objectweb.asm.ClassReader(bytes).accept(node, 0);
        return (int) node.methods.stream().filter(m -> m.name.equals(name)).count();
    }

    /** The mod built against 1.21.1-shaped classes, translated, loaded on 26.3-shaped ones. */
    private static final class Game {
        private final BridgeHarness.Run run;

        Game(Path dir) throws Exception {
            Map<String, byte[]> mod = BridgeHarness.only(BridgeHarness.compile(Files.createDirectories(dir.resolve("old")),
                    oldSources()), "mod/");
            Map<String, byte[]> host = BridgeHarness.compile(Files.createDirectories(dir.resolve("new")), newSources());
            run = new BridgeHarness.Run(host, translate(mod));
        }

        static Map<String, byte[]> translate(Map<String, byte[]> mod) {
            return BridgeHarness.transform("26.3", t -> {
                LegacyGuiExtractBridgeTest.moves(t);
                LegacyGuiExtractBridge.register(t);
            }, mod);
        }

        Object make(String name, Object... args) throws Exception {
            for (var constructor : run.loadClass(name).getDeclaredConstructors()) {
                if (constructor.getParameterCount() == args.length) {
                    constructor.setAccessible(true);
                    return constructor.newInstance(args);
                }
            }
            throw new AssertionError("no constructor for " + name);
        }

        void invoke(Object target, String name, Object... args) throws Throwable {
            for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
                for (Method method : type.getDeclaredMethods()) {
                    if (method.getName().equals(name) && method.getParameterCount() == args.length) {
                        method.setAccessible(true);
                        try {
                            method.invoke(target, args);
                            return;
                        } catch (java.lang.reflect.InvocationTargetException e) {
                            throw e.getCause();
                        }
                    }
                }
            }
            throw new AssertionError("no method " + name + " on " + target.getClass());
        }

        void call(String owner, String name, Object... args) throws Throwable {
            run.call(owner, name, args);
        }

        String log() throws Exception {
            return String.join(";", (java.util.List<String>) run.loadClass("mod.Log").getField("LINES").get(null));
        }

        static Map<String, String> oldSources() {
            return Map.ofEntries(
                    Map.entry("net/minecraft/client/gui/GuiGraphics.java", "package net.minecraft.client.gui; public class GuiGraphics {}"),
                    Map.entry("net/minecraft/client/gui/components/AbstractSelectionList.java", """
                            package net.minecraft.client.gui.components;
                            import net.minecraft.client.gui.GuiGraphics;
                            public abstract class AbstractSelectionList {
                                public abstract static class Entry {
                                    public abstract void render(GuiGraphics g, int index, int top, int left, int width, int height,
                                            int mouseX, int mouseY, boolean hovering, float partialTick);
                                    public void renderBack(GuiGraphics g, int index, int top, int left, int width, int height,
                                            int mouseX, int mouseY, boolean hovering, float partialTick) {}
                                }
                            }
                            """),
                    Map.entry("net/minecraft/client/gui/components/events/AbstractContainerEventHandler.java",
                            "package net.minecraft.client.gui.components.events; public abstract class AbstractContainerEventHandler {}"),
                    Map.entry("net/minecraft/client/gui/components/Renderable.java", """
                            package net.minecraft.client.gui.components;
                            public interface Renderable { void render(net.minecraft.client.gui.GuiGraphics g, int x, int y, float t); }
                            """),
                    Map.entry("net/minecraft/client/gui/components/AbstractWidget.java", """
                            package net.minecraft.client.gui.components;
                            public abstract class AbstractWidget {
                                public final void render(net.minecraft.client.gui.GuiGraphics g, int x, int y, float t) {}
                            }
                            """),
                    Map.entry("com/mojang/blaze3d/vertex/PoseStack.java", "package com.mojang.blaze3d.vertex; public class PoseStack {}"),
                    Map.entry("net/minecraft/client/gui/components/AbstractButton.java",
                            "package net.minecraft.client.gui.components; public abstract class AbstractButton extends AbstractWidget {"
                                    + " public abstract void onPress(); }"),
                    Map.entry("net/minecraft/client/gui/components/Button.java",
                            "package net.minecraft.client.gui.components; public class Button extends AbstractButton { public void onPress() {} }"),
                    Map.entry("org/spongepowered/asm/mixin/Mixin.java", """
                            package org.spongepowered.asm.mixin;
                            @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.CLASS)
                            public @interface Mixin { Class<?>[] value(); }
                            """),
                    Map.entry("mod/Log.java", "package mod; public class Log { public static final java.util.List<String> LINES = new java.util.ArrayList<>(); }"),
                    Map.entry("mod/Row.java", """
                            package mod;
                            import net.minecraft.client.gui.GuiGraphics;
                            public class Row extends net.minecraft.client.gui.components.AbstractSelectionList.Entry {
                                public void render(GuiGraphics g, int index, int top, int left, int width, int height, int mouseX, int mouseY,
                                        boolean hovering, float partialTick) {
                                    Log.LINES.add("row " + index + " " + top + " " + left + " " + width + " " + height + " " + mouseX + " "
                                            + mouseY + " " + hovering + " " + partialTick);
                                }
                                public void renderBack(GuiGraphics g, int index, int top, int left, int width, int height, int mouseX,
                                        int mouseY, boolean hovering, float partialTick) {
                                    super.renderBack(g, index, top, left, width, height, mouseX, mouseY, hovering, partialTick);
                                    Log.LINES.add("back");
                                }
                            }
                            """),
                    Map.entry("mod/BaseRow.java", """
                            package mod;
                            import net.minecraft.client.gui.GuiGraphics;
                            public abstract class BaseRow extends net.minecraft.client.gui.components.AbstractSelectionList.Entry {
                                public void renderBack(GuiGraphics g, int index, int top, int left, int width, int height, int mouseX,
                                        int mouseY, boolean hovering, float partialTick) {
                                    Log.LINES.add("base back");
                                }
                            }
                            """),
                    Map.entry("mod/PlainRow.java", """
                            package mod;
                            import net.minecraft.client.gui.GuiGraphics;
                            public class PlainRow extends BaseRow {
                                public void render(GuiGraphics g, int index, int top, int left, int width, int height, int mouseX, int mouseY,
                                        boolean hovering, float partialTick) {
                                    Log.LINES.add("plain row");
                                }
                            }
                            """),
                    Map.entry("mod/LayeredRow.java", """
                            package mod;
                            import net.minecraft.client.gui.GuiGraphics;
                            public class LayeredRow extends BaseRow {
                                public void render(GuiGraphics g, int index, int top, int left, int width, int height, int mouseX, int mouseY,
                                        boolean hovering, float partialTick) {
                                    Log.LINES.add("layered row");
                                }
                                public void renderBack(GuiGraphics g, int index, int top, int left, int width, int height, int mouseX,
                                        int mouseY, boolean hovering, float partialTick) {
                                    super.renderBack(g, index, top, left, width, height, mouseX, mouseY, hovering, partialTick);
                                    Log.LINES.add("layered back");
                                }
                            }
                            """),
                    Map.entry("mod/Current.java", """
                            package mod;
                            import net.minecraft.client.gui.GuiGraphics;
                            public class Current extends net.minecraft.client.gui.components.AbstractSelectionList.Entry {
                                public void render(GuiGraphics g, int index, int top, int left, int width, int height, int mouseX, int mouseY,
                                        boolean hovering, float partialTick) {}
                                public void extractContent(GuiGraphics g, int mouseX, int mouseY, boolean hovered, float partialTick) {}
                            }
                            """),
                    Map.entry("mod/RowMixin.java", """
                            package mod;
                            import net.minecraft.client.gui.GuiGraphics;
                            @org.spongepowered.asm.mixin.Mixin(Row.class)
                            public abstract class RowMixin extends net.minecraft.client.gui.components.AbstractSelectionList.Entry {
                                public void render(GuiGraphics g, int index, int top, int left, int width, int height, int mouseX, int mouseY,
                                        boolean hovering, float partialTick) {}
                            }
                            """),
                    Map.entry("mod/Badge.java", """
                            package mod;
                            public class Badge implements net.minecraft.client.gui.components.Renderable {
                                public void render(net.minecraft.client.gui.GuiGraphics g, int x, int y, float t) {
                                    Log.LINES.add("badge " + x + " " + y + " " + t);
                                }
                            }
                            """),
                    Map.entry("mod/Dropdown.java", """
                            package mod;
                            public class Dropdown extends net.minecraft.client.gui.components.events.AbstractContainerEventHandler
                                    implements net.minecraft.client.gui.components.Renderable {
                                public void render(net.minecraft.client.gui.GuiGraphics g, int x, int y, float t) {
                                    Log.LINES.add("dropdown " + x + " " + y + " " + t);
                                }
                            }
                            """),
                    Map.entry("mod/Toggle.java", """
                            package mod;
                            public class Toggle extends net.minecraft.client.gui.components.AbstractButton {
                                public void onPress() { Log.LINES.add("toggle"); }
                            }
                            """),
                    Map.entry("mod/Confirm.java", """
                            package mod;
                            public class Confirm extends net.minecraft.client.gui.components.Button {
                                public void onPress() { Log.LINES.add("confirm"); super.onPress(); }
                            }
                            """),
                    Map.entry("mod/PoseWidget.java", """
                            package mod;
                            public class PoseWidget extends net.minecraft.client.gui.components.AbstractButton {
                                public void onPress() {}
                                public void render(com.mojang.blaze3d.vertex.PoseStack pose, int x, int y, float t) {}
                            }
                            """),
                    Map.entry("mod/Clicker.java", """
                            package mod;
                            import net.minecraft.client.gui.components.*;
                            public class Clicker {
                                public static void drawWidget(PoseWidget widget, net.minecraft.client.gui.GuiGraphics g) { widget.render(g, 1, 2, 3F); }
                                public static void click(Button button) { button.onPress(); }
                                public static void draw(Renderable r, net.minecraft.client.gui.GuiGraphics g) { r.render(g, 7, 8, 9F); }
                            }
                            """));
        }

        // 26.3's shapes, with the content bounds fixed and the host button's press logged.
        static Map<String, String> newSources() {
            return Map.ofEntries(
                    Map.entry("net/minecraft/client/gui/GuiGraphicsExtractor.java",
                            "package net.minecraft.client.gui; public class GuiGraphicsExtractor {}"),
                    Map.entry("net/minecraft/client/gui/components/AbstractSelectionList.java", """
                            package net.minecraft.client.gui.components;
                            import net.minecraft.client.gui.GuiGraphicsExtractor;
                            public abstract class AbstractSelectionList {
                                public abstract static class Entry {
                                    public abstract void extractContent(GuiGraphicsExtractor g, int mouseX, int mouseY, boolean hovered, float a);
                                    public int getContentX() { return 10; }
                                    public int getContentY() { return 20; }
                                    public int getContentWidth() { return 30; }
                                    public int getContentHeight() { return 40; }
                                }
                            }
                            """),
                    Map.entry("net/minecraft/client/gui/components/events/AbstractContainerEventHandler.java",
                            "package net.minecraft.client.gui.components.events; public abstract class AbstractContainerEventHandler {}"),
                    Map.entry("net/minecraft/client/gui/components/Renderable.java", """
                            package net.minecraft.client.gui.components;
                            public interface Renderable {
                                void extractRenderState(net.minecraft.client.gui.GuiGraphicsExtractor g, int x, int y, float t);
                            }
                            """),
                    Map.entry("net/minecraft/client/input/InputWithModifiers.java",
                            "package net.minecraft.client.input; public interface InputWithModifiers { int input(); int modifiers(); }"),
                    Map.entry("net/minecraft/client/input/MouseButtonInfo.java",
                            "package net.minecraft.client.input; public record MouseButtonInfo(int button, int modifiers) implements InputWithModifiers {"
                                    + " public int input() { return button; } }"),
                    Map.entry("net/minecraft/client/gui/components/AbstractWidget.java", """
                            package net.minecraft.client.gui.components;
                            public abstract class AbstractWidget {
                                public final void extractRenderState(net.minecraft.client.gui.GuiGraphicsExtractor g, int x, int y, float t) {
                                    try {
                                        ((java.util.List<String>) Class.forName("mod.Log", true, getClass().getClassLoader())
                                                .getField("LINES").get(null)).add("widget draw");
                                    } catch (ReflectiveOperationException e) {
                                        throw new IllegalStateException(e);
                                    }
                                }
                            }
                            """),
                    Map.entry("com/mojang/blaze3d/vertex/PoseStack.java", "package com.mojang.blaze3d.vertex; public class PoseStack {}"),
                    Map.entry("net/minecraft/client/gui/components/AbstractButton.java", """
                            package net.minecraft.client.gui.components;
                            public abstract class AbstractButton extends AbstractWidget {
                                public abstract void onPress(net.minecraft.client.input.InputWithModifiers input);
                            }
                            """),
                    Map.entry("net/minecraft/client/gui/components/Button.java", """
                            package net.minecraft.client.gui.components;
                            public class Button extends AbstractButton {
                                public void onPress(net.minecraft.client.input.InputWithModifiers input) {
                                    try {
                                        ((java.util.List<String>) Class.forName("mod.Log", true, getClass().getClassLoader())
                                                .getField("LINES").get(null)).add("host press");
                                    } catch (ReflectiveOperationException e) {
                                        throw new IllegalStateException(e);
                                    }
                                }
                            }
                            """));
        }
    }
}
