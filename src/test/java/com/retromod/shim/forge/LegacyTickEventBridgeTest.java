/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** A Forge 1.20.1 tick listener checks the phase of one event; Forge 26.2 fires a Pre and a Post event instead. */
class LegacyTickEventBridgeTest {

    private static final String TICK = LegacyTickEventBridge.TICK;
    private static final String CLIENT_TICK = TICK + "$ClientTickEvent";
    private static final String SUBSCRIBE = "Lnet/minecraftforge/eventbus/api/listener/SubscribeEvent;";
    private final String target = RetromodVersion.TARGET_MC_VERSION;
    private final boolean forge = McReflect.isForceForge();
    private final boolean neoForge = McReflect.isForceNeoForge();

    // The bridge is a Forge one, so these offline runs translate for a Forge host.
    @BeforeEach
    void forgeTarget() {
        McReflect.setForceNeoForge(false);
        McReflect.setForceForge(true);
    }

    @AfterEach
    void reset() {
        RetromodVersion.TARGET_MC_VERSION = target;
        McReflect.setForceForge(forge);
        McReflect.setForceNeoForge(neoForge);
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("Only a Forge target from 26.2 takes the tick bridge; NeoForge, other loaders and older Forge do not")
    void gateFollowsTheForgeHostShape() {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        assertTrue(LegacyTickEventBridge.hostSplitTicks(), "Forge 26.2 split its tick events");
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        assertTrue(LegacyTickEventBridge.hostSplitTicks(), "the split carries forward to Forge 26.3");
        RetromodVersion.TARGET_MC_VERSION = "26.1";
        assertFalse(LegacyTickEventBridge.hostSplitTicks(), "Forge 26.1 still fires one tick event with a phase");
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        McReflect.setForceForge(false);
        assertFalse(LegacyTickEventBridge.hostSplitTicks(), "a run that does not target Forge keeps the old names");
        McReflect.setForceNeoForge(true);
        assertFalse(LegacyTickEventBridge.hostSplitTicks(), "NeoForge has its own tick events");
    }

    @Test
    @DisplayName("A spark-style listener on the base TickEvent gets ten listeners and reads its type through TickType")
    void baseListenerIsSplitIntoTen() {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        LegacyTickEventBridge.register(t);

        byte[] out = t.transformClass(tickHookClass(), "mod/TickHook.class");
        new ClassReader(out).accept(new CheckClassAdapter(new ClassWriter(0), true), 0);
        ClassNode node = new ClassNode();
        new ClassReader(out).accept(node, 0);

        assertFalse(subscribed(method(node, "onTick")), "EventBus 7 has no bus for the base TickEvent");
        List<String> listeners = new ArrayList<>();
        for (MethodNode method : node.methods) {
            if (subscribed(method)) listeners.add(method.name + method.desc);
        }
        List<String> expected = new ArrayList<>();
        for (String event : LegacyTickEventBridge.EVENTS) {
            for (String half : List.of("Pre", "Post")) {
                expected.add("retromod$onTick$" + event + "$" + half + "(L" + TICK + "$" + event + "$" + half + ";)V");
            }
        }
        assertEquals(expected, listeners, "one listener per old event and half, as the base event heard all of them");
        for (String listener : List.of("retromod$onTick$ServerTickEvent$Pre", "retromod$onTick$RenderTickEvent$Post")) {
            assertTrue(refs(method(node, listener)).contains(INVOKEVIRTUAL + " mod/TickHook.onTick(L" + TICK + ";)V"),
                    "each listener calls the original body");
        }

        List<String> refs = refs(method(node, "onTick"));
        assertTrue(refs.contains(INVOKESTATIC + " " + LegacyTickEventBridge.TICKS + ".type(Ljava/lang/Object;)L"
                + LegacyTickEventBridge.TYPE + ";"), refs.toString());
        assertTrue(refs.contains(INVOKESTATIC + " " + LegacyTickEventBridge.TICKS + ".phase(Ljava/lang/Object;)L"
                + LegacyTickEventBridge.PHASE + ";"), refs.toString());
        assertEquals("L" + LegacyTickEventBridge.TYPE + ";", node.fields.get(0).desc, "the hook's own type field");
        assertFalse(new String(out, java.nio.charset.StandardCharsets.ISO_8859_1).contains(TICK + "$Type"),
                "Forge 26.2 has no TickEvent.Type");
    }

    @Test
    @DisplayName("TickType keeps Forge 1.20.1's constants in order, and each Pre and Post event reports its old type")
    void tickTypeMapsEachEvent() throws Exception {
        // The rebuilt helper against the compiled stubs of Forge 26.2's tick records.
        URL compiled = Path.of("target", "legacyapi-classes").toUri().toURL();
        try (URLClassLoader host = new URLClassLoader(new URL[]{compiled}, ClassLoader.getPlatformClassLoader())) {
            Class<?> type = host.loadClass(LegacyTickEventBridge.TYPE.replace('/', '.'));
            List<String> constants = new ArrayList<>();
            for (Object constant : type.getEnumConstants()) constants.add(((Enum<?>) constant).name());
            assertEquals(List.of("LEVEL", "PLAYER", "CLIENT", "SERVER", "RENDER"), constants,
                    "switch tables and ordinal() read Forge 1.20.1's order");

            java.lang.reflect.Method read = host.loadClass(LegacyTickEventBridge.TICKS.replace('/', '.'))
                    .getMethod("type", Object.class);
            List<String> old = List.of("CLIENT", "SERVER", "LEVEL", "PLAYER", "RENDER");
            for (int i = 0; i < old.size(); i++) {
                for (String half : List.of("Pre", "Post")) {
                    String event = "net.minecraftforge.event.TickEvent$" + LegacyTickEventBridge.EVENTS.get(i) + "$" + half;
                    Object fired = host.loadClass(event).getConstructor().newInstance();
                    assertEquals(old.get(i), ((Enum<?>) read.invoke(null, fired)).name(), event);
                }
            }
        }
    }

    @Test
    @DisplayName("An old tick listener gets a Pre and a Post listener, and reads its phase through the helper")
    void listenerIsSplit() {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        LegacyTickEventBridge.register(t);

        byte[] out = t.transformClass(listenerClass(), "mod/Ticks.class");
        new ClassReader(out).accept(new CheckClassAdapter(new ClassWriter(0), true), 0);
        ClassNode node = new ClassNode();
        new ClassReader(out).accept(node, 0);

        MethodNode original = method(node, "onTick");
        assertFalse(subscribed(original), "the original no longer listens to an interface the bus cannot fire");
        assertTrue(subscribed(method(node, "retromod$onTick$Pre")));
        assertEquals("(L" + CLIENT_TICK + "$Post;)V", method(node, "retromod$onTick$Post").desc);
        List<String> refs = refs(original);
        assertTrue(refs.contains(INVOKESTATIC + " " + LegacyTickEventBridge.TICKS + ".phase(Ljava/lang/Object;)L"
                + LegacyTickEventBridge.PHASE + ";"), refs.toString());
        assertTrue(refs.contains(LegacyTickEventBridge.PHASE + ".END"), refs.toString());
        assertTrue(refs.stream().noneMatch(ref -> ref.contains("$Phase")), "the removed phase enum is still named: " + refs);
    }

    @Test
    @DisplayName("A render tick listener's renderTickTime read asks the helper, which reads the event's timer")
    void renderTickTimeReadsTheTimer() {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        LegacyTickEventBridge.register(t);

        byte[] out = t.transformClass(renderListenerClass(), "mod/RenderTicks.class");
        new ClassReader(out).accept(new CheckClassAdapter(new ClassWriter(0), true), 0);
        ClassNode node = new ClassNode();
        new ClassReader(out).accept(node, 0);

        List<String> refs = refs(method(node, "onRender"));
        assertTrue(refs.contains(INVOKESTATIC + " " + LegacyTickEventBridge.TICKS + ".renderTickTime(Ljava/lang/Object;)F"),
                refs.toString());
        assertFalse(refs.contains(RENDER_TICK + ".renderTickTime"), "Forge 26.2's render tick records have no such field: " + refs);
    }

    @Test
    @DisplayName("A tick listener's old priority reaches both halves as EventBus 7's byte, and nothing else changes")
    void halvesCarryTheNormalizedPriority() {
        ClassNode plain = new ClassNode();
        new ClassReader(LegacyTickEventBridge.splitListeners(remappedListener(null))).accept(plain, 0);
        ClassNode high = new ClassNode();
        new ClassReader(LegacyTickEventBridge.splitListeners(remappedListener("HIGH"))).accept(high, 0);

        for (String half : List.of("retromod$onTick$Pre", "retromod$onTick$Post")) {
            MethodNode withPriority = method(high, half);
            assertEquals(List.of("priority", (byte) 64), withPriority.visibleAnnotations.get(0).values,
                    "EventBus 7 reads a byte, and has no receiveCanceled");
            assertEquals(method(plain, half).access, withPriority.access);
            assertEquals(refs(method(plain, half)), refs(withPriority), "the listener body is unchanged");
        }
    }

    /**
     * {@code @SubscribeEvent(priority = HIGH, receiveCanceled = true) void onTick(ClientTickEvent e) {}} as the class
     * pass leaves it: EventBus 7's annotation still carrying the bridge's enum.
     */
    private static byte[] remappedListener(String priority) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "mod/Ticks", null, "java/lang/Object", null);
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC, "onTick", "(L" + CLIENT_TICK + ";)V", null, null);
        AnnotationVisitor annotation = m.visitAnnotation(SUBSCRIBE, true);
        if (priority != null) {
            annotation.visitEnum("priority", "L" + ForgeEventBusSynthetics.PRI + ";", priority);
            annotation.visit("receiveCanceled", true);
        }
        annotation.visitEnd();
        m.visitCode();
        m.visitInsn(RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static final String RENDER_TICK = TICK + "$RenderTickEvent";

    /** {@code @SubscribeEvent static void onRender(RenderTickEvent e) { float partial = e.renderTickTime; }}. */
    private static byte[] renderListenerClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "mod/RenderTicks", null, "java/lang/Object", null);
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "onRender", "(L" + RENDER_TICK + ";)V", null, null);
        m.visitAnnotation("Lnet/minecraftforge/eventbus/api/SubscribeEvent;", true).visitEnd();
        m.visitCode();
        m.visitVarInsn(ALOAD, 0);
        m.visitFieldInsn(GETFIELD, RENDER_TICK, "renderTickTime", "F");
        m.visitVarInsn(FSTORE, 1);
        m.visitInsn(RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow(() -> new AssertionError(name));
    }

    private static boolean subscribed(MethodNode method) {
        List<AnnotationNode> annotations = method.visibleAnnotations == null ? List.of() : method.visibleAnnotations;
        return annotations.stream().anyMatch(a -> a.desc.equals(SUBSCRIBE));
    }

    private static List<String> refs(MethodNode method) {
        List<String> refs = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call) refs.add(call.getOpcode() + " " + call.owner + "." + call.name + call.desc);
            if (insn instanceof FieldInsnNode field) refs.add(field.owner + "." + field.name);
        }
        return refs;
    }

    /** {@code @SubscribeEvent static void onTick(ClientTickEvent e) { if (e.phase == Phase.END) {...} }}. */
    private static byte[] listenerClass() {
        String phase = "L" + TICK + "$Phase;";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String a, String b) {
                return "java/lang/Object";
            }
        };
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "mod/Ticks", null, "java/lang/Object", null);
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "onTick", "(L" + CLIENT_TICK + ";)V", null, null);
        AnnotationVisitor annotation = m.visitAnnotation("Lnet/minecraftforge/eventbus/api/SubscribeEvent;", true);
        annotation.visitEnd();
        m.visitCode();
        Label done = new Label();
        m.visitVarInsn(ALOAD, 0);
        m.visitFieldInsn(GETFIELD, CLIENT_TICK, "phase", phase);
        m.visitFieldInsn(GETSTATIC, TICK + "$Phase", "END", phase);
        m.visitJumpInsn(IF_ACMPNE, done);
        m.visitInsn(NOP);
        m.visitLabel(done);
        m.visitInsn(RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * spark's tick hook: {@code private final TickEvent.Type type;} and
     * {@code @SubscribeEvent public void onTick(TickEvent e) { if (e.phase != START || e.type != type) return; ... }}.
     */
    private static byte[] tickHookClass() {
        String type = "L" + TICK + "$Type;";
        String phase = "L" + TICK + "$Phase;";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String a, String b) {
                return "java/lang/Object";
            }
        };
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "mod/TickHook", null, "java/lang/Object", null);
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "type", type, null, null).visitEnd();
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC, "onTick", "(L" + TICK + ";)V", null, null);
        m.visitAnnotation("Lnet/minecraftforge/eventbus/api/SubscribeEvent;", true).visitEnd();
        m.visitCode();
        Label done = new Label();
        m.visitVarInsn(ALOAD, 1);
        m.visitFieldInsn(GETFIELD, TICK, "phase", phase);
        m.visitFieldInsn(GETSTATIC, TICK + "$Phase", "START", phase);
        m.visitJumpInsn(IF_ACMPNE, done);
        m.visitVarInsn(ALOAD, 1);
        m.visitFieldInsn(GETFIELD, TICK, "type", type);
        m.visitVarInsn(ALOAD, 0);
        m.visitFieldInsn(GETFIELD, "mod/TickHook", "type", type);
        m.visitJumpInsn(IF_ACMPNE, done);
        m.visitInsn(NOP);
        m.visitLabel(done);
        m.visitInsn(RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
