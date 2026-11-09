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
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** Forge 26.2 fires BreakEvent without reading a cancel and refuses the break on DENY, so an old cancel becomes DENY. */
class BreakEventCancelTest {

    private static final String BREAK = LegacyForgeEventResultBridge.BREAK;
    private static final String LISTENER = "mod/BreakListener";
    private static final String TWINS = "mod/BreakTwins";
    private static final List<String> SHARED_SLOTS = List.of("retromod.shared.forge.cancel.frames.v1",
            "retromod.shared.forge.cancel.break.v1", "retromod.shared.forge.event.store.v1");

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();
    private ClassLoader game;

    @BeforeEach
    void forgeTarget() {
        clearSharedSlots();
        McReflect.setForceNeoForge(false);
        McReflect.setForceForge(true);
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        transformer.setModSourceVersion(null);
        transformer.clearRedirectsForTesting();
        clearSharedSlots();
    }

    private void clearSharedSlots() {
        SHARED_SLOTS.forEach(System.getProperties()::remove);
    }

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("An old listener that cancels BreakEvent stops later listeners and leaves the host result DENY")
    void cancelInAListenerDeniesTheBreak(String host, @TempDir Path dir) throws Exception {
        load(host, dir);
        Object event = breakEvent("DEFAULT");
        assertTrue(listener("cancel").test(event), "EventBus 7 stops later listeners");
        assertEquals("DENY", result(event), "the result Forge's break check reads");
        assertEquals(true, call("cancelled", event));

        Object changedMind = breakEvent("DEFAULT");
        assertFalse(listener("cancelThenUncancel").test(changedMind));
        assertEquals("DEFAULT", result(changedMind), "an un-cancel puts back the result the cancel replaced");
    }

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("isCanceled sees the DENY Forge presets for a restricted break, and an un-cancel does not lift it")
    void hostPresetDenialReadsAsCancelled(String host, @TempDir Path dir) throws Exception {
        load(host, dir);
        Object restricted = breakEvent("DENY");
        assertEquals(true, call("cancelled", restricted), "Forge 1.20.1 posted a restricted break already cancelled");
        assertEquals(false, call("cancelled", breakEvent("DEFAULT")));

        call("cancelThenUncancel", restricted);
        assertEquals("DENY", result(restricted), "only a cancel's own DENY is taken back");
    }

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("A cancel outside any listener, as Carry On sets on a break event it posts, reads back and is undone")
    void cancelOutsideAListener(String host, @TempDir Path dir) throws Exception {
        load(host, dir);
        Object event = breakEvent("ALLOW");
        call("cancel", event);
        assertEquals("DENY", result(event));
        assertEquals(true, call("cancelled", event));
        call("uncancel", event);
        assertEquals("ALLOW", result(event), "the value another mod chose comes back");
    }

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("A listener method's twin answers only its own cancel: Forge's preset DENY is not one, and an un-cancel puts the result back")
    void twinAnswersOnlyItsListenersCancel(String host, @TempDir Path dir) throws Exception {
        load(host, dir);
        Object restricted = breakEvent("DENY");
        assertEquals(false, twin("onLook", restricted), "native listeners below it still run on a restricted break");
        assertEquals("DENY", result(restricted), "and Forge still refuses the break");
        assertFalse(listener("cancelled").test(breakEvent("DENY")), "a lambda listener answers the same way");

        Object cancelled = breakEvent("DEFAULT");
        assertEquals(true, twin("onCancel", cancelled));
        assertEquals("DENY", result(cancelled));

        Object changedMind = breakEvent("ALLOW");
        assertEquals(false, twin("onFlip", changedMind));
        assertEquals("ALLOW", result(changedMind), "the result the cancel replaced comes back");
    }

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("A receiveCanceled listener run after a cancelled post reads it as cancelled and cannot lift the break's result")
    void monitorFrameWinsOverTheBreakResult(String host, @TempDir Path dir) throws Exception {
        load(host, dir);
        Class<?> cancel = game.loadClass(LegacyCancelBridge.CANCEL.replace('/', '.'));
        // A native listener cancelled by its answer, so no DENY was set.
        Object event = breakEvent("DEFAULT");
        Object frame = cancel.getMethod("enterCancelled", Object.class, boolean.class, Object.class, String.class)
                .invoke(null, event, true, null, "mod.Watch.onBreak");
        assertNotNull(frame);
        assertEquals(true, call("cancelled", event), "the post was cancelled");
        call("uncancel", event);
        assertEquals("DEFAULT", result(event), "the settled post keeps its answer");
        cancel.getMethod("exit", Object[].class).invoke(null, new Object[]{frame});
    }

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("The table marks BreakEvent as a cancel that only stops listeners, and the runtime helper names the same class")
    void tableAndRuntimeAgree(String host) throws Exception {
        LegacyCancelBridge.Section section = LegacyCancelBridge.section(host);
        assertEquals(Set.of(BREAK), section.denies());
        assertTrue(section.cancellable().containsAll(section.denies()), "a denies row is also a cancellable row");
        String runtime;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(LegacyCancelBridge.CANCEL + ".class")) {
            runtime = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
        for (String event : section.denies()) {
            assertTrue(runtime.contains(event.replace('/', '.')), "LegacyCancel carries the cancel of " + event);
        }
    }

    // ---- helpers ----

    /** Translates the old listener for {@code host} and loads it with stand-ins for Forge's BreakEvent and Result. */
    private void load(String host, Path dir) throws Exception {
        RetromodVersion.TARGET_MC_VERSION = host;
        new Forge_26_1_to_26_2().registerRedirects(transformer);
        transformer.setModSourceVersion("1.20.1");
        byte[] translated = transformer.transformClass(oldListener(), LISTENER + ".class");
        byte[] twins = transformer.transformClass(oldSubscriber(), TWINS + ".class");
        game = hostClasses().loader(dir, transformer, Map.of(LISTENER, translated, TWINS, twins));
    }

    /** One old listener method run as B07 adds it to a cancellable bus: a predicate that answers whether it cancelled. */
    @SuppressWarnings("unchecked")
    private Predicate<Object> listener(String method) throws Exception {
        Class<?> cancel = game.loadClass(LegacyCancelBridge.CANCEL.replace('/', '.'));
        Consumer<Object> body = event -> {
            try {
                call(method, event);
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        };
        return (Predicate<Object>) cancel.getMethod("predicate", Consumer.class).invoke(null, body);
    }

    private Object call(String method, Object event) throws Exception {
        for (Method each : game.loadClass(LISTENER.replace('/', '.')).getMethods()) {
            if (each.getName().equals(method)) {
                try {
                    return each.invoke(null, event);
                } catch (InvocationTargetException failure) {
                    if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
                    throw failure;
                }
            }
        }
        throw new AssertionError(LISTENER + " has no " + method);
    }

    /** Runs the boolean twin Retromod gave the old {@code @SubscribeEvent} method, as EventBus 7 would. */
    private Object twin(String handler, Object event) throws Exception {
        return game.loadClass(TWINS.replace('/', '.')).getMethod("retromod$cancellable$" + handler, event.getClass())
                .invoke(null, event);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object breakEvent(String preset) throws Exception {
        Class<?> result = game.loadClass("net.minecraftforge.common.util.Result");
        return game.loadClass(BREAK.replace('/', '.')).getConstructor(result)
                .newInstance(Enum.valueOf((Class) result, preset));
    }

    private static String result(Object event) throws Exception {
        return ((Enum<?>) event.getClass().getMethod("getResult").invoke(event)).name();
    }

    /** Old listener methods as javac wrote them for Forge 1.20.1, each taking the break event. */
    private static byte[] oldListener() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, LISTENER, null, "java/lang/Object", null);
        cancels(cw, "cancel", true);
        cancels(cw, "uncancel", false);
        cancels(cw, "cancelThenUncancel", true, false);
        MethodVisitor read = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "cancelled", "(L" + BREAK + ";)Z", null, null);
        read.visitCode();
        read.visitVarInsn(ALOAD, 0);
        read.visitMethodInsn(INVOKEVIRTUAL, BREAK, "isCanceled", "()Z", false);
        read.visitInsn(IRETURN);
        read.visitMaxs(0, 0);
        read.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Old {@code @SubscribeEvent} methods on the break event: one hands it to a helper and does not cancel, one
     * cancels, and one cancels and takes it back.
     */
    private static byte[] oldSubscriber() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, TWINS, null, "java/lang/Object", null);
        MethodVisitor look = subscribed(cw, "onLook");
        look.visitVarInsn(ALOAD, 0);
        look.visitMethodInsn(INVOKESTATIC, TWINS, "look", "(L" + BREAK + ";)V", false);
        look.visitInsn(RETURN);
        look.visitMaxs(0, 0);
        look.visitEnd();
        MethodVisitor helper = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "look", "(L" + BREAK + ";)V", null, null);
        helper.visitCode();
        helper.visitInsn(RETURN);
        helper.visitMaxs(0, 0);
        helper.visitEnd();
        for (String[] handler : new String[][]{{"onCancel", "1"}, {"onFlip", "10"}}) {
            MethodVisitor mv = subscribed(cw, handler[0]);
            for (char value : handler[1].toCharArray()) {
                mv.visitVarInsn(ALOAD, 0);
                mv.visitInsn(value == '1' ? ICONST_1 : ICONST_0);
                mv.visitMethodInsn(INVOKEVIRTUAL, BREAK, "setCanceled", "(Z)V", false);
            }
            mv.visitInsn(RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static MethodVisitor subscribed(ClassWriter cw, String name) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name, "(L" + BREAK + ";)V", null, null);
        mv.visitAnnotation("Lnet/minecraftforge/eventbus/api/SubscribeEvent;", true).visitEnd();
        mv.visitCode();
        return mv;
    }

    private static void cancels(ClassWriter cw, String name, boolean... values) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name, "(L" + BREAK + ";)V", null, null);
        mv.visitCode();
        for (boolean value : values) {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitInsn(value ? ICONST_1 : ICONST_0);
            mv.visitMethodInsn(INVOKEVIRTUAL, BREAK, "setCanceled", "(Z)V", false);
        }
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** Forge 26.2's BreakEvent as ForgeHooks builds it: a result preset to DENY for a restricted break, else DEFAULT. */
    private static ForgeHostStandIns hostClasses() {
        return new ForgeHostStandIns()
                .add("net/minecraftforge/common/util/Result.java", """
                        package net.minecraftforge.common.util;
                        public enum Result { ALLOW, DEFAULT, DENY }
                        """)
                .add("net/minecraftforge/common/util/HasResult.java", """
                        package net.minecraftforge.common.util;
                        public interface HasResult {
                            Result getResult();
                            void setResult(Result result);
                        }
                        """)
                .add("net/minecraftforge/event/level/BlockEvent.java", """
                        package net.minecraftforge.event.level;
                        import net.minecraftforge.common.util.HasResult;
                        import net.minecraftforge.common.util.Result;
                        public interface BlockEvent {
                            final class BreakEvent implements BlockEvent, HasResult {
                                private Result result;
                                public BreakEvent(Result preset) { result = preset; }
                                public Result getResult() { return result; }
                                public void setResult(Result value) { result = value; }
                            }
                        }
                        """);
    }
}
