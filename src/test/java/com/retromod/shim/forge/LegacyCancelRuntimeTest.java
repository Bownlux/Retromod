/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.shim.common.RebuiltApiClasses;
import com.retromod.shim.common.embedded.LegacyLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** Each mod's relocated copy of LegacyCancel shares one cancel state per thread, keyed by the event's identity. */
class LegacyCancelRuntimeTest {

    private static final String CANCEL = LegacyCancelBridge.CANCEL;
    private static final String STORE = RebuiltApiClasses.ROOT + "forge/event/LegacyEventStore";
    private static final String FRAMES_KEY = "retromod.shared.forge.cancel.frames.v1";
    private static final String STORE_KEY = "retromod.shared.forge.event.store.v1";
    private static final String PROJECTILE_IMPACT = "net/minecraftforge/event/entity/ProjectileImpactEvent";
    private static final String CANCELLABLE_BUS = "net/minecraftforge/eventbus/api/bus/CancellableEventBus";
    private static final String EVENT_BUS = "net/minecraftforge/eventbus/api/bus/EventBus";
    private static final String NAME_TAG = "net/minecraftforge/client/event/RenderNameTagEvent";

    private Class<?> first;
    private Class<?> second;

    record Click(int x) {}

    @BeforeEach
    void twoMods() throws Exception {
        clearSharedSlots();
        first = copy("com/retromod/embedded/first/").loadClass(dotted("com/retromod/embedded/first/" + CANCEL));
        second = copy("com/retromod/embedded/second/").loadClass(dotted("com/retromod/embedded/second/" + CANCEL));
        assertNotSame(first, second);
    }

    @AfterEach
    void clearSharedSlots() {
        System.getProperties().remove(FRAMES_KEY);
        System.getProperties().remove(STORE_KEY);
    }

    @Test
    @DisplayName("Two mods' copies share one frame stack, so a library can cancel for the mod whose listener runs")
    void copiesShareFrames() throws Exception {
        Object event = new Object();
        Object frame = call(first, "enter", event);
        call(second, "setCanceled", event, true);
        assertEquals(true, call(second, "isCanceled", event));
        assertEquals(true, call(first, "exit", frame));
        assertTrue(frames().isEmpty(), "the frame is gone once the listener ends");
    }

    @Test
    @DisplayName("Frames and remembered results are matched by identity, so equal records stay separate events")
    void equalRecordsStaySeparate() throws Exception {
        Click outer = new Click(1);
        Click inner = new Click(1);
        assertEquals(outer, inner);
        Object outerFrame = call(first, "enter", outer);
        Object innerFrame = call(first, "enter", inner);
        call(second, "setCanceled", outer, true);
        assertEquals(false, call(first, "exit", innerFrame));
        assertEquals(true, call(first, "exit", outerFrame));

        Click remembered = new Click(2);
        call(first, "remember", remembered, true);
        assertEquals(true, call(second, "isCanceled", remembered));
        assertEquals(false, call(second, "isCanceled", new Click(2)));
    }

    @Test
    @DisplayName("A listener that cancels and then un-cancels leaves the event not cancelled, and reads false at entry")
    void cancelThenUncancel() throws Exception {
        Object event = new Object();
        List<Boolean> atEntry = new ArrayList<>();
        Predicate<Object> listener = predicate(first, e -> {
            atEntry.add(isCanceled(second, e));
            setCanceled(second, e, true);
            setCanceled(second, e, false);
        });
        assertFalse(listener.test(event));
        assertEquals(List.of(false), atEntry);
        assertEquals(false, call(first, "isCanceled", event));

        Predicate<Object> canceller = predicate(second, e -> setCanceled(first, e, true));
        assertTrue(canceller.test(event), "EventBus 7 reads the cancel from the predicate's answer");
        assertEquals(true, call(first, "isCanceled", event), "and a read after the post still sees it");
    }

    @Test
    @DisplayName("A listener that throws leaves no frame behind")
    void throwingListenerPopsItsFrame() throws Exception {
        Object event = new Object();
        Predicate<Object> listener = predicate(first, e -> {
            setCanceled(second, e, true);
            throw new IllegalStateException("listener failed");
        });
        assertThrows(IllegalStateException.class, () -> listener.test(event));
        assertTrue(frames().isEmpty());
        assertEquals(true, call(second, "isCanceled", event), "the cancel it set first stays, as it stayed on a Forge 1.20.1 event");
    }

    @Test
    @DisplayName("A remembered result answers isCanceled, and an event that is collected leaves no entry")
    void rememberedResultsDoNotPinEvents() throws Exception {
        Object kept = new Object();
        call(first, "remember", kept, true);
        assertEquals(true, call(second, "isCanceled", kept));

        WeakReference<Object> collected = rememberGarbage();
        for (int i = 0; i < 50 && collected.get() != null; i++) {
            System.gc();
            Thread.sleep(10);
        }
        assertNull(collected.get(), "the store holds events weakly");
        Class<?> store = first.getClassLoader().loadClass(dotted("com/retromod/embedded/first/" + STORE));
        assertEquals(1, store.getMethod("size").invoke(null), "only the event still alive keeps an entry");
        assertEquals(true, call(second, "isCanceled", kept));
    }

    @Test
    @DisplayName("A receiveCanceled listener runs read-only after a cancelled post it did not see, and once only")
    void receiveCanceledListenerRunsAfterTheChain() throws Exception {
        Object event = new Object();
        List<Boolean> seen = new ArrayList<>();
        Predicate<Object> listener = predicateReceivingCanceled(first, e -> {
            seen.add(isCanceled(second, e));
            setCanceled(second, e, false);
        });
        // An earlier listener cancelled, so EventBus 7 never called this one.
        monitor(first, listener, event, true);
        assertEquals(List.of(true), seen, "it sees the event cancelled");
        assertEquals(true, call(first, "isCanceled", event), "and cannot un-cancel it after the post");

        // It ran itself, and a later listener cancelled.
        seen.clear();
        Object other = new Object();
        assertFalse(listener.test(other));
        monitor(first, listener, other, true);
        assertEquals(List.of(false), seen, "a listener that already ran on the event is not run again");

        // A post nobody cancelled leaves it alone.
        seen.clear();
        monitor(first, listener, new Object(), false);
        assertEquals(List.of(), seen);
    }

    @Test
    @DisplayName("A cancelled projectile impact skips the entity, as Forge 1.20.1's hook answered, and an un-cancel resets it")
    void projectileImpactSkipsTheEntity() throws Exception {
        ClassLoader host = new FakeHost(Map.of(PROJECTILE_IMPACT, projectileImpact(),
                PROJECTILE_IMPACT + "$ImpactResult", impactResult()));
        Object impact = host.loadClass(dotted(PROJECTILE_IMPACT)).getConstructor().newInstance();
        Method result = impact.getClass().getMethod("getImpactResult");

        assertEquals(true, call(first, "isCancelable", impact));
        call(first, "setCanceled", impact, true);
        assertEquals("SKIP_ENTITY", ((Enum<?>) result.invoke(impact)).name());
        assertEquals(true, call(second, "isCanceled", impact));
        call(second, "setCanceled", impact, false);
        assertEquals("DEFAULT", ((Enum<?>) result.invoke(impact)).name());
        assertEquals(false, call(first, "isCanceled", impact));
    }

    @Test
    @DisplayName("An event is cancelable where its static BUS is a CancellableEventBus, and its own methods answer first")
    void cancelableFromTheBusOrTheEventsOwnMethods() throws Exception {
        ClassLoader host = new FakeHost(Map.of(CANCELLABLE_BUS, cancellableBus(),
                "host/CancellableEvent", eventWithBus("host/CancellableEvent", CANCELLABLE_BUS),
                "host/PlainEvent", eventWithBus("host/PlainEvent", "java/lang/Object")));
        assertEquals(true, call(first, "isCancelable", host.loadClass("host.CancellableEvent").getConstructor().newInstance()));
        assertEquals(false, call(first, "isCancelable", host.loadClass("host.PlainEvent").getConstructor().newInstance()));

        // PlayLevelSoundEvent.AtEntity's BUS field names a plain EventBus, but EventBus 7 built a cancellable one.
        ClassLoader buses = new FakeHost(Map.of(EVENT_BUS, busInterface(EVENT_BUS, null),
                CANCELLABLE_BUS, busInterface(CANCELLABLE_BUS, EVENT_BUS), "host/CancellableBusImpl", cancellableBusImpl(),
                "host/SoundEvent", eventWithBusValue("host/SoundEvent"), NAME_TAG, eventWithBusValue(NAME_TAG)));
        Object sound = buses.loadClass("host.SoundEvent").getConstructor().newInstance();
        assertEquals(true, call(first, "isCancelable", sound), "the bus object decides, not the field's type");
        Object nameTag = buses.loadClass(dotted(NAME_TAG)).getConstructor().newInstance();
        assertEquals(false, call(first, "isCancelable", nameTag), "Forge fires the name tag event without reading a cancel");
        assertEquals(false, call(first, "cancelsThrough", nameTag.getClass()));

        OwnFlag own = new OwnFlag();
        Object frame = call(first, "enter", own);
        call(second, "setCanceled", own, true);
        assertTrue(own.cancelled, "an event with its own flag keeps it");
        assertEquals(false, call(first, "exit", frame), "and the frame is left alone");
        assertEquals(true, call(second, "isCanceled", own));
    }

    /** An event with its own cancel methods, as a rebuilt event has. */
    public static final class OwnFlag {
        boolean cancelled;

        public boolean isCancelable() {
            return true;
        }

        public boolean isCanceled() {
            return cancelled;
        }

        public void setCanceled(boolean cancel) {
            cancelled = cancel;
        }
    }

    @Test
    @DisplayName("leave answers from an event's own flag where it keeps one, and from the listener's frame for a host event")
    void leaveReadsTheEventsOwnFlag() throws Exception {
        OwnFlag rebuilt = new OwnFlag();
        Object rebuiltFrame = call(first, "enter", rebuilt);
        call(second, "setCanceled", rebuilt, true);
        assertEquals(true, call(first, "leave", rebuiltFrame), "a rebuilt event keeps its cancel in itself");

        Object host = new Object();
        Object hostFrame = call(first, "enter", host);
        call(second, "setCanceled", host, true);
        assertEquals(true, call(first, "leave", hostFrame));
        assertTrue(frames().isEmpty());
    }

    @Test
    @DisplayName("A receiveCanceled listener's monitor runs it only after a cancel it did not already see, in a read-only frame")
    void monitorEntryRunsOnlyWhenTheListenerMissedTheCancel() throws Exception {
        Object seen = new Object();
        call(first, "exit", call(first, "enter", seen, null, "mod.Handlers.onWatch"));
        assertNull(call(first, "enterCancelled", seen, true, null, "mod.Handlers.onWatch"), "it ran before the cancel");

        Object skipped = new Object();
        assertNull(call(first, "enterCancelled", skipped, false, null, "mod.Handlers.onWatch"), "no cancel, nothing to run");
        Object frame = call(first, "enterCancelled", skipped, true, null, "mod.Handlers.onWatch");
        assertNotNull(frame, "a cancel skipped it");
        assertEquals(true, call(second, "isCanceled", skipped));
        call(second, "setCanceled", skipped, false);
        assertEquals(true, call(second, "isCanceled", skipped), "the post's answer is settled by then");
        call(first, "exit", frame);
        assertEquals(true, call(second, "isCanceled", skipped), "a read after the post sees the cancel");
    }

    // ---- calls into one copy ----

    private WeakReference<Object> rememberGarbage() throws Exception {
        Object garbage = new Object();
        call(first, "remember", garbage, true);
        return new WeakReference<>(garbage);
    }

    @SuppressWarnings("unchecked")
    private static Predicate<Object> predicate(Class<?> copy, Consumer<Object> listener) throws Exception {
        return (Predicate<Object>) copy.getMethod("predicate", Consumer.class).invoke(null, listener);
    }

    @SuppressWarnings("unchecked")
    private static Predicate<Object> predicateReceivingCanceled(Class<?> copy, Consumer<Object> listener) throws Exception {
        return (Predicate<Object>) copy.getMethod("predicate", Consumer.class, boolean.class).invoke(null, listener, true);
    }

    private static void monitor(Class<?> copy, Predicate<Object> predicate, Object event, boolean cancelled) throws Exception {
        copy.getMethod("monitor", Predicate.class, Object.class, boolean.class).invoke(null, predicate, event, cancelled);
    }

    private static boolean isCanceled(Class<?> copy, Object event) {
        try {
            return (boolean) call(copy, "isCanceled", event);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void setCanceled(Class<?> copy, Object event, boolean cancel) {
        try {
            call(copy, "setCanceled", event, cancel);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static Object call(Class<?> copy, String name, Object... arguments) throws Exception {
        for (Method method : copy.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == arguments.length) {
                try {
                    return method.invoke(null, arguments);
                } catch (InvocationTargetException failure) {
                    if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
                    throw failure;
                }
            }
        }
        throw new AssertionError(copy.getName() + " has no " + name);
    }

    @SuppressWarnings("unchecked")
    private static ArrayDeque<Object[]> frames() {
        ThreadLocal<ArrayDeque<Object[]>> frames = (ThreadLocal<ArrayDeque<Object[]>>) System.getProperties().get(FRAMES_KEY);
        ArrayDeque<Object[]> stack = frames.get();
        return stack == null ? new ArrayDeque<>() : stack;
    }

    // ---- loaders ----

    /** One mod's copy of LegacyCancel and every Retromod class it names, relocated under {@code prefix} as the embedder does. */
    private static ClassLoader copy(String prefix) throws IOException {
        Remapper relocate = new Remapper() {
            @Override
            public String map(String internalName) {
                return internalName.startsWith("com/retromod/") ? prefix + internalName : internalName;
            }
        };
        List<String> names = new ArrayList<>(List.of(CANCEL, LegacyLog.class.getName().replace('.', '/')));
        Map<String, byte[]> classes = new HashMap<>();
        while (!names.isEmpty()) {
            String name = names.remove(0);
            if (classes.containsKey(prefix + name)) continue;
            byte[] bytes = read(name);
            ClassWriter writer = new ClassWriter(0);
            new ClassReader(bytes).accept(new ClassRemapper(writer, relocate), 0);
            classes.put(prefix + name, writer.toByteArray());
            names.addAll(RebuiltApiClasses.referencedClasses(bytes));
        }
        // Only the JDK below it, as in a mod's own class loader for these classes.
        return new LegacyEventRuntimeTest.Loader(ClassLoader.getPlatformClassLoader(), classes);
    }

    private static byte[] read(String internalName) throws IOException {
        try (InputStream in = LegacyCancelRuntimeTest.class.getClassLoader().getResourceAsStream(internalName + ".class")) {
            if (in == null) throw new IOException("missing class " + internalName);
            return in.readAllBytes();
        }
    }

    private static String dotted(String internalName) {
        return internalName.replace('/', '.');
    }

    /** Defines the given host classes; the JDK below them. */
    private static final class FakeHost extends ClassLoader {
        private final Map<String, byte[]> classes;

        FakeHost(Map<String, byte[]> classes) {
            super(ClassLoader.getPlatformClassLoader());
            this.classes = classes;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = classes.get(name.replace('.', '/'));
            if (bytes == null) throw new ClassNotFoundException(name);
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    // ---- host classes with the shapes Forge 26.2 gives them ----

    /** {@code ProjectileImpactEvent} with only its impact result: {@code get/setImpactResult}, starting at DEFAULT. */
    private static byte[] projectileImpact() {
        String result = "L" + PROJECTILE_IMPACT + "$ImpactResult;";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, PROJECTILE_IMPACT, null, "java/lang/Object", null);
        cw.visitField(ACC_PRIVATE, "result", result, null, null).visitEnd();
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitVarInsn(ALOAD, 0);
        init.visitFieldInsn(GETSTATIC, PROJECTILE_IMPACT + "$ImpactResult", "DEFAULT", result);
        init.visitFieldInsn(PUTFIELD, PROJECTILE_IMPACT, "result", result);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor get = cw.visitMethod(ACC_PUBLIC, "getImpactResult", "()" + result, null, null);
        get.visitCode();
        get.visitVarInsn(ALOAD, 0);
        get.visitFieldInsn(GETFIELD, PROJECTILE_IMPACT, "result", result);
        get.visitInsn(ARETURN);
        get.visitMaxs(0, 0);
        get.visitEnd();
        MethodVisitor set = cw.visitMethod(ACC_PUBLIC, "setImpactResult", "(" + result + ")V", null, null);
        set.visitCode();
        set.visitVarInsn(ALOAD, 0);
        set.visitVarInsn(ALOAD, 1);
        set.visitFieldInsn(PUTFIELD, PROJECTILE_IMPACT, "result", result);
        set.visitInsn(RETURN);
        set.visitMaxs(0, 0);
        set.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code enum ImpactResult { DEFAULT, SKIP_ENTITY }}. */
    private static byte[] impactResult() {
        String name = PROJECTILE_IMPACT + "$ImpactResult";
        String desc = "L" + name + ";";
        String[] constants = {"DEFAULT", "SKIP_ENTITY"};
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER | ACC_ENUM, name, null, "java/lang/Enum", null);
        for (String constant : constants) {
            cw.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL | ACC_ENUM, constant, desc, null, null).visitEnd();
        }
        MethodVisitor init = cw.visitMethod(ACC_PRIVATE, "<init>", "(Ljava/lang/String;I)V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 1);
        init.visitVarInsn(ILOAD, 2);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Enum", "<init>", "(Ljava/lang/String;I)V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        // Enum.valueOf reads the constants through values().
        MethodVisitor values = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "values", "()[" + desc, null, null);
        values.visitCode();
        values.visitLdcInsn(constants.length);
        values.visitTypeInsn(ANEWARRAY, name);
        for (int i = 0; i < constants.length; i++) {
            values.visitInsn(DUP);
            values.visitLdcInsn(i);
            values.visitFieldInsn(GETSTATIC, name, constants[i], desc);
            values.visitInsn(AASTORE);
        }
        values.visitInsn(ARETURN);
        values.visitMaxs(0, 0);
        values.visitEnd();
        MethodVisitor clinit = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        for (int i = 0; i < constants.length; i++) {
            clinit.visitTypeInsn(NEW, name);
            clinit.visitInsn(DUP);
            clinit.visitLdcInsn(constants[i]);
            clinit.visitLdcInsn(i);
            clinit.visitMethodInsn(INVOKESPECIAL, name, "<init>", "(Ljava/lang/String;I)V", false);
            clinit.visitFieldInsn(PUTSTATIC, name, constants[i], desc);
        }
        clinit.visitInsn(RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** An empty bus interface, extending {@code parent} when given. */
    private static byte[] busInterface(String name, String parent) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC | ACC_ABSTRACT | ACC_INTERFACE, name, null, "java/lang/Object",
                parent == null ? null : new String[]{parent});
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** What EventBus 7 builds for a Cancellable event: a class implementing CancellableEventBus. */
    private static byte[] cancellableBusImpl() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, "host/CancellableBusImpl", null, "java/lang/Object",
                new String[]{CANCELLABLE_BUS});
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code public final class name { public static final EventBus BUS = new CancellableBusImpl(); }}. */
    private static byte[] eventWithBusValue(String name) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, name, null, "java/lang/Object", null);
        cw.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "BUS", "L" + EVENT_BUS + ";", null, null).visitEnd();
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor clinit = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitTypeInsn(NEW, "host/CancellableBusImpl");
        clinit.visitInsn(DUP);
        clinit.visitMethodInsn(INVOKESPECIAL, "host/CancellableBusImpl", "<init>", "()V", false);
        clinit.visitFieldInsn(PUTSTATIC, name, "BUS", "L" + EVENT_BUS + ";");
        clinit.visitInsn(RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] cancellableBus() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC | ACC_ABSTRACT | ACC_INTERFACE, CANCELLABLE_BUS, null, "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code public final class name { public static final <busType> BUS; }} with a no-argument constructor. */
    private static byte[] eventWithBus(String name, String busType) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, name, null, "java/lang/Object", null);
        cw.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "BUS", "L" + busType + ";", null, null).visitEnd();
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
