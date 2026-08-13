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
    private static final String MOUSE_EVENT = "net/minecraft/client/input/MouseButtonEvent";
    private static final String MOUSE_INFO = "net/minecraft/client/input/MouseButtonInfo";
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

    @Test
    void transformedMouseSuperCallConstructsEventAndPreservesCoordinatesAndButton()
            throws Exception {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        transformer.registerClassRedirect(OLD_SCREEN, SCREEN);

        byte[] transformed = transformer.transformClass(
                legacyMouseScreen(OLD_SCREEN), LEGACY_SCREEN + ".class");
        ClassNode output = read(transformed);
        var method = output.methods.stream()
                .filter(candidate -> "mouseClicked".equals(candidate.name))
                .findFirst().orElseThrow();
        var calls = java.util.Arrays.stream(method.instructions.toArray())
                .filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast)
                .toList();

        assertFalse(calls.stream().anyMatch(call -> SCREEN.equals(call.owner)
                && "mouseClicked".equals(call.name) && "(DDI)Z".equals(call.desc)));
        assertTrue(calls.stream().anyMatch(call -> call.getOpcode() == Opcodes.INVOKESPECIAL
                && MOUSE_INFO.equals(call.owner) && "<init>".equals(call.name)
                && "(II)V".equals(call.desc)));
        assertTrue(calls.stream().anyMatch(call -> call.getOpcode() == Opcodes.INVOKESPECIAL
                && MOUSE_EVENT.equals(call.owner) && "<init>".equals(call.name)
                && ("(DDL" + MOUSE_INFO + ";)V").equals(call.desc)));
        assertTrue(calls.stream().anyMatch(call -> call.getOpcode() == Opcodes.INVOKESPECIAL
                && SCREEN.equals(call.owner) && "mouseClicked".equals(call.name)
                && ("(L" + MOUSE_EVENT + ";Z)Z").equals(call.desc)));

        Map<String, byte[]> definitions = new HashMap<>();
        definitions.put(MOUSE_INFO.replace('/', '.'), mouseButtonInfo());
        definitions.put(MOUSE_EVENT.replace('/', '.'), mouseButtonEvent());
        definitions.put(SCREEN.replace('/', '.'), currentScreen());
        definitions.put(LEGACY_SCREEN.replace('/', '.'), transformed);
        ClassLoader loader = new ByteArrayClassLoader(definitions);
        Class<?> screenClass = Class.forName(LEGACY_SCREEN.replace('/', '.'), true, loader);
        Object screen = screenClass.getConstructor().newInstance();
        Method legacyEntry = screenClass.getMethod(
                "mouseClicked", double.class, double.class, int.class);

        assertEquals(Boolean.TRUE, legacyEntry.invoke(screen, 12.5d, 19.75d, 2));
        Class<?> hostScreen = Class.forName(SCREEN.replace('/', '.'), false, loader);
        assertEquals(12.5d, hostScreen.getField("lastX").getDouble(null));
        assertEquals(19.75d, hostScreen.getField("lastY").getDouble(null));
        assertEquals(2, hostScreen.getField("lastButton").getInt(null));
        assertEquals(0, hostScreen.getField("lastMouseModifiers").getInt(null));
        assertFalse(hostScreen.getField("lastDoubleClick").getBoolean(null));
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
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "lastX", "D", null, null)
                .visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "lastY", "D", null, null)
                .visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "lastButton", "I", null, null)
                .visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "lastMouseModifiers", "I", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "lastDoubleClick", "Z", null, null).visitEnd();
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

        MethodVisitor mouse = writer.visitMethod(Opcodes.ACC_PUBLIC, "mouseClicked",
                "(L" + MOUSE_EVENT + ";Z)Z", null, null);
        mouse.visitCode();
        mouse.visitVarInsn(Opcodes.ALOAD, 1);
        mouse.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MOUSE_EVENT, "x", "()D", false);
        mouse.visitFieldInsn(Opcodes.PUTSTATIC, SCREEN, "lastX", "D");
        mouse.visitVarInsn(Opcodes.ALOAD, 1);
        mouse.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MOUSE_EVENT, "y", "()D", false);
        mouse.visitFieldInsn(Opcodes.PUTSTATIC, SCREEN, "lastY", "D");
        mouse.visitVarInsn(Opcodes.ALOAD, 1);
        mouse.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MOUSE_EVENT, "button", "()I", false);
        mouse.visitFieldInsn(Opcodes.PUTSTATIC, SCREEN, "lastButton", "I");
        mouse.visitVarInsn(Opcodes.ALOAD, 1);
        mouse.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MOUSE_EVENT, "modifiers", "()I", false);
        mouse.visitFieldInsn(Opcodes.PUTSTATIC, SCREEN, "lastMouseModifiers", "I");
        mouse.visitVarInsn(Opcodes.ILOAD, 2);
        mouse.visitFieldInsn(Opcodes.PUTSTATIC, SCREEN, "lastDoubleClick", "Z");
        mouse.visitInsn(Opcodes.ICONST_1);
        mouse.visitInsn(Opcodes.IRETURN);
        mouse.visitMaxs(0, 0);
        mouse.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] mouseButtonInfo() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                MOUSE_INFO, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "button", "I", null, null)
                .visitEnd();
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "modifiers", "I", null, null)
                .visitEnd();
        MethodVisitor constructor = writer.visitMethod(
                Opcodes.ACC_PUBLIC, "<init>", "(II)V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL,
                "java/lang/Object", "<init>", "()V", false);
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitVarInsn(Opcodes.ILOAD, 1);
        constructor.visitFieldInsn(Opcodes.PUTFIELD, MOUSE_INFO, "button", "I");
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitVarInsn(Opcodes.ILOAD, 2);
        constructor.visitFieldInsn(Opcodes.PUTFIELD, MOUSE_INFO, "modifiers", "I");
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        intGetter(writer, MOUSE_INFO, "button");
        intGetter(writer, MOUSE_INFO, "modifiers");
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] mouseButtonEvent() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                MOUSE_EVENT, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "x", "D", null, null)
                .visitEnd();
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "y", "D", null, null)
                .visitEnd();
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                "buttonInfo", "L" + MOUSE_INFO + ";", null, null).visitEnd();
        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>",
                "(DDL" + MOUSE_INFO + ";)V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL,
                "java/lang/Object", "<init>", "()V", false);
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitVarInsn(Opcodes.DLOAD, 1);
        constructor.visitFieldInsn(Opcodes.PUTFIELD, MOUSE_EVENT, "x", "D");
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitVarInsn(Opcodes.DLOAD, 3);
        constructor.visitFieldInsn(Opcodes.PUTFIELD, MOUSE_EVENT, "y", "D");
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitVarInsn(Opcodes.ALOAD, 5);
        constructor.visitFieldInsn(Opcodes.PUTFIELD,
                MOUSE_EVENT, "buttonInfo", "L" + MOUSE_INFO + ";");
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        doubleGetter(writer, "x");
        doubleGetter(writer, "y");
        delegatedMouseIntGetter(writer, "button");
        delegatedMouseIntGetter(writer, "modifiers");
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

    private static byte[] legacyMouseScreen(String screenOwner) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC,
                LEGACY_SCREEN, null, screenOwner, null);
        noArgConstructor(writer, screenOwner);
        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC, "mouseClicked", "(DDI)Z", null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitVarInsn(Opcodes.DLOAD, 1);
        method.visitVarInsn(Opcodes.DLOAD, 3);
        method.visitVarInsn(Opcodes.ILOAD, 5);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL,
                screenOwner, "mouseClicked", "(DDI)Z", false);
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
        intGetter(writer, KEY_EVENT, name);
    }

    private static void intGetter(ClassWriter writer, String owner, String name) {
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, name, "()I", null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitFieldInsn(Opcodes.GETFIELD, owner, name, "I");
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void doubleGetter(ClassWriter writer, String name) {
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, name, "()D", null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitFieldInsn(Opcodes.GETFIELD, MOUSE_EVENT, name, "D");
        method.visitInsn(Opcodes.DRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static void delegatedMouseIntGetter(ClassWriter writer, String name) {
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, name, "()I", null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitFieldInsn(Opcodes.GETFIELD,
                MOUSE_EVENT, "buttonInfo", "L" + MOUSE_INFO + ";");
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MOUSE_INFO, name, "()I", false);
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
