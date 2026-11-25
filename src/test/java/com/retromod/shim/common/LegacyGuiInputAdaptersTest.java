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
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Old widget input overrides, tooltip components and GUI calls run on a 26.x shaped host, both ways round. */
class LegacyGuiInputAdaptersTest {

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("the game's event calls reach a widget's old onClick and onDrag, whose super calls reach the game's")
    void widgetOverridesRunFromHostEvents(@TempDir Path dir) throws Throwable {
        BridgeHarness.Run run = run(dir, "26.3");
        assertEquals("click 3.0,4.0;host click;drag 3.0,4.0,5.0,6.0;host drag;release 3.0,4.0;",
                run.call("mod/Calls", "pressSlider"));
    }

    @Test
    @DisplayName("a mod's old call on a vanilla widget becomes a left click event")
    void oldCallsOnVanillaWidgets(@TempDir Path dir) throws Throwable {
        BridgeHarness.Run run = run(dir, "1.21.9");
        assertEquals("host click 1.0,2.0 button 0;", run.call("mod/Calls", "pokeEditBox"));
    }

    @Test
    @DisplayName("an old tooltip component answers the game's font height and full size image, and mod calls reach it too")
    void tooltipComponentsBothWays(@TempDir Path dir) throws Throwable {
        BridgeHarness.Run run = run(dir, "26.3");
        assertEquals(7, run.call("mod/Calls", "hostMeasures"));
        assertEquals("image 1,2;", run.call("mod/Calls", "hostPaints"));
        assertEquals(7, run.call("mod/Calls", "modMeasures"));
        assertEquals("image 4,5;", run.call("mod/Calls", "modPaints"));
    }

    @Test
    @DisplayName("old window, click, range and tooltip background calls reach the host through the rebuilt helpers")
    void rebuiltHelpersRunOnTheHost(@TempDir Path dir) throws Throwable {
        BridgeHarness.Run run = run(dir, "26.3");
        assertEquals(30, run.call("mod/Calls", "framerate"));
        assertEquals("scale 2;", run.call("mod/Calls", "scale"));
        assertEquals("game click;", run.call("mod/Calls", "clickCommand"));
        assertEquals("click;", run.call("mod/Calls", "clickUrl"));
        assertEquals(Boolean.TRUE, run.call("mod/Calls", "rangeIsDiscrete"));
        assertEquals("background 1,2,3,4 null;", run.call("mod/Calls", "background"));
    }

    @Test
    @DisplayName("an old texture image button, built directly or by a mod subclass, draws its texture region")
    void oldImageButtonsDrawTheirTexture(@TempDir Path dir) throws Throwable {
        BridgeHarness.Run run = run(dir, "26.3");
        assertEquals("blit gui/icons.png 10,20,26,36 uv 0.0625,0.125 0.125,0.1875;", run.call("mod/Calls", "drawPlainButton"));
        assertEquals("blit gui/tabs.png 1,2,9,10 uv 0.0625,0.1875 0.1875,0.3125;", run.call("mod/Calls", "drawTab"));
        assertEquals("sprites;", run.call("mod/Calls", "drawSpriteButton"));
        assertEquals("blit gui/icons.png 0,0,20,10 uv 0.0,0.0390625 0.078125,0.078125;", run.call("mod/Calls", "drawNoOffsetButton"),
                "without an offset the hovered image sits one button height down");
    }

    @Test
    @DisplayName("a widget that keeps onClick(x, y) as a helper of its event override is not made to call itself")
    void helperNextToTheEventFormIsLeftAlone(@TempDir Path dir) throws Throwable {
        BridgeHarness.Run run = run(dir, "26.3");
        assertEquals("event;helper 3.0,4.0;host drag;", run.call("mod/Calls", "pressNewWidget"));
    }

    @Test
    @DisplayName("a mod built for 1.21.9 or newer, or a tooltip component built for 1.21.2 or newer, is left as it is")
    void newerModsAreNotAdapted(@TempDir Path dir) throws Exception {
        Map<String, byte[]> old = BridgeHarness.compile(dir.resolve("old"), oldWorld());
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        byte[] slider = old.get("mod/Slider");
        assertSame(slider, LegacyWidgetInputAdapter.apply(slider, "1.21.9"));
        byte[] icon = old.get("mod/Icon");
        assertSame(icon, LegacyTooltipComponentAdapter.apply(icon, "1.21.2"));
    }

    @Test
    @DisplayName("a mixin that extends ImageButton keeps its superclass")
    void mixinImageButtonIsNotRebased(@TempDir Path dir) throws Exception {
        Map<String, String> sources = oldWorld();
        sources.put("mod/ButtonMixin.java", """
                package mod;
                @org.spongepowered.asm.mixin.Mixin(Tab.class)
                public abstract class ButtonMixin extends net.minecraft.client.gui.components.ImageButton {
                    ButtonMixin() { super(0, 0, 0, 0, 0, 0, 0, null, null); }
                }""");
        sources.put("org/spongepowered/asm/mixin/Mixin.java", """
                package org.spongepowered.asm.mixin;
                public @interface Mixin { Class<?>[] value(); }""");
        Map<String, byte[]> old = BridgeHarness.compile(dir.resolve("old"), sources);
        Map<String, byte[]> host = BridgeHarness.compile(dir.resolve("new"), newWorld());
        Function<String, ClassNode> reader = name -> {
            byte[] bytes = host.get(name);
            if (bytes == null) return null;
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE);
            return node;
        };
        Map<String, byte[]> mod = BridgeHarness.transform("26.3", t -> LegacyGuiMembersBridge.register(t, reader),
                Map.of("mod/ButtonMixin", old.get("mod/ButtonMixin"), "mod/Tab", old.get("mod/Tab")), host);
        assertEquals(LegacyGuiMembersBridge.IMAGE_BUTTON, new ClassReader(mod.get("mod/ButtonMixin")).getSuperName());
        assertEquals(LegacyGuiMembersBridge.LEGACY_IMAGE_BUTTON, new ClassReader(mod.get("mod/Tab")).getSuperName());
    }

    @Test
    @DisplayName("a mixin keeps its old widget methods without bridges")
    void mixinsGetNoBridges(@TempDir Path dir) throws Exception {
        Map<String, String> sources = oldWorld();
        sources.put("mod/WidgetMixin.java", """
                package mod;
                @org.spongepowered.asm.mixin.Mixin(net.minecraft.client.gui.components.EditBox.class)
                public abstract class WidgetMixin extends net.minecraft.client.gui.components.AbstractWidget {
                    public void onClick(double x, double y) {}
                }""");
        sources.put("org/spongepowered/asm/mixin/Mixin.java", """
                package org.spongepowered.asm.mixin;
                public @interface Mixin { Class<?>[] value(); }""");
        Map<String, byte[]> old = BridgeHarness.compile(dir.resolve("old"), sources);
        Map<String, byte[]> host = BridgeHarness.compile(dir.resolve("new"), newWorld());
        Map<String, byte[]> mod = BridgeHarness.transform("26.3", t -> { }, Map.of("mod/WidgetMixin", old.get("mod/WidgetMixin")), host);
        ClassNode mixin = new ClassNode();
        new ClassReader(mod.get("mod/WidgetMixin")).accept(mixin, 0);
        assertEquals(1, mixin.methods.stream().filter(m -> m.name.equals("onClick")).count(), "only the mixin's own onClick");
        assertSame(null, mixin.methods.stream().filter(m -> m.desc.contains("MouseButtonEvent")).findFirst().orElse(null));
    }

    private static BridgeHarness.Run run(Path dir, String target) throws Exception {
        Map<String, byte[]> old = BridgeHarness.compile(dir.resolve("old"), oldWorld());
        Map<String, byte[]> host = BridgeHarness.compile(dir.resolve("new"), newWorld());
        Function<String, ClassNode> reader = name -> {
            byte[] bytes = host.get(name);
            if (bytes == null) return null;
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE);
            return node;
        };
        Map<String, byte[]> mod = BridgeHarness.transform(target, t -> LegacyGuiMembersBridge.register(t, reader),
                BridgeHarness.only(old, "mod/"), host);
        return new BridgeHarness.Run(host, mod);
    }

    // 1.20.1 and 1.21.1 shapes, under the names 26.1's renames give them.
    private static Map<String, String> oldWorld() {
        Map<String, String> s = new HashMap<>();
        s.put("net/minecraft/client/gui/components/AbstractWidget.java", """
                package net.minecraft.client.gui.components;
                public abstract class AbstractWidget {
                    public void onClick(double x, double y) {}
                    public void onRelease(double x, double y) {}
                    protected void onDrag(double x, double y, double dx, double dy) {}
                }""");
        s.put("net/minecraft/client/gui/components/EditBox.java", """
                package net.minecraft.client.gui.components;
                public class EditBox extends AbstractWidget {}""");
        s.put("net/minecraft/resources/Identifier.java", """
                package net.minecraft.resources;
                public class Identifier { public static Identifier of(String p) { return null; } }""");
        s.put("net/minecraft/client/gui/components/Button.java", """
                package net.minecraft.client.gui.components;
                public class Button extends AbstractWidget { public interface OnPress { void onPress(Button b); } }""");
        s.put("net/minecraft/client/gui/components/ImageButton.java", """
                package net.minecraft.client.gui.components;
                import net.minecraft.resources.Identifier;
                public class ImageButton extends Button {
                    public ImageButton(int x, int y, int w, int h, int u, int v, Identifier t, Button.OnPress p) {}
                    public ImageButton(int x, int y, int w, int h, int u, int v, int hover, Identifier t, Button.OnPress p) {}
                    public ImageButton(int x, int y, int w, int h, int u, int v, int hover, Identifier t, int tw, int th, Button.OnPress p) {}
                }""");
        s.put("net/minecraft/client/input/MouseButtonInfo.java", """
                package net.minecraft.client.input;
                public record MouseButtonInfo(int button, int modifiers) {}""");
        s.put("net/minecraft/client/input/MouseButtonEvent.java", """
                package net.minecraft.client.input;
                public record MouseButtonEvent(double x, double y, MouseButtonInfo buttonInfo) {}""");
        // A 1.21.9 style widget: the event override calls its own old-shaped helper.
        s.put("mod/NewWidget.java", """
                package mod;
                public class NewWidget extends net.minecraft.client.gui.components.AbstractWidget {
                    public void onClick(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
                        net.minecraft.client.Game.log += "event;";
                        onClick(event.x(), event.y());
                    }
                    public void onClick(double x, double y) { net.minecraft.client.Game.log += "helper " + x + "," + y + ";"; }
                }""");
        s.put("mod/Tab.java", """
                package mod;
                public class Tab extends net.minecraft.client.gui.components.ImageButton {
                    public Tab() { super(1, 2, 8, 8, 4, 4, 8, net.minecraft.resources.Identifier.of("gui/tabs.png"), 64, 64, b -> { }); }
                }""");
        s.put("net/minecraft/client/gui/Font.java", "package net.minecraft.client.gui; public class Font {}");
        s.put("net/minecraft/client/gui/GuiGraphicsExtractor.java", "package net.minecraft.client.gui; public class GuiGraphicsExtractor {}");
        s.put("net/minecraft/client/gui/screens/inventory/tooltip/ClientTooltipComponent.java", """
                package net.minecraft.client.gui.screens.inventory.tooltip;
                import net.minecraft.client.gui.*;
                public interface ClientTooltipComponent {
                    int getHeight();
                    int getWidth(Font font);
                    default void extractImage(Font font, int x, int y, GuiGraphicsExtractor graphics) {}
                }""");
        s.put("net/minecraft/client/gui/screens/inventory/tooltip/TooltipRenderUtil.java", """
                package net.minecraft.client.gui.screens.inventory.tooltip;
                public class TooltipRenderUtil {
                    public static void extractTooltipBackground(net.minecraft.client.gui.GuiGraphicsExtractor g, int x, int y, int w, int h, int z) {}
                }""");
        s.put("com/mojang/blaze3d/platform/Window.java", """
                package com.mojang.blaze3d.platform;
                public class Window {
                    public int getFramerateLimit() { return 0; }
                    public void setGuiScale(double scale) {}
                }""");
        s.put("net/minecraft/client/Minecraft.java", """
                package net.minecraft.client;
                public class Minecraft {
                    public static Minecraft getInstance() { return null; }
                    public com.mojang.blaze3d.platform.Window getWindow() { return null; }
                }""");
        s.put("net/minecraft/network/chat/Style.java", "package net.minecraft.network.chat; public class Style { public static Style of(Object e) { return null; } }");
        s.put("net/minecraft/client/gui/screens/Screen.java", """
                package net.minecraft.client.gui.screens;
                public class Screen {
                    public boolean handleComponentClicked(net.minecraft.network.chat.Style style) { return false; }
                }""");
        s.put("net/minecraft/client/OptionInstance.java", """
                package net.minecraft.client;
                import java.util.function.*;
                public class OptionInstance {
                    public interface SliderableValueSet<T> {}
                    public interface IntRangeBase {
                        default <R> SliderableValueSet<R> xmap(IntFunction<? extends R> to, ToIntFunction<? super R> from) { return null; }
                    }
                    public record IntRange(int min, int max) implements IntRangeBase {}
                }""");
        s.put("mod/Slider.java", """
                package mod;
                public class Slider extends net.minecraft.client.gui.components.AbstractWidget {
                    public void onClick(double x, double y) { net.minecraft.client.Game.log += "click " + x + "," + y + ";"; super.onClick(x, y); }
                    public void onRelease(double x, double y) { net.minecraft.client.Game.log += "release " + x + "," + y + ";"; }
                    protected void onDrag(double x, double y, double dx, double dy) {
                        net.minecraft.client.Game.log += "drag " + x + "," + y + "," + dx + "," + dy + ";";
                        super.onDrag(x, y, dx, dy);
                    }
                }""");
        s.put("mod/Icon.java", """
                package mod;
                import net.minecraft.client.gui.*;
                public class Icon implements net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent {
                    public int getHeight() { return 7; }
                    public int getWidth(Font font) { return 9; }
                    public void extractImage(Font font, int x, int y, GuiGraphicsExtractor graphics) { net.minecraft.client.Game.log += "image " + x + "," + y + ";"; }
                }""");
        s.put("mod/Calls.java", """
                package mod;
                import net.minecraft.client.*;
                import net.minecraft.client.gui.components.*;
                import net.minecraft.client.gui.screens.inventory.tooltip.*;
                public final class Calls {
                    public static String pressSlider() { Game.log = ""; Game.press(new Slider()); return Game.log; }
                    public static String drawPlainButton() {
                        return Game.draw(new ImageButton(10, 20, 16, 16, 16, 16, 16, net.minecraft.resources.Identifier.of("gui/icons.png"), b -> { }));
                    }
                    public static String drawTab() { return Game.draw(new Tab()); }
                    public static String drawNoOffsetButton() {
                        return Game.draw(new ImageButton(0, 0, 20, 10, 0, 0, net.minecraft.resources.Identifier.of("gui/icons.png"), b -> { }));
                    }
                    public static String pressNewWidget() { Game.log = ""; Game.press(new NewWidget()); return Game.log.replace("host release;", ""); }
                    public static String drawSpriteButton() { return Game.drawSprites(); }
                    public static String pokeEditBox() { Game.log = ""; new EditBox().onClick(1, 2); return Game.log; }
                    public static int hostMeasures() { return Game.measure(new Icon()); }
                    public static String hostPaints() { Game.log = ""; Game.paint(new Icon()); return Game.log; }
                    public static int modMeasures() { ClientTooltipComponent c = new Icon(); return c.getHeight(); }
                    public static String modPaints() { Game.log = ""; ClientTooltipComponent c = new Icon(); c.extractImage(null, 4, 5, null); return Game.log; }
                    public static int framerate() { return Minecraft.getInstance().getWindow().getFramerateLimit(); }
                    public static String scale() { Game.log = ""; Minecraft.getInstance().getWindow().setGuiScale(2.0); return Game.log; }
                    public static String clickCommand() { return click("command"); }
                    public static String clickUrl() { return click("url"); }
                    private static String click(String kind) {
                        Game.log = "";
                        new net.minecraft.client.gui.screens.Screen().handleComponentClicked(net.minecraft.network.chat.Style.of(kind));
                        return Game.log;
                    }
                    public static Object rangeIsDiscrete() { return new OptionInstance.IntRange(0, 4).xmap(i -> i, i -> i) != null; }
                    public static String background() {
                        Game.log = "";
                        TooltipRenderUtil.extractTooltipBackground(null, 1, 2, 3, 4, 400);
                        return Game.log;
                    }
                }""");
        // The game's own calls; the mod is compiled against this old shape and runs against the new one below.
        s.put("net/minecraft/client/Game.java", """
                package net.minecraft.client;
                public final class Game {
                    public static String log = "";
                    public static void press(net.minecraft.client.gui.components.AbstractWidget w) {}
                    public static String draw(net.minecraft.client.gui.components.ImageButton b) { return null; }
                    public static String drawSprites() { return null; }
                    public static int measure(Object c) { return 0; }
                    public static void paint(Object c) {}
                }""");
        return s;
    }

    // 26.x shapes: widget input takes a mouse event, tooltip components take a font and their full size.
    private static Map<String, String> newWorld() {
        Map<String, String> s = new HashMap<>();
        s.put("net/minecraft/client/input/MouseButtonInfo.java", """
                package net.minecraft.client.input;
                public record MouseButtonInfo(int button, int modifiers) {}""");
        s.put("net/minecraft/client/input/MouseButtonEvent.java", """
                package net.minecraft.client.input;
                public record MouseButtonEvent(double x, double y, MouseButtonInfo buttonInfo) {
                    public int button() { return buttonInfo.button(); }
                }""");
        s.put("net/minecraft/client/gui/components/AbstractWidget.java", """
                package net.minecraft.client.gui.components;
                import net.minecraft.client.input.MouseButtonEvent;
                public abstract class AbstractWidget {
                    protected int width = 16;
                    protected int height = 16;
                    private int x;
                    private int y;
                    public void place(int x, int y, int width, int height) { this.x = x; this.y = y; this.width = width; this.height = height; }
                    public int getX() { return x; }
                    public int getY() { return y; }
                    public boolean isActive() { return true; }
                    public boolean isHoveredOrFocused() { return true; }
                    public void onClick(MouseButtonEvent event, boolean doubleClick) { net.minecraft.client.Game.log += "host click;"; }
                    public void onRelease(MouseButtonEvent event) { net.minecraft.client.Game.log += "host release;"; }
                    protected void onDrag(MouseButtonEvent event, double dx, double dy) { net.minecraft.client.Game.log += "host drag;"; }
                }""");
        s.put("net/minecraft/client/gui/components/EditBox.java", """
                package net.minecraft.client.gui.components;
                import net.minecraft.client.input.MouseButtonEvent;
                public class EditBox extends AbstractWidget {
                    public void onClick(MouseButtonEvent event, boolean doubleClick) {
                        net.minecraft.client.Game.log += "host click " + event.x() + "," + event.y() + " button " + event.button() + ";";
                    }
                }""");
        s.put("net/minecraft/client/gui/Font.java", "package net.minecraft.client.gui; public class Font {}");
        s.put("net/minecraft/client/gui/GuiGraphicsExtractor.java", """
                package net.minecraft.client.gui;
                public class GuiGraphicsExtractor {
                    public void blit(net.minecraft.resources.Identifier t, int x0, int y0, int x1, int y1, float u0, float u1, float v0, float v1) {
                        net.minecraft.client.Game.log += "blit " + t + " " + x0 + "," + y0 + "," + x1 + "," + y1 + " uv " + u0 + "," + v0 + " " + u1 + "," + v1 + ";";
                    }
                }""");
        s.put("net/minecraft/client/gui/screens/inventory/tooltip/ClientTooltipComponent.java", """
                package net.minecraft.client.gui.screens.inventory.tooltip;
                import net.minecraft.client.gui.*;
                public interface ClientTooltipComponent {
                    int getHeight(Font font);
                    int getWidth(Font font);
                    default void extractImage(Font font, int x, int y, int width, int height, GuiGraphicsExtractor graphics) {}
                }""");
        s.put("net/minecraft/client/gui/screens/inventory/tooltip/TooltipRenderUtil.java", """
                package net.minecraft.client.gui.screens.inventory.tooltip;
                public class TooltipRenderUtil {
                    public static void extractTooltipBackground(net.minecraft.client.gui.GuiGraphicsExtractor g, int x, int y, int w, int h,
                            net.minecraft.resources.Identifier style) {
                        net.minecraft.client.Game.log += "background " + x + "," + y + "," + w + "," + h + " " + style + ";";
                    }
                }""");
        s.put("net/minecraft/resources/Identifier.java", """
                package net.minecraft.resources;
                public class Identifier {
                    private final String path;
                    private Identifier(String path) { this.path = path; }
                    public static Identifier of(String path) { return new Identifier(path); }
                    public String toString() { return path; }
                }""");
        s.put("net/minecraft/network/chat/CommonComponents.java", """
                package net.minecraft.network.chat;
                public final class CommonComponents { public static final Component EMPTY = new Component() { }; }""");
        s.put("net/minecraft/client/gui/components/Button.java", """
                package net.minecraft.client.gui.components;
                public class Button extends AbstractWidget { public interface OnPress { void onPress(Button b); } }""");
        s.put("net/minecraft/client/gui/components/WidgetSprites.java", """
                package net.minecraft.client.gui.components;
                public record WidgetSprites(net.minecraft.resources.Identifier enabled) {}""");
        s.put("net/minecraft/client/gui/components/ImageButton.java", """
                package net.minecraft.client.gui.components;
                import net.minecraft.network.chat.Component;
                public class ImageButton extends Button {
                    public ImageButton(int x, int y, int w, int h, WidgetSprites s, Button.OnPress p) { place(x, y, w, h); }
                    public ImageButton(int x, int y, WidgetSprites s, Button.OnPress p, Component m) { place(x, y, 16, 16); }
                    public ImageButton(int x, int y, int w, int h, WidgetSprites s, Button.OnPress p, Component m) { place(x, y, w, h); }
                    public void extractContents(net.minecraft.client.gui.GuiGraphicsExtractor g, int mx, int my, float pt) {
                        net.minecraft.client.Game.log += "sprites;";
                    }
                }""");
        s.put("com/mojang/blaze3d/platform/FramerateLimitTracker.java", """
                package com.mojang.blaze3d.platform;
                public class FramerateLimitTracker {
                    public int getFramerateLimit() { return 30; }
                    public void setFramerateLimit(int limit) {}
                }""");
        s.put("com/mojang/blaze3d/platform/Window.java", """
                package com.mojang.blaze3d.platform;
                public class Window {
                    public void setGuiScale(int scale) { net.minecraft.client.Game.log += "scale " + scale + ";"; }
                }""");
        s.put("net/minecraft/client/Minecraft.java", """
                package net.minecraft.client;
                public class Minecraft {
                    private static final Minecraft INSTANCE = new Minecraft();
                    public final net.minecraft.client.gui.Font font = new net.minecraft.client.gui.Font();
                    public net.minecraft.client.player.LocalPlayer player = new net.minecraft.client.player.LocalPlayer();
                    public static Minecraft getInstance() { return INSTANCE; }
                    public com.mojang.blaze3d.platform.Window getWindow() { return new com.mojang.blaze3d.platform.Window(); }
                    public com.mojang.blaze3d.platform.FramerateLimitTracker getFramerateLimitTracker() {
                        return new com.mojang.blaze3d.platform.FramerateLimitTracker();
                    }
                    public boolean hasShiftDown() { return false; }
                }""");
        s.put("net/minecraft/network/chat/ClickEvent.java", """
                package net.minecraft.network.chat;
                public interface ClickEvent {
                    record RunCommand(String command) implements ClickEvent {}
                    record OpenUrl(String uri) implements ClickEvent {}
                    record ShowDialog(Object dialog) implements ClickEvent {}
                    record Custom(Object id, Object payload) implements ClickEvent {}
                    record ChangePage(int page) implements ClickEvent {}
                }""");
        s.put("net/minecraft/CrashReport.java", "package net.minecraft; public class CrashReport {}");
        s.put("net/minecraft/ReportedException.java", "package net.minecraft; public class ReportedException extends RuntimeException {}");
        s.put("net/minecraft/client/player/LocalPlayer.java", "package net.minecraft.client.player; public class LocalPlayer {}");
        s.put("net/minecraft/network/chat/Component.java", "package net.minecraft.network.chat; public interface Component {}");
        s.put("net/minecraft/network/chat/Style.java", """
                package net.minecraft.network.chat;
                public class Style {
                    private final ClickEvent click;
                    private Style(ClickEvent click) { this.click = click; }
                    public static Style of(Object kind) {
                        return new Style("command".equals(kind) ? new ClickEvent.RunCommand("/help") : new ClickEvent.OpenUrl("https://example.com"));
                    }
                    public ClickEvent getClickEvent() { return click; }
                }""");
        s.put("net/minecraft/client/gui/screens/Screen.java", """
                package net.minecraft.client.gui.screens;
                import net.minecraft.client.Minecraft;
                import net.minecraft.network.chat.*;
                public class Screen {
                    public Screen() {}
                    protected Screen(Component title) {}
                    protected static void defaultHandleClickEvent(ClickEvent click, Minecraft minecraft, Screen screen) { net.minecraft.client.Game.log += "click;"; }
                    protected static void defaultHandleGameClickEvent(ClickEvent click, Minecraft minecraft, Screen screen) { net.minecraft.client.Game.log += "game click;"; }
                }""");
        s.put("net/minecraft/client/OptionInstance.java", """
                package net.minecraft.client;
                import java.util.function.*;
                public class OptionInstance {
                    public interface SliderableValueSet<T> {}
                    public interface IntRangeBase {
                        @SuppressWarnings("unchecked")
                        default <R> SliderableValueSet<R> xmap(IntFunction<? extends R> to, ToIntFunction<? super R> from, boolean discrete) {
                            return discrete ? (SliderableValueSet<R>) new Discrete() : null;
                        }
                    }
                    public static final class Discrete implements SliderableValueSet<Object> {}
                    public record IntRange(int min, int max) implements IntRangeBase {}
                }""");
        s.put("net/minecraft/client/Game.java", """
                package net.minecraft.client;
                import net.minecraft.client.input.*;
                import net.minecraft.client.gui.*;
                import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
                public final class Game {
                    public static String log = "";
                    // The game's own input dispatch: a click, a drag and a release, as one event each.
                    public static void press(net.minecraft.client.gui.components.AbstractWidget w) {
                        MouseButtonEvent event = new MouseButtonEvent(3, 4, new MouseButtonInfo(0, 0));
                        w.onClick(event, false);
                        net.minecraft.client.gui.components.Dragger.drag(w, event, 5, 6);
                        w.onRelease(event);
                    }
                    public static String draw(net.minecraft.client.gui.components.ImageButton b) {
                        log = "";
                        b.extractContents(new GuiGraphicsExtractor(), 0, 0, 0);
                        return log;
                    }
                    public static String drawSprites() {
                        return draw(new net.minecraft.client.gui.components.ImageButton(0, 0, 16, 16,
                                new net.minecraft.client.gui.components.WidgetSprites(null), b -> { }));
                    }
                    public static int measure(Object c) { return ((ClientTooltipComponent) c).getHeight(new Font()); }
                    public static void paint(Object c) { ((ClientTooltipComponent) c).extractImage(new Font(), 1, 2, 9, 7, new GuiGraphicsExtractor()); }
                }""");
        // onDrag is protected, so the game's own package calls it.
        s.put("net/minecraft/client/gui/components/Dragger.java", """
                package net.minecraft.client.gui.components;
                public final class Dragger {
                    public static void drag(AbstractWidget w, net.minecraft.client.input.MouseButtonEvent e, double dx, double dy) { w.onDrag(e, dx, dy); }
                }""");
        return s;
    }
}
