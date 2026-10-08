/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.embedded;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Runtime half of the 1.12.2 Forge bridge. Each legacy mod gets its own relocated copy, so the
 * static state below is per mod.
 *
 * <p>1.12.2 content registration has three parts the modern loaders lack:
 * {@code @SubscribeEvent} handlers on {@code MinecraftForge.EVENT_BUS} (registered by
 * {@code @Mod.EventBusSubscriber} or by an explicit {@code register} call),
 * {@code RegistryEvent.Register<T>} fired on that same bus, and {@code setRegistryName} on the
 * entry itself. Handlers are wired here, registry handlers are called from the host's
 * {@code RegisterEvent} on the mod bus, and names live in a weak side table.
 *
 * <p>Everything touching the host is reflective: this class is compiled without Minecraft or a
 * loader on the classpath and must run on both NeoForge and Forge. It holds no nested classes,
 * because the embedder copies exactly the classes the transformer registered.
 */
public final class LegacyForgeEvents {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-Legacy1122");

    /** Lower-case mod id of the mod this copy is embedded in; the default name namespace. */
    static volatile String modId = "minecraft";
    private static volatile boolean attached;

    /** Registry names set through {@code setRegistryName}. Weak so unregistered entries can go. */
    private static final Map<Object, Object> NAMES = Collections.synchronizedMap(new WeakHashMap<>());
    /** Targets already subscribed, by identity (a Class for static subscribers). */
    private static final Set<Object> SUBSCRIBED =
            Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
    /** Pending registry handlers: {bound handle, legacy event class, registry path, name}. */
    private static final List<Object[]> REGISTRY_HANDLERS =
            Collections.synchronizedList(new ArrayList<>());

    private static volatile Object modBus;
    /** Handlers already reported as failing, by name and descriptor. */
    private static final Set<String> FAILED_HANDLERS =
            Collections.synchronizedSet(new java.util.HashSet<>());

    private LegacyForgeEvents() {}

    // ------------------------------------------------------------------ lifecycle

    /**
     * Called at the end of the legacy {@code @Mod} class constructor. Fills
     * {@code @Mod.Instance} and {@code @SidedProxy} fields and wires {@code @Mod.EventBusSubscriber}
     * classes. Pre-init runs at the start of the first registry event, before any registry
     * handler, and init and post-init on common setup, after the registry events, as in 1.12.2.
     */
    public static void attach(Object mod, String id, Class<?> preEvent, Class<?> initEvent,
                              Class<?> postEvent) {
        if (attached) return;
        attached = true;
        modId = id.toLowerCase(java.util.Locale.ROOT);
        Class<?> modClass = mod.getClass();
        injectModInstances(modClass, mod, id);
        injectSidedProxies(modClass);
        modBus = findModBus(id);
        installRegistryDispatcher();
        subscribeAnnotatedClasses(id, modClass.getClassLoader());

        // Pre-init waits for the first registry event. A mod may build itself from its own static
        // initializer, before its other static fields are assigned, and 1.12 pre-init creates
        // blocks and items, which a host only accepts while its registries are open.
        if (!deferToFirstRegistryEvent(mod, id, preEvent)) {
            if (!deferToConstruct(mod, id, preEvent)) fireLifecycle(mod, id, preEvent);
        }
        if (!deferToCommonSetup(mod, id, initEvent, postEvent)) {
            fireLifecycle(mod, id, initEvent);
            fireLifecycle(mod, id, postEvent);
        }
    }

    static void fireLifecycle(Object mod, String id, Class<?> eventType) {
        if (eventType == null) return;
        String wanted = "(L" + eventType.getName().replace('.', '/') + ";)V";
        for (String[] h : handlers(mod.getClass())) {
            if (!h[0].equals("L") || !relocate(h[3]).equals(wanted)) continue;
            try {
                Object event = eventType.getDeclaredConstructor(String.class).newInstance(id);
                handle(mod.getClass(), h, mod).invokeWithArguments(event);
            } catch (Throwable t) {
                LOGGER.warn("Legacy mod {} failed in {}.{}; continuing", id,
                        mod.getClass().getName(), h[2], unwrap(t));
            }
        }
    }

    /** Pre-init work that runs before the first registry handler, or null once it has run. */
    private static volatile Runnable pendingPreInit;

    static boolean deferToFirstRegistryEvent(Object mod, String id, Class<?> pre) {
        if (!registryDispatcherInstalled) return false;
        pendingPreInit = () -> fireLifecycle(mod, id, pre);
        return true;
    }

    private static void runPendingPreInit() {
        Runnable preInit;
        synchronized (LegacyForgeEvents.class) {
            preInit = pendingPreInit;
            pendingPreInit = null;
        }
        if (preInit != null) preInit.run();
    }

    private static boolean deferToConstruct(Object mod, String id, Class<?> pre) {
        Object bus = modBus;
        Class<?> construct = firstClass("net.neoforged.fml.event.lifecycle.FMLConstructModEvent",
                "net.minecraftforge.fml.event.lifecycle.FMLConstructModEvent");
        if (bus == null || construct == null) return false;
        return addListener(bus, construct, false, event -> fireLifecycle(mod, id, pre));
    }

    private static boolean deferToCommonSetup(Object mod, String id, Class<?> init, Class<?> post) {
        Object bus = modBus;
        Class<?> setup = firstClass("net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent",
                "net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent");
        if (bus == null || setup == null) return false;
        Consumer<Object> listener = event -> {
            Runnable work = () -> {
                fireLifecycle(mod, id, init);
                fireLifecycle(mod, id, post);
            };
            try {
                // Common setup runs on worker threads; 1.12 init code expects the main thread.
                event.getClass().getMethod("enqueueWork", Runnable.class).invoke(event, work);
            } catch (Throwable t) {
                work.run();
            }
        };
        return addListener(bus, setup, false, listener);
    }

    static void injectModInstances(Class<?> modClass, Object mod, String id) {
        for (String[] h : handlers(modClass)) {
            if (!h[0].equals("I") || (!h[4].isEmpty() && !h[4].equals(id))) continue;
            try {
                staticSetter(modClass, h).invoke(mod);
            } catch (Throwable t) {
                LOGGER.warn("Legacy mod {}: could not set @Mod.Instance field {}", id, h[2], unwrap(t));
            }
        }
    }

    private static void injectSidedProxies(Class<?> modClass) {
        boolean client = isClient();
        for (String[] h : handlers(modClass)) {
            if (!h[0].equals("P")) continue;
            String name = client ? h[4] : h[5];
            if (name.isEmpty()) continue;
            try {
                Class<?> proxy = Class.forName(name, true, modClass.getClassLoader());
                Constructor<?> ctor = proxy.getDeclaredConstructor();
                ctor.setAccessible(true);
                staticSetter(modClass, h).invoke(ctor.newInstance());
            } catch (Throwable t) {
                LOGGER.warn("Legacy mod {}: could not create @SidedProxy {} for field {}", modId,
                        name, h[2], unwrap(t));
            }
        }
    }

    /** A setter for one recorded static field, resolving only that field's type. */
    private static MethodHandle staticSetter(Class<?> type, String[] h) throws Throwable {
        Class<?> fieldType = MethodType.fromMethodDescriptorString("(" + relocate(h[3]) + ")V",
                type.getClassLoader()).parameterType(0);
        MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(type, MethodHandles.lookup());
        return lookup.findStaticSetter(type, h[2], fieldType);
    }

    private static boolean isClient() {
        Class<?> env = firstClass("net.neoforged.fml.loading.FMLEnvironment",
                "net.minecraftforge.fml.loading.FMLEnvironment");
        try {
            Object dist = env.getField("dist").get(null);
            return "CLIENT".equals(String.valueOf(dist));
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------ event bus

    /** {@code MinecraftForge.EVENT_BUS.register(target)} from a 1.12 mod. */
    public static void subscribe(Object target) {
        if (target == null || !SUBSCRIBED.add(target)) return;
        boolean staticOnly = target instanceof Class<?>;
        for (Class<?> type = staticOnly ? (Class<?>) target : target.getClass();
             type != null && type != Object.class; type = type.getSuperclass()) {
            for (String[] h : handlers(type)) {
                if (!h[0].equals("E")) continue;
                boolean isStatic = h[1].equals("s");
                if (staticOnly && !isStatic) continue;
                try {
                    subscribeHandler(handle(type, h, isStatic ? null : target), h);
                } catch (Throwable t) {
                    // Usually a handler for an event the host no longer has; the rest still work.
                    LOGGER.debug("Legacy mod {}: skipped handler {}.{}", modId, type.getName(), h[2], t);
                }
            }
        }
    }

    /** The recorded handler table of exactly this class (not its supertypes). */
    static List<String[]> handlers(Class<?> type) {
        List<String[]> out = new ArrayList<>();
        LegacyHandlers table = type.getDeclaredAnnotation(LegacyHandlers.class);
        if (table == null) return out;
        for (String entry : table.value()) {
            String[] parts = entry.split("\\|", -1);
            if (parts.length == 6) out.add(parts);
        }
        return out;
    }

    /** A handle bound to {@code receiver} for instance handlers, so it takes only the event. */
    private static MethodHandle handle(Class<?> type, String[] h, Object receiver) throws Throwable {
        MethodType mt = MethodType.fromMethodDescriptorString(relocate(h[3]), type.getClassLoader());
        MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(type, MethodHandles.lookup());
        if (h[1].equals("s")) return lookup.findStatic(type, h[2], mt);
        return lookup.findVirtual(type, h[2], mt).bindTo(receiver);
    }

    /**
     * Descriptors are recorded before the embedder moves Retromod's classes into the mod's own
     * package, and strings are not rewritten by that move, so apply the same prefix here.
     */
    static String relocate(String descriptor) {
        String home = "com.retromod.shim.forge.embedded.LegacyForgeEvents";
        String self = LegacyForgeEvents.class.getName();
        if (!self.endsWith(home) || self.length() == home.length()) return descriptor;
        String prefix = self.substring(0, self.length() - home.length()).replace('.', '/');
        return descriptor.replace("Lcom/retromod/", "L" + prefix + "com/retromod/");
    }

    /** Listeners on the host bus cannot be removed through this bridge; the call is ignored. */
    public static void unsubscribe(Object target) {
        LOGGER.debug("Legacy mod {}: ignoring EVENT_BUS.unregister({})", modId, target);
    }

    /** {@code MinecraftForge.EVENT_BUS.post(event)}: forward to the host game bus. */
    public static boolean post(Object event) {
        Object bus = gameBus();
        if (bus == null || event == null) return false;
        try {
            Method post = findMethod(bus.getClass(), "post", 1);
            if (post == null) return false;
            Object result = post.invoke(bus, event);
            if (result instanceof Boolean b) return b;
            Method canceled = findMethod(event.getClass(), "isCanceled", 0);
            return canceled != null && Boolean.TRUE.equals(canceled.invoke(event));
        } catch (Throwable t) {
            LOGGER.warn("Legacy mod {}: could not post {}", modId, event.getClass().getName(), unwrap(t));
            return false;
        }
    }

    private static void subscribeHandler(MethodHandle handler, String[] h) {
        Class<?> param = handler.type().parameterType(0);
        if (!h[4].isEmpty()) {
            REGISTRY_HANDLERS.add(new Object[]{handler, param, h[4], h[2]});
            return;
        }
        Class<?> hostEvent = firstClass("net.neoforged.bus.api.Event",
                "net.minecraftforge.eventbus.api.Event");
        if (hostEvent == null || !hostEvent.isAssignableFrom(param)
                || param.getName().contains("com.retromod.")) {
            // A 1.12 event with no host counterpart (a Retromod stub). It never fires.
            LOGGER.debug("Legacy mod {}: {} has no host event; {} stays idle", modId,
                    param.getName(), h[2]);
            return;
        }
        Consumer<Object> listener = event -> {
            try {
                handler.invokeWithArguments(event);
            } catch (Throwable t) {
                // Game-bus events can fire every tick; report each broken handler once.
                if (FAILED_HANDLERS.add(h[2] + h[3])) {
                    LOGGER.warn("Legacy mod {}: handler {} failed on {}; further failures are "
                            + "logged at debug", modId, h[2], param.getSimpleName(), unwrap(t));
                } else {
                    LOGGER.debug("Legacy mod {}: handler {} failed again", modId, h[2], unwrap(t));
                }
            }
        };
        Object bus = isModBusEvent(param) ? modBus : gameBus();
        if (bus == null || !addListener(bus, param, h[5].equals("1"), listener)) {
            LOGGER.warn("Legacy mod {}: could not listen for {} in {}", modId, param.getName(), h[2]);
        }
    }

    private static boolean isModBusEvent(Class<?> event) {
        Class<?> marker = firstClass("net.neoforged.fml.event.IModBusEvent",
                "net.minecraftforge.fml.event.IModBusEvent");
        return marker != null && marker.isAssignableFrom(event);
    }

    /** {@code addListener(EventPriority, boolean, Class, Consumer)} exists on both host buses. */
    private static boolean addListener(Object bus, Class<?> eventType, boolean receiveCanceled,
                                       Consumer<Object> listener) {
        for (Method m : publicMethods(bus.getClass())) {
            if (!m.getName().equals("addListener") || m.getParameterCount() != 4) continue;
            Class<?>[] p = m.getParameterTypes();
            if (!p[0].isEnum() || p[1] != boolean.class || p[2] != Class.class
                    || p[3] != Consumer.class) continue;
            try {
                @SuppressWarnings({"unchecked", "rawtypes"})
                Object normal = Enum.valueOf((Class) p[0], "NORMAL");
                m.invoke(bus, normal, receiveCanceled, eventType, listener);
                return true;
            } catch (Throwable t) {
                LOGGER.warn("Legacy mod {}: host bus rejected a listener for {}", modId,
                        eventType.getName(), unwrap(t));
                return false;
            }
        }
        return false;
    }

    private static Object gameBus() {
        Object bus = staticField("net.neoforged.neoforge.common.NeoForge", "EVENT_BUS");
        return bus != null ? bus : staticField("net.minecraftforge.common.MinecraftForge", "EVENT_BUS");
    }

    private static Object findModBus(String id) {
        for (String listName : new String[]{"net.neoforged.fml.ModList", "net.minecraftforge.fml.ModList"}) {
            Class<?> list = firstClass(listName);
            if (list == null) continue;
            try {
                Object modList = list.getMethod("get").invoke(null);
                Object found = list.getMethod("getModContainerById", String.class).invoke(modList, id);
                Object container = found instanceof Optional<?> o ? o.orElse(null) : found;
                if (container == null) continue;
                Object bus = call(container, "getEventBus");
                if (bus != null) return bus;
            } catch (Throwable ignored) {
                // try the next loader
            }
        }
        LOGGER.warn("Legacy mod {}: no mod event bus found; registry events will not reach it", id);
        return null;
    }

    /**
     * {@code @Mod.EventBusSubscriber} classes, read from the loader's scan of the mod jar. The
     * transform renamed their annotation to {@link LegacyEventBusSubscriber}, so the host loader
     * never acts on it.
     */
    private static void subscribeAnnotatedClasses(String id, ClassLoader loader) {
        String marker = "L" + LegacyEventBusSubscriber.class.getName().replace('.', '/') + ";";
        boolean client = isClient();
        for (String className : annotatedClasses(id, marker, client)) {
            try {
                subscribe(Class.forName(className, false, loader));
            } catch (Throwable t) {
                LOGGER.warn("Legacy mod {}: could not load event subscriber {}", id, className, unwrap(t));
            }
        }
    }

    private static List<String> annotatedClasses(String id, String annotationDesc, boolean client) {
        List<String> out = new ArrayList<>();
        for (Object[] entry : scannedAnnotations(id, annotationDesc)) {
            if (!client && entry[2] instanceof Map<?, ?> v && Boolean.TRUE.equals(v.get("clientOnly"))) {
                continue;
            }
            if (!out.contains((String) entry[0])) out.add((String) entry[0]);
        }
        return out;
    }

    /**
     * Every use of one annotation in the mod's own jar, from the loader's scan, as
     * {@code {className, memberName, annotationValues}}. Empty when the loader has no scan for
     * the mod.
     */
    static List<Object[]> scannedAnnotations(String id, String annotationDesc) {
        List<Object[]> out = new ArrayList<>();
        for (String listName : new String[]{"net.neoforged.fml.ModList", "net.minecraftforge.fml.ModList"}) {
            Class<?> list = firstClass(listName);
            if (list == null) continue;
            try {
                Object modList = list.getMethod("get").invoke(null);
                Object info = list.getMethod("getModFileById", String.class).invoke(modList, id);
                if (info == null) continue;
                Object file = call(info, "getFile");
                Object scan = call(file, "getScanResult");
                Object annotations = call(scan, "getAnnotations");
                for (Object data : (Collection<?>) annotations) {
                    Object type = call(data, "annotationType");
                    if (!annotationDesc.equals(String.valueOf(type))) continue;
                    String name = (String) call(call(data, "clazz"), "getClassName");
                    out.add(new Object[]{name, String.valueOf(call(data, "memberName")),
                            call(data, "annotationData")});
                }
                return out;
            } catch (Throwable t) {
                LOGGER.warn("Legacy mod {}: could not read the loader scan data", id, unwrap(t));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ registries

    private static void installRegistryDispatcher() {
        Object bus = modBus;
        Class<?> registerEvent = firstClass("net.neoforged.neoforge.registries.RegisterEvent",
                "net.minecraftforge.registries.RegisterEvent");
        if (bus == null || registerEvent == null) return;
        registryDispatcherInstalled =
                addListener(bus, registerEvent, false, LegacyForgeEvents::dispatchRegistry);
    }

    static volatile boolean registryDispatcherInstalled;

    static void dispatchRegistry(Object registerEvent) {
        // Pre-init registers handlers and builds content, so it runs before any handler is read.
        runPendingPreInit();
        Object key;
        try {
            key = registerEvent.getClass().getMethod("getRegistryKey").invoke(registerEvent);
        } catch (Throwable t) {
            return;
        }
        String path = registryPathOfKey(String.valueOf(key));
        if (path == null) return;
        List<Object[]> handlers;
        synchronized (REGISTRY_HANDLERS) {
            handlers = new ArrayList<>(REGISTRY_HANDLERS);
        }
        Object vanilla = builtInRegistry(String.valueOf(key));
        for (Object[] h : handlers) {
            if (!path.equals(h[2])) continue;
            Set<Object> registered = Collections.newSetFromMap(new IdentityHashMap<>());
            Consumer<Object> registrar = entry -> {
                if (registerEntry(registerEvent, key, entry)) registered.add(entry);
            };
            List<Set<Object>> before = holderKeys(vanilla);
            try {
                Object legacyEvent = ((Class<?>) h[1]).getConstructor(Object.class).newInstance(registrar);
                ((MethodHandle) h[0]).invokeWithArguments(legacyEvent);
            } catch (Throwable t) {
                LOGGER.warn("Legacy mod {}: registry handler {} failed for {}", modId, h[3], path,
                        unwrap(t));
            }
            dropOrphans(vanilla, before, registered, path);
        }
    }

    /** The vanilla registry behind a registry key, matched by its printed key. */
    private static Object builtInRegistry(String keyText) {
        Class<?> registries = firstClass("net.minecraft.core.registries.BuiltInRegistries");
        if (registries == null) return null;
        for (Field f : registries.getFields()) {
            if (!Modifier.isStatic(f.getModifiers())) continue;
            try {
                Object registry = f.get(null);
                if (registry != null && String.valueOf(registry).contains(keyText)) return registry;
            } catch (Throwable ignored) {
                // not readable; keep looking
            }
        }
        return null;
    }

    /**
     * Each holder map of the registry ({@code byValue} and the unregistered intrusive holders),
     * found by shape because their names differ between Mojang and SRG hosts.
     */
    private static List<Map<?, ?>> holderMaps(Object registry) {
        List<Map<?, ?>> maps = new ArrayList<>();
        Class<?> reference = firstClass("net.minecraft.core.Holder$Reference");
        if (registry == null || reference == null) return maps;
        for (Class<?> c = registry.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || !Map.class.isAssignableFrom(f.getType())) continue;
                try {
                    f.setAccessible(true);
                    Object value = f.get(registry);
                    if (value instanceof Map<?, ?> map && holdsReferences(map, reference)) maps.add(map);
                } catch (Throwable ignored) {
                    // inaccessible on this host; the orphan check just finds less
                }
            }
        }
        return maps;
    }

    private static boolean holdsReferences(Map<?, ?> map, Class<?> reference) {
        if (map.isEmpty()) return true;
        Object first = map.entrySet().iterator().next().getKey();
        Object value = map.get(first);
        return reference.isInstance(value) && !reference.isInstance(first)
                && !first.getClass().getName().startsWith("net.minecraft.resources.");
    }

    private static List<Set<Object>> holderKeys(Object registry) {
        List<Set<Object>> keys = new ArrayList<>();
        for (Map<?, ?> map : holderMaps(registry)) {
            Set<Object> snapshot = Collections.newSetFromMap(new IdentityHashMap<>());
            synchronized (map) {
                snapshot.addAll(map.keySet());
            }
            keys.add(snapshot);
        }
        return keys;
    }

    /**
     * Forget entries a handler built but never registered. Each one already holds an intrusive
     * registry holder, and an unregistered holder aborts the registry freeze, so one failing
     * 1.12 handler would otherwise stop the whole server. An orphan is an unbound holder (it
     * prints as {@code Reference{null=...}}, the same text the freeze error shows) whose entry
     * this handler added, or whose class is this mod's own, and that was not registered here.
     * Another mod's pending entries are neither, so they are left alone.
     */
    private static void dropOrphans(Object registry, List<Set<Object>> before, Set<Object> registered,
                                    String path) {
        List<Map<?, ?>> maps = holderMaps(registry);
        if (maps.size() != before.size()) return;
        Module own = LegacyForgeEvents.class.getModule();
        for (int i = 0; i < maps.size(); i++) {
            Map<?, ?> map = maps.get(i);
            List<Object> orphans = new ArrayList<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                Object k = e.getKey();
                if (registered.contains(k) || !String.valueOf(e.getValue()).startsWith("Reference{null")) continue;
                if (!before.get(i).contains(k) || k.getClass().getModule() == own) orphans.add(k);
            }
            for (Object k : orphans) {
                map.remove(k);
                LOGGER.warn("Legacy mod {}: {} {} was created but not registered; dropped",
                        modId, path, k.getClass().getName());
            }
        }
    }

    /** {@code ResourceKey[minecraft:root / minecraft:block]} -> {@code block}. */
    static String registryPathOfKey(String keyText) {
        int slash = keyText.lastIndexOf(" / ");
        if (slash < 0) return null;
        String location = keyText.substring(slash + 3).replace("]", "").trim();
        return location.startsWith("minecraft:") ? location.substring("minecraft:".length()) : location;
    }

    /** Register one entry; false when it could not be (and the reason was logged). */
    private static boolean registerEntry(Object registerEvent, Object key, Object entry) {
        if (entry == null) return false;
        Object name = getRegistryName(entry);
        if (name == null) {
            LOGGER.warn("Legacy mod {}: {} was registered without a registry name; skipped", modId,
                    entry.getClass().getName());
            return false;
        }
        try {
            Method register = null;
            for (Method m : registerEvent.getClass().getMethods()) {
                if (m.getName().equals("register") && m.getParameterCount() == 3
                        && m.getParameterTypes()[2] == Supplier.class) {
                    register = m;
                    break;
                }
            }
            if (register == null) throw new NoSuchMethodException("RegisterEvent.register");
            Supplier<Object> value = () -> entry;
            register.invoke(registerEvent, key, name, value);
            return true;
        } catch (Throwable t) {
            LOGGER.warn("Legacy mod {}: could not register {} as {}", modId,
                    entry.getClass().getName(), name, unwrap(t));
            return false;
        }
    }

    /** {@code IForgeRegistry.register(entry)} on the registry a legacy handler received. */
    @SuppressWarnings("unchecked")
    public static void registryRegister(Object registry, Object entry) {
        if (registry instanceof Consumer<?> c) {
            ((Consumer<Object>) c).accept(entry);
        } else {
            LOGGER.warn("Legacy mod {}: register({}) on a registry outside a registry event is "
                    + "not supported", modId, entry == null ? "null" : entry.getClass().getName());
        }
    }

    /** {@code IForgeRegistry.registerAll(entries...)}. */
    public static void registryRegisterAll(Object registry, Object[] entries) {
        if (entries == null) return;
        for (Object entry : entries) registryRegister(registry, entry);
    }

    // ------------------------------------------------------------------ registry names

    public static Object setRegistryName(Object entry, String name) {
        Object own = callOwn(entry, "setRegistryName", new Class<?>[]{String.class}, name);
        if (own != NOT_DECLARED) return own;
        int colon = name.indexOf(':');
        String ns = colon < 0 ? modId : name.substring(0, colon);
        String path = colon < 0 ? name : name.substring(colon + 1);
        return putName(entry, resourceLocation(ns, path));
    }

    public static Object setRegistryName(Object entry, String namespace, String path) {
        return putName(entry, resourceLocation(namespace, path));
    }

    public static Object setRegistryName(Object entry, Object location) {
        if (location instanceof String s) return setRegistryName(entry, s);
        return putName(entry, location);
    }

    public static Object getRegistryName(Object entry) {
        if (entry == null) return null;
        Object name = NAMES.get(entry);
        if (name != null) return name;
        Object own = callOwn(entry, "getRegistryName", new Class<?>[0]);
        return own == NOT_DECLARED ? null : own;
    }

    private static Object putName(Object entry, Object location) {
        if (entry != null && location != null) NAMES.put(entry, location);
        return entry;
    }

    private static final Object NOT_DECLARED = new Object();

    /** A mod class may still declare its own method of that name; respect it when it does. */
    private static Object callOwn(Object target, String name, Class<?>[] params, Object... args) {
        if (target == null) return NOT_DECLARED;
        try {
            Method m = target.getClass().getMethod(name, params);
            try {
                return m.invoke(target, args);
            } catch (IllegalAccessException e) {
                // A public method on a package-private mod class. This copy lives in the mod's
                // own module, so it may open the method; a host class keeps the first failure.
                m.setAccessible(true);
                return m.invoke(target, args);
            }
        } catch (NoSuchMethodException e) {
            return NOT_DECLARED;
        } catch (Throwable t) {
            LOGGER.warn("Legacy mod {}: {}.{} failed", modId, target.getClass().getName(), name, unwrap(t));
            return NOT_DECLARED;
        }
    }

    /** 1.12.2 lower-cased names with a warning; modern locations reject upper case outright. */
    static Object resourceLocation(String namespace, String path) {
        String ns = namespace.toLowerCase(java.util.Locale.ROOT);
        String p = path.toLowerCase(java.util.Locale.ROOT);
        Class<?> rl = firstClass("net.minecraft.resources.ResourceLocation",
                "net.minecraft.resources.Identifier");
        if (rl == null) return ns + ":" + p;
        try {
            return rl.getMethod("fromNamespaceAndPath", String.class, String.class).invoke(null, ns, p);
        } catch (NoSuchMethodException e) {
            try {
                return rl.getConstructor(String.class, String.class).newInstance(ns, p);
            } catch (Throwable t) {
                LOGGER.warn("Legacy mod {}: could not build the name {}:{}", modId, ns, p, unwrap(t));
                return null;
            }
        } catch (Throwable t) {
            LOGGER.warn("Legacy mod {}: invalid registry name {}:{}", modId, ns, p, unwrap(t));
            return null;
        }
    }

    /**
     * {@code BlockBehaviour.Properties.of()}, found by shape: on Forge 1.20.1 its runtime name is
     * an SRG id, and the call is emitted after the transformer's SRG rename.
     */
    public static Object defaultBlockProperties() {
        Class<?> props = firstClass("net.minecraft.world.level.block.state.BlockBehaviour$Properties");
        if (props == null) throw new IllegalStateException("BlockBehaviour.Properties is missing");
        for (Method m : props.getMethods()) {
            if (Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 0
                    && m.getReturnType() == props) {
                try {
                    return withProvisionalBlockId(m.invoke(null));
                } catch (Throwable t) {
                    throw new IllegalStateException("BlockBehaviour.Properties.of() failed", unwrap(t));
                }
            }
        }
        throw new IllegalStateException("BlockBehaviour.Properties has no default factory");
    }

    /**
     * Item properties for a 1.12 {@code ItemFood(amount, saturation, isWolfFood)}: default
     * properties with host food values. The builder and the setters are found by shape, because
     * Forge 1.20.1 names them with SRG ids and 1.20.5 renamed {@code saturationMod}. The wolf-food
     * flag has no shape of its own on any host and is not carried over.
     */
    public static Object foodItemProperties(int nutrition, float saturation, boolean wolfFood) {
        Class<?> props = firstClass("net.minecraft.world.item.Item$Properties");
        Class<?> builderType = firstClass("net.minecraft.world.food.FoodProperties$Builder");
        if (props == null || builderType == null) throw new IllegalStateException("Food properties are missing");
        try {
            Object builder = builderType.getConstructor().newInstance();
            Method build = null;
            for (Method m : builderType.getMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 1 && p[0] == int.class && m.getReturnType() == builderType) {
                    m.invoke(builder, nutrition);
                } else if (p.length == 1 && p[0] == float.class && m.getReturnType() == builderType) {
                    m.invoke(builder, saturation);
                } else if (p.length == 0 && m.getReturnType().getName()
                        .equals("net.minecraft.world.food.FoodProperties")) {
                    build = m;
                }
            }
            if (build == null) throw new IllegalStateException("FoodProperties.Builder has no build method");
            Object food = build.invoke(builder);
            Object properties = props.getConstructor().newInstance();
            for (Method m : props.getMethods()) {
                if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == food.getClass()
                        && m.getReturnType() == props) {
                    return m.invoke(properties, food);
                }
            }
            throw new IllegalStateException("Item.Properties has no food setter");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not build food properties", unwrap(e));
        }
    }

    private static final java.util.concurrent.atomic.AtomicInteger PROVISIONAL_BLOCK_IDS =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * 1.21.2 requires a block's properties to carry its registry id before the block is built, but
     * a 1.12 block gets its name from {@code setRegistryName} after construction. A provisional id
     * lets construction finish. The block still registers under the name the mod gives it; only its
     * default translation key and loot table follow the provisional id. Older hosts have no
     * {@code setId}, so their properties pass through unchanged.
     */
    static Object withProvisionalBlockId(Object properties) {
        Method setId = null;
        for (Method m : properties.getClass().getMethods()) {
            if (m.getName().equals("setId") && m.getParameterCount() == 1
                    && m.getParameterTypes()[0].getName().equals("net.minecraft.resources.ResourceKey")) {
                setId = m;
            }
        }
        if (setId == null) return properties;
        try {
            Class<?> resourceKey = setId.getParameterTypes()[0];
            Class<?> registries = firstClass("net.minecraft.core.registries.Registries");
            Object blockRegistry = registries.getField("BLOCK").get(null);
            Object location = resourceLocation(modId,
                    "legacy_block_" + PROVISIONAL_BLOCK_IDS.incrementAndGet());
            Object id = null;
            for (Method create : resourceKey.getMethods()) {
                if (create.getName().equals("create") && create.getParameterCount() == 2
                        && Modifier.isStatic(create.getModifiers())) {
                    id = create.invoke(null, blockRegistry, location);
                }
            }
            return id == null ? properties : setId.invoke(properties, id);
        } catch (Throwable t) {
            LOGGER.warn("Legacy mod {}: could not give a block a provisional id", modId, unwrap(t));
            return properties;
        }
    }

    // ------------------------------------------------------------------ block states

    /** The host builder a legacy {@code createBlockState()} is adding properties to. */
    private static final ThreadLocal<Object> STATE_BUILDER = new ThreadLocal<>();
    private static volatile Method builderAdd;

    /**
     * The host's {@code createBlockStateDefinition(builder)} on a 1.12 block: runs the block's
     * {@code createBlockState()} so the properties it lists reach the builder. A container this
     * bridge cannot build leaves the block without properties, as before the bridge, instead of
     * failing the block's construction.
     */
    public static void defineLegacyStates(Object block, Object builder, String createBlockState) {
        Object previous = beginLegacyStates(builder);
        try {
            // A method handle resolves this one method. Reflection would load the types of every
            // method the block declares, and 1.12 blocks name removed client types there.
            Class<?> definition = firstClass("net.minecraft.world.level.block.state.StateDefinition");
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(block.getClass(), MethodHandles.lookup());
            lookup.findVirtual(block.getClass(), createBlockState, MethodType.methodType(definition))
                    .invoke(block);
        } catch (Throwable t) {
            LOGGER.warn("Legacy mod {}: block {} keeps no state properties; its createBlockState failed",
                    modId, block.getClass().getName(), unwrap(t));
        } finally {
            endLegacyStates(previous);
        }
    }

    /**
     * Begins a 1.12 {@code createBlockState()} run inside the host's
     * {@code createBlockStateDefinition(builder)}. Both run from the block constructor, so the
     * properties the mod lists go to the builder the host is filling. Returns the builder of an
     * enclosing definition, for {@link #endLegacyStates}.
     */
    public static Object beginLegacyStates(Object builder) {
        Object previous = STATE_BUILDER.get();
        STATE_BUILDER.set(builder);
        return previous;
    }

    /** Ends a legacy state definition and restores the builder of an enclosing one. */
    public static void endLegacyStates(Object previous) {
        if (previous == null) STATE_BUILDER.remove(); else STATE_BUILDER.set(previous);
    }

    /**
     * {@code new BlockStateContainer(block, properties)}: adds the properties to the builder of
     * the definition in progress. Returns null, because the host builds the definition itself
     * once the builder is filled. The add method is found by shape, since Forge 1.20.1 names it
     * {@code m_61104_}.
     */
    public static Object addLegacyStates(Object block, Object[] properties) {
        Object builder = STATE_BUILDER.get();
        if (builder == null) {
            note("BlockStateContainer outside block construction", block);
            return null;
        }
        Method add = builderAdd;
        if (add == null) {
            for (Method m : builder.getClass().getMethods()) {
                if (m.getParameterCount() == 1 && m.getParameterTypes()[0].isArray()
                        && m.getReturnType() == builder.getClass()) {
                    add = m;
                }
            }
            if (add == null) throw new IllegalStateException("StateDefinition.Builder has no add method");
            builderAdd = add;
        }
        Object typed = java.lang.reflect.Array.newInstance(
                add.getParameterTypes()[0].getComponentType(), properties.length);
        System.arraycopy(properties, 0, typed, 0, properties.length);
        try {
            add.invoke(builder, typed);
        } catch (Throwable t) {
            throw new IllegalStateException("Could not add the block state properties of "
                    + block.getClass().getName(), unwrap(t));
        }
        return null;
    }

    /**
     * 1.12 {@code Potion.registerPotionAttributeModifier(attribute, uuid, amount, operation)} on
     * the host {@code MobEffect.addAttributeModifier}. The method is found by shape: Forge 1.20.1
     * takes the UUID string and names it with an SRG id, and 1.20.5 and newer take an attribute
     * holder and a modifier id, which is built from the UUID. The 1.12 operation numbers match the
     * host enum order.
     */
    public static Object effectAttribute(Object effect, Object attribute, String uuid, double amount,
                                         int operation) {
        for (Method m : effect.getClass().getMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (p.length != 4 || p[2] != double.class || !p[3].isEnum()
                    || Modifier.isStatic(m.getModifiers()) || !p[0].isInstance(attribute)) continue;
            Object[] operations = p[3].getEnumConstants();
            Object id = p[1] == String.class ? uuid
                    : resourceLocation(modId, "effect." + String.valueOf(uuid));
            try {
                m.invoke(effect, attribute, id, amount,
                        operations[Math.max(0, Math.min(operation, operations.length - 1))]);
            } catch (Throwable t) {
                LOGGER.warn("Legacy mod {}: could not add an attribute modifier to {}", modId,
                        effect.getClass().getName(), unwrap(t));
            }
            return effect;
        }
        note("Potion attribute modifiers", effect);
        return effect;
    }

    private static volatile Method baseStateRead;

    /**
     * {@code StateDefinition.any()}, the default state a 1.12 block read through
     * {@code getBaseState()}. It is found by shape, the definition's only public no-argument
     * method that returns a state, because Forge 1.20.1 names it {@code m_61090_}.
     */
    public static Object baseState(Object definition) {
        Method read = baseStateRead;
        if (read == null) {
            for (Method candidate : definition.getClass().getMethods()) {
                if (candidate.getParameterCount() == 0 && !Modifier.isStatic(candidate.getModifiers())
                        && candidate.getReturnType().getName()
                                .equals("net.minecraft.world.level.block.state.StateHolder")) {
                    read = candidate;
                    break;
                }
            }
            if (read == null) {
                throw new IllegalStateException(definition.getClass().getName() + " has no default state");
            }
            baseStateRead = read;
        }
        try {
            return read.invoke(definition);
        } catch (Throwable t) {
            throw new IllegalStateException("StateDefinition.any() failed", unwrap(t));
        }
    }

    /**
     * The host {@code MobEffectCategory} for a 1.12 {@code Potion(isBadEffect, color)}. Looked up
     * by constant name, which Forge 1.20.1 keeps (enum constants are not given SRG ids), because
     * this class is compiled without Minecraft on its classpath.
     */
    public static Object effectCategory(boolean harmful) {
        Class<?> category = firstClass("net.minecraft.world.effect.MobEffectCategory");
        if (category == null || !category.isEnum()) {
            throw new IllegalStateException("MobEffectCategory is missing");
        }
        String wanted = harmful ? "HARMFUL" : "BENEFICIAL";
        for (Object constant : category.getEnumConstants()) {
            if (((Enum<?>) constant).name().equals(wanted)) return constant;
        }
        throw new IllegalStateException("MobEffectCategory has no " + wanted);
    }

    private static final Set<String> NOTED = Collections.synchronizedSet(new java.util.HashSet<>());

    /** Say once per feature that a 1.12 system loads but does nothing on this host. */
    static void note(String feature, Object owner) {
        if (NOTED.add(feature)) {
            LOGGER.info("Legacy mod {}: {} loads as an inactive stand-in on this host", modId, feature);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static Class<?> firstClass(String... names) {
        ClassLoader loader = LegacyForgeEvents.class.getClassLoader();
        for (String name : names) {
            try {
                // initialize=false: probing must never run a Minecraft static initializer
                return Class.forName(name, false, loader);
            } catch (Throwable ignored) {
                // try the next name
            }
        }
        return null;
    }

    private static Object staticField(String owner, String name) {
        Class<?> c = firstClass(owner);
        if (c == null) return null;
        try {
            return c.getField(name).get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Method findMethod(Class<?> type, String name, int params) {
        for (Method m : publicMethods(type)) {
            if (m.getName().equals(name) && m.getParameterCount() == params) return m;
        }
        return null;
    }

    /** Invoke a public no-argument method, preferring the declaration on a public interface. */
    private static Object call(Object target, String name) throws ReflectiveOperationException {
        Method m = findMethod(target.getClass(), name, 0);
        if (m == null) throw new NoSuchMethodException(target.getClass().getName() + "." + name);
        return m.invoke(target);
    }

    /**
     * Interface methods first, then the class's own. Loader implementation classes often live in
     * packages their module does not export, so a {@code Method} taken from the implementation
     * class throws {@code IllegalAccessException} from the mod's module while the same method
     * taken from its exported interface does not.
     */
    private static List<Method> publicMethods(Class<?> type) {
        List<Method> out = new ArrayList<>();
        java.util.ArrayDeque<Class<?>> pending = new java.util.ArrayDeque<>();
        Set<Class<?>> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            Collections.addAll(pending, c.getInterfaces());
        }
        while (!pending.isEmpty()) {
            Class<?> itf = pending.poll();
            if (!seen.add(itf)) continue;
            Collections.addAll(out, itf.getMethods());
            Collections.addAll(pending, itf.getInterfaces());
        }
        Collections.addAll(out, type.getMethods());
        return out;
    }

    private static Throwable unwrap(Throwable t) {
        return t instanceof InvocationTargetException ite && ite.getCause() != null ? ite.getCause() : t;
    }
}
