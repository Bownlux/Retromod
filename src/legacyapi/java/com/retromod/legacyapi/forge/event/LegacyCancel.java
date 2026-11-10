/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.event;

import com.retromod.legacyapi.forge.LegacySharedSlots;
import com.retromod.shim.common.embedded.LegacyLog;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Forge 1.20.1's cancel flag for host events, which EventBus 7 replaced with the return value of each listener. */
public final class LegacyCancel {

    private static final String CANCELLABLE_BUS = "net.minecraftforge.eventbus.api.bus.CancellableEventBus";
    private static final String PROJECTILE_IMPACT = "net.minecraftforge.event.entity.ProjectileImpactEvent";
    // Forge fires it without reading a cancel, and Forge 1.20.1 gave it a result, never a cancel.
    private static final String NAME_TAG = "net.minecraftforge.client.event.RenderNameTagEvent";
    // Forge 26.2 fires it without reading a cancel and refuses the break when its result is DENY.
    private static final String BREAK = "net.minecraftforge.event.level.BlockEvent$BreakEvent";
    // Retromod's own rebuilt classes, in every mod's relocated copy, which a log line looks past for the caller.
    private static final String REBUILT = "com.retromod.legacyapi.";

    // A frame is one wrapped listener running on one event: {event, cancelled, read-only}.
    private static final int EVENT = 0;
    private static final int CANCELED = 1;
    private static final int READ_ONLY = 2;
    private static final int PROTOCOL = 1;

    // Every mod's copy shares the frames, so a library such as Zeta can cancel for the mod whose listener runs.
    @SuppressWarnings("unchecked")
    private static final ThreadLocal<ArrayDeque<Object[]>> FRAMES =
            LegacySharedSlots.get("forge.cancel.frames", PROTOCOL, ThreadLocal.class, ThreadLocal::new);

    // An event class's own isCancelable, isCanceled and setCanceled, as a rebuilt event has.
    private static final int IS_CANCELABLE = 0;
    private static final int IS_CANCELED = 1;
    private static final int SET_CANCELED = 2;
    private static final MethodHandle[] NONE = new MethodHandle[3];
    private static final Map<Class<?>, MethodHandle[]> OWN = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Boolean> CANCEL_CHANNEL = new ConcurrentHashMap<>();
    // ProjectileImpactEvent's getImpactResult and setImpactResult.
    private static final Map<Class<?>, MethodHandle[]> IMPACT = new ConcurrentHashMap<>();
    // BreakEvent's getResult and setResult.
    private static final Map<Class<?>, MethodHandle[]> BREAK_RESULT = new ConcurrentHashMap<>();
    // The result a cancel replaced with DENY, per BreakEvent, shared so another mod's un-cancel puts it back.
    @SuppressWarnings("unchecked")
    private static final Map<Object, Object> REPLACED_BY_CANCEL = LegacySharedSlots.get("forge.cancel.break",
            PROTOCOL, Map.class, () -> Collections.synchronizedMap(new WeakHashMap<>()));
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();
    // The listener methods that take cancelled events and ran on this thread: {receiver, listener, event}, held weakly.
    private static final ThreadLocal<List<Object[]>> RAN = ThreadLocal.withInitial(ArrayList::new);

    private LegacyCancel() {}

    /** Starts one listener's run on {@code event}, which reads as not cancelled until it cancels. */
    public static Object[] enter(Object event) {
        return push(event, false, false);
    }

    /** Ends the run {@code frame} started and says whether the listener left the event cancelled. */
    public static boolean exit(Object[] frame) {
        ArrayDeque<Object[]> frames = FRAMES.get();
        if (frames != null) frames.removeFirstOccurrence(frame);
        boolean cancelled = (Boolean) frame[CANCELED];
        // A read after the post, such as Carry On's isCanceled() after posting a break event, still sees it.
        if (cancelled && !(Boolean) frame[READ_ONLY]) remember(frame[EVENT], true);
        return cancelled;
    }

    /** As {@link #enter(Object)}, for a listener method that also takes cancelled events: notes that it ran on the event. */
    public static Object[] enter(Object event, Object receiver, String listener) {
        noteRun(receiver, listener, event);
        return enter(event);
    }

    /**
     * Ends a listener's run and says whether that listener left the event cancelled: a rebuilt event's own flag, or
     * the frame for any other event, so a result the host preset, such as a restricted break's DENY, is not read as
     * the listener's cancel and later listeners still run.
     */
    public static boolean leave(Object[] frame) {
        try {
            MethodHandle own = own(frame[EVENT].getClass())[IS_CANCELED];
            return own != null ? (boolean) call(own, frame[EVENT]) : (Boolean) frame[CANCELED];
        } finally {
            exit(frame);
        }
    }

    /**
     * The frame a {@code receiveCanceled} listener method's monitor runs it in, or null when the post was not
     * cancelled or the listener already ran on the event.
     */
    public static Object[] enterCancelled(Object event, boolean cancelled, Object receiver, String listener) {
        Object ran = takeRun(receiver, listener);
        if (!cancelled) return null;
        // Any listener may have cancelled, a newer mod's too, so a read after the post sees it as well.
        remember(event, true);
        // The note stays, so a second monitor of the same listener on this post, as a child bus that got its parent's
        // copy has, skips too.
        noteRun(receiver, listener, event);
        if (ran == event) return null;
        // The event reads as cancelled, and the post's answer is already settled.
        return push(event, true, true);
    }

    /** {@code listener} as an EventBus 7 predicate, which answers whether it cancelled the event. */
    public static Predicate<Object> predicate(Consumer<Object> listener) {
        return predicate(listener, false);
    }

    /** As {@link #predicate(Consumer)}; with {@code receiveCanceled} it also notes the event it ran on, for {@link #monitor}. */
    public static Predicate<Object> predicate(Consumer<Object> listener, boolean receiveCanceled) {
        return new CancelListener(listener, receiveCanceled);
    }

    /** Runs a {@code receiveCanceled} listener after a cancelled post, unless it already ran on that event. */
    public static void monitor(Predicate<Object> predicate, Object event, boolean cancelled) {
        // EventBus 7 stops at the first cancel, so this is the only place such a listener still sees a cancelled event.
        if (!(predicate instanceof CancelListener own) || own.ranOn == null) return;
        WeakReference<Object> ran = own.ranOn.get();
        own.ranOn.remove();
        if (!cancelled) return;
        // Any listener may have cancelled, a newer mod's too, so a read after the post sees it as well.
        remember(event, true);
        if (ran != null && ran.get() == event) return;
        // The event reads as cancelled, and the post's answer is already settled.
        Object[] frame = push(event, true, true);
        try {
            own.listener.accept(event);
        } finally {
            exit(frame);
        }
    }

    /** {@code event.setCanceled(cancel)}: the running listener's answer, or kept beside the event outside one. */
    public static void setCanceled(Object event, boolean cancel) {
        // A rebuilt event keeps its own flag.
        MethodHandle own = own(event.getClass())[SET_CANCELED];
        if (own != null) {
            call(own, event, cancel);
            return;
        }
        Object[] frame = innermost(event);
        if (frame != null && (Boolean) frame[READ_ONLY]) {
            warnOnce("monitor", event, "The old receiveCanceled listener in %s tried to change the cancel of %s "
                    + "after EventBus 7 had settled the post, so Retromod left it cancelled");
            return;
        }
        // The cancel still stops later listeners below; only the DENY result stops the break itself.
        if (event.getClass().getName().equals(BREAK)) setBreakDenied(event, cancel);
        if (frame != null) {
            frame[CANCELED] = cancel;
            return;
        }
        // Forge 1.20.1 answered a cancelled projectile impact by skipping the entity it hit.
        if (event.getClass().getName().equals(PROJECTILE_IMPACT)) {
            setImpactSkipped(event, cancel);
            return;
        }
        remember(event, cancel);
        if (cancel && !isCancelable(event)) {
            warnOnce("channel", event, "The old code in %s cancelled %s, which this Forge does not let a listener cancel, "
                    + "so Retromod kept the cancel only for that mod's own isCanceled() reads");
        }
    }

    /** {@code event.isCanceled()}, read from the same places {@link #setCanceled} writes to. */
    public static boolean isCanceled(Object event) {
        MethodHandle own = own(event.getClass())[IS_CANCELED];
        if (own != null) return (boolean) call(own, event);
        Object[] frame = innermost(event);
        // A listener the monitor runs after a cancelled post reads that settled answer, a break's result included.
        if (frame != null && (Boolean) frame[READ_ONLY]) return (Boolean) frame[CANCELED];
        // Forge 1.20.1 posted a restricted break already cancelled; Forge 26.2 presets DENY instead.
        if (event.getClass().getName().equals(BREAK)) return breakDenied(event);
        if (frame != null) return (Boolean) frame[CANCELED];
        if (event.getClass().getName().equals(PROJECTILE_IMPACT)) return impactSkipped(event);
        return Boolean.TRUE.equals(LegacyEventStore.get(event, LegacyEventStore.CANCELED));
    }

    /** Whether this host lets the event be cancelled: its own answer, a cancellable bus, or a projectile impact. */
    public static boolean isCancelable(Object event) {
        MethodHandle own = own(event.getClass())[IS_CANCELABLE];
        if (own != null) return (boolean) call(own, event);
        return cancelsThrough(event.getClass());
    }

    /** Whether a listener on events of this host type can cancel them, as {@link #isCancelable} answers for one. */
    public static boolean cancelsThrough(Class<?> type) {
        return CANCEL_CHANNEL.computeIfAbsent(type, LegacyCancel::hasCancelChannel);
    }

    /** EventBus 6.2's {@code cancel()}, which Forge 1.21 mods call. */
    public static void cancel(Object event) {
        setCanceled(event, true);
    }

    /** Keeps {@code cancelled} beside the event, for reads outside any listener such as after a post. */
    public static void remember(Object event, boolean cancelled) {
        LegacyEventStore.put(event, LegacyEventStore.CANCELED, cancelled);
    }

    private static void noteRun(Object receiver, String listener, Object event) {
        Object[] record = ran(receiver, listener, false);
        if (record == null) {
            RAN.get().add(new Object[]{new WeakReference<>(receiver), listener, new WeakReference<>(event)});
        } else {
            record[2] = new WeakReference<>(event);
        }
    }

    // The event the listener last ran on, which its record gives up, or null when it has not run since.
    private static Object takeRun(Object receiver, String listener) {
        Object[] record = ran(receiver, listener, true);
        return record == null ? null : ((WeakReference<?>) record[2]).get();
    }

    // Matched by the receiver's identity, null for a static listener, and by the listener's name.
    private static Object[] ran(Object receiver, String listener, boolean remove) {
        for (Iterator<Object[]> records = RAN.get().iterator(); records.hasNext(); ) {
            Object[] record = records.next();
            if (((WeakReference<?>) record[0]).get() != receiver || !record[1].equals(listener)) continue;
            if (remove) records.remove();
            return record;
        }
        return null;
    }

    private static Object[] push(Object event, boolean cancelled, boolean readOnly) {
        ArrayDeque<Object[]> frames = FRAMES.get();
        if (frames == null) {
            frames = new ArrayDeque<>();
            FRAMES.set(frames);
        }
        Object[] frame = {event, cancelled, readOnly};
        frames.push(frame);
        return frame;
    }

    // Compared by identity: a record event equals another with the same components.
    private static Object[] innermost(Object event) {
        ArrayDeque<Object[]> frames = FRAMES.get();
        if (frames == null) return null;
        for (Object[] frame : frames) {
            if (frame[EVENT] == event) return frame;
        }
        return null;
    }

    private static boolean hasCancelChannel(Class<?> type) {
        if (type.getName().equals(NAME_TAG)) return false;
        if (type.getName().equals(PROJECTILE_IMPACT)) return true;
        try {
            Field bus = type.getField("BUS");
            if (!Modifier.isStatic(bus.getModifiers())) return false;
            // EventBus 7 builds a cancellable bus for every Cancellable event, even under a BUS field typed plain
            // EventBus, as PlayLevelSoundEvent.AtEntity has; the bus object says which it built.
            Object value = bus.get(null);
            return isCancellableBus(value == null ? bus.getType() : value.getClass(), new HashSet<>());
        } catch (ReflectiveOperationException | LinkageError none) {
            return false;
        }
    }

    // Matched by name, so this class needs nothing from EventBus.
    private static boolean isCancellableBus(Class<?> type, Set<Class<?>> seen) {
        if (type == null || !seen.add(type)) return false;
        if (type.getName().equals(CANCELLABLE_BUS)) return true;
        for (Class<?> parent : type.getInterfaces()) {
            if (isCancellableBus(parent, seen)) return true;
        }
        return isCancellableBus(type.getSuperclass(), seen);
    }

    private static MethodHandle[] own(Class<?> type) {
        return OWN.computeIfAbsent(type, LegacyCancel::lookUp);
    }

    private static MethodHandle[] lookUp(Class<?> type) {
        // The nearest public class answers for the event: a call through it still reaches a subclass's override.
        Class<?> visible = type;
        while (visible != null && !Modifier.isPublic(visible.getModifiers())) visible = visible.getSuperclass();
        if (visible == null) return NONE;
        MethodHandle[] own = {find(visible, "isCancelable"), find(visible, "isCanceled"),
                find(visible, "setCanceled", boolean.class)};
        return own[IS_CANCELABLE] == null && own[IS_CANCELED] == null && own[SET_CANCELED] == null ? NONE : own;
    }

    private static MethodHandle find(Class<?> type, String name, Class<?>... parameters) {
        try {
            return MethodHandles.publicLookup().unreflect(type.getMethod(name, parameters));
        } catch (NoSuchMethodException | IllegalAccessException | LinkageError none) {
            return null;
        }
    }

    private static Object call(MethodHandle handle, Object... arguments) {
        try {
            return handle.invokeWithArguments(arguments);
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static boolean impactSkipped(Object event) {
        Object result = call(impactMembers(event)[0], event);
        return result instanceof Enum<?> constant && constant.name().equals("SKIP_ENTITY");
    }

    // An un-cancel resets only the skip a cancel set, and leaves any other result a listener chose.
    private static void setImpactSkipped(Object event, boolean skip) {
        if (!skip && !impactSkipped(event)) return;
        MethodHandle setter = impactMembers(event)[1];
        call(setter, event, enumConstant(setter.type().parameterType(1), skip ? "SKIP_ENTITY" : "DEFAULT"));
    }

    private static MethodHandle[] impactMembers(Object event) {
        return IMPACT.computeIfAbsent(event.getClass(),
                type -> new MethodHandle[]{publicMethod(type, "getImpactResult"), publicMethod(type, "setImpactResult")});
    }

    private static boolean breakDenied(Object event) {
        Object result = call(breakResultMembers(event)[0], event);
        return result instanceof Enum<?> constant && constant.name().equals("DENY");
    }

    // A cancel denies the break and keeps the result it replaced; an un-cancel puts back only what a cancel replaced,
    // so a denial the host preset or another mod chose stays.
    private static void setBreakDenied(Object event, boolean deny) {
        MethodHandle setter = breakResultMembers(event)[1];
        if (!deny) {
            Object replaced = REPLACED_BY_CANCEL.remove(event);
            if (replaced != null) call(setter, event, replaced);
            return;
        }
        if (breakDenied(event)) return;
        REPLACED_BY_CANCEL.put(event, call(breakResultMembers(event)[0], event));
        call(setter, event, enumConstant(setter.type().parameterType(1), "DENY"));
    }

    private static MethodHandle[] breakResultMembers(Object event) {
        return BREAK_RESULT.computeIfAbsent(event.getClass(),
                type -> new MethodHandle[]{publicMethod(type, "getResult"), publicMethod(type, "setResult")});
    }

    private static MethodHandle publicMethod(Class<?> type, String name) {
        try {
            for (Method method : type.getMethods()) {
                if (method.getName().equals(name)) return MethodHandles.publicLookup().unreflect(method);
            }
        } catch (IllegalAccessException | LinkageError unreachable) {
            // Reported below with the member's name.
        }
        throw new IllegalStateException(type.getName() + " has no public " + name);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumConstant(Class<?> type, String name) {
        return Enum.valueOf((Class) type, name);
    }

    // One line per kind, event class and calling class; the message's two %s are the caller and the event.
    private static void warnOnce(String kind, Object event, String message) {
        String caller = caller();
        String type = event.getClass().getName();
        if (WARNED.add(kind + " " + type + " " + caller)) LegacyLog.warn(String.format(message, caller, type));
    }

    // The first class on the stack that is not Retromod's rebuilt code or the JDK: the mod's listener or helper.
    private static String caller() {
        return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).walk(frames -> frames
                .map(frame -> frame.getDeclaringClass().getName())
                .filter(name -> !name.contains(REBUILT) && !name.startsWith("java.") && !name.startsWith("jdk.")
                        && !name.startsWith("sun."))
                .findFirst().orElse("an unknown class"));
    }

    /** One old listener as a predicate: true when it left the event cancelled. */
    private static final class CancelListener implements Predicate<Object> {
        private final Consumer<Object> listener;
        // The event this listener last ran on, per thread; only a receiveCanceled listener's monitor reads it.
        private final ThreadLocal<WeakReference<Object>> ranOn;

        CancelListener(Consumer<Object> listener, boolean receiveCanceled) {
            this.listener = listener;
            this.ranOn = receiveCanceled ? new ThreadLocal<>() : null;
        }

        @Override
        public boolean test(Object event) {
            if (ranOn != null) ranOn.set(new WeakReference<>(event));
            Object[] frame = enter(event);
            try {
                listener.accept(event);
            } catch (Throwable failure) {
                exit(frame);
                throw failure;
            }
            // The same answer a listener method's twin gives.
            return leave(frame);
        }
    }
}
