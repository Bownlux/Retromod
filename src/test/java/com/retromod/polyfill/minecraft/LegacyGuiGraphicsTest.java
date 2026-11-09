/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.polyfill.minecraft;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** LegacyGuiGraphics draws each 1.21.1 call through the overload the running host has. */
class LegacyGuiGraphicsTest {

    private static final String HELPER = "com.retromod.polyfill.minecraft.LegacyGuiGraphics";
    private static final String TEST = LegacyGuiGraphicsTest.class.getName();

    @BeforeEach
    void clear() {
        Recorder.CALLS.clear();
    }

    @Test
    void onAPipelineHostEachBlitMapsItsArgumentsAndCarriesTheTint() throws Exception {
        Host host = new Host(PipelineGraphics.class);

        host.call("blitScaled", "gui", 1, 2, 3, 4, 5f, 6f, 7, 8, 9, 10);
        host.call("blitTile", "gui", 1, 2, 3, 4, 5, 6);
        host.call("setColor", 1f, 0f, 0f, 0.5f);
        host.call("blitSprite", "icon", 1, 2, 3, 4);

        assertEquals(List.of(
                List.of("blit", "gui_textured", "gui", 1, 2, 5f, 6f, 3, 4, 7, 8, 9, 10, -1),
                List.of("blit", "gui_textured", "gui", 1, 2, 3f, 4f, 5, 6, 5, 6, 256, 256, -1),
                List.of("blitSprite", "gui_textured", "icon", 1, 2, 3, 4, 0x80FF0000)), Recorder.CALLS,
                "x, y, u, v, drawn size, region size, texture size, then the tint as ARGB");
    }

    @Test
    void onAFactoryHostTheTintStaysShaderStateAndTheDepthIsDropped() throws Exception {
        Host host = new Host(FactoryGraphics.class);

        host.call("setColor", 0.5f, 1f, 1f, 1f);
        host.call("blitAtDepth", "gui", 1, 2, 99, 3f, 4f, 5, 6, 7, 8);
        host.call("blit", "gui", 1, 2, 3f, 4f, 5, 6, 7, 8);

        List<Object> blit = List.of("blit", "textured:gui", "gui", 1, 2, 3f, 4f, 5, 6, 5, 6, 7, 8, -1);
        assertEquals(List.of(
                List.of("flush"),
                List.of("setShaderColor", 0.5f, 1f, 1f, 1f),
                blit, blit), Recorder.CALLS,
                "1.21.1 flushed before setting the shader color, and the factory gives the textured type");
    }

    @Test
    void bufferSourceIsTheGamesMainOne() throws Exception {
        Host host = new Host(FactoryGraphics.class);

        assertEquals("main buffer source", host.call("bufferSource"));
    }

    /** A fresh copy of the helper, beside one stub GuiGraphics and the stub host classes it looks up. */
    private static final class Host extends ClassLoader {
        private final Map<String, String> stubs = Map.of(
                "net.minecraft.client.renderer.RenderPipelines", TEST + "$StubRenderPipelines",
                "net.minecraft.client.renderer.RenderType", TEST + "$StubRenderType",
                "com.mojang.blaze3d.systems.RenderSystem", TEST + "$StubRenderSystem",
                "net.minecraft.client.Minecraft", TEST + "$StubMinecraft");
        private final Object graphics;
        private final Class<?> helper;

        Host(Class<?> graphicsStub) throws Exception {
            super(LegacyGuiGraphicsTest.class.getClassLoader());
            this.graphics = loadClass(graphicsStub.getName()).getConstructor().newInstance();
            this.helper = loadClass(HELPER);
        }

        /** Calls the helper method whose parameters fit, with the stub GuiGraphics first. */
        Object call(String name, Object... args) throws Exception {
            for (var method : helper.getMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == args.length + 1) {
                    Object[] all = new Object[args.length + 1];
                    all[0] = graphics;
                    System.arraycopy(args, 0, all, 1, args.length);
                    return method.invoke(null, all);
                }
            }
            throw new NoSuchMethodException(name);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded != null) return loaded;
                // The helper and the GuiGraphics stubs load here, so each test sees its own host.
                boolean own = name.equals(HELPER) || name.startsWith(HELPER + "$")
                        || name.endsWith("Graphics") && name.startsWith(TEST + "$");
                if (own) return define(name, name);
                if (stubs.containsKey(name)) return define(name, stubs.get(name));
                return super.loadClass(name, resolve);
            }
        }

        /** Defines {@code name} from the compiled {@code source}, renamed when they differ. */
        private Class<?> define(String name, String source) throws ClassNotFoundException {
            String path = source.replace('.', '/') + ".class";
            try (InputStream in = getParent().getResourceAsStream(path)) {
                if (in == null) throw new ClassNotFoundException(source);
                ClassReader reader = new ClassReader(in);
                ClassWriter writer = new ClassWriter(0);
                reader.accept(new ClassRemapper(writer, new SimpleRemapper(
                        source.replace('.', '/'), name.replace('.', '/'))), 0);
                byte[] bytes = writer.toByteArray();
                return defineClass(name, bytes, 0, bytes.length);
            } catch (IOException e) {
                throw new ClassNotFoundException(source, e);
            }
        }
    }

    /** What the stub host was asked to do, shared with the stubs through the parent loader. */
    public static final class Recorder {
        public static final List<List<Object>> CALLS = new ArrayList<>();

        public static void record(Object... values) {
            CALLS.add(Arrays.asList(values));
        }
    }

    /** A 1.21.6 and newer GuiGraphics: a render pipeline and a color with each draw. */
    public static final class PipelineGraphics {
        public void blit(Object pipeline, Object texture, int x, int y, float u, float v, int width, int height,
                int regionWidth, int regionHeight, int textureWidth, int textureHeight, int color) {
            Recorder.record("blit", pipeline, texture, x, y, u, v, width, height, regionWidth, regionHeight,
                    textureWidth, textureHeight, color);
        }

        public void blitSprite(Object pipeline, Object sprite, int x, int y, int width, int height, int color) {
            Recorder.record("blitSprite", pipeline, sprite, x, y, width, height, color);
        }

        // The alpha twin of the sprite draw, which the helper must not pick.
        public void blitSprite(Object pipeline, Object sprite, int x, int y, int width, int height, float alpha) {
            Recorder.record("wrong blitSprite");
        }
    }

    /** A 1.21.2 to 1.21.5 GuiGraphics: a render type factory, with the tint kept in the shader. */
    public static final class FactoryGraphics {
        public void blit(Function<Object, Object> renderType, Object texture, int x, int y, float u, float v,
                int width, int height, int regionWidth, int regionHeight, int textureWidth, int textureHeight,
                int color) {
            Recorder.record("blit", renderType.apply(texture), texture, x, y, u, v, width, height,
                    regionWidth, regionHeight, textureWidth, textureHeight, color);
        }

        public void blitSprite(Function<Object, Object> renderType, Object sprite, int x, int y, int width,
                int height, int color) {
            Recorder.record("blitSprite", renderType.apply(sprite), sprite, x, y, width, height, color);
        }

        public void flush() {
            Recorder.record("flush");
        }
    }

    /** Stands in for {@code RenderPipelines}. */
    public static final class StubRenderPipelines {
        public static final Object GUI_TEXTURED = "gui_textured";
    }

    /** Stands in for {@code RenderType} on 1.21.2 to 1.21.5. */
    public static final class StubRenderType {
        public static Object guiTextured(Object texture) {
            return "textured:" + texture;
        }
    }

    /** Stands in for {@code RenderSystem} on 1.21.2 to 1.21.5. */
    public static final class StubRenderSystem {
        public static void setShaderColor(float red, float green, float blue, float alpha) {
            Recorder.record("setShaderColor", red, green, blue, alpha);
        }
    }

    /** Stands in for {@code Minecraft}, whose render buffers hold the main buffer source. */
    public static final class StubMinecraft {
        public static StubMinecraft getInstance() {
            return new StubMinecraft();
        }

        public StubRenderBuffers renderBuffers() {
            return new StubRenderBuffers();
        }
    }

    /** Stands in for {@code RenderBuffers}. */
    public static final class StubRenderBuffers {
        public Object bufferSource() {
            return "main buffer source";
        }
    }
}
