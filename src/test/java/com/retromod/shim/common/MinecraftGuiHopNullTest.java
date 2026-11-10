/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.objectweb.asm.Opcodes.*;

/** A field hop through {@code Minecraft.gui} reads null and drops writes while gui is unset; each case runs in its own loader, so the JVM verifies the frames. */
class MinecraftGuiHopNullTest {

    private static final String MC = "net/minecraft/client/Minecraft";
    private static final String GUI = "net/minecraft/client/gui/Gui";
    private static final String SCREEN = "net/minecraft/client/gui/screens/Screen";
    private static final String OVERLAY = "net/minecraft/client/gui/screens/Overlay";
    private static final String HOLDER = "test/ScreenHolder";
    private static final String CALLER = "test/Caller";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();

    @BeforeEach
    void setUp() {
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("Minecraft.screen reads null while Minecraft.gui is unset, and the gui's screen once it is set")
    void screenReadWithoutGui() throws Throwable {
        Mc26_1To26_2CoreMoves.register(transformer);
        FakeClient client = new FakeClient(caller(mv -> {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(GETFIELD, MC, "screen", "L" + SCREEN + ";");
            mv.visitInsn(ARETURN);
        }, "(L" + MC + ";)L" + SCREEN + ";"));

        Object minecraft = client.minecraft(null);
        assertNull(client.call(minecraft), "a read before the gui exists gives the old field's null");

        Object gui = client.gui();
        Object screen = client.screen();
        client.type(GUI).getMethod("setScreen", client.type(SCREEN)).invoke(gui, screen);
        assertSame(screen, client.call(client.minecraft(gui)), "a read with a gui reaches Gui.screen()");
    }

    @Test
    @DisplayName("A Minecraft.screen write does nothing while Minecraft.gui is unset, and reaches Gui.setScreen once it is set")
    void screenWriteWithoutGui() throws Throwable {
        Mc26_1To26_2CoreMoves.register(transformer);
        FakeClient client = new FakeClient(caller(mv -> {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitFieldInsn(PUTFIELD, MC, "screen", "L" + SCREEN + ";");
            mv.visitInsn(RETURN);
        }, "(L" + MC + ";L" + SCREEN + ";)V"));

        Object screen = client.screen();
        assertDoesNotThrow(() -> client.call(client.minecraft(null), screen),
                "a write before the gui exists has nowhere to go and is dropped");

        Object gui = client.gui();
        client.call(client.minecraft(gui), screen);
        assertSame(screen, client.type(GUI).getMethod("screen").invoke(gui), "a write with a gui reaches Gui.setScreen");
    }

    @Test
    @DisplayName("A hopped read inside constructor arguments still verifies and gives null")
    void screenReadAsConstructorArgument() throws Throwable {
        Mc26_1To26_2CoreMoves.register(transformer);
        FakeClient client = new FakeClient(caller(mv -> {
            // new ScreenHolder(flag ? mc.screen : null): the hop's branch sits beside an uninitialized object.
            mv.visitTypeInsn(NEW, HOLDER);
            mv.visitInsn(DUP);
            org.objectweb.asm.Label none = new org.objectweb.asm.Label();
            org.objectweb.asm.Label join = new org.objectweb.asm.Label();
            mv.visitVarInsn(ILOAD, 1);
            mv.visitJumpInsn(IFEQ, none);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(GETFIELD, MC, "screen", "L" + SCREEN + ";");
            mv.visitJumpInsn(GOTO, join);
            mv.visitLabel(none);
            mv.visitInsn(ACONST_NULL);
            mv.visitLabel(join);
            mv.visitMethodInsn(INVOKESPECIAL, HOLDER, "<init>", "(L" + SCREEN + ";)V", false);
            mv.visitInsn(ARETURN);
        }, "(L" + MC + ";Z)Ljava/lang/Object;"));

        Object holder = client.call(client.minecraft(null), true);
        assertNull(client.type(HOLDER).getField("screen").get(holder));
    }

    @Test
    @DisplayName("A primitive hopped field reads 0 or false and ignores a write while its sibling is null")
    void primitiveHopDefaults() throws Throwable {
        transformer.registerFieldHopAccessor(MC, "ticks", "gui", "L" + GUI + ";",
                "ticks", "()I", "setTicks", "(I)V");
        transformer.registerFieldHopAccessor(MC, "paused", "gui", "L" + GUI + ";",
                "paused", "()Z", null, null);
        FakeClient reads = new FakeClient(caller(mv -> {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(GETFIELD, MC, "ticks", "I");
            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(GETFIELD, MC, "paused", "Z");
            mv.visitInsn(IADD);
            mv.visitInsn(IRETURN);
        }, "(L" + MC + ";)I"));
        assertEquals(0, reads.call(reads.minecraft(null)));
        Object gui = reads.gui();
        reads.type(GUI).getMethod("setTicks", int.class).invoke(gui, 41);
        assertEquals(42, reads.call(reads.minecraft(gui)), "41 ticks plus a paused flag that is always true");

        FakeClient writes = new FakeClient(caller(mv -> {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ILOAD, 1);
            mv.visitFieldInsn(PUTFIELD, MC, "ticks", "I");
            mv.visitInsn(RETURN);
        }, "(L" + MC + ";I)V"));
        assertDoesNotThrow(() -> writes.call(writes.minecraft(null), 7));
        Object other = writes.gui();
        writes.call(writes.minecraft(other), 7);
        assertEquals(7, writes.type(GUI).getMethod("ticks").invoke(other));
    }

    @Test
    @DisplayName("Minecraft.getOverlay and setOverlay keep working while Minecraft.gui is unset")
    void overlayForwardersWithoutGui() throws Throwable {
        Mc26_1To26_2CoreMoves.register(transformer);
        String hop = "com/retromod/generated/MinecraftToGuiHop";
        FakeClient client = new FakeClient(Map.of(hop, transformer.getSyntheticClasses().get(hop)));
        Class<?> forwarder = client.type(hop);
        Method get = forwarder.getMethod("getOverlay", client.type(MC));
        Method set = forwarder.getMethod("setOverlay", client.type(MC), client.type(OVERLAY));

        assertNull(get.invoke(null, client.minecraft(null)));
        assertDoesNotThrow(() -> set.invoke(null, client.minecraft(null), null));

        Object gui = client.gui();
        Object overlay = client.type(OVERLAY).getConstructor().newInstance();
        set.invoke(null, client.minecraft(gui), overlay);
        assertSame(overlay, get.invoke(null, client.minecraft(gui)));
    }

    /** {@code test/Caller.call} with the given body, transformed the way a mod's class is. */
    private Map<String, byte[]> caller(Consumer<MethodVisitor> body, String descriptor) {
        // Frames as javac would write them; no merge needs a common superclass beyond null.
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(V17, ACC_PUBLIC, CALLER, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "call", descriptor, null, null);
        mv.visitCode();
        body.accept(mv);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return Map.of(CALLER, transformer.transformClass(cw.toByteArray(), CALLER + ".class"));
    }

    /** A 26.2 client's shape: Minecraft with a public gui field that starts out null, and the Gui accessors. */
    private static final class FakeClient {
        private final ClassLoader loader;

        FakeClient(Map<String, byte[]> extra) {
            Map<String, byte[]> classes = new HashMap<>(extra);
            classes.put(SCREEN, plainClass(SCREEN));
            classes.put(OVERLAY, plainClass(OVERLAY));
            classes.put(MC, minecraftClass());
            classes.put(GUI, guiClass());
            classes.put(HOLDER, holderClass());
            loader = new ClassLoader(ClassLoader.getPlatformClassLoader()) {
                @Override
                protected Class<?> findClass(String name) throws ClassNotFoundException {
                    byte[] bytes = classes.get(name.replace('.', '/'));
                    if (bytes == null) throw new ClassNotFoundException(name);
                    return defineClass(name, bytes, 0, bytes.length);
                }
            };
        }

        Class<?> type(String internalName) throws ClassNotFoundException {
            return Class.forName(internalName.replace('/', '.'), true, loader);
        }

        Object minecraft(Object gui) throws Exception {
            Object minecraft = type(MC).getConstructor().newInstance();
            type(MC).getField("gui").set(minecraft, gui);
            return minecraft;
        }

        Object gui() throws Exception {
            return type(GUI).getConstructor().newInstance();
        }

        Object screen() throws Exception {
            return type(SCREEN).getConstructor().newInstance();
        }

        Object call(Object... arguments) throws Throwable {
            Class<?> caller = type(CALLER);
            Method call = null;
            for (Method method : caller.getMethods()) {
                if (method.getName().equals("call")) call = method;
            }
            try {
                return call.invoke(null, arguments);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }

        private static byte[] plainClass(String name) {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(V17, ACC_PUBLIC, name, null, "java/lang/Object", null);
            constructor(cw, "java/lang/Object");
            cw.visitEnd();
            return cw.toByteArray();
        }

        private static byte[] minecraftClass() {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(V17, ACC_PUBLIC, MC, null, "java/lang/Object", null);
            cw.visitField(ACC_PUBLIC, "gui", "L" + GUI + ";", null, null).visitEnd();
            constructor(cw, "java/lang/Object");
            cw.visitEnd();
            return cw.toByteArray();
        }

        private static byte[] guiClass() {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(V17, ACC_PUBLIC, GUI, null, "java/lang/Object", null);
            constructor(cw, "java/lang/Object");
            property(cw, "screen", "L" + SCREEN + ";", "screen", "setScreen");
            property(cw, "overlay", "L" + OVERLAY + ";", "overlay", "setOverlay");
            property(cw, "ticks", "I", "ticks", "setTicks");
            MethodVisitor paused = cw.visitMethod(ACC_PUBLIC, "paused", "()Z", null, null);
            paused.visitCode();
            paused.visitInsn(ICONST_1);
            paused.visitInsn(IRETURN);
            paused.visitMaxs(0, 0);
            paused.visitEnd();
            cw.visitEnd();
            return cw.toByteArray();
        }

        private static byte[] holderClass() {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(V17, ACC_PUBLIC, HOLDER, null, "java/lang/Object", null);
            cw.visitField(ACC_PUBLIC, "screen", "L" + SCREEN + ";", null, null).visitEnd();
            MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "(L" + SCREEN + ";)V", null, null);
            init.visitCode();
            init.visitVarInsn(ALOAD, 0);
            init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            init.visitVarInsn(ALOAD, 0);
            init.visitVarInsn(ALOAD, 1);
            init.visitFieldInsn(PUTFIELD, HOLDER, "screen", "L" + SCREEN + ";");
            init.visitInsn(RETURN);
            init.visitMaxs(0, 0);
            init.visitEnd();
            cw.visitEnd();
            return cw.toByteArray();
        }

        private static void constructor(ClassWriter cw, String superName) {
            MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
            init.visitCode();
            init.visitVarInsn(ALOAD, 0);
            init.visitMethodInsn(INVOKESPECIAL, superName, "<init>", "()V", false);
            init.visitInsn(RETURN);
            init.visitMaxs(0, 0);
            init.visitEnd();
        }

        /** A private field with a public getter and setter, as 26.2's Gui keeps its screen. */
        private static void property(ClassWriter cw, String field, String descriptor, String getter, String setter) {
            cw.visitField(ACC_PRIVATE, field, descriptor, null, null).visitEnd();
            org.objectweb.asm.Type type = org.objectweb.asm.Type.getType(descriptor);
            MethodVisitor get = cw.visitMethod(ACC_PUBLIC, getter, "()" + descriptor, null, null);
            get.visitCode();
            get.visitVarInsn(ALOAD, 0);
            get.visitFieldInsn(GETFIELD, GUI, field, descriptor);
            get.visitInsn(type.getOpcode(IRETURN));
            get.visitMaxs(0, 0);
            get.visitEnd();
            MethodVisitor set = cw.visitMethod(ACC_PUBLIC, setter, "(" + descriptor + ")V", null, null);
            set.visitCode();
            set.visitVarInsn(ALOAD, 0);
            set.visitVarInsn(type.getOpcode(ILOAD), 1);
            set.visitFieldInsn(PUTFIELD, GUI, field, descriptor);
            set.visitInsn(RETURN);
            set.visitMaxs(0, 0);
            set.visitEnd();
        }
    }
}
