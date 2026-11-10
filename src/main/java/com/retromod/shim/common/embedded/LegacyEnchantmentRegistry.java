/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common.embedded;

import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Spliterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

/** The enchantment registry old code read from {@code BuiltInRegistries.ENCHANTMENT}, answered from the loaded world. */
public final class LegacyEnchantmentRegistry {

    /** The system property Retromod's runtime fills with the registries of the data pack load in progress. */
    public static final String RELOAD_SLOT = "retromod.shared.reload-registries.v1";

    // This class's name before the embedder moves it under a mod's own package.
    private static final String HOME = "com.retromod.shim.common.embedded.LegacyEnchantmentRegistry";
    // The class LegacyEnchantmentBridge puts in place of the old Enchantment in a jar written against it.
    private static final String STAND_IN = "com.retromod.generated.LegacyEnchantment";
    private static final String HOST_ENCHANTMENT = "net.minecraft.world.item.enchantment.Enchantment";
    private static final String REGISTRY = "net.minecraft.core.Registry";
    private static final String RESOURCE_KEY = "net.minecraft.resources.ResourceKey";
    private static final String MINECRAFT = "net.minecraft.client.Minecraft";
    private static final String PROVIDER = "net.minecraft.core.HolderLookup$Provider";
    private static final String CODEC = "com.mojang.serialization.Codec";
    private static final String[] SERVER_HOOKS = {
            "net.neoforged.neoforge.server.ServerLifecycleHooks", "net.minecraftforge.server.ServerLifecycleHooks"};
    private static final String[] FML_ENVIRONMENTS = {
            "net.neoforged.fml.loading.FMLEnvironment", "net.minecraftforge.fml.loading.FMLEnvironment"};
    // Fabric hosts from 1.21 to 1.21.11 run intermediary names, checked against both versions' mappings.
    private static final Map<String, String> INTERMEDIARY = Map.ofEntries(
            Map.entry(REGISTRY, "net.minecraft.class_2378"),
            Map.entry(RESOURCE_KEY, "net.minecraft.class_5321"),
            Map.entry("net.minecraft.core.registries.Registries", "net.minecraft.class_7924"),
            Map.entry("net.minecraft.core.MappedRegistry", "net.minecraft.class_2370"),
            Map.entry(MINECRAFT, "net.minecraft.class_310"),
            Map.entry(PROVIDER, "net.minecraft.class_7225$class_7874"),
            Map.entry("net.minecraft.resources.ResourceLocation", "net.minecraft.class_2960"),
            Map.entry(HOST_ENCHANTMENT, "net.minecraft.class_1887"),
            Map.entry("Registries.ENCHANTMENT", "field_41265"),
            Map.entry("MinecraftServer.registryAccess", "method_30611"),
            Map.entry("Minecraft.getInstance", "method_1551"),
            Map.entry("Minecraft.getSingleplayerServer", "method_1576"),
            Map.entry("Minecraft.getConnection", "method_1562"),
            Map.entry("Minecraft.isSameThread", "method_18854"),
            Map.entry("ClientPacketListener.registryAccess", "method_29091"),
            Map.entry("RegistryAccess.registry", "method_33310"),
            Map.entry("RegistryAccess.lookup", "method_46759"),
            Map.entry("Registry.getKey", "method_10221"),
            Map.entry("Registry.getResourceKey", "method_29113"),
            Map.entry("Registry.getOptional", "method_31189"),
            Map.entry("Registry.wrapAsHolder", "method_47983"),
            Map.entry("Registry.iterator", "iterator"),
            Map.entry("Registry.freeze", "method_40276"),
            Map.entry("Holder.unwrapKey", "method_40230"),
            Map.entry("ItemStack.enchant", "method_7978"),
            Map.entry("ResourceLocation.tryParse", "method_12829"),
            Map.entry("ResourceKey.create", "method_29179"));
    // Marks a call the view does not answer from a stand-in's id.
    private static final Object UNANSWERED = new Object();

    private static final Map<String, Optional<Method>> STATICS = new ConcurrentHashMap<>();
    private static final Map<String, Optional<Class<?>>> CLASSES = new ConcurrentHashMap<>();
    private static final Map<String, Object> CODECS = new ConcurrentHashMap<>();
    private static volatile Object view;
    private static volatile Object empty;
    private static volatile Optional<Class<?>> standIn;
    private static volatile Boolean client;
    private static volatile Boolean intermediary;
    private static volatile Optional<Class<?>> resourceKeyType;
    // The registries object last searched and the registry found in it, both weak so a closed world is released.
    private static volatile Object[] last;

    private LegacyEnchantmentRegistry() {}

    /** {@code BuiltInRegistries.ENCHANTMENT}: one registry object that reads the loaded world's enchantments on every call. */
    public static Object enchantments() {
        Object current = view;
        if (current != null) return current;
        synchronized (LegacyEnchantmentRegistry.class) {
            if (view == null) {
                Class<?> registry = hostClass(REGISTRY);
                InvocationHandler handler = LegacyEnchantmentRegistry::answer;
                view = Proxy.newProxyInstance(registry.getClassLoader(), new Class<?>[] {registry}, handler);
            }
            return view;
        }
    }

    /** The enchantment registry of the world the calling code sees, or null before any world loads. */
    public static Object loaded() {
        Object registries = currentRegistries();
        return registries == null ? null : registryIn(registries);
    }

    /** The registries of the world the calling code sees, as a {@code HolderLookup.Provider}, or {@code fallback} before any world loads. */
    public static Object registriesOr(Object fallback) {
        Object registries = currentRegistries();
        Class<?> provider = optionalHostClass(hostName(PROVIDER));
        return provider != null && provider.isInstance(registries) ? registries : fallback;
    }

    /** {@code ItemStack.enchant(enchantment, level)}, which now takes the enchantment's holder in the loaded world. */
    public static void enchant(Object stack, Object enchantment, int level) {
        Object loaded = loaded();
        if (loaded == null) {
            LegacyLog.warnOnce("legacy-enchant-no-world", "An old mod put an enchantment on an item before a world"
                    + " loaded its enchantments, so the item was left as it was.");
            return;
        }
        try {
            Object value = toHost(loaded, enchantment);
            Object holder = LegacyPublicMethods.method(loaded.getClass(), member("Registry.wrapAsHolder"), Object.class)
                    .invoke(loaded, value);
            Object key = LegacyPublicMethods.method(holder.getClass(), member("Holder.unwrapKey")).invoke(holder);
            // Only an enchantment the world holds has an id the item can save.
            if (key instanceof Optional<?> optional && optional.isPresent()) {
                for (Method method : LegacyPublicMethods.methods(stack.getClass())) {
                    Class<?>[] parameters = method.getParameterTypes();
                    if (method.getName().equals(member("ItemStack.enchant")) && parameters.length == 2
                            && parameters[0].isInstance(holder) && parameters[1] == int.class) {
                        method.invoke(stack, holder, level);
                        return;
                    }
                }
            }
            LegacyLog.warnOnce("legacy-enchant " + describe(enchantment), "An old mod put " + describe(enchantment)
                    + " on an item, but the loaded world does not have that enchantment, so the item was left as it was.");
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not enchant an item for an old mod", e);
        }
    }

    // ---- the registry view -----------------------------------------------------------------

    private static Object answer(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        int count = method.getParameterCount();
        // The view is one object, so identity decides equality.
        if (count == 0 && name.equals("hashCode")) return System.identityHashCode(proxy);
        if (count == 1 && name.equals("equals")) return proxy == args[0];
        if (count == 0 && name.equals("toString")) return "Retromod's view of the loaded world's enchantment registry";
        // A codec is often built once in a static initializer, so it reads the world loaded when it runs.
        if (count == 0 && method.getReturnType().getName().equals(CODEC)) return codec(method);
        // key(), intermediary method_30517 or method_46765, never changes; DeferredRegister.create reads it before any world.
        if (count == 0 && (name.equals("key") || method.getReturnType() == resourceKeyType())) {
            return registryKey();
        }
        Object registry = current();
        Object byId = answerFromStandIn(method, args);
        if (byId != UNANSWERED) return byId;
        // forEach hands each value to the mod's own consumer, so it gets the jar's stand-ins too.
        if (count == 1 && name.equals("forEach") && standInClass() != null && args[0] instanceof Consumer<?> consumer) {
            forEachValue(registry, consumer);
            return null;
        }
        return toMod(registry, invoke(method, registry, toHostArguments(registry, args)));
    }

    // The loaded world's registry, or before any world, an empty one that answers as the old one did for an unknown id.
    private static Object current() {
        Object registry = loaded();
        return registry != null ? registry : emptyRegistry();
    }

    // The registries of the world the calling code sees: the client's, a server's, or a data pack load in progress.
    private static Object currentRegistries() {
        Object minecraft = onClient() ? callStatic(hostName(MINECRAFT), member("Minecraft.getInstance")) : null;
        Object integrated = minecraft == null ? null : call(minecraft, member("Minecraft.getSingleplayerServer"));
        // The client keeps registry objects of its own even in singleplayer, and its items and screens hold those.
        boolean clientThread = minecraft != null && Boolean.TRUE.equals(call(minecraft, member("Minecraft.isSameThread")));
        if (minecraft != null && (clientThread || integrated == null)) {
            Object connection = call(minecraft, member("Minecraft.getConnection"));
            Object registries = connection == null ? null : call(connection, member("ClientPacketListener.registryAccess"));
            if (registries != null) return registries;
        }
        Object registries = serverRegistries(integrated);
        return registries != null ? registries : reloadRegistries();
    }

    private static Object serverRegistries(Object integrated) {
        String registryAccess = member("MinecraftServer.registryAccess");
        for (String hooks : SERVER_HOOKS) {
            Object server = callStatic(hooks, "getCurrentServer");
            if (server != null) return call(server, registryAccess);
        }
        // Fabric hands out its game, which is a dedicated server or the client; a client plays on its own server.
        Object loader = callStatic("net.fabricmc.loader.api.FabricLoader", "getInstance");
        Object game = loader == null ? null : call(loader, "getGameInstance");
        Class<?> server = optionalHostClass("net.minecraft.server.MinecraftServer");
        if (server != null && server.isInstance(game)) return call(game, registryAccess);
        return integrated == null ? null : call(integrated, registryAccess);
    }

    // A dedicated server refuses to load a client class, and logs the attempt, so the side is asked first.
    private static boolean onClient() {
        Boolean known = client;
        if (known != null) return known;
        Object side = null;
        for (String environment : FML_ENVIRONMENTS) {
            Class<?> type = optionalHostClass(environment);
            if (type == null) continue;
            try {
                side = type.getField("dist").get(null);
            } catch (ReflectiveOperationException | RuntimeException noField) {
                side = callStatic(environment, "getDist");
            }
            if (side != null) break;
        }
        // Fabric names its side too; with no loader to ask, a client is one that ships the client class.
        Object fabric = side != null ? null : callStatic("net.fabricmc.loader.api.FabricLoader", "getInstance");
        if (fabric != null) side = call(fabric, "getEnvironmentType");
        known = side != null ? "CLIENT".equals(String.valueOf(side))
                : LegacyEnchantmentRegistry.class.getClassLoader().getResource(hostName(MINECRAFT).replace('.', '/') + ".class") != null;
        client = known;
        return known;
    }

    private static Object reloadRegistries() {
        Object slot = System.getProperties().get(RELOAD_SLOT);
        Object held = slot instanceof AtomicReference<?> reference ? reference.get() : null;
        return held instanceof WeakReference<?> weak ? weak.get() : null;
    }

    // RegistryAccess.registry(key) before 1.21.2 and lookup(key) after; only a whole registry answers like the old field.
    private static Object registryIn(Object registries) {
        Object[] cached = last;
        if (cached != null && ((WeakReference<?>) cached[0]).get() == registries) {
            Object registry = ((WeakReference<?>) cached[1]).get();
            if (registry != null) return registry;
        }
        Class<?> registryType = hostClass(REGISTRY);
        for (String name : new String[] {member("RegistryAccess.registry"), member("RegistryAccess.lookup")}) {
            Object found;
            try {
                found = LegacyPublicMethods.method(registries.getClass(), name, hostClass(RESOURCE_KEY))
                        .invoke(registries, registryKey());
            } catch (ReflectiveOperationException | RuntimeException | LinkageError absent) {
                continue;
            }
            if (found instanceof Optional<?> optional) found = optional.orElse(null);
            if (registryType.isInstance(found)) {
                last = new Object[] {new WeakReference<>(registries), new WeakReference<>(found)};
                return found;
            }
        }
        return null;
    }

    private static Object emptyRegistry() {
        Object built = empty;
        if (built != null) return built;
        try {
            Class<?> lifecycle = hostClass("com.mojang.serialization.Lifecycle");
            Object registry = hostClass("net.minecraft.core.MappedRegistry")
                    .getConstructor(hostClass(RESOURCE_KEY), lifecycle)
                    .newInstance(registryKey(), lifecycle.getMethod("stable").invoke(null));
            // Frozen, so it never takes an entry and answers lookups the way a loaded registry does.
            built = LegacyPublicMethods.method(registry.getClass(), member("Registry.freeze")).invoke(registry);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not build an empty enchantment registry", e);
        }
        empty = built;
        return built;
    }

    // ---- codecs ------------------------------------------------------------------------------

    // One codec per registry method; its encode and decode run on that method's codec of the registry loaded at the time.
    private static Object codec(Method method) {
        return CODECS.computeIfAbsent(method.getName(), name -> {
            Class<?> codec = method.getReturnType();
            InvocationHandler handler = (proxy, call, args) -> codecAnswer(method, proxy, call, args);
            return Proxy.newProxyInstance(codec.getClassLoader(), new Class<?>[] {codec}, handler);
        });
    }

    private static Object codecAnswer(Method source, Object proxy, Method call, Object[] args) throws Throwable {
        String name = call.getName();
        int count = call.getParameterCount();
        if (count == 0 && name.equals("hashCode")) return System.identityHashCode(proxy);
        if (count == 1 && name.equals("equals")) return proxy == args[0];
        if (count == 0 && name.equals("toString")) return "Retromod codec for the loaded world's enchantments";
        // encode(value, ops, prefix) and decode(ops, input) are the two methods every other one builds on.
        boolean encode = count == 3 && name.equals("encode");
        if (!encode && !(count == 2 && name.equals("decode"))) return InvocationHandler.invokeDefault(proxy, call, args);
        Object registry = current();
        Object codec = invoke(source, registry, null);
        if (encode) {
            Object[] host = args.clone();
            host[0] = toHost(registry, args[0]);
            return invoke(call, codec, host);
        }
        Object result = invoke(call, codec, args);
        return standInClass() == null ? result : mapDecoded(registry, result);
    }

    // DataResult<Pair<value, rest>>: the decoded value becomes the jar's stand-in.
    private static Object mapDecoded(Object registry, Object result) throws ReflectiveOperationException {
        Function<Object, Object> value = decoded -> toMod(registry, decoded);
        Function<Object, Object> pair = decoded -> {
            try {
                return LegacyPublicMethods.method(decoded.getClass(), "mapFirst", Function.class).invoke(decoded, value);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Retromod could not read a decoded enchantment", e);
            }
        };
        return LegacyPublicMethods.method(result.getClass(), "map", Function.class).invoke(result, pair);
    }

    // ---- stand-ins in a jar written against the old Enchantment class ------------------------

    // Each mod's copy sits under that mod's own package, beside the jar's other Retromod classes.
    private static Class<?> standInClass() {
        Optional<Class<?>> known = standIn;
        if (known == null) {
            String self = LegacyEnchantmentRegistry.class.getName();
            Class<?> found = null;
            if (self.endsWith(HOME)) {
                found = optionalHostClass(self.substring(0, self.length() - HOME.length()) + STAND_IN);
            }
            known = Optional.ofNullable(found);
            standIn = known;
        }
        return known.orElse(null);
    }

    // A stand-in names its own id, which holds before any world loads, as Enchantments.MENDING's does.
    private static Object answerFromStandIn(Method method, Object[] args) throws ReflectiveOperationException {
        Class<?> own = standInClass();
        if (own == null || args == null || args.length != 1 || !own.isInstance(args[0])) return UNANSWERED;
        String id = String.valueOf(own.getField("id").get(args[0]));
        if (method.getName().equals(member("Registry.getKey"))) return identifier(id);
        if (method.getName().equals(member("Registry.getResourceKey"))) return Optional.ofNullable(resourceKey(id));
        return UNANSWERED;
    }

    private static Object[] toHostArguments(Object registry, Object[] args) throws ReflectiveOperationException {
        if (args == null || standInClass() == null) return args;
        Object[] host = args.clone();
        for (int i = 0; i < host.length; i++) host[i] = toHost(registry, host[i]);
        return host;
    }

    // A stand-in becomes the world's entry with its id; anything else, or an id the world lacks, passes as it is.
    private static Object toHost(Object registry, Object value) throws ReflectiveOperationException {
        Class<?> own = standInClass();
        if (own == null || !own.isInstance(value)) return value;
        Object key = resourceKey(String.valueOf(own.getField("id").get(value)));
        Object found = key == null ? null : LegacyPublicMethods.method(registry.getClass(), member("Registry.getOptional"),
                hostClass(RESOURCE_KEY)).invoke(registry, key);
        return found instanceof Optional<?> optional && optional.isPresent() ? optional.get() : value;
    }

    private static Object toMod(Object registry, Object result) {
        Class<?> own = standInClass();
        if (own == null || result == null) return result;
        if (result.getClass().getName().equals(hostName(HOST_ENCHANTMENT))) return standInFor(registry, result, own);
        if (result instanceof Optional<?> optional) return optional.map(value -> toMod(registry, value));
        if (result instanceof Stream<?> stream) return stream.map(value -> toMod(registry, value));
        if (result instanceof Iterator<?> iterator) {
            List<Object> values = new ArrayList<>();
            iterator.forEachRemaining(value -> values.add(toMod(registry, value)));
            return values.iterator();
        }
        if (result instanceof Spliterator<?> spliterator) {
            List<Object> values = new ArrayList<>();
            spliterator.forEachRemaining(value -> values.add(toMod(registry, value)));
            return values.spliterator();
        }
        // entrySet() pairs each key with its value.
        if (result instanceof Set<?> set && !set.isEmpty() && set.iterator().next() instanceof Map.Entry<?, ?>) {
            Set<Object> entries = new LinkedHashSet<>();
            for (Object entry : set) {
                Map.Entry<?, ?> pair = (Map.Entry<?, ?>) entry;
                entries.add(Map.entry(pair.getKey(), toMod(registry, pair.getValue())));
            }
            return entries;
        }
        return result;
    }

    private static Object standInFor(Object registry, Object value, Class<?> own) {
        try {
            Object key = LegacyPublicMethods.method(registry.getClass(), member("Registry.getResourceKey"), Object.class)
                    .invoke(registry, value);
            if (!(key instanceof Optional<?> optional) || optional.isEmpty()) return value;
            return own.getMethod("fromKey", hostClass(RESOURCE_KEY)).invoke(null, optional.get());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not give an old mod the enchantment " + value, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static void forEachValue(Object registry, Consumer<?> consumer) throws ReflectiveOperationException {
        Object values = LegacyPublicMethods.method(registry.getClass(), member("Registry.iterator")).invoke(registry);
        ((Iterator<Object>) toMod(registry, values)).forEachRemaining((Consumer<Object>) consumer);
    }

    // ---- host names --------------------------------------------------------------------------

    private static Object registryKey() {
        try {
            return hostClass("net.minecraft.core.registries.Registries").getField(member("Registries.ENCHANTMENT")).get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not name the enchantment registry", e);
        }
    }

    // ResourceKey.create(Registries.ENCHANTMENT, id); its second parameter is the host's id class, renamed in 26.1.
    private static Object resourceKey(String id) throws ReflectiveOperationException {
        Object location = identifier(id);
        if (location == null) return null;
        for (Method method : hostClass(RESOURCE_KEY).getMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (method.getName().equals(member("ResourceKey.create")) && parameters.length == 2
                    && parameters[0] == hostClass(RESOURCE_KEY) && parameters[1].isInstance(location)) {
                return method.invoke(null, registryKey(), location);
            }
        }
        return null;
    }

    private static Object identifier(String id) throws ReflectiveOperationException {
        Class<?> type = optionalHostClass("net.minecraft.resources.Identifier");
        if (type == null) type = hostClass("net.minecraft.resources.ResourceLocation");
        return type.getMethod(member("ResourceLocation.tryParse"), String.class).invoke(null, id);
    }

    // A stand-in's id; a host enchantment the world lacks has no id, and its record text runs to pages.
    private static String describe(Object enchantment) {
        Class<?> own = standInClass();
        try {
            if (own != null && own.isInstance(enchantment)) return "the enchantment " + own.getField("id").get(enchantment);
        } catch (ReflectiveOperationException e) {
            return "an enchantment";
        }
        return "an enchantment";
    }

    // Intermediary on Fabric before 26.1, where Registry has its intermediary name; Mojang's everywhere else.
    private static boolean intermediary() {
        Boolean known = intermediary;
        if (known == null) {
            known = optionalHostClass(INTERMEDIARY.get(REGISTRY)) != null;
            intermediary = known;
        }
        return known;
    }

    // Looked up once, since the view asks on every call that takes no argument.
    private static Class<?> resourceKeyType() {
        Optional<Class<?>> known = resourceKeyType;
        if (known == null) {
            known = Optional.ofNullable(optionalHostClass(hostName(RESOURCE_KEY)));
            resourceKeyType = known;
        }
        return known.orElse(null);
    }

    private static String hostName(String className) {
        return intermediary() ? INTERMEDIARY.getOrDefault(className, className) : className;
    }

    // "Owner.member" names one member; the Mojang name is the part after the dot.
    private static String member(String ownerAndName) {
        if (intermediary()) return INTERMEDIARY.get(ownerAndName);
        return ownerAndName.substring(ownerAndName.indexOf('.') + 1);
    }

    private static Class<?> hostClass(String name) {
        Class<?> type = optionalHostClass(hostName(name));
        if (type == null) throw new IllegalStateException("Retromod could not find " + name + " for an old mod's enchantments");
        return type;
    }

    // Looked up once per name, since text and registry reads ask on every call.
    private static Class<?> optionalHostClass(String name) {
        return CLASSES.computeIfAbsent(name, LegacyEnchantmentRegistry::findHostClass).orElse(null);
    }

    private static Optional<Class<?>> findHostClass(String name) {
        try {
            return Optional.of(Class.forName(name, false, LegacyEnchantmentRegistry.class.getClassLoader()));
        } catch (ClassNotFoundException | LinkageError | RuntimeException absent) {
            // A loader may refuse a class it ships for the other side with a runtime exception.
            return Optional.empty();
        }
    }

    // A public static no-argument method, or nothing on a loader or side without its class.
    private static Object callStatic(String owner, String name) {
        Optional<Method> method = STATICS.computeIfAbsent(owner + '#' + name, key -> {
            Class<?> type = optionalHostClass(owner);
            try {
                return type == null ? Optional.empty() : Optional.of(type.getMethod(name));
            } catch (ReflectiveOperationException | LinkageError | RuntimeException absent) {
                // Listing a class's methods loads the types they name, which a partial classpath may lack.
                return Optional.empty();
            }
        });
        try {
            return method.isPresent() ? method.get().invoke(null) : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    private static Object call(Object target, String name) {
        try {
            return LegacyPublicMethods.method(target.getClass(), name).invoke(target);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError absent) {
            return null;
        }
    }

    private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
