/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** An old Forge listener on an event NeoForge made abstract reaches every concrete event below it, once each. */
class NeoForgeAbstractEventListenersTest {

    private static final String PLAYER = "net/neoforged/neoforge/event/entity/player/";
    private static final String ADVANCEMENT = PLAYER + "AdvancementEvent";
    private static final String EARN = ADVANCEMENT + "$AdvancementEarnEvent";
    private static final String PROGRESS = ADVANCEMENT + "$AdvancementProgressEvent";
    private static final String STAGE = "net/neoforged/neoforge/client/event/RenderLevelStageEvent";
    private static final String TICK_POST = "net/neoforged/neoforge/event/tick/ServerTickEvent$Post";
    private static final String SUBSCRIBE = NeoForgeAbstractEventListeners.SUBSCRIBE;
    private static final Function<String, ClassNode> OFFLINE = name -> null;

    @Test
    @DisplayName("#398: a static listener on AdvancementEvent becomes one listener per child on NeoForge 1.21.1")
    void staticListenerSplitsOffline() {
        ClassNode out = read(NeoForgeAbstractEventListeners.apply(listener(ADVANCEMENT, true), OFFLINE, "1.21.1"));
        assertNull(subscription(method(out, "onEvent", ADVANCEMENT)), "NeoForge must not see the abstract listener");
        for (String child : List.of(EARN, PROGRESS)) {
            MethodNode sibling = sibling(out, child);
            assertNotNull(subscription(sibling), "each child listener keeps the old annotation");
            assertTrue((sibling.access & Opcodes.ACC_STATIC) != 0, "a static listener stays static");
            assertTrue(calls(sibling, "onEvent"), "the child listener runs the mod's own method");
        }
    }

    @Test
    @DisplayName("an instance listener splits the same way on NeoForge 26.3 and keeps its priority")
    void instanceListenerSplitsOn263() {
        ClassNode out = read(NeoForgeAbstractEventListeners.apply(listener(ADVANCEMENT, false), OFFLINE, "26.3"));
        MethodNode sibling = sibling(out, EARN);
        assertEquals(0, sibling.access & Opcodes.ACC_STATIC);
        assertEquals("priority", subscription(sibling).values.get(0), "the child listener keeps the old priority");
    }

    @Test
    @DisplayName("concrete events, events below NeoForge 1.21.1 and stage-split events keep their listener")
    void otherListenersUnchanged() {
        byte[] concrete = listener(TICK_POST, true);
        assertSame(concrete, NeoForgeAbstractEventListeners.apply(concrete, OFFLINE, "26.3"));
        byte[] old = listener(ADVANCEMENT, true);
        assertSame(old, NeoForgeAbstractEventListeners.apply(old, OFFLINE, "1.20.4"));
        byte[] stage = listener(STAGE, true);
        assertSame(stage, NeoForgeAbstractEventListeners.apply(stage, OFFLINE, "26.3"));
    }

    @Test
    @DisplayName("a host that can be read decides: a concrete parent stays, a missing child is skipped")
    void hostDecides() {
        Map<String, ClassNode> host = new HashMap<>();
        host.put(NeoForgeAbstractEventListeners.EVENT, node(NeoForgeAbstractEventListeners.EVENT, Opcodes.ACC_ABSTRACT));
        host.put(ADVANCEMENT, node(ADVANCEMENT, Opcodes.ACC_ABSTRACT));
        host.put(EARN, node(EARN, 0));
        ClassNode out = read(NeoForgeAbstractEventListeners.apply(listener(ADVANCEMENT, true), host::get, "1.21.1"));
        assertNotNull(sibling(out, EARN));
        assertTrue(out.methods.stream().noneMatch(m -> m.desc.contains(PROGRESS)), "a child the host lacks gets none");

        host.put(ADVANCEMENT, node(ADVANCEMENT, 0));
        byte[] concreteOnHost = listener(ADVANCEMENT, true);
        assertSame(concreteOnHost, NeoForgeAbstractEventListeners.apply(concreteOnHost, host::get, "1.21.1"));
    }

    @Test
    @DisplayName("#398: addListener with a lambda on AdvancementEvent adds it to each child through the helper")
    void lambdaListenerMoves() {
        ClassNode out = read(NeoForgeAbstractEventListeners.apply(lambdaListener(ADVANCEMENT), OFFLINE, "1.21.1"));
        MethodNode register = method(out, "register", "(L" + NeoForgeAbstractEventListeners.BUS + ";)V");
        MethodInsnNode helper = null;
        LdcInsnNode children = null;
        for (var insn : register.instructions.toArray()) {
            if (insn instanceof MethodInsnNode call && call.owner.equals(NeoForgeAbstractEventListeners.HELPER)) helper = call;
            if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String text && text.contains(EARN)) children = ldc;
        }
        assertNotNull(helper, "the bus call must move to the helper");
        assertEquals("(L" + NeoForgeAbstractEventListeners.BUS + ";Ljava/util/function/Consumer;Ljava/lang/String;)V",
                helper.desc);
        assertNotNull(children, "the children travel with the call");
        assertEquals(EARN + "," + PROGRESS, children.cst);
    }

    @Test
    @DisplayName("every annotated listener NeoForge sees takes a concrete event and runs the mod's method once")
    void siblingsRunTheOriginal() throws Exception {
        byte[] out = NeoForgeAbstractEventListeners.apply(listener(ADVANCEMENT, true), OFFLINE, "26.3");
        Map<String, byte[]> classes = new HashMap<>();
        classes.put(NeoForgeAbstractEventListeners.EVENT, eventClass(NeoForgeAbstractEventListeners.EVENT,
                "java/lang/Object", true));
        classes.put(ADVANCEMENT, eventClass(ADVANCEMENT, NeoForgeAbstractEventListeners.EVENT, true));
        classes.put(EARN, eventClass(EARN, ADVANCEMENT, false));
        classes.put(PROGRESS, eventClass(PROGRESS, ADVANCEMENT, false));
        classes.put("net/neoforged/bus/api/SubscribeEvent", subscribeAnnotation());
        classes.put("test/Listener", out);
        ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classes.get(name.replace('.', '/'));
                if (bytes == null) throw new ClassNotFoundException(name);
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
        @SuppressWarnings("unchecked")
        Class<? extends Annotation> subscribe = (Class<? extends Annotation>) loader.loadClass("net.neoforged.bus.api.SubscribeEvent");
        Class<?> listener = loader.loadClass("test.Listener");
        int listeners = 0;
        for (Method method : listener.getDeclaredMethods()) {
            if (!method.isAnnotationPresent(subscribe)) continue;
            Class<?> event = method.getParameterTypes()[0];
            assertFalse(Modifier.isAbstract(event.getModifiers()), method + " takes an abstract event");
            method.setAccessible(true);
            method.invoke(null, event.getConstructor().newInstance());
            listeners++;
        }
        assertEquals(2, listeners);
        assertEquals(2, listener.getField("calls").getInt(null), "each child event reaches the mod's method once");
    }

    /** A post-remap listener class with one {@code @SubscribeEvent(priority = HIGH)} method on {@code event}. */
    private static byte[] listener(String event, boolean isStatic) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/Listener", null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "calls", "I", null, null).visitEnd();
        MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC | (isStatic ? Opcodes.ACC_STATIC : 0), "onEvent",
                "(L" + event + ";)V", null, null);
        var annotation = m.visitAnnotation(SUBSCRIBE, true);
        annotation.visitEnum("priority", "Lnet/neoforged/bus/api/EventPriority;", "HIGH");
        annotation.visitEnd();
        m.visitCode();
        m.visitFieldInsn(Opcodes.GETSTATIC, "test/Listener", "calls", "I");
        m.visitInsn(Opcodes.ICONST_1);
        m.visitInsn(Opcodes.IADD);
        m.visitFieldInsn(Opcodes.PUTSTATIC, "test/Listener", "calls", "I");
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code bus.addListener(Listener::onEvent)} on a static method taking {@code event}. */
    private static byte[] lambdaListener(String event) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/Listener", null, "java/lang/Object", null);
        MethodVisitor target = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "onEvent",
                "(L" + event + ";)V", null, null);
        target.visitCode();
        target.visitInsn(Opcodes.RETURN);
        target.visitMaxs(0, 0);
        target.visitEnd();
        MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "register",
                "(L" + NeoForgeAbstractEventListeners.BUS + ";)V", null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 0);
        Handle metafactory = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                        + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                        + "Ljava/lang/invoke/CallSite;", false);
        m.visitInvokeDynamicInsn("accept", "()Ljava/util/function/Consumer;", metafactory,
                Type.getType("(Ljava/lang/Object;)V"),
                new Handle(Opcodes.H_INVOKESTATIC, "test/Listener", "onEvent", "(L" + event + ";)V", false),
                Type.getType("(L" + event + ";)V"));
        m.visitMethodInsn(Opcodes.INVOKEINTERFACE, NeoForgeAbstractEventListeners.BUS, "addListener",
                "(Ljava/util/function/Consumer;)V", true);
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] eventClass(String name, String superName, boolean isAbstract) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | (isAbstract ? Opcodes.ACC_ABSTRACT : 0), name, null, superName, null);
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A runtime-visible annotation type standing in for NeoForge's SubscribeEvent. */
    private static byte[] subscribeAnnotation() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE | Opcodes.ACC_ANNOTATION,
                "net/neoforged/bus/api/SubscribeEvent", null, "java/lang/Object",
                new String[]{"java/lang/annotation/Annotation"});
        var retention = cw.visitAnnotation("Ljava/lang/annotation/Retention;", true);
        retention.visitEnum("value", "Ljava/lang/annotation/RetentionPolicy;", "RUNTIME");
        retention.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ClassNode node(String name, int access) {
        ClassNode node = new ClassNode();
        node.name = name;
        node.access = Opcodes.ACC_PUBLIC | access;
        return node;
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static MethodNode method(ClassNode node, String name, String event) {
        String desc = event.startsWith("(") ? event : "(L" + event + ";)V";
        return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)).findFirst().orElseThrow();
    }

    private static MethodNode sibling(ClassNode node, String child) {
        return node.methods.stream().filter(m -> m.desc.equals("(L" + child + ";)V") && !m.name.equals("onEvent"))
                .findFirst().orElseThrow(() -> new AssertionError("no listener on " + child));
    }

    private static AnnotationNode subscription(MethodNode method) {
        if (method.visibleAnnotations == null) return null;
        return method.visibleAnnotations.stream().filter(a -> a.desc.equals(SUBSCRIBE)).findFirst().orElse(null);
    }

    private static boolean calls(MethodNode method, String name) {
        for (var insn : method.instructions.toArray()) {
            if (insn instanceof MethodInsnNode call && call.name.equals(name)) return true;
        }
        return false;
    }
}
