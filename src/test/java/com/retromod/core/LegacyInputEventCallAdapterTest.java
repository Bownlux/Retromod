/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end regression for the 26.x primitive-key-input call-site adapter. */
class LegacyInputEventCallAdapterTest {

    private static final String KEY_EVENT = "net/minecraft/client/input/KeyEvent";
    private static final String SCREEN = "net/minecraft/client/gui/screens/Screen";
    private static final String OLD_SCREEN = "net/minecraft/client/gui/screen/Screen";
    private static final String LEGACY_SCREEN = "test/input/LegacyScreen";

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void restoreTransformerState() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    void transformedSuperCallConstructsKeyEventAndExecutesWithInvokespecial() throws Exception {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        transformer.registerClassRedirect(OLD_SCREEN, SCREEN);

        byte[] transformed = transformer.transformClass(
                legacyScreen(OLD_SCREEN), LEGACY_SCREEN + ".class");
        ClassNode output = read(transformed);
        var method = output.methods.stream()
                .filter(candidate -> "keyPressed".equals(candidate.name))
                .findFirst().orElseThrow();
        var calls = java.util.Arrays.stream(method.instructions.toArray())
                .filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast)
                .toList();

        assertFalse(calls.stream().anyMatch(call -> SCREEN.equals(call.owner)
                && "keyPressed".equals(call.name) && "(III)Z".equals(call.desc)),
                "the removed primitive Screen call must not survive");
        assertTrue(calls.stream().anyMatch(call -> call.getOpcode() == Opcodes.INVOKESPECIAL
                && KEY_EVENT.equals(call.owner) && "<init>".equals(call.name)
                && "(III)V".equals(call.desc)),
                "the adapter must construct KeyEvent from all three old operands");
        assertTrue(calls.stream().anyMatch(call -> call.getOpcode() == Opcodes.INVOKESPECIAL
                && SCREEN.equals(call.owner) && "keyPressed".equals(call.name)
                && ("(L" + KEY_EVENT + ";)Z").equals(call.desc)),
                "a source super call must stay INVOKESPECIAL on the direct superclass");

        Map<String, byte[]> definitions = new HashMap<>();
        definitions.put(KEY_EVENT.replace('/', '.'), keyEvent());
        definitions.put(SCREEN.replace('/', '.'), currentScreen());
        definitions.put(LEGACY_SCREEN.replace('/', '.'), transformed);
        ClassLoader loader = new ByteArrayClassLoader(definitions);
        Class<?> screenClass = Class.forName(LEGACY_SCREEN.replace('/', '.'), true, loader);
        Object screen = screenClass.getConstructor().newInstance();
        Method legacyEntry = screenClass.getMethod("keyPressed", int.class, int.class, int.class);

        assertEquals(Boolean.TRUE, legacyEntry.invoke(screen, 17, 29, 5));
        Class<?> hostScreen = Class.forName(SCREEN.replace('/', '.'), false, loader);
        assertEquals(17, hostScreen.getField("lastKey").getInt(null));
        assertEquals(29, hostScreen.getField("lastScanCode").getInt(null));
        assertEquals(5, hostScreen.getField("lastModifiers").getInt(null));
    }

    @Test
    void pre26HostLeavesLegacyCallByteForByteUntouched() {
        RetromodVersion.TARGET_MC_VERSION = "1.21.11";
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        byte[] input = legacyScreen(SCREEN);

        assertArrayEquals(input, transformer.transformClass(input, LEGACY_SCREEN + ".class"));
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static byte[] keyEvent() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                KEY_EVENT, null, "java/lang/Object", null);
        field(writer, "key");
        field(writer, "scancode");
        field(writer, "modifiers");

        MethodVisitor constructor = writer.visitMethod(
                Opcodes.ACC_PUBLIC, "<init>", "(III)V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL,
                "java/lang/Object", "<init>", "()V", false);
        putIntField(constructor, "key", 1);
        putIntField(constructor, "scancode", 2);
        putIntField(constructor, "modifiers", 3);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();

        intGetter(writer, "key");
        intGetter(writer, "scancode");
        intGetter(writer, "modifiers");
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] currentScreen() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, SCREEN, null, "java/lang/Object", null);
        for (String name : new String[]{"lastKey", "lastScanCode", "lastModifiers"}) {
            writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, "I", null, null)
                    .visitEnd();
        }
        noArgConstructor(writer, "java/lang/Object");

        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "keyPressed",
                "(L" + KEY_EVENT + ";)Z", null, null);
        method.visitCode();
        copyEventValue(method, "key", "lastKey");
        copyEventValue(method, "scancode", "lastScanCode");
        copyEventValue(method, "modifiers", "lastModifiers");
        method.visitInsn(Opcodes.ICONST_1);
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] legacyScreen(String screenOwner) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC,
                LEGACY_SCREEN, null, screenOwner, null);
        noArgConstructor(writer, screenOwner);

        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC, "keyPressed", "(III)Z", null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitVarInsn(Opcodes.ILOAD, 1);
        method.visitVarInsn(Opcodes.ILOAD, 2);
        method.visitVarInsn(Opcodes.ILOAD, 3);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL,
                screenOwner, "keyPressed", "(III)Z", false);
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void field(ClassWriter writer, String name) {
        FieldVisitor field = writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                name, "I", null, null);
        field.visitEnd();
    }

    private static void putIntField(MethodVisitor method, String name, int local) {
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitVarInsn(Opcodes.ILOAD, local);
        method.visitFieldInsn(Opcodes.PUTFIELD, KEY_EVENT, name, "I");
    }

    private static void intGetter(ClassWriter writer, String name) {
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, name, "()I", null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitFieldInsn(Opcodes.GETFIELD, KEY_EVENT, name, "I");
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void noArgConstructor(ClassWriter writer, String superName) {
        MethodVisitor constructor = writer.visitMethod(
                Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL,
                superName, "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
    }

    private static void copyEventValue(MethodVisitor method, String getter, String field) {
        method.visitVarInsn(Opcodes.ALOAD, 1);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, KEY_EVENT, getter, "()I", false);
        method.visitFieldInsn(Opcodes.PUTSTATIC, SCREEN, field, "I");
    }

    private static final class ByteArrayClassLoader extends ClassLoader {
        private final Map<String, byte[]> definitions;

        private ByteArrayClassLoader(Map<String, byte[]> definitions) {
            super(LegacyInputEventCallAdapterTest.class.getClassLoader());
            this.definitions = definitions;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = definitions.get(name);
            if (bytes == null) return super.findClass(name);
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
