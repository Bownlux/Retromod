/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * Constructor-to-factory redirects in shapes javac never writes. JEI's build keeps the new object
 * in a local ({@code NEW, DUP, ASTORE}), and the redirect used to leave that store ahead of the
 * factory call, so the class failed with "Operand stack underflow".
 */
class CtorRedirectStoredObjectTest {

    private static final String OLD = "fixture/OldId";
    private static final String ID = Type.getInternalName(Id.class);
    private static final String HOLDER = Type.getInternalName(Holder.class);
    private static final String SHAPES = "fixture/Shapes";
    private static final String L_OLD = "L" + OLD + ";";
    private static final String TWO_STRINGS = "(Ljava/lang/String;Ljava/lang/String;)V";
    private static final String WIDE = "(Ljava/lang/String;JLjava/lang/String;)V";

    /** What the factory builds. Its public constructors serve the calls no redirect covers. */
    public static final class Id {
        static final AtomicInteger FACTORY_CALLS = new AtomicInteger();
        private final String text;

        public Id(String text) { this.text = text; }

        public Id(String namespace, String path) { this(namespace + ":" + path); }

        public static Id of(String namespace, String path) {
            FACTORY_CALLS.incrementAndGet();
            return new Id(namespace + ":" + path);
        }

        public static Id of(String namespace, long number, String path) {
            FACTORY_CALLS.incrementAndGet();
            return new Id(namespace + ":" + number + ":" + path);
        }

        public String path() { return text.substring(text.indexOf(':') + 1); }

        @Override public String toString() { return text; }
    }

    /** A mod's own class that takes an id, as JEI's RecipeType does. */
    public static final class Holder {
        private final String text;

        public Holder(Id id, Object extra) { this.text = id + "|" + extra; }

        public Holder(String prefix, Id id, Object extra) { this.text = prefix + "|" + id + "|" + extra; }

        @Override public String toString() { return text; }
    }

    private RetromodTransformer transformer;
    private Class<?> shapes;

    @BeforeEach
    void setUp() throws Exception {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        // As for ResourceLocation on 26.x: the class moves first, then its constructors become factories.
        transformer.registerClassRedirect(OLD, ID);
        transformer.registerConstructorRedirect(ID, TWO_STRINGS, ID, "of", "(Ljava/lang/String;Ljava/lang/String;)L" + ID + ";");
        transformer.registerConstructorRedirect(ID, WIDE, ID, "of", "(Ljava/lang/String;JLjava/lang/String;)L" + ID + ";");
        byte[] out = transformer.transformClass(shapesClass(), SHAPES + ".class");
        assertNotNull(out);
        assertVerifies(out);
        shapes = new FixtureLoader(getClass().getClassLoader(), out).loadClass(SHAPES.replace('/', '.'));
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("JEI's RecipeType.create: the stored id is built before it is stored")
    void jeiShape() throws Exception {
        assertEquals("jei:recipe|extra", call("jeiShape", "jei", "recipe", "extra"));
        assertFactoryBeforeFirstStore("jeiShape");
    }

    @Test
    @DisplayName("javac's NEW, DUP shape inside another constructor's arguments")
    void javacNested() throws Exception {
        assertEquals("a:b|c", call("javacNested", "a", "b", "c"));
    }

    @Test
    @DisplayName("NEW, ASTORE, ALOAD: the object is stored and loaded back before its arguments")
    void storeThenLoad() throws Exception {
        assertEquals("a:b", call("storeThenLoad", "a", "b"));
    }

    @Test
    @DisplayName("The JEI shape in an instance method")
    void instanceMethod() throws Exception {
        Object instance = shapes.getConstructor().newInstance();
        Method method = shapes.getMethod("jeiInstance", String.class, String.class, Object.class);
        assertEquals("i:j|k", String.valueOf(method.invoke(instance, "i", "j", "k")));
    }

    @Test
    @DisplayName("The JEI shape between other arguments of a plain constructor")
    void argumentsAround() throws Exception {
        assertEquals("p|a:b|c", call("argumentsAround", "p", "a", "b", "c"));
    }

    @Test
    @DisplayName("A redirected constructor in the arguments of another redirected constructor, both stored")
    void pendingOuter() throws Exception {
        int before = Id.FACTORY_CALLS.get();
        assertEquals("b:c", call("pendingOuter", "a", "b", "c"));
        assertEquals(2, Id.FACTORY_CALLS.get() - before, "both constructors go through the factory");
    }

    @Test
    @DisplayName("NEW with no DUP builds an object nobody keeps, on one branch only")
    void discardedOnOnePath() throws Exception {
        int before = Id.FACTORY_CALLS.get();
        Method method = shapes.getMethod("discardedOnOnePath", String.class, String.class, boolean.class);
        assertEquals(7, method.invoke(null, "a", "b", true));
        assertEquals(1, Id.FACTORY_CALLS.get() - before);
        assertEquals(7, method.invoke(null, "a", "b", false));
    }

    @Test
    @DisplayName("Two locals hold the same new object")
    void twoLocals() throws Exception {
        assertEquals(true, shapes.getMethod("twoLocals", String.class, String.class).invoke(null, "a", "b"));
    }

    @Test
    @DisplayName("A long argument between two references")
    void wideArgument() throws Exception {
        Method method = shapes.getMethod("wideArgument", String.class, long.class, String.class);
        assertEquals("a:5:b", String.valueOf(method.invoke(null, "a", 5L, "b")));
    }

    @Test
    @DisplayName("A constructor with no factory keeps its stored NEW")
    void noFactory() throws Exception {
        int before = Id.FACTORY_CALLS.get();
        assertEquals("plain", call("noFactory", "plain"));
        assertEquals(0, Id.FACTORY_CALLS.get() - before, "no factory exists for Id(String)");
    }

    @Test
    @DisplayName("A stack operation on the new object leaves the constructor call as it was")
    void stackOperationOnNew() throws Exception {
        assertEquals("a:b", call("stackOperationOnNew", "a", "b"));
    }

    @Test
    @DisplayName("A branch inside the arguments while the new object waits in a local")
    void branchInArguments() throws Exception {
        Method method = shapes.getMethod("branchInArguments", String.class, boolean.class);
        assertEquals("x:a", String.valueOf(method.invoke(null, "a", true)));
        assertEquals("y:a", String.valueOf(method.invoke(null, "a", false)));
    }

    @Test
    @DisplayName("JEI's optimizer swaps the new object under the next call's argument, so its NEW stays")
    void swappedUnderNextArgument() throws Exception {
        int before = Id.FACTORY_CALLS.get();
        assertEquals("a:b|x", call("swappedUnderNextArgument", "x", "a", "b"));
        assertEquals(0, Id.FACTORY_CALLS.get() - before, "the constructor call stays as written");
    }

    @Test
    @DisplayName("A SWAP of two arguments above the stored object still takes the factory")
    void swappedArguments() throws Exception {
        int before = Id.FACTORY_CALLS.get();
        assertEquals("x:y", call("swappedArguments", "x", "y"));
        assertEquals(1, Id.FACTORY_CALLS.get() - before);
    }

    @Test
    @DisplayName("An argument stored over the id's local keeps the NEW, since the factory result would overwrite it")
    void argumentReusesTheLocal() throws Exception {
        int before = Id.FACTORY_CALLS.get();
        assertEquals("a", call("argumentReusesTheLocal", "a", "b"));
        assertEquals(0, Id.FACTORY_CALLS.get() - before, "the constructor call stays as written");
    }

    /** Runs the static shape of that name, the only method with it. */
    private String call(String name, Object... args) throws Exception {
        for (Method method : shapes.getMethods()) {
            if (method.getName().equals(name)) return String.valueOf(method.invoke(null, args));
        }
        throw new AssertionError("the fixture has no method " + name);
    }

    /** The factory call comes before the first store, which used to come first and find an empty stack. */
    private void assertFactoryBeforeFirstStore(String methodName) throws Exception {
        ClassNode node = new ClassNode();
        new ClassReader(transformer.transformClass(shapesClass(), SHAPES + ".class")).accept(node, 0);
        MethodNode method = node.methods.stream().filter(m -> m.name.equals(methodName)).findFirst().orElseThrow();
        int factory = -1;
        int store = -1;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call && call.getOpcode() == INVOKESTATIC && call.name.equals("of") && factory < 0) {
                factory = method.instructions.indexOf(insn);
            }
            if (insn.getOpcode() == ASTORE && store < 0) store = method.instructions.indexOf(insn);
            assertFalse(insn instanceof TypeInsnNode type && type.getOpcode() == NEW && type.desc.equals(ID),
                    "the redirected NEW must be gone");
        }
        assertTrue(factory >= 0 && factory < store, "the factory runs before its result is stored");
    }

    /** The JVM-style check ASM offers, with this test's loader so the fixture's own types resolve. */
    private void assertVerifies(byte[] bytes) {
        StringWriter report = new StringWriter();
        CheckClassAdapter.verify(new ClassReader(bytes), getClass().getClassLoader(), false, new PrintWriter(report));
        assertEquals("", report.toString(), "the transformed class must verify");
    }

    /** Defines the transformed fixture beside this test's classes. */
    private static final class FixtureLoader extends ClassLoader {
        private final byte[] bytes;

        FixtureLoader(ClassLoader parent, byte[] bytes) {
            super(parent);
            this.bytes = bytes;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (!name.equals(SHAPES.replace('/', '.'))) throw new ClassNotFoundException(name);
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    /** One class with every shape, as other compilers write them. */
    private static byte[] shapesClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, SHAPES, null, "java/lang/Object", null);
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        // new Holder(new OldId(a, b), c) with the id kept in local 3, as JEI writes it.
        MethodVisitor mv = start(cw, ACC_STATIC, "jeiShape", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;");
        storedId(mv, 3, 0, 1);
        mv.visitTypeInsn(NEW, HOLDER);
        mv.visitInsn(DUP);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKESPECIAL, HOLDER, "<init>", "(" + L_OLD + "Ljava/lang/Object;)V", false);
        end(mv, ARETURN);

        mv = start(cw, ACC_STATIC, "javacNested", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;");
        mv.visitTypeInsn(NEW, HOLDER);
        mv.visitInsn(DUP);
        mv.visitTypeInsn(NEW, OLD);
        mv.visitInsn(DUP);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKESPECIAL, OLD, "<init>", TWO_STRINGS, false);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKESPECIAL, HOLDER, "<init>", "(" + L_OLD + "Ljava/lang/Object;)V", false);
        end(mv, ARETURN);

        mv = start(cw, ACC_STATIC, "storeThenLoad", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Object;");
        mv.visitTypeInsn(NEW, OLD);
        mv.visitVarInsn(ASTORE, 2);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKESPECIAL, OLD, "<init>", TWO_STRINGS, false);
        mv.visitVarInsn(ALOAD, 2);
        end(mv, ARETURN);

        // The JEI shape again, where local 0 is this.
        mv = start(cw, 0, "jeiInstance", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;");
        storedId(mv, 4, 1, 2);
        mv.visitTypeInsn(NEW, HOLDER);
        mv.visitInsn(DUP);
        mv.visitVarInsn(ALOAD, 4);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitMethodInsn(INVOKESPECIAL, HOLDER, "<init>", "(" + L_OLD + "Ljava/lang/Object;)V", false);
        end(mv, ARETURN);

        // new Holder(p, new OldId(a, b), c), the id stored while Holder's NEW and p wait on the stack.
        mv = start(cw, ACC_STATIC, "argumentsAround",
                "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;");
        mv.visitTypeInsn(NEW, HOLDER);
        mv.visitInsn(DUP);
        mv.visitVarInsn(ALOAD, 0);
        storedId(mv, 4, 1, 2);
        mv.visitVarInsn(ALOAD, 4);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitMethodInsn(INVOKESPECIAL, HOLDER, "<init>", "(Ljava/lang/String;" + L_OLD + "Ljava/lang/Object;)V", false);
        end(mv, ARETURN);

        // new OldId(new OldId(a, b).path(), c), both stored.
        mv = start(cw, ACC_STATIC, "pendingOuter",
                "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Object;");
        mv.visitTypeInsn(NEW, OLD);
        mv.visitInsn(DUP);
        mv.visitVarInsn(ASTORE, 3);
        storedId(mv, 4, 0, 1);
        mv.visitVarInsn(ALOAD, 4);
        mv.visitMethodInsn(INVOKEVIRTUAL, OLD, "path", "()Ljava/lang/String;", false);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKESPECIAL, OLD, "<init>", TWO_STRINGS, false);
        mv.visitVarInsn(ALOAD, 3);
        end(mv, ARETURN);

        // if (make) new OldId(a, b); return 7;
        mv = start(cw, ACC_STATIC, "discardedOnOnePath", "(Ljava/lang/String;Ljava/lang/String;Z)I");
        Label skip = new Label();
        mv.visitVarInsn(ILOAD, 2);
        mv.visitJumpInsn(IFEQ, skip);
        mv.visitTypeInsn(NEW, OLD);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKESPECIAL, OLD, "<init>", TWO_STRINGS, false);
        mv.visitLabel(skip);
        mv.visitIntInsn(BIPUSH, 7);
        end(mv, IRETURN);

        // Locals 2 and 3 both hold the new object; the result is whether they are the same object.
        mv = start(cw, ACC_STATIC, "twoLocals", "(Ljava/lang/String;Ljava/lang/String;)Z");
        Label differ = new Label();
        mv.visitTypeInsn(NEW, OLD);
        mv.visitInsn(DUP);
        mv.visitInsn(DUP);
        mv.visitVarInsn(ASTORE, 2);
        mv.visitVarInsn(ASTORE, 3);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKESPECIAL, OLD, "<init>", TWO_STRINGS, false);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitJumpInsn(IF_ACMPNE, differ);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitJumpInsn(IFNULL, differ);
        mv.visitInsn(ICONST_1);
        mv.visitInsn(IRETURN);
        mv.visitLabel(differ);
        mv.visitInsn(ICONST_0);
        end(mv, IRETURN);

        mv = start(cw, ACC_STATIC, "wideArgument", "(Ljava/lang/String;JLjava/lang/String;)Ljava/lang/Object;");
        mv.visitTypeInsn(NEW, OLD);
        mv.visitInsn(DUP);
        mv.visitVarInsn(ASTORE, 4);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(LLOAD, 1);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitMethodInsn(INVOKESPECIAL, OLD, "<init>", WIDE, false);
        mv.visitVarInsn(ALOAD, 4);
        end(mv, ARETURN);

        mv = start(cw, ACC_STATIC, "noFactory", "(Ljava/lang/String;)Ljava/lang/Object;");
        mv.visitTypeInsn(NEW, OLD);
        mv.visitInsn(DUP);
        mv.visitVarInsn(ASTORE, 1);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, OLD, "<init>", "(Ljava/lang/String;)V", false);
        mv.visitVarInsn(ALOAD, 1);
        end(mv, ARETURN);

        // The object goes under the first argument with SWAP, which no factory call can copy.
        mv = start(cw, ACC_STATIC, "stackOperationOnNew", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Object;");
        mv.visitTypeInsn(NEW, OLD);
        mv.visitVarInsn(ASTORE, 2);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitInsn(SWAP);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKESPECIAL, OLD, "<init>", TWO_STRINGS, false);
        mv.visitVarInsn(ALOAD, 2);
        end(mv, ARETURN);

        // new OldId(flag ? "x" : "y", a) with the id stored.
        mv = start(cw, ACC_STATIC, "branchInArguments", "(Ljava/lang/String;Z)Ljava/lang/Object;");
        Label no = new Label();
        Label done = new Label();
        mv.visitTypeInsn(NEW, OLD);
        mv.visitInsn(DUP);
        mv.visitVarInsn(ASTORE, 2);
        mv.visitVarInsn(ILOAD, 1);
        mv.visitJumpInsn(IFEQ, no);
        mv.visitLdcInsn("x");
        mv.visitJumpInsn(GOTO, done);
        mv.visitLabel(no);
        mv.visitLdcInsn("y");
        mv.visitLabel(done);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, OLD, "<init>", TWO_STRINGS, false);
        mv.visitVarInsn(ALOAD, 2);
        end(mv, ARETURN);

        // new Holder(new OldId(a, b), x) as JEI's optimizer writes it: x is pushed early and swapped under the id.
        mv = start(cw, ACC_STATIC, "swappedUnderNextArgument",
                "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Object;");
        mv.visitTypeInsn(NEW, HOLDER);
        mv.visitInsn(DUP);
        mv.visitTypeInsn(NEW, OLD);
        mv.visitInsn(DUP);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitInsn(SWAP);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKESPECIAL, OLD, "<init>", TWO_STRINGS, false);
        mv.visitMethodInsn(INVOKESPECIAL, HOLDER, "<init>", "(" + L_OLD + "Ljava/lang/Object;)V", false);
        end(mv, ARETURN);

        // new OldId(x, y) stored, with its two arguments pushed in reverse and swapped.
        mv = start(cw, ACC_STATIC, "swappedArguments", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Object;");
        mv.visitTypeInsn(NEW, OLD);
        mv.visitInsn(DUP);
        mv.visitVarInsn(ASTORE, 2);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitInsn(SWAP);
        mv.visitMethodInsn(INVOKESPECIAL, OLD, "<init>", TWO_STRINGS, false);
        mv.visitVarInsn(ALOAD, 2);
        end(mv, ARETURN);

        // NEW, DUP, ASTORE 2, then the first argument is stored to local 2 too, so it returns "a" as written.
        mv = start(cw, ACC_STATIC, "argumentReusesTheLocal", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Object;");
        mv.visitTypeInsn(NEW, OLD);
        mv.visitInsn(DUP);
        mv.visitVarInsn(ASTORE, 2);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ASTORE, 2);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKESPECIAL, OLD, "<init>", TWO_STRINGS, false);
        mv.visitVarInsn(ALOAD, 2);
        end(mv, ARETURN);

        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code NEW OldId, DUP, ASTORE local, ALOAD a, ALOAD b, INVOKESPECIAL <init>}: JEI's id. */
    private static void storedId(MethodVisitor mv, int local, int a, int b) {
        mv.visitTypeInsn(NEW, OLD);
        mv.visitInsn(DUP);
        mv.visitVarInsn(ASTORE, local);
        mv.visitVarInsn(ALOAD, a);
        mv.visitVarInsn(ALOAD, b);
        mv.visitMethodInsn(INVOKESPECIAL, OLD, "<init>", TWO_STRINGS, false);
    }

    private static MethodVisitor start(ClassWriter cw, int access, String name, String desc) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | access, name, desc, null, null);
        mv.visitCode();
        return mv;
    }

    private static void end(MethodVisitor mv, int returnOpcode) {
        mv.visitInsn(returnOpcode);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }
}
