/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import javax.tools.ToolProvider;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * #392: Meulti's Backrooms (Forge 1.20.1) adds a texture image button to the title screen, and NeoForge 1.21.1 stopped
 * the client with {@code NoSuchMethodError: ImageButton.<init>(int, int, int, int, int, int, int, ResourceLocation, OnPress)}.
 * The rebuilt button is checked against compiled stand-ins of the 1.21.1 widget classes.
 */
class LegacyTextureImageButtonBridgeTest {

    private static final String BUTTON = LegacyTextureImageButtonBridge.IMAGE_BUTTON;

    @TempDir
    Path dir;

    private RetromodTransformer transformer;
    private String previousTarget;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        previousTarget = RetromodVersion.TARGET_MC_VERSION;
    }

    @AfterEach
    void tearDown() {
        RetromodVersion.TARGET_MC_VERSION = previousTarget;
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("the old constructors draw their texture region, one offset down when hovered and two when inactive")
    void drawsTheTextureRegion() throws Exception {
        ClassLoader loader = standIns();
        Class<?> texture = loader.loadClass("net.minecraft.resources.ResourceLocation");
        Object id = texture.getConstructor(String.class).newInstance("mbackrooms:textures/gui/config.png");
        Class<?> onPress = loader.loadClass("net.minecraft.client.gui.components.Button$OnPress");
        Class<?> rebuilt = loader.loadClass(LegacyTextureImageButtonBridge.CLASS.replace('/', '.'));

        // new ImageButton(x, y, width, height, u, v, hoverOffset, texture, onPress), as the title screen button is built.
        Method create = rebuilt.getMethod("create", int.class, int.class, int.class, int.class, int.class, int.class, int.class,
                texture, onPress);
        Object button = create.invoke(null, 10, 20, 16, 16, 0, 32, 16, id, null);
        assertEquals(List.of(id, 10, 20, 0.0F, 32.0F, 16, 16, 256, 256), draw(loader, button));
        button.getClass().getField("hovered").setBoolean(button, true);
        assertEquals(48.0F, draw(loader, button).get(4), "hovered: one offset down");
        button.getClass().getField("active").setBoolean(button, false);
        assertEquals(64.0F, draw(loader, button).get(4), "inactive: two offsets down");

        // Without an offset the button height is the offset; with a size, that size is the texture's.
        Object plain = rebuilt.getMethod("create", int.class, int.class, int.class, int.class, int.class, int.class, texture, onPress)
                .invoke(null, 0, 0, 20, 18, 4, 0, id, null);
        plain.getClass().getField("hovered").setBoolean(plain, true);
        assertEquals(List.of(id, 0, 0, 4.0F, 18.0F, 20, 18, 256, 256), draw(loader, plain));
        Object sized = rebuilt.getMethod("create", int.class, int.class, int.class, int.class, int.class, int.class, int.class,
                texture, int.class, int.class, onPress).invoke(null, 0, 0, 8, 8, 0, 0, 8, id, 64, 32, null);
        assertEquals(List.of(id, 0, 0, 0.0F, 0.0F, 8, 8, 64, 32), draw(loader, sized));
    }

    @Test
    @DisplayName("a button built with sprites draws as the host's ImageButton does")
    void spriteButtonsDrawAsTheHost() throws Exception {
        ClassLoader loader = standIns();
        Class<?> sprites = loader.loadClass("net.minecraft.client.gui.components.WidgetSprites");
        Class<?> texture = loader.loadClass("net.minecraft.resources.ResourceLocation");
        Object id = texture.getConstructor(String.class).newInstance("mod:button");
        Object spriteSet = sprites.getConstructor(texture, texture).newInstance(id, id);
        Class<?> rebuilt = loader.loadClass(LegacyTextureImageButtonBridge.CLASS.replace('/', '.'));
        Object button = rebuilt.getConstructor(int.class, int.class, int.class, int.class, sprites,
                loader.loadClass("net.minecraft.client.gui.components.Button$OnPress")).newInstance(1, 2, 3, 4, spriteSet, null);
        assertEquals(List.of("sprites"), draw(loader, button));
    }

    @Test
    @DisplayName("on a 1.21.1 target, new ImageButton(...) calls the factory and a mod's own button moves onto the rebuilt one")
    void modCallsAndSubclassesMove() {
        RetromodVersion.TARGET_MC_VERSION = "1.21.1";
        LegacyTextureImageButtonBridge.register(transformer, name -> null);
        String old = LegacyTextureImageButtonBridge.OLD_CONSTRUCTORS[1];

        ClassNode screen = node(transformer.transformClass(screenAddingButton(old), "mod/MenuHandler"));
        assertTrue(calls(screen).contains(LegacyTextureImageButtonBridge.CLASS + ".create" + old.replace(")V", ")L" + BUTTON + ";")),
                calls(screen).toString());

        ClassNode own = node(transformer.transformClass(customButton(old), "mod/CustomImageButton"));
        assertEquals(LegacyTextureImageButtonBridge.CLASS, own.superName);
        assertTrue(calls(own).contains(LegacyTextureImageButtonBridge.CLASS + ".<init>" + old), calls(own).toString());
    }

    @Test
    @DisplayName("hosts from 1.21.2 and 26.x keep their own image buttons, as does a host that still has the old constructors")
    void otherHostsAreLeftAlone() {
        for (String host : List.of("1.20.1", "1.21.2", "1.21.11", "26.3")) {
            RetromodVersion.TARGET_MC_VERSION = host;
            assertFalse(LegacyTextureImageButtonBridge.hostShape(null, null), host);
        }
        RetromodVersion.TARGET_MC_VERSION = "1.21.1";
        assertTrue(LegacyTextureImageButtonBridge.hostShape(null, null));
    }

    /** Runs {@code renderWidget} with a stand-in GuiGraphics and returns what it drew. */
    private static List<Object> draw(ClassLoader loader, Object button) throws Exception {
        Class<?> graphicsType = loader.loadClass("net.minecraft.client.gui.GuiGraphics");
        Object graphics = graphicsType.getConstructor().newInstance();
        button.getClass().getMethod("renderWidget", graphicsType, int.class, int.class, float.class)
                .invoke(button, graphics, 0, 0, 0.0F);
        @SuppressWarnings("unchecked")
        List<Object> drawn = (List<Object>) graphicsType.getField("drawn").get(graphics);
        return drawn;
    }

    /** The 1.21.1 widget classes the rebuilt button extends and calls, with the generated class beside them. */
    private ClassLoader standIns() throws Exception {
        Map<String, String> sources = Map.of(
                "net/minecraft/resources/ResourceLocation.java", """
                        package net.minecraft.resources;
                        public final class ResourceLocation { final String id; public ResourceLocation(String id) { this.id = id; } }""",
                "net/minecraft/network/chat/Component.java", "package net.minecraft.network.chat; public interface Component {}",
                "net/minecraft/network/chat/CommonComponents.java", """
                        package net.minecraft.network.chat;
                        public final class CommonComponents { public static final Component EMPTY = new Component() {}; }""",
                "net/minecraft/client/gui/GuiGraphics.java", """
                        package net.minecraft.client.gui;
                        import net.minecraft.resources.ResourceLocation;
                        public class GuiGraphics {
                            public java.util.List<Object> drawn = new java.util.ArrayList<>();
                            public void blit(ResourceLocation t, int x, int y, float u, float v, int w, int h, int tw, int th) {
                                drawn.addAll(java.util.List.of(t, x, y, u, v, w, h, tw, th));
                            }
                        }""",
                "net/minecraft/client/gui/components/Button.java", """
                        package net.minecraft.client.gui.components;
                        import net.minecraft.network.chat.Component;
                        public class Button {
                            public interface OnPress { void onPress(Button button); }
                            public boolean active = true;
                            public boolean hovered;
                            private final int x, y, width, height;
                            protected Button(int x, int y, int width, int height, Component message, OnPress onPress) {
                                this.x = x; this.y = y; this.width = width; this.height = height;
                            }
                            public boolean isActive() { return active; }
                            public boolean isHoveredOrFocused() { return hovered; }
                            public int getX() { return x; }
                            public int getY() { return y; }
                            public int getWidth() { return width; }
                            public int getHeight() { return height; }
                        }""",
                "net/minecraft/client/gui/components/WidgetSprites.java", """
                        package net.minecraft.client.gui.components;
                        import net.minecraft.resources.ResourceLocation;
                        public record WidgetSprites(ResourceLocation enabled, ResourceLocation disabled) {}""",
                "net/minecraft/client/gui/components/ImageButton.java", """
                        package net.minecraft.client.gui.components;
                        import net.minecraft.client.gui.GuiGraphics;
                        import net.minecraft.network.chat.Component;
                        public class ImageButton extends Button {
                            public ImageButton(int x, int y, int w, int h, WidgetSprites s, OnPress p) { super(x, y, w, h, null, p); }
                            public ImageButton(int x, int y, int w, int h, WidgetSprites s, OnPress p, Component m) { super(x, y, w, h, m, p); }
                            public ImageButton(int x, int y, WidgetSprites s, OnPress p, Component m) { super(x, y, 0, 0, m, p); }
                            public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
                                graphics.drawn.add("sprites");
                            }
                        }""");
        Path out = Files.createDirectories(dir.resolve("out"));
        List<String> args = new ArrayList<>(List.of("-d", out.toString(), "--release", "17", "-nowarn"));
        for (Map.Entry<String, String> source : sources.entrySet()) {
            Path file = dir.resolve("src").resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
            args.add(file.toString());
        }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(new String[0])));
        Path generated = out.resolve(LegacyTextureImageButtonBridge.CLASS + ".class");
        Files.createDirectories(generated.getParent());
        Files.write(generated, LegacyTextureImageButtonBridge.generate());
        return new URLClassLoader(new URL[] {out.toUri().toURL()}, getClass().getClassLoader());
    }

    /** A screen handler that adds {@code new ImageButton(...)} with old arguments, as Backrooms' MenuHandler does. */
    private static byte[] screenAddingButton(String desc) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "mod/MenuHandler", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "button", "()Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitTypeInsn(NEW, BUTTON);
        mv.visitInsn(DUP);
        pushOldArguments(mv);
        mv.visitMethodInsn(INVOKESPECIAL, BUTTON, "<init>", desc, false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A mod's own image button whose constructor calls the old super constructor. */
    private static byte[] customButton(String desc) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "mod/CustomImageButton", null, BUTTON, null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        pushOldArguments(mv);
        mv.visitMethodInsn(INVOKESPECIAL, BUTTON, "<init>", desc, false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** x, y, width, height, u, v, hover offset, a null texture and a null press handler. */
    private static void pushOldArguments(MethodVisitor mv) {
        for (int i = 0; i < 7; i++) mv.visitIntInsn(BIPUSH, i);
        mv.visitInsn(ACONST_NULL);
        mv.visitInsn(ACONST_NULL);
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static List<String> calls(ClassNode node) {
        List<String> calls = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name + call.desc);
            }
        }
        return calls;
    }
}
