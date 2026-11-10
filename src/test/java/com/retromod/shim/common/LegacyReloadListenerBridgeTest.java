/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.common.LegacyReloadListenerBridge.Names;
import com.retromod.shim.common.LegacyReloadListenerBridge.Shape;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Reload listeners written for an older {@code PreparableReloadListener.reload}, compiled against that shape,
 * translated, and reloaded the way a newer host reloads its listeners.
 */
class LegacyReloadListenerBridgeTest {

    private static final Path CLIENT_263 = Path.of(System.getProperty("user.home"),
            "Library/Application Support/PrismLauncher/libraries/com/mojang/minecraft/26.3/minecraft-26.3-client.jar");
    private static final String RESOURCES = "net/minecraft/server/packs/resources/";
    private static final String PROFILING = "net/minecraft/util/profiling/";

    @TempDir
    Path dir;

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @BeforeEach
    void clear() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    // ---- Hosts from 1.21.9: reload(state, background, barrier, game) ----

    @Test
    @DisplayName("an old listener class prepares on the background executor and applies on the game executor")
    void oldClassRunsThroughTheHostReload() throws Throwable {
        Reload reload = onStateHost(OLD_API, OLD_MODS);
        Object loader = reload.instance("mod.ModelLoader");

        reload.run(loader).join();

        assertEquals(List.of("prepare on background", "apply models on game"), reload.field(loader, "log"));
        assertSame(reload.resources, reload.field(loader, "preparedWith"), "prepare reads the state's resources");
        assertSame(reload.resources, reload.field(loader, "appliedWith"));
        Object inactive = reload.inactiveProfiler();
        assertSame(inactive, reload.field(loader, "preparations"), "the old profilers become the inactive one");
        assertSame(inactive, reload.field(loader, "reloads"));
        assertEquals(List.of("models"), reload.waited, "the result waits at the host's barrier");
    }

    @Test
    @DisplayName("a method reference and a capturing lambda of the old shape are built as host listeners")
    void lambdasRunThroughTheHostReload() throws Throwable {
        Reload reload = onStateHost(OLD_API, OLD_MODS);

        reload.run(reload.call("mod.Registrations", "methodReference")).join();
        reload.run(reload.call("mod.Registrations", "capturing", "geo")).join();

        assertEquals(List.of("prepare on background", "apply on game", "geo on game"),
                reload.staticField("mod.Registrations", "LOG"));
        assertEquals(List.of("prepared", "geo"), reload.waited);
    }

    @Test
    @DisplayName("a listener that wraps a host listener hands it the host reload's state")
    void forwardedHostListenerSharesTheReloadState() throws Throwable {
        Reload reload = onStateHost(OLD_API, OLD_MODS);
        Object host = reload.instance(RESOURCES.replace('/', '.') + "HostListener");

        reload.run(reload.forwarding("mod.Forwarding", host)).join();

        assertSame(reload.state, reload.field(host, "reloaded"), "the wrapped listener reads the state the host prepared");
        assertNull(reload.field(host, "prepared"), "the host prepared that state already");
        assertSame(reload.background, reload.field(host, "background"));
        assertSame(reload.game, reload.field(host, "game"));
    }

    @Test
    @DisplayName("a wrapped listener reads a key another listener's prepareSharedState put in the reload's state")
    void wrappedListenerReadsPreparedKeys() throws Throwable {
        Reload reload = onStateHost(OLD_API, OLD_MODS);
        // As the host does: the atlas manager prepares the state before any listener reloads.
        Object atlas = reload.instance(RESOURCES.replace('/', '.') + "AtlasManager");
        reload.type(RESOURCES + "PreparableReloadListener").getMethod("prepareSharedState", reload.state.getClass())
                .invoke(atlas, reload.state);
        Object particles = reload.instance(RESOURCES.replace('/', '.') + "ParticleResources");

        reload.run(reload.forwarding("mod.Forwarding", particles)).join();

        assertEquals("stitched atlas", reload.field(particles, "stitched"),
                "a new state would have no key, and the host's get throws on a missing one");
    }

    @Test
    @DisplayName("outside a host reload, a wrapped listener gets a state of its own, prepared first")
    void forwardingOutsideAReloadPreparesItsOwnState() throws Throwable {
        Reload reload = onStateHost(OLD_API, OLD_MODS);
        Object host = reload.instance(RESOURCES.replace('/', '.') + "HostListener");

        reload.runOld(reload.forwarding("mod.Forwarding", host)).join();

        Object prepared = reload.field(host, "prepared");
        assertNotNull(prepared, "prepareSharedState runs before reload, as the host runs it");
        assertSame(prepared, reload.field(host, "reloaded"));
        assertNotSame(reload.state, prepared);
        assertSame(reload.resources, reload.resourcesOf(prepared));
    }

    @Test
    @DisplayName("a host reload started inside another leaves the outer reload's state in place afterwards")
    void nestedReloadRestoresTheOuterState() throws Throwable {
        Reload reload = onStateHost(OLD_API, OLD_MODS);
        Object outerHost = reload.instance(RESOURCES.replace('/', '.') + "HostListener");
        Object innerHost = reload.instance(RESOURCES.replace('/', '.') + "HostListener");
        Object inner = reload.forwarding("mod.Forwarding", innerHost);
        Object innerState = reload.newState();
        reload.setStatic("mod.HookedForwarding", "beforeForward", (Runnable) () -> {
            try {
                reload.run(inner, innerState).join();
            } catch (Throwable t) {
                throw new AssertionError(t);
            }
        });

        reload.run(reload.forwarding("mod.HookedForwarding", outerHost)).join();

        assertSame(innerState, reload.field(innerHost, "reloaded"));
        assertSame(reload.state, reload.field(outerHost, "reloaded"), "the outer wrapper forwards after the inner reload");
    }

    @Test
    @DisplayName("a Balm-shaped named wrapper around an old lambda runs each once, with no loop through the bridge")
    void balmShapedWrapperRunsEachListenerOnce() throws Throwable {
        Reload reload = onStateHost(OLD_API, merge(OLD_MODS, BALM_MOD));
        Object listener = reload.call("mod.BalmRegistrar", "modListener");
        Object wrapper = reload.call("mod.BalmRegistrar", "register", "balm:test", listener);

        reload.run(wrapper).join();

        assertEquals(List.of("wrapper", "listener", "applied on game"), reload.staticField("mod.BalmRegistrar", "LOG"));
    }

    @Test
    @DisplayName("a super call to the host's default reload stays a super call and reaches it")
    void superCallReachesTheHostDefault() throws Throwable {
        Reload reload = onStateHost(OLD_API, OLD_MODS);
        Object language = reload.instance("mod.LanguageListener");

        reload.run(language).join();

        assertEquals(List.of("reload", "onResourceManagerReload"), reload.field(language, "log"));
        assertSame(reload.resources, reload.field(language, "reloadedWith"));
    }

    @Test
    @DisplayName("a listener that throws, or fails its future, fails the host's reload with its own error")
    void failuresReachTheHost() throws Throwable {
        Reload reload = onStateHost(OLD_API, OLD_MODS);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> reload.run(reload.instance("mod.Broken")));
        assertEquals("broken models", thrown.getMessage());

        CompletableFuture<?> failed = reload.run(reload.instance("mod.FailsLater"));
        CompletionException completion = assertThrows(CompletionException.class, failed::join);
        assertEquals("bad file", completion.getCause().getMessage());

        // The failed reload's state is no longer active, so a later wrapper outside a reload builds its own.
        Object host = reload.instance(RESOURCES.replace('/', '.') + "HostListener");
        reload.runOld(reload.forwarding("mod.Forwarding", host)).join();
        assertNotSame(reload.state, reload.field(host, "reloaded"));
    }

    @Test
    @DisplayName("a listener written for 1.21.2 to 1.21.8 runs on a host from 1.21.9")
    void barrierShapedListenerRunsOnAStateHost() throws Throwable {
        Reload reload = onStateHost(BARRIER_API, BARRIER_MODS);
        Object loader = reload.instance("mod.ModelLoader");

        reload.run(loader).join();
        reload.run(reload.call("mod.Registrations", "methodReference")).join();

        assertEquals(List.of("prepare on background", "apply models on game"), reload.field(loader, "log"));
        assertSame(reload.resources, reload.field(loader, "preparedWith"));
        assertEquals(List.of("prepare on background", "apply on game"), reload.staticField("mod.Registrations", "LOG"));
    }

    @Test
    @DisplayName("the 26.3 client's own dispatch reaches an old listener and an old method reference")
    void realHostDispatchReachesOldListeners() throws Throwable {
        assumeTrue(Files.isRegularFile(CLIENT_263), "needs the 26.3 client from Prism Launcher");
        Map<String, byte[]> mod = translate(OLD_API, OLD_MODS, Names.MOJANG, Shape.STATE, "26.3");
        try (URLClassLoader client = new URLClassLoader(new URL[] {CLIENT_263.toUri().toURL()},
                ClassLoader.getPlatformClassLoader())) {
            Reload reload = new Reload(new ModLoader(client, mod), Names.MOJANG);
            Class<?> factory = reload.type(RESOURCES + "SimpleReloadInstance$StateFactory");
            Object simple = factory.getField("SIMPLE").get(null);
            Method create = factory.getMethod("create", reload.type(RESOURCES + "PreparableReloadListener$SharedState"),
                    reload.type(RESOURCES + "PreparableReloadListener$PreparationBarrier"),
                    reload.type(RESOURCES + "PreparableReloadListener"), Executor.class, Executor.class);
            Object loader = reload.instance("mod.ModelLoader");

            // The host's own lambda calls listener.reload(state, taskExecutor, barrier, reloadExecutor).
            ((CompletableFuture<?>) create.invoke(simple, reload.state, reload.barrier, loader,
                    reload.background, reload.game)).join();
            ((CompletableFuture<?>) create.invoke(simple, reload.state, reload.barrier,
                    reload.call("mod.Registrations", "methodReference"), reload.background, reload.game)).join();

            assertEquals(List.of("prepare on background", "apply models on game"), reload.field(loader, "log"));
            assertSame(reload.resources, reload.field(loader, "appliedWith"));
            assertSame(reload.inactiveProfiler(), reload.field(loader, "preparations"));
            assertEquals(List.of("prepare on background", "apply on game"),
                    reload.staticField("mod.Registrations", "LOG"));
        }
    }

    // ---- Hosts from 1.21.2 to 1.21.8: reload(barrier, resources, background, game) ----

    @Test
    @DisplayName("on a 1.21.2 to 1.21.8 host the old class, its lambdas and its super call drop only the profilers")
    void oldListenersRunOnABarrierHost() throws Throwable {
        Map<String, byte[]> mod = translate(OLD_API, OLD_MODS, Names.MOJANG, Shape.BARRIER, "1.21.5");
        Reload reload = new Reload(new ModLoader(BridgeHarness.class.getClassLoader(),
                merge(compileHost(BARRIER_API), mod)), Names.MOJANG);
        Object loader = reload.instance("mod.ModelLoader");
        Object language = reload.instance("mod.LanguageListener");

        reload.runBarrierShape(loader).join();
        reload.runBarrierShape(reload.call("mod.Registrations", "methodReference")).join();
        reload.runBarrierShape(language).join();

        assertEquals(List.of("prepare on background", "apply models on game"), reload.field(loader, "log"));
        assertSame(reload.resources, reload.field(loader, "appliedWith"));
        assertSame(reload.inactiveProfiler(), reload.field(loader, "reloads"));
        assertEquals(List.of("prepare on background", "apply on game"), reload.staticField("mod.Registrations", "LOG"));
        assertEquals(List.of("reload", "onResourceManagerReload"), reload.field(language, "log"));
    }

    // ---- Fabric hosts before 26.1 run intermediary names ----

    @Test
    @DisplayName("on Fabric hosts before 26.1 the same bridges work in intermediary names")
    void intermediaryListenersRunOnFabricHosts() throws Throwable {
        Map<String, byte[]> barrier = translate(INTERMEDIARY_OLD_API, INTERMEDIARY_MOD, Names.INTERMEDIARY,
                Shape.BARRIER, "1.21.5");
        Reload onBarrierHost = new Reload(new ModLoader(BridgeHarness.class.getClassLoader(),
                merge(compileHost(INTERMEDIARY_BARRIER_API), barrier)), Names.INTERMEDIARY);
        Object first = onBarrierHost.instance("mod.IntermediaryLoader");
        onBarrierHost.runBarrierShape(first).join();
        onBarrierHost.runBarrierShape(onBarrierHost.call("mod.IntermediaryLoader", "methodReference")).join();
        assertEquals(List.of("prepare", "apply", "static prepare"), onBarrierHost.field(first, "log"));

        Map<String, byte[]> state = translate(INTERMEDIARY_OLD_API, INTERMEDIARY_MOD, Names.INTERMEDIARY,
                Shape.STATE, "1.21.11");
        Reload onStateHost = new Reload(new ModLoader(BridgeHarness.class.getClassLoader(),
                merge(compileHost(INTERMEDIARY_STATE_API), state)), Names.INTERMEDIARY);
        Object second = onStateHost.instance("mod.IntermediaryLoader");
        onStateHost.run(second).join();
        onStateHost.run(onStateHost.call("mod.IntermediaryLoader", "methodReference")).join();
        assertEquals(List.of("prepare", "apply", "static prepare"), onStateHost.field(second, "log"));
        assertSame(onStateHost.resources, onStateHost.field(second, "resources"));
    }

    // ---- What stays as it is ----

    @Test
    @DisplayName("a mixin keeps its methods, since they merge into a Minecraft class that has the host's reload")
    void mixinGetsNoHostMethod() throws Exception {
        Map<String, byte[]> mod = translate(OLD_API, OLD_MODS, Names.MOJANG, Shape.STATE, "26.3");
        ClassNode mixin = node(mod.get("mod/FontManagerMixin"));
        assertEquals(List.of("<init>", "reload"), mixin.methods.stream().map(m -> m.name).toList());
        assertEquals(Names.MOJANG.desc(Shape.PROFILED), mixin.methods.get(1).desc);
    }

    @Test
    @DisplayName("a subclass of a host class whose reload became final keeps loading, without the host method")
    void finalHostReloadIsNotOverridden() throws Exception {
        Map<String, byte[]> host = compileHost(STATE_API);
        Map<String, byte[]> compiled = BridgeHarness.compile(dir.resolve("final"), merge(OLD_API, OLD_MODS));
        Map<String, byte[]> translated = BridgeHarness.transform("26.3",
                transformer -> LegacyReloadListenerBridge.registerFor(transformer, Names.MOJANG, Shape.STATE),
                BridgeHarness.only(compiled, "mod/"), host);

        ClassNode subclass = node(translated.get("mod/ModelManagerSubclass"));
        assertEquals(List.of("<init>", "reload"), subclass.methods.stream().map(m -> m.name).toList(),
                "26.x's model manager made reload final, so overriding it would fail class loading");
        Map<String, byte[]> classes = merge(host, translated);
        classes.putAll(RetromodTransformer.getInstance().getSyntheticClasses());
        Reload reload = new Reload(new ModLoader(BridgeHarness.class.getClassLoader(), classes), Names.MOJANG);
        assertNotNull(reload.instance("mod.ModelManagerSubclass"));
    }

    @Test
    @DisplayName("a listener of the host's own shape, and any listener on a 1.21.1 host, are left alone")
    void currentListenersAreUntouched() throws Exception {
        Map<String, byte[]> compiled = BridgeHarness.compile(dir.resolve("current"), merge(STATE_API, CURRENT_MOD));
        Map<String, byte[]> current = BridgeHarness.only(compiled, "mod/");
        Map<String, byte[]> translated = BridgeHarness.transform("26.3",
                transformer -> LegacyReloadListenerBridge.registerFor(transformer, Names.MOJANG, Shape.STATE), current);
        assertArrayEquals(current.get("mod/CurrentListener"), translated.get("mod/CurrentListener"));

        RetromodVersion.TARGET_MC_VERSION = "1.21.1";
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyReloadListenerBridge.register(transformer);
        assertFalse(transformer.getSyntheticClasses().containsKey(LegacyReloadListenerBridge.HELPER),
                "a 1.21.1 host keeps the old shape, so nothing is registered");
    }

    @Test
    @DisplayName("offline, the host's version decides its shape")
    void shapeFollowsTheVersionOffline() {
        assertEquals(Shape.PROFILED, LegacyReloadListenerBridge.shapeForVersion("1.21.1"));
        assertEquals(Shape.BARRIER, LegacyReloadListenerBridge.shapeForVersion("1.21.2"));
        assertEquals(Shape.BARRIER, LegacyReloadListenerBridge.shapeForVersion("1.21.8"));
        assertEquals(Shape.STATE, LegacyReloadListenerBridge.shapeForVersion("1.21.9"));
        assertEquals(Shape.STATE, LegacyReloadListenerBridge.shapeForVersion("26.3"));
    }

    // ---- Harness ----

    private Reload onStateHost(Map<String, String> api, Map<String, String> mods) throws Exception {
        Map<String, byte[]> mod = translate(api, mods, Names.MOJANG, Shape.STATE, "26.3");
        return new Reload(new ModLoader(BridgeHarness.class.getClassLoader(), merge(compileHost(STATE_API), mod)),
                Names.MOJANG);
    }

    /** Compiles the mod against {@code api}, then translates its classes for a host of {@code shape}. */
    private Map<String, byte[]> translate(Map<String, String> api, Map<String, String> mods, Names names, Shape shape,
            String target) throws Exception {
        Map<String, byte[]> compiled = BridgeHarness.compile(dir.resolve("mod-" + shape + names.reload()),
                merge(api, mods));
        Map<String, byte[]> translated = BridgeHarness.transform(target,
                transformer -> LegacyReloadListenerBridge.registerFor(transformer, names, shape),
                BridgeHarness.only(compiled, "mod/"));
        Map<String, byte[]> withHelpers = new HashMap<>(translated);
        withHelpers.putAll(RetromodTransformer.getInstance().getSyntheticClasses());
        return withHelpers;
    }

    private Map<String, byte[]> compileHost(Map<String, String> api) throws Exception {
        return BridgeHarness.compile(dir.resolve("host-" + api.hashCode()), api);
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    @SafeVarargs
    private static <V> Map<String, V> merge(Map<String, V>... maps) {
        Map<String, V> all = new LinkedHashMap<>();
        for (Map<String, V> map : maps) all.putAll(map);
        return all;
    }

    /** Defines the host's and the mod's classes in one loader, ahead of {@code parent}. */
    private static final class ModLoader extends ClassLoader {
        private final Map<String, byte[]> classes = new HashMap<>();

        ModLoader(ClassLoader parent, Map<String, byte[]> classes) {
            super(parent);
            classes.forEach((name, bytes) -> this.classes.put(name.replace('/', '.'), bytes));
        }

        @Override
        protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            Class<?> loaded = findLoadedClass(name);
            if (loaded != null) return loaded;
            byte[] bytes = classes.get(name);
            if (bytes == null) return super.loadClass(name, resolve);
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    /** One reload as a host runs it: a state over stand-in resources, a barrier, and two recording executors. */
    private static final class Reload {
        final ClassLoader loader;
        final Names names;
        final Object resources;
        final Object state;
        final Object barrier;
        final List<Object> waited = new ArrayList<>();
        final Executor background;
        final Executor game;
        private final List<String> executorLog = new ArrayList<>();

        Reload(ClassLoader loader, Names names) throws Exception {
            this.loader = loader;
            this.names = names;
            this.resources = Proxy.newProxyInstance(loader, new Class<?>[] {type(names.resources())},
                    (proxy, method, args) -> method.getName().equals("toString") ? "resources" : null);
            this.barrier = Proxy.newProxyInstance(loader, new Class<?>[] {type(names.barrier())},
                    (proxy, method, args) -> {
                        if (method.getName().equals("toString")) return "barrier";
                        waited.add(args[0]);
                        return CompletableFuture.completedFuture(args[0]);
                    });
            Class<?> stateType = loadOrNull(names.state());
            this.state = stateType == null ? null : stateType.getConstructor(type(names.resources())).newInstance(resources);
            this.background = recording("background");
            this.game = recording("game");
        }

        /** An executor that runs at once and names itself to the mod through the thread name. */
        private Executor recording(String name) {
            return task -> {
                String saved = Thread.currentThread().getName();
                Thread.currentThread().setName(name);
                try {
                    task.run();
                } finally {
                    Thread.currentThread().setName(saved);
                }
            };
        }

        /** {@code listener.reload(state, background, barrier, game)}, as SimpleReloadInstance calls it. */
        CompletableFuture<?> run(Object listener) throws Throwable {
            return run(listener, state);
        }

        CompletableFuture<?> run(Object listener, Object withState) throws Throwable {
            Method reload = type(names.listener()).getMethod(names.reload(), type(names.state()), Executor.class,
                    type(names.barrier()), Executor.class);
            return invoke(reload, listener, withState, background, barrier, game);
        }

        /** The listener's own old reload, called directly, as code outside any host reload calls it. */
        CompletableFuture<?> runOld(Object listener) throws Throwable {
            for (Method method : listener.getClass().getMethods()) {
                if (method.getName().equals(names.reload()) && method.getParameterCount() == 6) {
                    return invoke(method, listener, barrier, resources, null, null, background, game);
                }
            }
            throw new AssertionError("no old reload on " + listener.getClass());
        }

        /** A mod listener of {@code name} that wraps {@code delegate}. */
        Object forwarding(String name, Object delegate) throws Exception {
            return instance(name, type(names.listener()), delegate);
        }

        Object newState() throws Exception {
            return type(names.state()).getConstructor(type(names.resources())).newInstance(resources);
        }

        Object resourcesOf(Object someState) throws Exception {
            return type(names.state()).getMethod(names.stateResources()).invoke(someState);
        }

        void setStatic(String owner, String name, Object value) throws Exception {
            type(owner.replace('.', '/')).getField(name).set(null, value);
        }

        /** {@code listener.reload(barrier, resources, background, game)}, as 1.21.2 to 1.21.8 call it. */
        CompletableFuture<?> runBarrierShape(Object listener) throws Throwable {
            Method reload = type(names.listener()).getMethod(names.reload(), type(names.barrier()),
                    type(names.resources()), Executor.class, Executor.class);
            return invoke(reload, listener, barrier, resources, background, game);
        }

        private static CompletableFuture<?> invoke(Method method, Object target, Object... args) throws Throwable {
            try {
                return (CompletableFuture<?>) method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }

        Object inactiveProfiler() throws Exception {
            return type(names.inactive()).getField(names.inactiveField()).get(null);
        }

        Class<?> type(String internalName) throws ClassNotFoundException {
            return Class.forName(internalName.replace('/', '.'), true, loader);
        }

        private Class<?> loadOrNull(String internalName) {
            try {
                return type(internalName);
            } catch (ClassNotFoundException absent) {
                return null;
            }
        }

        Object instance(String name, Object... args) throws Exception {
            Class<?> type = type(name.replace('.', '/'));
            if (args.length == 0) return type.getConstructor().newInstance();
            return type.getConstructor((Class<?>) args[0]).newInstance(args[1]);
        }

        Object call(String owner, String method, Object... args) throws Throwable {
            for (Method candidate : type(owner.replace('.', '/')).getMethods()) {
                if (candidate.getName().equals(method) && candidate.getParameterCount() == args.length) {
                    try {
                        return candidate.invoke(null, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                }
            }
            throw new AssertionError("no " + owner + "." + method);
        }

        Object field(Object target, String name) throws Exception {
            return target.getClass().getField(name).get(target);
        }

        Object staticField(String owner, String name) throws Exception {
            return type(owner.replace('.', '/')).getField(name).get(null);
        }
    }

    // ---- Sources: the old API the mods compiled against ----

    private static final String PROFILER_SOURCE = """
            package net.minecraft.util.profiling;

            public interface ProfilerFiller {
                void push(String name);
                void pop();
            }
            """;

    private static final Map<String, String> OLD_API = Map.of(
            RESOURCES + "PreparableReloadListener.java", """
                    package net.minecraft.server.packs.resources;

                    import java.util.concurrent.CompletableFuture;
                    import java.util.concurrent.Executor;
                    import net.minecraft.util.profiling.ProfilerFiller;

                    public interface PreparableReloadListener {
                        CompletableFuture<Void> reload(PreparationBarrier barrier, ResourceManager resources,
                                ProfilerFiller preparations, ProfilerFiller reloads, Executor background, Executor game);

                        interface PreparationBarrier {
                            <T> CompletableFuture<T> wait(T value);
                        }
                    }
                    """,
            RESOURCES + "ResourceManagerReloadListener.java", """
                    package net.minecraft.server.packs.resources;

                    import java.util.concurrent.CompletableFuture;
                    import java.util.concurrent.Executor;
                    import net.minecraft.util.profiling.ProfilerFiller;

                    public interface ResourceManagerReloadListener extends PreparableReloadListener {
                        @Override
                        default CompletableFuture<Void> reload(PreparationBarrier barrier, ResourceManager resources,
                                ProfilerFiller preparations, ProfilerFiller reloads, Executor background, Executor game) {
                            return barrier.wait(null).thenRunAsync(() -> onResourceManagerReload(resources), game);
                        }

                        void onResourceManagerReload(ResourceManager resources);
                    }
                    """,
            RESOURCES + "ResourceManager.java", "package net.minecraft.server.packs.resources;\npublic interface ResourceManager {}\n",
            RESOURCES + "HostListener.java", """
                    package net.minecraft.server.packs.resources;

                    public class HostListener implements PreparableReloadListener {
                        public java.util.concurrent.CompletableFuture<Void> reload(PreparationBarrier barrier,
                                ResourceManager resources, net.minecraft.util.profiling.ProfilerFiller preparations,
                                net.minecraft.util.profiling.ProfilerFiller reloads, java.util.concurrent.Executor background,
                                java.util.concurrent.Executor game) {
                            return null;
                        }
                    }
                    """,
            RESOURCES + "ModelManager.java", """
                    package net.minecraft.server.packs.resources;

                    public class ModelManager implements PreparableReloadListener {
                        public java.util.concurrent.CompletableFuture<Void> reload(PreparationBarrier barrier,
                                ResourceManager resources, net.minecraft.util.profiling.ProfilerFiller preparations,
                                net.minecraft.util.profiling.ProfilerFiller reloads, java.util.concurrent.Executor background,
                                java.util.concurrent.Executor game) {
                            return null;
                        }
                    }
                    """,
            PROFILING + "ProfilerFiller.java", PROFILER_SOURCE,
            "org/spongepowered/asm/mixin/Mixin.java", """
                    package org.spongepowered.asm.mixin;

                    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.CLASS)
                    public @interface Mixin {
                        Class<?>[] value();
                    }
                    """);

    // ---- Sources: mods written for 1.21.1 or older ----

    private static final Map<String, String> OLD_MODS = Map.of(
            "mod/ModelLoader.java", """
                    package mod;

                    import java.util.ArrayList;
                    import java.util.List;
                    import java.util.concurrent.CompletableFuture;
                    import java.util.concurrent.Executor;
                    import net.minecraft.server.packs.resources.PreparableReloadListener;
                    import net.minecraft.server.packs.resources.ResourceManager;
                    import net.minecraft.util.profiling.ProfilerFiller;

                    /** Loads models off the game thread, the shape GeckoLib 4 and most model loaders use. */
                    public class ModelLoader implements PreparableReloadListener {
                        public final List<String> log = new ArrayList<>();
                        public ResourceManager preparedWith;
                        public ResourceManager appliedWith;
                        public ProfilerFiller preparations;
                        public ProfilerFiller reloads;

                        @Override
                        public CompletableFuture<Void> reload(PreparationBarrier barrier, ResourceManager resources,
                                ProfilerFiller preparations, ProfilerFiller reloads, Executor background, Executor game) {
                            this.preparations = preparations;
                            this.reloads = reloads;
                            return CompletableFuture.supplyAsync(() -> {
                                preparedWith = resources;
                                log.add("prepare on " + Thread.currentThread().getName());
                                return "models";
                            }, background).thenCompose(barrier::wait).thenAcceptAsync(models -> {
                                appliedWith = resources;
                                log.add("apply " + models + " on " + Thread.currentThread().getName());
                            }, game);
                        }
                    }
                    """,
            "mod/Registrations.java", """
                    package mod;

                    import java.util.ArrayList;
                    import java.util.List;
                    import java.util.concurrent.CompletableFuture;
                    import java.util.concurrent.Executor;
                    import net.minecraft.server.packs.resources.PreparableReloadListener;
                    import net.minecraft.server.packs.resources.ResourceManager;
                    import net.minecraft.util.profiling.ProfilerFiller;

                    /** Listeners registered as lambdas, as GeckoLibCache::reload is. */
                    public class Registrations {
                        public static final List<String> LOG = new ArrayList<>();

                        public static PreparableReloadListener methodReference() {
                            return Registrations::reloadModels;
                        }

                        public static PreparableReloadListener capturing(String label) {
                            return (barrier, resources, preparations, reloads, background, game) ->
                                    barrier.wait(label).thenAcceptAsync(
                                            value -> LOG.add(value + " on " + Thread.currentThread().getName()), game);
                        }

                        static CompletableFuture<Void> reloadModels(PreparableReloadListener.PreparationBarrier barrier,
                                ResourceManager resources,
                                ProfilerFiller preparations, ProfilerFiller reloads, Executor background, Executor game) {
                            return CompletableFuture.runAsync(() -> LOG.add("prepare on " + Thread.currentThread().getName()),
                                    background).thenCompose(ignored -> barrier.wait("prepared"))
                                    .thenRunAsync(() -> LOG.add("apply on " + Thread.currentThread().getName()), game);
                        }
                    }
                    """,
            "mod/Forwarding.java", """
                    package mod;

                    import java.util.concurrent.CompletableFuture;
                    import java.util.concurrent.Executor;
                    import net.minecraft.server.packs.resources.PreparableReloadListener;
                    import net.minecraft.server.packs.resources.ResourceManager;
                    import net.minecraft.util.profiling.ProfilerFiller;

                    /** Wraps another listener and calls its reload, as PuzzlesLib and ModernFix do. */
                    public class Forwarding implements PreparableReloadListener {
                        private final PreparableReloadListener delegate;

                        public Forwarding(PreparableReloadListener delegate) {
                            this.delegate = delegate;
                        }

                        @Override
                        public CompletableFuture<Void> reload(PreparationBarrier barrier, ResourceManager resources,
                                ProfilerFiller preparations, ProfilerFiller reloads, Executor background, Executor game) {
                            return delegate.reload(barrier, resources, preparations, reloads, background, game);
                        }
                    }
                    """,
            "mod/HookedForwarding.java", """
                    package mod;

                    import java.util.concurrent.CompletableFuture;
                    import java.util.concurrent.Executor;
                    import net.minecraft.server.packs.resources.PreparableReloadListener;
                    import net.minecraft.server.packs.resources.ResourceManager;
                    import net.minecraft.util.profiling.ProfilerFiller;

                    /** Forwards after a hook, so a test can run another reload in between. */
                    public class HookedForwarding implements PreparableReloadListener {
                        public static Runnable beforeForward;
                        private final PreparableReloadListener delegate;

                        public HookedForwarding(PreparableReloadListener delegate) {
                            this.delegate = delegate;
                        }

                        @Override
                        public CompletableFuture<Void> reload(PreparationBarrier barrier, ResourceManager resources,
                                ProfilerFiller preparations, ProfilerFiller reloads, Executor background, Executor game) {
                            if (beforeForward != null) beforeForward.run();
                            return delegate.reload(barrier, resources, preparations, reloads, background, game);
                        }
                    }
                    """,
            "mod/LanguageListener.java", """
                    package mod;

                    import java.util.ArrayList;
                    import java.util.List;
                    import java.util.concurrent.CompletableFuture;
                    import java.util.concurrent.Executor;
                    import net.minecraft.server.packs.resources.ResourceManager;
                    import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
                    import net.minecraft.util.profiling.ProfilerFiller;

                    /** Overrides the default reload and calls it, as PuzzlesLib's forwarding listener does. */
                    public class LanguageListener implements ResourceManagerReloadListener {
                        public final List<String> log = new ArrayList<>();
                        public ResourceManager reloadedWith;

                        @Override
                        public CompletableFuture<Void> reload(PreparationBarrier barrier, ResourceManager resources,
                                ProfilerFiller preparations, ProfilerFiller reloads, Executor background, Executor game) {
                            log.add("reload");
                            return ResourceManagerReloadListener.super.reload(barrier, resources, preparations, reloads,
                                    background, game);
                        }

                        @Override
                        public void onResourceManagerReload(ResourceManager resources) {
                            log.add("onResourceManagerReload");
                            reloadedWith = resources;
                        }
                    }
                    """,
            "mod/Broken.java", """
                    package mod;

                    import java.util.concurrent.CompletableFuture;
                    import java.util.concurrent.Executor;
                    import net.minecraft.server.packs.resources.PreparableReloadListener;
                    import net.minecraft.server.packs.resources.ResourceManager;
                    import net.minecraft.util.profiling.ProfilerFiller;

                    public class Broken implements PreparableReloadListener {
                        @Override
                        public CompletableFuture<Void> reload(PreparationBarrier barrier, ResourceManager resources,
                                ProfilerFiller preparations, ProfilerFiller reloads, Executor background, Executor game) {
                            throw new IllegalStateException("broken models");
                        }
                    }
                    """,
            "mod/FailsLater.java", """
                    package mod;

                    import java.util.concurrent.CompletableFuture;
                    import java.util.concurrent.Executor;
                    import net.minecraft.server.packs.resources.PreparableReloadListener;
                    import net.minecraft.server.packs.resources.ResourceManager;
                    import net.minecraft.util.profiling.ProfilerFiller;

                    public class FailsLater implements PreparableReloadListener {
                        @Override
                        public CompletableFuture<Void> reload(PreparationBarrier barrier, ResourceManager resources,
                                ProfilerFiller preparations, ProfilerFiller reloads, Executor background, Executor game) {
                            return CompletableFuture.failedFuture(new IllegalStateException("bad file"));
                        }
                    }
                    """,
            "mod/ModelManagerSubclass.java", """
                    package mod;

                    import java.util.concurrent.CompletableFuture;
                    import java.util.concurrent.Executor;
                    import net.minecraft.server.packs.resources.ModelManager;
                    import net.minecraft.server.packs.resources.ResourceManager;
                    import net.minecraft.util.profiling.ProfilerFiller;

                    public class ModelManagerSubclass extends ModelManager {
                        @Override
                        public CompletableFuture<Void> reload(PreparationBarrier barrier, ResourceManager resources,
                                ProfilerFiller preparations, ProfilerFiller reloads, Executor background, Executor game) {
                            return super.reload(barrier, resources, preparations, reloads, background, game);
                        }
                    }
                    """,
            "mod/FontManagerMixin.java", """
                    package mod;

                    import java.util.concurrent.CompletableFuture;
                    import java.util.concurrent.Executor;
                    import net.minecraft.server.packs.resources.PreparableReloadListener;
                    import net.minecraft.server.packs.resources.ResourceManager;
                    import net.minecraft.util.profiling.ProfilerFiller;

                    @org.spongepowered.asm.mixin.Mixin(Object.class)
                    public abstract class FontManagerMixin {
                        public CompletableFuture<Void> reload(PreparableReloadListener.PreparationBarrier barrier,
                                ResourceManager resources, ProfilerFiller preparations, ProfilerFiller reloads,
                                Executor background, Executor game) {
                            return null;
                        }
                    }
                    """);

    // Balm's Fabric build registers each mod listener behind an anonymous named wrapper that forwards to it.
    private static final Map<String, String> BALM_MOD = Map.of("mod/BalmRegistrar.java", """
            package mod;

            import java.util.ArrayList;
            import java.util.List;
            import java.util.concurrent.CompletableFuture;
            import java.util.concurrent.Executor;
            import net.minecraft.server.packs.resources.PreparableReloadListener;
            import net.minecraft.server.packs.resources.ResourceManager;
            import net.minecraft.util.profiling.ProfilerFiller;

            public class BalmRegistrar {
                public static final List<String> LOG = new ArrayList<>();

                /** A named listener, as Fabric API's identifiable one is. */
                public interface NamedListener extends PreparableReloadListener {
                    String name();
                }

                public static PreparableReloadListener register(String name, PreparableReloadListener listener) {
                    return new NamedListener() {
                        @Override
                        public String name() {
                            return name;
                        }

                        @Override
                        public CompletableFuture<Void> reload(PreparationBarrier barrier, ResourceManager resources,
                                ProfilerFiller preparations, ProfilerFiller reloads, Executor background, Executor game) {
                            LOG.add("wrapper");
                            return listener.reload(barrier, resources, preparations, reloads, background, game);
                        }
                    };
                }

                public static PreparableReloadListener modListener() {
                    return (barrier, resources, preparations, reloads, background, game) -> {
                        LOG.add("listener");
                        return barrier.wait(null).thenRunAsync(
                                () -> LOG.add("applied on " + Thread.currentThread().getName()), game);
                    };
                }
            }
            """);

    // ---- Sources: 1.21.2 to 1.21.8, and a mod written for them ----

    private static final Map<String, String> BARRIER_API = Map.of(
            RESOURCES + "PreparableReloadListener.java", """
                    package net.minecraft.server.packs.resources;

                    import java.util.concurrent.CompletableFuture;
                    import java.util.concurrent.Executor;

                    public interface PreparableReloadListener {
                        CompletableFuture<Void> reload(PreparationBarrier barrier, ResourceManager resources,
                                Executor background, Executor game);

                        interface PreparationBarrier {
                            <T> CompletableFuture<T> wait(T value);
                        }
                    }
                    """,
            RESOURCES + "ResourceManagerReloadListener.java", """
                    package net.minecraft.server.packs.resources;

                    import java.util.concurrent.CompletableFuture;
                    import java.util.concurrent.Executor;

                    public interface ResourceManagerReloadListener extends PreparableReloadListener {
                        @Override
                        default CompletableFuture<Void> reload(PreparationBarrier barrier, ResourceManager resources,
                                Executor background, Executor game) {
                            return barrier.wait(null).thenRunAsync(() -> onResourceManagerReload(resources), game);
                        }

                        void onResourceManagerReload(ResourceManager resources);
                    }
                    """,
            RESOURCES + "ResourceManager.java", "package net.minecraft.server.packs.resources;\npublic interface ResourceManager {}\n",
            PROFILING + "ProfilerFiller.java", PROFILER_SOURCE,
            PROFILING + "InactiveProfiler.java", """
                    package net.minecraft.util.profiling;

                    public class InactiveProfiler implements ProfilerFiller {
                        public static final InactiveProfiler INSTANCE = new InactiveProfiler();
                        public void push(String name) {}
                        public void pop() {}
                    }
                    """);

    private static final Map<String, String> BARRIER_MODS = Map.of(
            "mod/ModelLoader.java", """
                    package mod;

                    import java.util.ArrayList;
                    import java.util.List;
                    import java.util.concurrent.CompletableFuture;
                    import java.util.concurrent.Executor;
                    import net.minecraft.server.packs.resources.PreparableReloadListener;
                    import net.minecraft.server.packs.resources.ResourceManager;

                    public class ModelLoader implements PreparableReloadListener {
                        public final List<String> log = new ArrayList<>();
                        public ResourceManager preparedWith;

                        @Override
                        public CompletableFuture<Void> reload(PreparationBarrier barrier, ResourceManager resources,
                                Executor background, Executor game) {
                            return CompletableFuture.supplyAsync(() -> {
                                preparedWith = resources;
                                log.add("prepare on " + Thread.currentThread().getName());
                                return "models";
                            }, background).thenCompose(barrier::wait).thenAcceptAsync(
                                    models -> log.add("apply " + models + " on " + Thread.currentThread().getName()), game);
                        }
                    }
                    """,
            "mod/Registrations.java", """
                    package mod;

                    import java.util.ArrayList;
                    import java.util.List;
                    import java.util.concurrent.CompletableFuture;
                    import java.util.concurrent.Executor;
                    import net.minecraft.server.packs.resources.PreparableReloadListener;
                    import net.minecraft.server.packs.resources.ResourceManager;

                    public class Registrations {
                        public static final List<String> LOG = new ArrayList<>();

                        public static PreparableReloadListener methodReference() {
                            return Registrations::reloadModels;
                        }

                        static CompletableFuture<Void> reloadModels(PreparableReloadListener.PreparationBarrier barrier,
                                ResourceManager resources,
                                Executor background, Executor game) {
                            return CompletableFuture.runAsync(() -> LOG.add("prepare on " + Thread.currentThread().getName()),
                                    background).thenCompose(ignored -> barrier.wait("prepared"))
                                    .thenRunAsync(() -> LOG.add("apply on " + Thread.currentThread().getName()), game);
                        }
                    }
                    """);

    // ---- Sources: hosts from 1.21.9, and a mod already written for them ----

    private static final Map<String, String> STATE_API = Map.of(
            RESOURCES + "PreparableReloadListener.java", """
                    package net.minecraft.server.packs.resources;

                    import java.util.concurrent.CompletableFuture;
                    import java.util.concurrent.Executor;

                    public interface PreparableReloadListener {
                        CompletableFuture<Void> reload(SharedState state, Executor background, PreparationBarrier barrier,
                                Executor game);

                        default void prepareSharedState(SharedState state) {}

                        interface PreparationBarrier {
                            <T> CompletableFuture<T> wait(T value);
                        }

                        final class SharedState {
                            private final ResourceManager resources;
                            private final java.util.Map<StateKey<?>, Object> values = new java.util.IdentityHashMap<>();

                            public SharedState(ResourceManager resources) {
                                this.resources = resources;
                            }

                            public ResourceManager resourceManager() {
                                return resources;
                            }

                            public <T> void set(StateKey<T> key, T value) {
                                values.put(key, value);
                            }

                            @SuppressWarnings("unchecked")
                            public <T> T get(StateKey<T> key) {
                                return (T) java.util.Objects.requireNonNull(values.get(key));
                            }
                        }

                        final class StateKey<T> {
                        }
                    }
                    """,
            RESOURCES + "AtlasManager.java", """
                    package net.minecraft.server.packs.resources;

                    import java.util.concurrent.CompletableFuture;
                    import java.util.concurrent.Executor;

                    /** Puts its pending stitch into the state before any listener reloads, as 26.x's atlas manager does. */
                    public class AtlasManager implements PreparableReloadListener {
                        public static final StateKey<String> PENDING_STITCH = new StateKey<>();

                        @Override
                        public void prepareSharedState(SharedState state) {
                            state.set(PENDING_STITCH, "stitched atlas");
                        }

                        @Override
                        public CompletableFuture<Void> reload(SharedState state, Executor background,
                                PreparationBarrier barrier, Executor game) {
                            return CompletableFuture.completedFuture(null);
                        }
                    }
                    """,
            RESOURCES + "ParticleResources.java", """
                    package net.minecraft.server.packs.resources;

                    import java.util.concurrent.CompletableFuture;
                    import java.util.concurrent.Executor;

                    /** Reads the atlas manager's stitch from the state, as 26.x's particle resources do. */
                    public class ParticleResources implements PreparableReloadListener {
                        public String stitched;

                        @Override
                        public CompletableFuture<Void> reload(SharedState state, Executor background,
                                PreparationBarrier barrier, Executor game) {
                            stitched = state.get(AtlasManager.PENDING_STITCH);
                            return CompletableFuture.completedFuture(null);
                        }
                    }
                    """,
            RESOURCES + "ResourceManagerReloadListener.java", """
                    package net.minecraft.server.packs.resources;

                    import java.util.concurrent.CompletableFuture;
                    import java.util.concurrent.Executor;

                    public interface ResourceManagerReloadListener extends PreparableReloadListener {
                        @Override
                        default CompletableFuture<Void> reload(SharedState state, Executor background,
                                PreparationBarrier barrier, Executor game) {
                            return barrier.wait(null).thenRunAsync(() -> onResourceManagerReload(state.resourceManager()), game);
                        }

                        void onResourceManagerReload(ResourceManager resources);
                    }
                    """,
            RESOURCES + "HostListener.java", """
                    package net.minecraft.server.packs.resources;

                    import java.util.concurrent.CompletableFuture;
                    import java.util.concurrent.Executor;

                    /** A host listener that records the state it is prepared and reloaded with. */
                    public class HostListener implements PreparableReloadListener {
                        public SharedState prepared;
                        public SharedState reloaded;
                        public Executor background;
                        public Executor game;

                        @Override
                        public void prepareSharedState(SharedState state) {
                            prepared = state;
                        }

                        @Override
                        public CompletableFuture<Void> reload(SharedState state, Executor background,
                                PreparationBarrier barrier, Executor game) {
                            reloaded = state;
                            this.background = background;
                            this.game = game;
                            return CompletableFuture.completedFuture(null);
                        }
                    }
                    """,
            RESOURCES + "ModelManager.java", """
                    package net.minecraft.server.packs.resources;

                    public class ModelManager implements PreparableReloadListener {
                        @Override
                        public final java.util.concurrent.CompletableFuture<Void> reload(SharedState state,
                                java.util.concurrent.Executor background, PreparationBarrier barrier,
                                java.util.concurrent.Executor game) {
                            return java.util.concurrent.CompletableFuture.completedFuture(null);
                        }
                    }
                    """,
            RESOURCES + "ResourceManager.java", "package net.minecraft.server.packs.resources;\npublic interface ResourceManager {}\n",
            PROFILING + "ProfilerFiller.java", PROFILER_SOURCE,
            PROFILING + "InactiveProfiler.java", BARRIER_API.get(PROFILING + "InactiveProfiler.java"));

    private static final Map<String, String> CURRENT_MOD = Map.of("mod/CurrentListener.java", """
            package mod;

            import java.util.concurrent.CompletableFuture;
            import java.util.concurrent.Executor;
            import net.minecraft.server.packs.resources.PreparableReloadListener;

            public class CurrentListener implements PreparableReloadListener {
                @Override
                public CompletableFuture<Void> reload(SharedState state, Executor background, PreparationBarrier barrier,
                        Executor game) {
                    return barrier.wait(null).thenRunAsync(() -> { }, game);
                }
            }
            """);

    // ---- Sources: the same in Fabric's intermediary names ----

    private static final Map<String, String> INTERMEDIARY_OLD_API = Map.of(
            "net/minecraft/class_3302.java", """
                    package net.minecraft;

                    import java.util.concurrent.CompletableFuture;
                    import java.util.concurrent.Executor;

                    public interface class_3302 {
                        CompletableFuture<Void> method_25931(class_4045 barrier, class_3300 resources,
                                class_3695 preparations, class_3695 reloads, Executor background, Executor game);

                        interface class_4045 {
                            <T> CompletableFuture<T> method_18352(T value);
                        }
                    }
                    """,
            "net/minecraft/class_3300.java", "package net.minecraft;\npublic interface class_3300 {}\n",
            "net/minecraft/class_3695.java", "package net.minecraft;\npublic interface class_3695 {}\n");

    private static final Map<String, String> INTERMEDIARY_MOD = Map.of("mod/IntermediaryLoader.java", """
            package mod;

            import java.util.ArrayList;
            import java.util.List;
            import java.util.concurrent.CompletableFuture;
            import java.util.concurrent.Executor;
            import net.minecraft.class_3300;
            import net.minecraft.class_3302;
            import net.minecraft.class_3695;

            public class IntermediaryLoader implements class_3302 {
                public static final List<String> LOG = new ArrayList<>();
                public final List<String> log = LOG;
                public class_3300 resources;

                @Override
                public CompletableFuture<Void> method_25931(class_4045 barrier, class_3300 resources,
                        class_3695 preparations, class_3695 reloads, Executor background, Executor game) {
                    this.resources = resources;
                    return CompletableFuture.runAsync(() -> LOG.add("prepare"), background)
                            .thenCompose(ignored -> barrier.method_18352("models"))
                            .thenRunAsync(() -> LOG.add("apply"), game);
                }

                public static class_3302 methodReference() {
                    return IntermediaryLoader::reloadStatic;
                }

                static CompletableFuture<Void> reloadStatic(class_3302.class_4045 barrier, class_3300 resources,
                        class_3695 preparations, class_3695 reloads, Executor background, Executor game) {
                    return CompletableFuture.runAsync(() -> LOG.add("static prepare"), background);
                }
            }
            """);

    private static final Map<String, String> INTERMEDIARY_BARRIER_API = Map.of(
            "net/minecraft/class_3302.java", """
                    package net.minecraft;

                    import java.util.concurrent.CompletableFuture;
                    import java.util.concurrent.Executor;

                    public interface class_3302 {
                        CompletableFuture<Void> method_25931(class_4045 barrier, class_3300 resources, Executor background,
                                Executor game);

                        interface class_4045 {
                            <T> CompletableFuture<T> method_18352(T value);
                        }
                    }
                    """,
            "net/minecraft/class_3300.java", "package net.minecraft;\npublic interface class_3300 {}\n",
            "net/minecraft/class_3695.java", "package net.minecraft;\npublic interface class_3695 {}\n",
            "net/minecraft/class_3694.java", """
                    package net.minecraft;

                    public class class_3694 implements class_3695 {
                        public static final class_3694 field_16280 = new class_3694();
                    }
                    """);

    private static final Map<String, String> INTERMEDIARY_STATE_API = Map.of(
            "net/minecraft/class_3302.java", """
                    package net.minecraft;

                    import java.util.concurrent.CompletableFuture;
                    import java.util.concurrent.Executor;

                    public interface class_3302 {
                        CompletableFuture<Void> method_25931(class_11558 state, Executor background, class_4045 barrier,
                                Executor game);

                        default void prepareSharedState(class_11558 state) {}

                        interface class_4045 {
                            <T> CompletableFuture<T> method_18352(T value);
                        }

                        final class class_11558 {
                            private final class_3300 resources;

                            public class_11558(class_3300 resources) {
                                this.resources = resources;
                            }

                            public class_3300 method_72361() {
                                return resources;
                            }
                        }
                    }
                    """,
            "net/minecraft/class_3300.java", "package net.minecraft;\npublic interface class_3300 {}\n",
            "net/minecraft/class_3695.java", "package net.minecraft;\npublic interface class_3695 {}\n",
            "net/minecraft/class_3694.java", INTERMEDIARY_BARRIER_API.get("net/minecraft/class_3694.java"));
}
