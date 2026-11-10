/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.eventbus;

import com.retromod.legacyapi.forge.capabilities.LegacyAttachListeners;
import com.retromod.legacyapi.forge.event.LegacyCancel;
import com.retromod.legacyapi.forge.fml.LegacyModNamespaces;
import com.retromod.shim.common.embedded.LegacyLog;
import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.eventbus.api.bus.EventBus;
import net.minecraftforge.eventbus.api.event.InheritableEvent;
import net.minecraftforge.eventbus.api.listener.EventListener;
import net.minecraftforge.eventbus.api.listener.Priority;
import net.minecraftforge.fml.ModLoader;
import net.minecraftforge.fml.core.ModStateProvider;
import net.minecraftforge.fml.event.IModBusEvent;

/** Forge 1.20.1's dispatch rules for legacy events, kept on the EventBus 7 buses every mod's copy shares. */
public final class LegacyEvents {

    // Each mod embeds its own copies, so a class or annotation from another copy is matched by the end of its name.
    private static final String LEGACY_EVENT = "com.retromod.legacyapi.forge.eventbus.LegacyEvent";
    private static final String LEGACY_GENERIC_EVENT = "com.retromod.legacyapi.forge.eventbus.LegacyGenericEvent";
    private static final String LEGACY_SUBSCRIBE = "com.retromod.legacyapi.forge.eventbus.LegacySubscribe";
    private static final String SUBSCRIBE7 = "net.minecraftforge.eventbus.api.listener.SubscribeEvent";
    private static final String[] HOST_PACKAGES = {"net.minecraftforge.", "net.minecraft.", "com.mojang.", "java."};

    // HIGHEST starts at 111, so it plus the deepest class level still fits below EventBus 7's top byte.
    private static final int HIGHEST_BAND = 111;
    private static final int DEEPEST = 15;

    private static final Map<Class<?>, Integer> DEPTHS = new ConcurrentHashMap<>();
    private static final Map<BusGroup, Set<Object>> SUBSCRIBED = new ConcurrentHashMap<>();
    // Each class's BUS field, looked up once: a class without one would otherwise build an exception on every
    // call, and render events ask every frame.
    private static final ClassValue<Optional<Field>> BUS_FIELDS = new ClassValue<>() {
        @Override
        protected Optional<Field> computeValue(Class<?> type) {
            busFieldLookups.incrementAndGet();
            return Optional.ofNullable(busField(type));
        }
    };
    // How many classes have had their BUS field looked up, which tests read.
    private static final AtomicInteger busFieldLookups = new AtomicInteger();
    private static volatile MethodHandle post;
    private static volatile boolean gameBusStarted;

    private LegacyEvents() {}

    /** Whether the class is an event an old mod built on its copy of the rebuilt base. */
    public static boolean isLegacyEvent(Class<?> type) {
        return depth(type) > 0;
    }

    /** {@code bus.post(event)}: a legacy event with Forge 1.20.1's rules, a host event on the host's own bus. */
    public static boolean post(BusGroup group, Object event) {
        Class<?> type = event.getClass();
        if (!isLegacyEvent(type)) return postHostEvent(group, type, event);
        // Forge 1.20.1 built the game bus shut down, and started it once mod loading completed.
        if (group == BusGroup.DEFAULT && !gameBusStarted()) return false;
        EventBus<?> bus = legacyBus(group, type);
        if (bus == null) return false;
        invokePost(bus, event);
        // The flag is read after the last listener, so a later listener's un-cancel counts.
        return LegacyEventAccess.isCancelable(event) && LegacyEventAccess.isCanceled(event);
    }

    /** A host event's post, with the cancel kept beside the event, since EventBus 7 keeps it nowhere. */
    private static boolean postHostEvent(BusGroup group, Class<?> type, Object event) {
        // EventBus 7 has no flag to hand a cancel to, so an event an old mod cancelled before posting is not posted.
        if (LegacyCancel.isCancelable(event) && LegacyCancel.isCanceled(event)) return true;
        boolean cancelled = invokePost(busOf(group, type), event);
        // So that isCanceled() after the post, which Carry On and JEI read, answers as the post did.
        LegacyCancel.remember(event, cancelled);
        // A projectile impact keeps an old cancel as its impact result, which a post on its plain bus does not report.
        return cancelled || LegacyCancel.isCanceled(event);
    }

    /** {@code bus.register(target)} for old handlers of mod events; true when EventBus 7 handlers are left for the group. */
    public static boolean subscribe(BusGroup group, Object target) {
        // Forge 1.20.1 took static handlers from a class and public instance handlers from an object.
        boolean statics = target instanceof Class<?>;
        Class<?> owner = statics ? (Class<?>) target : target.getClass();
        Method[] methods;
        try {
            methods = owner.getMethods();
        } catch (LinkageError unreadable) {
            // EventBus 7's own registration reports the class it cannot read.
            return true;
        }
        Map<Method, Annotation> handlers = new LinkedHashMap<>();
        for (Method method : methods) {
            if (Modifier.isStatic(method.getModifiers()) != statics) continue;
            Annotation subscription = statics ? legacyAnnotation(method) : inheritedSubscription(owner, method);
            if (subscription != null) handlers.put(method, subscription);
        }
        // A second registration of the same object or class added nothing on Forge 1.20.1.
        if (!handlers.isEmpty() && SUBSCRIBED.computeIfAbsent(group, any -> ConcurrentHashMap.newKeySet()).add(target)) {
            handlers.forEach((method, subscription) -> hook(group, method, statics ? null : target, subscription, target));
        }
        return hasEventBus7Handler(owner, statics);
    }

    /** {@code bus.unregister(target)}: removes what the group's registrations of {@code target} added, so a later register hooks it again. */
    public static void unsubscribe(BusGroup group, Object target) {
        Set<Object> subscribed = SUBSCRIBED.get(group);
        if (subscribed != null) subscribed.remove(target);
        LegacyListenerSets.forget(group, target);
    }

    /** Adds a listener to a legacy event's bus with Forge 1.20.1's order, cancel, phase and generic rules. */
    public static void addListener(BusGroup group, Class<?> type, byte priority, boolean receiveCanceled,
            Type filter, Consumer<Object> listener) {
        // EventBus 6 removed a listener added this way when the mod unregistered the listener itself.
        addListener(group, type, priority, receiveCanceled, filter, listener, listener);
    }

    private static void addListener(BusGroup group, Class<?> type, byte priority, boolean receiveCanceled,
            Type filter, Consumer<Object> listener, Object registered) {
        EventBus<?> bus = legacyBus(group, type);
        if (bus == null) return;
        // Only the game bus tracked phases; a mod bus leaves the phase unset.
        byte phase = group == BusGroup.DEFAULT ? priority : LegacyEvent.NO_PHASE;
        Consumer<Object> rules = event -> {
            if (!LegacyEventAccess.deliver(event, receiveCanceled, phase)) return;
            if (filter != null && LegacyEventAccess.getGenericType(event) != filter) return;
            listener.accept(event);
        };
        LegacyListenerSets.remember(group, registered, bus, addTo(bus, order(priority, depth(type)), rules));
    }

    /** {@code bus.addGenericListener(filter, ...)}: a legacy generic event filters by type; a host event must still report one. */
    public static void addGenericListener(BusGroup group, Class<?> type, Class<?> filter, byte priority,
            boolean receiveCanceled, Consumer<Object> listener) {
        if (type == null) {
            LegacyLog.warnOnce("generic listener " + listener.getClass().getName(), "The generic event listener "
                    + listener.getClass().getName() + " names no event type Retromod can read, so it will not run");
            return;
        }
        if (isLegacyEvent(type)) {
            addListener(group, type, priority, receiveCanceled, filter, listener);
            return;
        }
        // Forge 26.2 fires one attach event per holder kind, so the filter picks the event; none is cancellable.
        if (LegacyAttachListeners.isSplitAttachEvent(type)) {
            LegacyAttachListeners.add(group, type, filter, priority, listener);
            return;
        }
        Method genericType = genericTypeGetter(type);
        if (genericType == null) {
            LegacyLog.warnOnce("generic listener " + type.getName() + " " + listener.getClass().getName(),
                    "The generic event listener " + listener.getClass().getName() + " listens to " + type.getName()
                            + ", which no longer reports a generic type on this Forge, so it will not run");
            return;
        }
        // Forge 1.20.1 compared the event's generic type with the filter by identity.
        EventBus<?> bus = busOf(group, type);
        LegacyListenerSets.remember(group, listener, bus, addTo(bus, priority, event -> {
            if (call(genericType, event) == filter) listener.accept(event);
        }));
    }

    /** The bus a listener or post of {@code type} belongs on: its own BUS, or a bus in the group that takes it. */
    public static EventBus<?> busOf(BusGroup group, Class<?> type) {
        EventBus<?> own = staticBus(type);
        if (own != null) return own;
        // A mod's group refuses every event but its own and mod-bus ones, so any other goes to the game group.
        if (isLegacyEvent(type) || IModBusEvent.class.isAssignableFrom(type)) return create(group, type);
        // A Forge parent with no bus is never posted itself, so a bus made for it would take listeners nothing calls.
        if (isHostClass(type) && Modifier.isAbstract(type.getModifiers()) && !InheritableEvent.class.isAssignableFrom(type)) {
            throw new IllegalArgumentException(type.getName() + " has no bus of its own on this Forge, so nothing posts it");
        }
        return create(BusGroup.DEFAULT, type);
    }

    /** Moves an old handler onto its event's bus under the object or class registered; one that fails is noted. */
    private static void hook(BusGroup group, Method method, Object receiver, Annotation subscription, Object registered) {
        try {
            Class<?> type = method.getParameterTypes()[0];
            // A handler of the old base itself took every event, and EventBus 7 has no bus that carries them all.
            if (type == Object.class) {
                LegacyLog.warn("The old event handler " + method.getDeclaringClass().getName() + "." + method.getName()
                        + " listens to every event, which EventBus 7 cannot deliver, so it will not run");
                return;
            }
            byte priority = priorityByte(element(subscription, "priority"));
            Consumer<Object> call = handler(method, receiver);
            if (isLegacyEvent(type)) {
                addListener(group, type, priority, Boolean.TRUE.equals(element(subscription, "receiveCanceled")),
                        filterOf(method), call, registered);
            } else {
                // A mod event built on a host event keeps EventBus 7's rules, on the bus of its own class; a host event
                // moved off the mod bus names the subscribing mod while it runs.
                EventBus<?> bus = busOf(group, type);
                Consumer<Object> named = LegacyModNamespaces.ofListener(group, type, call);
                LegacyListenerSets.remember(group, registered, bus, addTo(bus, priority, named));
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            LegacyLog.warn("The old event handler " + method.getDeclaringClass().getName() + "." + method.getName()
                    + " could not be hooked onto EventBus 7 and will not run: " + failure);
        }
    }

    // The handler as a Consumer, called through a handle opened from this copy, which sits in the mod's own module.
    private static Consumer<Object> handler(Method method, Object receiver) throws IllegalAccessException {
        method.setAccessible(true);
        MethodHandle handle = MethodHandles.lookup().unreflect(method);
        if (receiver != null) handle = handle.bindTo(receiver);
        MethodHandle typed = handle.asType(MethodType.methodType(void.class, Object.class));
        return event -> {
            try {
                typed.invokeExact(event);
            } catch (RuntimeException | Error failure) {
                throw failure;
            } catch (Throwable failure) {
                throw new IllegalStateException(failure);
            }
        };
    }

    /** The type a handler's generic event must report, read as Forge 1.20.1 read it, or null for none. */
    private static Type filterOf(Method method) {
        if (!isGenericLegacyEvent(method.getParameterTypes()[0])) return null;
        if (!(method.getGenericParameterTypes()[0] instanceof ParameterizedType parameter)) return null;
        Type filter = parameter.getActualTypeArguments()[0];
        if (filter instanceof ParameterizedType nested) return nested.getRawType();
        // A bare ? takes every event; a bounded wildcard matched none on Forge 1.20.1 either.
        if (filter instanceof WildcardType wildcard && wildcard.getLowerBounds().length == 0
                && wildcard.getUpperBounds().length == 1 && wildcard.getUpperBounds()[0] == Object.class) {
            return null;
        }
        return filter;
    }

    // An object's handler counts when any of its declarations up the class tree carries the annotation.
    private static Annotation inheritedSubscription(Class<?> owner, Method method) {
        ArrayDeque<Class<?>> types = new ArrayDeque<>(List.of(owner));
        Set<Class<?>> seen = new HashSet<>();
        while (!types.isEmpty()) {
            Class<?> type = types.poll();
            if (!seen.add(type)) continue;
            try {
                Annotation found = legacyAnnotation(type.getDeclaredMethod(method.getName(), method.getParameterTypes()));
                if (found != null) return found;
            } catch (NoSuchMethodException | LinkageError notDeclaredHere) {
                // The next type up may declare it.
            }
            if (type.getSuperclass() != null) types.add(type.getSuperclass());
            types.addAll(List.of(type.getInterfaces()));
        }
        return null;
    }

    private static Annotation legacyAnnotation(Method method) {
        try {
            for (Annotation annotation : method.getDeclaredAnnotations()) {
                if (isCopyOf(annotation.annotationType().getName(), LEGACY_SUBSCRIBE)) return annotation;
            }
        } catch (RuntimeException | LinkageError unreadable) {
            // An annotation naming a missing class reads as none, as it would for EventBus 7.
        }
        return null;
    }

    // Whether the class still declares or inherits a handler EventBus 7's own registration takes.
    private static boolean hasEventBus7Handler(Class<?> owner, boolean statics) {
        try {
            for (Method[] methods : new Method[][]{owner.getDeclaredMethods(), owner.getMethods()}) {
                for (Method method : methods) {
                    if (Modifier.isStatic(method.getModifiers()) != statics) continue;
                    for (Annotation annotation : method.getDeclaredAnnotations()) {
                        if (annotation.annotationType().getName().equals(SUBSCRIBE7)) return true;
                    }
                }
            }
            return false;
        } catch (RuntimeException | LinkageError unreadable) {
            return true;
        }
    }

    /** Another copy's annotation element read by name, or null when it cannot be read. */
    private static Object element(Annotation annotation, String name) {
        try {
            return annotation.annotationType().getMethod(name).invoke(annotation);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unreadable) {
            return null;
        }
    }

    /** EventBus 7's byte for an old priority's constant name. */
    private static byte priorityByte(Object name) {
        if (!(name instanceof String constant)) return Priority.NORMAL;
        return switch (constant) {
            case "HIGHEST" -> Priority.HIGHEST;
            case "HIGH" -> Priority.HIGH;
            case "LOW" -> Priority.LOW;
            case "LOWEST" -> Priority.LOWEST;
            default -> Priority.NORMAL;
        };
    }

    // Priority first, then the deeper class: Forge 1.20.1 ran an event's own listeners before its parents' ones.
    private static byte order(byte priority, int depth) {
        int band = Math.max(Math.min(priority, HIGHEST_BAND), Priority.LOWEST);
        return (byte) (band + Math.min(depth, DEEPEST));
    }

    // How far below its LegacyEvent copy a class sits, or 0 for a class that is no legacy event.
    private static int depth(Class<?> type) {
        return DEPTHS.computeIfAbsent(type, LegacyEvents::measure);
    }

    private static int measure(Class<?> type) {
        int depth = 0;
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            if (isCopyOf(current.getName(), LEGACY_EVENT)) return depth;
            depth++;
        }
        return 0;
    }

    private static boolean isGenericLegacyEvent(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            if (isCopyOf(current.getName(), LEGACY_GENERIC_EVENT)) return true;
        }
        return false;
    }

    private static boolean isCopyOf(String name, String rebuilt) {
        return name.equals(rebuilt) || name.endsWith("." + rebuilt);
    }

    /** A public getGenericType() a host event still declares, or null. */
    private static Method genericTypeGetter(Class<?> type) {
        try {
            Method getter = type.getMethod("getGenericType");
            return Type.class.isAssignableFrom(getter.getReturnType()) ? getter : null;
        } catch (NoSuchMethodException | LinkageError none) {
            return null;
        }
    }

    private static Object call(Method getter, Object event) {
        try {
            return getter.invoke(event);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    // The host event's own public static BUS; a mod's subclass of a host event gets a bus of its own class.
    private static EventBus<?> staticBus(Class<?> type) {
        Field field = BUS_FIELDS.get(type).orElse(null);
        if (field == null) return null;
        try {
            // Read on every call, as a class that is still initializing may not have set it yet.
            return field.get(null) instanceof EventBus<?> bus ? bus : null;
        } catch (IllegalAccessException none) {
            return null;
        }
    }

    private static Field busField(Class<?> type) {
        try {
            Field field = type.getField("BUS");
            if (!Modifier.isStatic(field.getModifiers())) return null;
            return field.getDeclaringClass() == type || isHostClass(type) ? field : null;
        } catch (NoSuchFieldException none) {
            return null;
        }
    }

    private static boolean isHostClass(Class<?> type) {
        for (String prefix : HOST_PACKAGES) {
            if (type.getName().startsWith(prefix)) return true;
        }
        return false;
    }

    // A legacy event's bus in the group, or null when the group refuses the type, as a mod group refuses game events.
    private static EventBus<?> legacyBus(BusGroup group, Class<?> type) {
        try {
            return create(group, type);
        } catch (IllegalArgumentException refused) {
            LegacyLog.warnOnce("legacy bus " + group.name() + " " + type.getName(), "The event " + type.getName()
                    + " cannot use the event bus " + group.name() + " on EventBus 7, so it is not delivered there: "
                    + refused.getMessage());
            return null;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static EventBus<?> create(BusGroup group, Class<?> type) {
        return EventBus.create(group, (Class) type);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static EventListener addTo(EventBus<?> bus, byte priority, Consumer<Object> listener) {
        return ((EventBus) bus).addListener(priority, (Consumer) listener);
    }

    private static boolean invokePost(EventBus<?> bus, Object event) {
        try {
            return (boolean) postHandle().invokeExact(bus, event);
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException(failure);
        }
    }

    // EventBus 7's post takes its root event type, whose package it keeps closed, so it is called through a handle.
    private static MethodHandle postHandle() throws ReflectiveOperationException {
        MethodHandle handle = post;
        if (handle == null) {
            MethodType takesEvent = MethodType.methodType(boolean.class, LegacyEventAccess.eventRoot());
            handle = MethodHandles.publicLookup().findVirtual(EventBus.class, "post", takesEvent)
                    .asType(MethodType.methodType(boolean.class, EventBus.class, Object.class));
            post = handle;
        }
        return handle;
    }

    private static boolean gameBusStarted() {
        if (!gameBusStarted) {
            try {
                // COMPLETE is the last state Forge 26.2 and 26.3 finish; nothing dispatches the DONE phase.
                gameBusStarted = ModLoader.hasCompletedState(ModStateProvider.COMPLETE);
            } catch (LinkageError noModLoader) {
                // Without FML's loading states there is no mod loading to wait for.
                gameBusStarted = true;
            }
        }
        return gameBusStarted;
    }
}
