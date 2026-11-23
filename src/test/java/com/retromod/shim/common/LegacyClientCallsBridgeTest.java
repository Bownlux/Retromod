/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.shim.common.embedded.LegacyLog;
import com.retromod.shim.common.embedded.LegacyRenderCalls;
import com.retromod.testsupport.fakemc.cycle.FakeCycleBuilder;
import com.retromod.testsupport.fakemc.cycle.FakeValueList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** Old client calls 26.x removed reach Retromod's helpers or the host's new shape. */
public class LegacyClientCallsBridgeTest {

    private static final String RS = LegacyClientCallsBridge.RENDER_SYSTEM;
    private static final String TARGET = LegacyClientCallsBridge.TEXTURE_TARGET;
    private static final String BUILDER = "L" + LegacyClientCallsBridge.CYCLE_BUILDER + ";";
    private static final String CONSUMER = LegacyClientCallsBridge.BOOLEAN_CONSUMER;
    private static final Path COMPILED = Path.of("target", "legacyapi-classes");

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("render getters, chat characters and the old target calls go to the helpers or the int form")
    void renderGettersAndChatCharacterAreRedirected() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        LegacyClientCallsBridge.registerChatCharacter(t);
        LegacyClientCallsBridge.registerRenderGetters(t);

        List<String> calls = calls(t.transformClass(oldClientCalls(), "test/client/OldCalls"));

        assertTrue(calls.contains("net/minecraft/util/StringUtil.isAllowedChatCharacter(I)Z"), calls.toString());
        assertTrue(calls.contains(LegacyClientCallsBridge.RENDER_CALLS + ".shaderFogStart()F"), calls.toString());
        assertTrue(calls.contains(LegacyClientCallsBridge.RENDER_CALLS + ".shaderColor()[F"), calls.toString());
        assertTrue(calls.contains(LegacyClientCallsBridge.RENDER_CALLS + ".colorTextureId(Ljava/lang/Object;)I"), calls.toString());
        assertTrue(calls.contains(LegacyClientCallsBridge.RENDER_CALLS + ".resize(Ljava/lang/Object;IIZ)V"), calls.toString());
        assertFalse(calls.stream().anyMatch(call -> call.startsWith(RS + ".") || call.startsWith(TARGET + ".")),
                "no removed RenderSystem or TextureTarget call may survive: " + calls);
    }

    @Test
    @DisplayName("the removed RenderSystem and RenderTarget void calls are dropped on 26.x")
    void removedVoidCallsAreDropped() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        RemovedRenderStateNeutralize.register(t);

        List<String> calls = calls(t.transformClass(removedVoidCalls(), "test/client/VoidCalls"));

        assertEquals(List.of(), calls, "clear, bindTexture, the fog range and RenderTarget.clear must all be dropped");
    }

    @Test
    @DisplayName("the old TextureTarget constructor builds through the helper that matches the host")
    void textureTargetUsesTheHostShape() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        LegacyClientCallsBridge.registerTextureTarget(t, LegacyClientCallsBridge.TargetShape.FORMATS);

        List<String> calls = calls(t.transformClass(newTextureTarget(), "test/client/NewTarget"));

        assertTrue(calls.contains(LegacyClientCallsBridge.FORMAT_TARGETS + ".create(IIZZ)L" + TARGET + ";"), calls.toString());
        assertFalse(calls.contains(TARGET + ".<init>(IIZZ)V"), calls.toString());
    }

    @Test
    @DisplayName("on 26.2 the old TextureTarget constructor builds through the color format helper")
    void textureTargetOn26_2UsesTheColorFormatHelper() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        LegacyClientCallsBridge.registerTextureTarget(t, LegacyClientCallsBridge.TargetShape.COLOR_FORMAT);

        List<String> calls = calls(t.transformClass(newTextureTarget(), "test/client/NewTarget"));

        assertTrue(calls.contains(LegacyClientCallsBridge.COLOR_FORMAT_TARGETS + ".create(IIZZ)L" + TARGET + ";"), calls.toString());
        assertFalse(calls.contains(TARGET + ".<init>(IIZZ)V"), calls.toString());
    }

    @Test
    @DisplayName("a link the old screen took as a string becomes a URI, quoted when a plain parse refuses it")
    void linkStringsBecomeUris() throws Exception {
        Method uri = rebuilt(Map.of(), "com/retromod/legacyapi/minecraft/client/gui/LegacyLinkScreens")
                .getDeclaredMethod("uri", String.class);
        uri.setAccessible(true);

        assertEquals(URI.create("https://example.com/wiki?page=1"), uri.invoke(null, "https://example.com/wiki?page=1"));
        URI spaced = (URI) uri.invoke(null, "https://example.com/my page#part two");
        assertEquals("https://example.com/my%20page#part%20two", spaced.toASCIIString());
        assertEquals("example.com", spaced.getHost(), "the quoted link keeps its host");
        assertEquals(URI.create("about:blank"), uri.invoke(null, "not a link"), "a string with no scheme cannot be opened");
        assertEquals(URI.create("about:blank"), uri.invoke(null, (Object) null));
    }

    @Test
    @DisplayName("a builder from builder(Function) starts on withInitialValue's value, or on its first value without it")
    @SuppressWarnings("unchecked")
    void cycleBuilderInitialValue() throws Exception {
        Map<Class<?>, String> fakes = Map.of(
                FakeCycleBuilder.class, LegacyClientCallsBridge.CYCLE_BUILDER,
                FakeValueList.class, LegacyClientCallsBridge.CYCLE_BUTTON + "$ValueListSupplier");
        Class<?> helper = rebuilt(fakes, LegacyClientCallsBridge.CYCLE_BUTTONS);
        Method builder = helper.getMethod("builder", Function.class);
        Method withInitialValue = null;
        for (Method method : helper.getMethods()) if (method.getName().equals("withInitialValue")) withInitialValue = method;

        Object unset = builder.invoke(null, (Function<Object, Object>) value -> value);
        unset.getClass().getMethod("withValues", List.class).invoke(unset, List.of("low", "high"));
        assertEquals("low", unset.getClass().getMethod("create").invoke(unset),
                "the old builder started on its first value when withInitialValue was never called");

        Object set = builder.invoke(null, (Function<Object, Object>) value -> value);
        set.getClass().getMethod("withValues", List.class).invoke(set, List.of("low", "high"));
        withInitialValue.invoke(null, set, "high");
        assertEquals("high", set.getClass().getMethod("create").invoke(set));

        Object empty = builder.invoke(null, (Function<Object, Object>) value -> value);
        assertNull(empty.getClass().getMethod("create").invoke(empty), "a builder with no values has no first value");
    }

    @Test
    @DisplayName("CycleButton.builder(Function) and withInitialValue go to the rebuilt builder steps")
    void cycleButtonBuilderIsBridged() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        LegacyClientCallsBridge.registerCycleButton(t);

        List<String> calls = calls(t.transformClass(oldCycleBuilder(), "test/client/OldCycle"));

        assertTrue(calls.contains(LegacyClientCallsBridge.CYCLE_BUTTONS + ".builder(Ljava/util/function/Function;)" + BUILDER), calls.toString());
        assertTrue(calls.contains(LegacyClientCallsBridge.CYCLE_BUTTONS + ".withInitialValue(" + BUILDER + "Ljava/lang/Object;)" + BUILDER),
                calls.toString());
    }

    @Test
    @DisplayName("each host shape picks the right TextureTarget helper, and a host with the old constructor gets none")
    void textureTargetShapeFollowsTheHost() {
        assertEquals(LegacyClientCallsBridge.TargetShape.NAMED,
                LegacyClientCallsBridge.textureTargetShape(withConstructors(LegacyClientCallsBridge.NAMED_TARGET_CTOR), true));
        assertEquals(LegacyClientCallsBridge.TargetShape.COLOR_FORMAT,
                LegacyClientCallsBridge.textureTargetShape(withConstructors(LegacyClientCallsBridge.COLOR_FORMAT_TARGET_CTOR), true));
        assertEquals(LegacyClientCallsBridge.TargetShape.FORMATS,
                LegacyClientCallsBridge.textureTargetShape(withConstructors(LegacyClientCallsBridge.FORMAT_TARGET_CTOR), true));
        assertEquals(LegacyClientCallsBridge.TargetShape.NONE, LegacyClientCallsBridge.textureTargetShape(
                withConstructors(LegacyClientCallsBridge.OLD_TARGET_CTOR, LegacyClientCallsBridge.NAMED_TARGET_CTOR), true));
        assertEquals(LegacyClientCallsBridge.TargetShape.NONE, LegacyClientCallsBridge.textureTargetShape(null, true));
    }

    @Test
    @DisplayName("the static String link helpers reach the rebuilt ones that parse the link")
    void staticLinkHelpersAreBridged() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        LegacyClientCallsBridge.registerConfirmLink(t);
        String screen = "Lnet/minecraft/client/gui/screens/Screen;Ljava/lang/String;";
        String onPress = "Lnet/minecraft/client/gui/components/Button$OnPress;";

        List<String> calls = calls(t.transformClass(method("test/client/OldLinks", mv -> {
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitLdcInsn("https://example.com");
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, LegacyClientCallsBridge.CONFIRM_LINK, "confirmLink", "(" + screen + ")" + onPress, false);
            mv.visitInsn(Opcodes.POP);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitLdcInsn("https://example.com");
            mv.visitInsn(Opcodes.ICONST_1);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, LegacyClientCallsBridge.CONFIRM_LINK, "confirmLinkNow", "(" + screen + "Z)V", false);
        }), "test/client/OldLinks"));

        assertTrue(calls.contains(LegacyClientCallsBridge.LINK_SCREENS + ".confirmLink(" + screen + ")" + onPress), calls.toString());
        assertTrue(calls.contains(LegacyClientCallsBridge.LINK_SCREENS + ".confirmLinkNow(" + screen + "Z)V"), calls.toString());
    }

    @Test
    @DisplayName("ConfirmLinkScreen is bridged only on a host that takes a URI and lost the String form")
    void linkScreenNeedsTheUriShape() {
        String uri = "(" + CONSUMER + "Ljava/net/URI;Z)V";
        String titledUri = "(" + CONSUMER + LegacyClientCallsBridge.COMPONENT + "Ljava/net/URI;Z)V";
        String string = "(" + CONSUMER + "Ljava/lang/String;Z)V";
        assertTrue(LegacyClientCallsBridge.linkScreenTakesUri(withConstructors(uri, titledUri), true));
        assertFalse(LegacyClientCallsBridge.linkScreenTakesUri(withConstructors(string, uri, titledUri), true));
        assertFalse(LegacyClientCallsBridge.linkScreenTakesUri(null, true));
    }

    @Test
    @DisplayName("the render helper reads the GL id through the texture and resizes with two arguments")
    void renderHelperWorksOnStandIns() {
        StandInTarget target = new StandInTarget();

        assertEquals(42, LegacyRenderCalls.colorTextureId(target));
        assertEquals(-1, LegacyRenderCalls.depthTextureId(target), "a texture without a GL id answers -1");
        LegacyRenderCalls.resize(target, 640, 480, true);
        assertEquals("640x480", target.size);
        assertArrayEquals(new float[] {1, 1, 1, 1}, LegacyRenderCalls.shaderColor());
        boolean[] ran = {false};
        LegacyRenderCalls.runAsFancy(() -> ran[0] = true);
        assertTrue(ran[0], "runAsFancy must still run its task");
    }

    /** A render target as 26.x shapes it: textures behind getters, a two-argument resize. */
    public static final class StandInTarget {
        public String size = "";

        public StandInTexture getColorTexture() {
            return new StandInTexture();
        }

        public Object getDepthTexture() {
            return new Object();
        }

        public void resize(int width, int height) {
            size = width + "x" + height;
        }
    }

    /** An OpenGL texture with its id. */
    public static final class StandInTexture {
        public int glId() {
            return 42;
        }
    }

    private static byte[] oldClientCalls() {
        return method("test/client/OldCalls", mv -> {
            mv.visitIntInsn(Opcodes.BIPUSH, 'a');
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/util/StringUtil", "isAllowedChatCharacter", "(C)Z", false);
            mv.visitInsn(Opcodes.POP);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, RS, "getShaderFogStart", "()F", false);
            mv.visitInsn(Opcodes.POP);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, RS, "getShaderColor", "()[F", false);
            mv.visitInsn(Opcodes.POP);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, TARGET, "getColorTextureId", "()I", false);
            mv.visitInsn(Opcodes.POP);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitInsn(Opcodes.ICONST_1);
            mv.visitInsn(Opcodes.ICONST_1);
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, TARGET, "resize", "(IIZ)V", false);
        });
    }

    private static byte[] removedVoidCalls() {
        return method("test/client/VoidCalls", mv -> {
            mv.visitIntInsn(Opcodes.SIPUSH, 16384);
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, RS, "clear", "(IZ)V", false);
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, RS, "bindTexture", "(I)V", false);
            mv.visitInsn(Opcodes.FCONST_0);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, RS, "setShaderFogStart", "(F)V", false);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/mojang/blaze3d/pipeline/RenderTarget", "clear", "(Z)V", false);
        });
    }

    private static byte[] newTextureTarget() {
        return method("test/client/NewTarget", mv -> {
            mv.visitTypeInsn(Opcodes.NEW, TARGET);
            mv.visitInsn(Opcodes.DUP);
            mv.visitIntInsn(Opcodes.BIPUSH, 16);
            mv.visitIntInsn(Opcodes.BIPUSH, 16);
            mv.visitInsn(Opcodes.ICONST_1);
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, TARGET, "<init>", "(IIZZ)V", false);
            mv.visitInsn(Opcodes.POP);
        });
    }

    private static byte[] oldCycleBuilder() {
        return method("test/client/OldCycle", mv -> {
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, LegacyClientCallsBridge.CYCLE_BUTTON, "builder",
                    "(Ljava/util/function/Function;)" + BUILDER, false);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LegacyClientCallsBridge.CYCLE_BUILDER, "withInitialValue",
                    "(Ljava/lang/Object;)" + BUILDER, false);
            mv.visitInsn(Opcodes.POP);
        });
    }

    /** A class with one static void method whose body {@code body} writes. */
    private static byte[] method(String name, java.util.function.Consumer<MethodVisitor> body) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
        mv.visitCode();
        body.accept(mv);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(8, 0);
        mv.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** Loads a compiled rebuilt class beside the given stand-ins, renamed to the host classes they stand for. */
    private static Class<?> rebuilt(Map<Class<?>, String> fakes, String name) throws IOException, ClassNotFoundException {
        Map<String, String> rename = new HashMap<>();
        fakes.forEach((fake, host) -> rename.put(fake.getName().replace('.', '/'), host));
        SimpleRemapper remapper = new SimpleRemapper(rename);
        Map<String, byte[]> classes = new HashMap<>();
        for (Map.Entry<Class<?>, String> fake : fakes.entrySet()) {
            ClassWriter writer = new ClassWriter(0);
            new ClassReader(bytesOf(fake.getKey())).accept(new ClassRemapper(writer, remapper), 0);
            classes.put(fake.getValue().replace('/', '.'), writer.toByteArray());
        }
        classes.put(name.replace('/', '.'), Files.readAllBytes(COMPILED.resolve(name + ".class")));
        // The rebuilt class's own nested classes come along.
        try (var nested = Files.list(COMPILED.resolve(name).getParent())) {
            String simple = name.substring(name.lastIndexOf('/') + 1) + "$";
            for (Path path : nested.filter(p -> p.getFileName().toString().startsWith(simple)).toList()) {
                String file = path.getFileName().toString();
                classes.put(name.substring(0, name.lastIndexOf('/') + 1).replace('/', '.') + file.substring(0, file.length() - 6),
                        Files.readAllBytes(path));
            }
        }
        classes.put(LegacyLog.class.getName(), bytesOf(LegacyLog.class));
        ClassLoader loader = new ClassLoader(LegacyClientCallsBridgeTest.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String className, boolean resolve) throws ClassNotFoundException {
                synchronized (getClassLoadingLock(className)) {
                    Class<?> loaded = findLoadedClass(className);
                    if (loaded != null) return loaded;
                    byte[] bytes = classes.get(className);
                    if (bytes != null) return defineClass(className, bytes, 0, bytes.length);
                    // Host types the signatures name come from the compile-only stubs built beside the rebuilt classes.
                    Path stub = COMPILED.resolve(className.replace('.', '/') + ".class");
                    if (!className.startsWith("java.") && Files.exists(stub)) {
                        try {
                            byte[] stubBytes = Files.readAllBytes(stub);
                            return defineClass(className, stubBytes, 0, stubBytes.length);
                        } catch (IOException unreadable) {
                            throw new ClassNotFoundException(className, unreadable);
                        }
                    }
                    return super.loadClass(className, resolve);
                }
            }
        };
        return loader.loadClass(name.replace('/', '.'));
    }

    private static byte[] bytesOf(Class<?> type) throws IOException {
        try (InputStream in = type.getResourceAsStream(type.getSimpleName() + ".class")) {
            return in.readAllBytes();
        }
    }

    private static ClassNode withConstructors(String... descriptors) {
        ClassNode node = new ClassNode();
        node.name = "test/Host";
        for (String desc : descriptors) node.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "<init>", desc, null, null));
        return node;
    }

    private static List<String> calls(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        List<String> calls = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name + call.desc);
            }
        }
        return calls;
    }
}
