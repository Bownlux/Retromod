/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** The shared @Mod constructor injector puts each call where a bridge asks, once per constructor path. */
class ModConstructorHooksTest {

    private static final String MOD = "test/MyMod";
    private static final String BASE = "test/Base";
    private static final String HOOK = "test/Hook";
    private static final ModConstructorHooks.Call HEAD = new ModConstructorHooks.Call(
            ModConstructorHooks.Position.HEAD, HOOK, "install", "()V", List.of());
    private static final ModConstructorHooks.Call TAIL = new ModConstructorHooks.Call(
            ModConstructorHooks.Position.TAIL, HOOK, "finish", "(Ljava/lang/Object;Ljava/lang/String;)V",
            List.of(ModConstructorHooks.Instance.MOD, "mymod"));

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();

    @AfterEach
    void reset() {
        transformer.clearRedirectsForTesting();
        Forge1122LifecycleSynthetics.resetForTesting();
    }

    @Test
    @DisplayName("The tail call comes right before every return of each constructor")
    void tailBeforeEveryReturn() {
        ClassNode node = node(ModConstructorHooks.addCalls(modClass(MOD, "mymod", ModConstructorHooksTest::twoReturns),
                List.of(TAIL)));
        verify(node);
        MethodNode branching = constructor(node, "(Z)V");
        List<AbstractInsnNode> code = List.of(branching.instructions.toArray());
        int returns = 0;
        for (int i = 0; i < code.size(); i++) {
            if (code.get(i).getOpcode() != RETURN) continue;
            returns++;
            assertTrue(isCall(code.get(i - 1), "finish"), "the call sits right before return number " + returns);
            assertTrue(code.get(i - 2) instanceof LdcInsnNode ldc && "mymod".equals(ldc.cst), "its string argument");
            assertTrue(code.get(i - 3) instanceof VarInsnNode load && load.getOpcode() == ALOAD && load.var == 0,
                    "the mod object is passed first");
        }
        assertEquals(2, returns);
        assertEquals(2, calls(branching, "finish"), "one call per return");
        assertEquals(1, calls(constructor(node, "()V"), "finish"));
    }

    @Test
    @DisplayName("The head call follows the super call, not the constructor of an argument built for it")
    void headAfterSuperCall() {
        ClassNode node = node(ModConstructorHooks.addCalls(modClass(MOD, "mymod", ModConstructorHooksTest::superWithNewArgument),
                List.of(HEAD)));
        verify(node);
        MethodNode constructor = constructor(node, "(Ljava/lang/Object;)V");
        AbstractInsnNode install = find(constructor, "install");
        assertNotNull(install);
        AbstractInsnNode before = install.getPrevious();
        assertTrue(before instanceof MethodInsnNode call && call.owner.equals(BASE) && call.name.equals("<init>"),
                "the call follows super(...), so the object is ready");
        assertEquals(1, calls(constructor, "install"));
    }

    @Test
    @DisplayName("A class with several constructors gets one call per constructor path, and none where this(...) hands over")
    void oneCallPerConstructorPath() {
        ClassNode node = node(modClass(MOD, "mymod", (writer, owner) -> {
            twoReturns(writer, owner);
            delegating(writer, owner);
        }));
        assertEquals(3, ModConstructorHooks.addCall(node, HEAD),
                "()V, (Z)V and (Object)V start the object; (I)V and (J)V hand over");
        node = node(modClass(MOD, "mymod", (writer, owner) -> {
            twoReturns(writer, owner);
            delegating(writer, owner);
        }));
        assertEquals(3, ModConstructorHooks.addCall(node, TAIL));
        assertEquals(0, calls(constructor(node, "(I)V"), "finish"), "this(...) runs the other constructor's call");
        assertEquals(0, calls(constructor(node, "(J)V"), "finish"), "an argument built with new does not hide this(...)");
        assertEquals(1, calls(constructor(node, "(Ljava/lang/Object;)V"), "finish"));
    }

    @Test
    @DisplayName("Only the jar's @Mod classes get calls, and a planner leaves with its transformer's registrations")
    void onlyModClassesAndResetClearsPlanners() {
        byte[] mod = modClass(MOD, "mymod", (writer, owner) -> { });
        byte[] other = modClass("test/Other", null, (writer, owner) -> { });
        List<String> seen = new ArrayList<>();
        ModConstructorHooks.register(transformer, "test", (classes, modIds) -> new ModConstructorHooks.JarPlan() {
            @Override
            public List<ModConstructorHooks.Call> callsFor(String modClass, String modId) {
                seen.add(modClass + "=" + modId);
                return List.of(HEAD);
            }
        });
        ModConstructorHooks.JarHooks hooks = ModConstructorHooks.forJar(transformer, List.of(mod, other));
        assertNotNull(hooks);
        assertSame(other, hooks.apply(other), "a class without @Mod is returned as it was");
        assertEquals(1, calls(constructor(node(hooks.apply(mod)), "()V"), "install"));
        assertEquals(List.of(MOD + "=mymod"), seen);
        assertNull(ModConstructorHooks.forJar(transformer, List.of(other)), "a jar without a @Mod class has nothing to call");

        transformer.clearRedirectsForTesting();
        assertNull(ModConstructorHooks.forJar(transformer, List.of(mod, other)),
                "a planner registered for one registration set does not reach the next");
    }

    @Test
    @DisplayName("The 1.12 lifecycle bridge writes the same bytes through the shared injector as it did before")
    void legacyAttachUnchanged() {
        Forge1122LifecycleSynthetics.register(transformer);
        List<Shape> shapes = List.of((writer, owner) -> { }, ModConstructorHooksTest::twoReturns, (writer, owner) -> {
            twoReturns(writer, owner);
            plainThisCall(writer, owner);
        });
        for (Shape shape : shapes) {
            byte[] input = legacyModClass(shape);
            byte[] now = Forge1122LifecycleSynthetics.upgradeLegacyModClass(input);
            assertArrayEquals(attachAsBefore(input), now, "the 1.12 attach call and its class bytes are unchanged");
        }
    }

    // ---- what the 1.12 bridge did before the injector was shared ----

    private static byte[] attachAsBefore(byte[] input) {
        ClassNode cn = node(input);
        AnnotationNode mod = cn.visibleAnnotations.get(0);
        String modid = (String) mod.values.get(mod.values.indexOf("modid") + 1);
        mod.values = new ArrayList<>(List.of("value", modid));
        for (MethodNode m : cn.methods) {
            if (!m.name.equals("<init>") || delegatesAsBefore(cn, m)) continue;
            for (AbstractInsnNode in : m.instructions.toArray()) {
                if (in.getOpcode() != RETURN) continue;
                m.instructions.insertBefore(in, new VarInsnNode(ALOAD, 0));
                m.instructions.insertBefore(in, new LdcInsnNode(modid));
                m.instructions.insertBefore(in, new LdcInsnNode(Type.getObjectType(Forge1122LifecycleSynthetics.PRE_EVENT)));
                m.instructions.insertBefore(in, new LdcInsnNode(Type.getObjectType(Forge1122LifecycleSynthetics.INIT_EVENT)));
                m.instructions.insertBefore(in, new LdcInsnNode(Type.getObjectType(Forge1122LifecycleSynthetics.POST_EVENT)));
                m.instructions.insertBefore(in, new MethodInsnNode(INVOKESTATIC, Forge1122EventBridge.EVENTS, "attach",
                        "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/Class;Ljava/lang/Class;Ljava/lang/Class;)V", false));
            }
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    private static boolean delegatesAsBefore(ClassNode cn, MethodNode constructor) {
        for (AbstractInsnNode in : constructor.instructions.toArray()) {
            if (in instanceof MethodInsnNode call && call.getOpcode() == INVOKESPECIAL && call.name.equals("<init>")) {
                return call.owner.equals(cn.name);
            }
        }
        return false;
    }

    // ---- fixtures ----

    /** Constructors written into a class, which call this(...) through its name. */
    @FunctionalInterface
    interface Shape {
        void write(ClassWriter cw, String owner);
    }

    /** A class extending {@link #BASE} with a no-argument constructor, @Mod("modId") when an id is given, and more constructors. */
    static byte[] modClass(String name, String modId, Shape constructors) {
        return modClass(name, modId, BASE, constructors);
    }

    /** As {@link #modClass(String, String, Shape)}, extending {@code superName}. */
    static byte[] modClass(String name, String modId, String superName, Shape constructors) {
        ClassWriter cw = writer();
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, superName, null);
        if (modId != null) {
            AnnotationVisitor mod = cw.visitAnnotation("Lnet/minecraftforge/fml/common/Mod;", true);
            mod.visit("value", modId);
            mod.visitEnd();
        }
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, superName, "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        constructors.write(cw, name);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A 1.12.2 @Mod(modid = "oldmod") class with the given extra constructors. */
    private static byte[] legacyModClass(Shape constructors) {
        ClassNode node = node(modClass("test/OldMod", null, constructors));
        AnnotationNode mod = new AnnotationNode("Lnet/minecraftforge/fml/common/Mod;");
        mod.values = new ArrayList<>(List.of("modid", "oldmod", "name", "Old Mod"));
        node.visibleAnnotations = new ArrayList<>(List.of(mod));
        ClassWriter cw = new ClassWriter(0);
        node.accept(cw);
        return cw.toByteArray();
    }

    // MyMod(boolean early) { super(); if (early) return; }
    private static void twoReturns(ClassWriter cw, String owner) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", "(Z)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, BASE, "<init>", "()V", false);
        Label later = new Label();
        mv.visitVarInsn(ILOAD, 1);
        mv.visitJumpInsn(IFEQ, later);
        mv.visitInsn(RETURN);
        mv.visitLabel(later);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    // MyMod(int ignored) { this(true); }
    private static void plainThisCall(ClassWriter cw, String owner) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", "(I)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitInsn(ICONST_1);
        mv.visitMethodInsn(INVOKESPECIAL, owner, "<init>", "(Z)V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    // MyMod(int) { this(true); }, MyMod(Object) { super(); }, MyMod(long) { this(new StringBuilder()); }
    private static void delegating(ClassWriter cw, String owner) {
        plainThisCall(cw, owner);
        MethodVisitor object = cw.visitMethod(ACC_PUBLIC, "<init>", "(Ljava/lang/Object;)V", null, null);
        object.visitCode();
        object.visitVarInsn(ALOAD, 0);
        object.visitMethodInsn(INVOKESPECIAL, BASE, "<init>", "()V", false);
        object.visitInsn(RETURN);
        object.visitMaxs(0, 0);
        object.visitEnd();
        MethodVisitor built = cw.visitMethod(ACC_PUBLIC, "<init>", "(J)V", null, null);
        built.visitCode();
        built.visitVarInsn(ALOAD, 0);
        built.visitTypeInsn(NEW, "java/lang/StringBuilder");
        built.visitInsn(DUP);
        built.visitMethodInsn(INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false);
        built.visitMethodInsn(INVOKESPECIAL, owner, "<init>", "(Ljava/lang/Object;)V", false);
        built.visitInsn(RETURN);
        built.visitMaxs(0, 0);
        built.visitEnd();
    }

    // MyMod(Object ignored) { super(new StringBuilder()); }
    private static void superWithNewArgument(ClassWriter cw, String owner) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", "(Ljava/lang/Object;)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitTypeInsn(NEW, "java/lang/StringBuilder");
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false);
        mv.visitMethodInsn(INVOKESPECIAL, BASE, "<init>", "(Ljava/lang/Object;)V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static ClassWriter writer() {
        return new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String a, String b) {
                return "java/lang/Object";
            }
        };
    }

    static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static void verify(ClassNode node) {
        ClassWriter cw = new ClassWriter(0);
        node.accept(cw);
        new ClassReader(cw.toByteArray()).accept(new CheckClassAdapter(new ClassWriter(0), true), 0);
    }

    static MethodNode constructor(ClassNode node, String desc) {
        return node.methods.stream().filter(m -> m.name.equals("<init>") && m.desc.equals(desc)).findFirst().orElseThrow();
    }

    private static boolean isCall(AbstractInsnNode insn, String name) {
        return insn instanceof MethodInsnNode call && call.getOpcode() == INVOKESTATIC && call.owner.equals(HOOK)
                && call.name.equals(name);
    }

    private static AbstractInsnNode find(MethodNode method, String name) {
        for (AbstractInsnNode insn : method.instructions) {
            if (isCall(insn, name)) return insn;
        }
        return null;
    }

    private static int calls(MethodNode method, String name) {
        int count = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (isCall(insn, name)) count++;
        }
        return count;
    }
}
