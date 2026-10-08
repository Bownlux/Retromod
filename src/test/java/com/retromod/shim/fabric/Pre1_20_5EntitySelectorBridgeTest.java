/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * A 1.20.1 mod that builds an {@code EntitySelector} by hand (KubeJS does it for its static "all
 * entities" selector) must keep working on a 1.20.5+ Fabric host, where the predicate argument
 * became a list of predicates.
 */
class Pre1_20_5EntitySelectorBridgeTest {

    private RetromodTransformer transformer;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("new EntitySelector(..., Predicate, ...) goes through the list factory")
    void oldConstructorIsRedirected() {
        Pre1_20_5EntitySelectorBridge.registerRedirects(transformer);

        byte[] out = transformer.transformClass(oldSelectorUser(), "test/Selectors");

        List<MethodInsnNode> calls = calls(read(out));
        assertTrue(calls.stream().anyMatch(c -> c.owner.equals(Pre1_20_5EntitySelectorBridge.FACTORY)
                        && c.desc.equals(Pre1_20_5EntitySelectorBridge.FACTORY_DESC)),
                "the old constructor call must reach the generated factory");
        assertFalse(calls.stream().anyMatch(c -> c.name.equals("<init>")
                        && c.desc.equals(Pre1_20_5EntitySelectorBridge.OLD_DESC)),
                "no call to the removed constructor may remain");
    }

    @Test
    @DisplayName("A selector built with the 1.20.5 list constructor is left alone")
    void modernConstructorIsLeftAlone() {
        Pre1_20_5EntitySelectorBridge.registerRedirects(transformer);

        byte[] out = transformer.transformClass(selectorUser(Pre1_20_5EntitySelectorBridge.MODERN_DESC),
                "test/Selectors");

        assertTrue(calls(read(out)).stream().noneMatch(
                c -> c.owner.equals(Pre1_20_5EntitySelectorBridge.FACTORY)));
    }

    @Test
    @DisplayName("The factory hands the old predicate to the list constructor and keeps every other argument")
    void factoryWrapsThePredicateInAList() throws Exception {
        StubLoader loader = new StubLoader();
        loader.add(Pre1_20_5EntitySelectorBridge.ENTITY_SELECTOR, recordingSelector());
        loader.add(Pre1_20_5EntitySelectorBridge.FACTORY, Pre1_20_5EntitySelectorBridge.generateFactory());
        // Reflection resolves every parameter type, so the selector's other argument types exist too.
        for (String argumentType : List.of("net/minecraft/class_2096$class_2099", "net/minecraft/class_238",
                "net/minecraft/class_1299")) {
            loader.add(argumentType, emptyClass(argumentType));
        }
        Class<?> factory = loader.loadClass(Pre1_20_5EntitySelectorBridge.FACTORY.replace('/', '.'));

        Predicate<Object> onlyPlayers = entity -> false;
        Object[] arguments = defaultArguments();
        arguments[0] = 7;
        arguments[3] = onlyPlayers;
        arguments[9] = "Steve";
        Object selector = factory.getMethods()[indexOf(factory, Pre1_20_5EntitySelectorBridge.FACTORY_METHOD)]
                .invoke(null, arguments);

        assertEquals(List.of(onlyPlayers), selector.getClass().getField("predicates").get(selector));
        assertEquals(7, selector.getClass().getField("maxResults").get(selector));
        assertEquals("Steve", selector.getClass().getField("playerName").get(selector));
    }

    // ---- fixtures ------------------------------------------------------------------------

    private static byte[] oldSelectorUser() {
        return selectorUser(Pre1_20_5EntitySelectorBridge.OLD_DESC);
    }

    /** {@code static Object make() { return new EntitySelector(<defaults for desc>); }} */
    private static byte[] selectorUser(String constructorDesc) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/Selectors", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "make", "()Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitTypeInsn(NEW, Pre1_20_5EntitySelectorBridge.ENTITY_SELECTOR);
        mv.visitInsn(DUP);
        for (Type argument : Type.getArgumentTypes(constructorDesc)) {
            mv.visitInsn(argument.getSort() == Type.OBJECT ? ACONST_NULL : ICONST_0);
        }
        mv.visitMethodInsn(INVOKESPECIAL, Pre1_20_5EntitySelectorBridge.ENTITY_SELECTOR, "<init>",
                constructorDesc, false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A host stand-in whose list constructor records the arguments the selector would keep. */
    private static byte[] recordingSelector() {
        String owner = Pre1_20_5EntitySelectorBridge.ENTITY_SELECTOR;
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, owner, null, "java/lang/Object", null);
        cw.visitField(ACC_PUBLIC, "maxResults", "I", null, null).visitEnd();
        cw.visitField(ACC_PUBLIC, "predicates", "Ljava/util/List;", null, null).visitEnd();
        cw.visitField(ACC_PUBLIC, "playerName", "Ljava/lang/String;", null, null).visitEnd();
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", Pre1_20_5EntitySelectorBridge.MODERN_DESC, null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ILOAD, 1);
        mv.visitFieldInsn(PUTFIELD, owner, "maxResults", "I");
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 4);
        mv.visitFieldInsn(PUTFIELD, owner, "predicates", "Ljava/util/List;");
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 10);
        mv.visitFieldInsn(PUTFIELD, owner, "playerName", "Ljava/lang/String;");
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] emptyClass(String internalName) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static Object[] defaultArguments() {
        Type[] types = Type.getArgumentTypes(Pre1_20_5EntitySelectorBridge.OLD_DESC);
        Object[] values = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            values[i] = switch (types[i].getSort()) {
                case Type.INT -> 0;
                case Type.BOOLEAN -> false;
                default -> null;
            };
        }
        return values;
    }

    private static int indexOf(Class<?> type, String methodName) {
        for (int i = 0; i < type.getMethods().length; i++) {
            if (type.getMethods()[i].getName().equals(methodName)) return i;
        }
        throw new AssertionError("no method " + methodName + " on " + type.getName());
    }

    private static final class StubLoader extends ClassLoader {
        private final java.util.Map<String, byte[]> classes = new java.util.HashMap<>();

        StubLoader() {
            super(Pre1_20_5EntitySelectorBridgeTest.class.getClassLoader());
        }

        void add(String internalName, byte[] bytes) {
            classes.put(internalName.replace('/', '.'), bytes);
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = classes.get(name);
            if (bytes == null) throw new ClassNotFoundException(name);
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static List<MethodInsnNode> calls(ClassNode node) {
        List<MethodInsnNode> calls = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call) calls.add(call);
            }
        }
        return calls;
    }
}
