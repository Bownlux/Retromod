/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.polyfill.registry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Polyfill for the MC 1.21+ registry-key migration of vanilla content holders.
 *
 * <p>In MC 1.21+, fields like {@code Enchantments.SHARPNESS} changed type from
 * {@code Enchantment} to {@code ResourceKey<Enchantment>}, and {@code MobEffects.*}
 * (yarn {@code StatusEffects.*}) migrated the same way. Old mods do
 * {@code GETSTATIC Enchantments.SHARPNESS:Enchantment} and crash with
 * {@code NoSuchFieldError}.
 *
 * <p>Field-to-method redirects rewrite that access to
 * {@code INVOKESTATIC RegistryRefLookup.SHARPNESS():Enchantment}, which resolves
 * the value at runtime via {@code BuiltInRegistries} and caches it. The transformer
 * emits a CHECKCAST after the call to restore the original field type.
 *
 * <p>Reflection is used because Retromod doesn't compile against Minecraft. Adding
 * an entry is one method here plus a {@code registerFieldRedirect} call in the shim
 * (see {@code Fabric_1_21_11_to_26_1.java}).
 */
public final class RegistryRefLookup {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-RegistryLookup");

    private static final String ENCHANTMENT_REGISTRY = "ENCHANTMENT";
    private static final String MOB_EFFECT_REGISTRY  = "MOB_EFFECT";

    /** Cache: "<registryName>:<entryName>" -> resolved value (or NULL_SENTINEL on miss). */
    private static final Map<String, Object> CACHE = new ConcurrentHashMap<>();
    private static final Object NULL_SENTINEL = new Object();

    /** Registries that have logged their one-time "first resolve" INFO line. */
    private static final java.util.Set<String> RESOLVED_REGISTRIES =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Resolved BuiltInRegistries class, cached after first successful lookup. */
    private static volatile Class<?> builtInRegistriesClass;
    /** Resolved ResourceLocation class, cached after first successful lookup. */
    private static volatile Class<?> resourceLocationClass;
    /** Cached registry instances keyed by registry-field-name. */
    private static final Map<String, Object> REGISTRIES = new ConcurrentHashMap<>();

    private RegistryRefLookup() {}

    /** Cached anchor classloader, resolved once on the first successful call. */
    private static volatile ClassLoader anchorClassLoader;

    /**
     * Resolve an MC class by name. On Fabric, this helper's loader, the thread
     * context loader, and the system loader can't see MC; the reliable anchor is
     * the caller's classloader (transformed mod bytecode is loaded by Knot, which
     * sees MC). Tries the cached anchor, then walks the stack for a caller loader,
     * then the context/own/system loaders.
     */
    private static Class<?> loadMcClass(String... names) {
        ClassLoader anchor = anchorClassLoader;
        if (anchor != null) {
            Class<?> cls = tryLoadAny(anchor, names);
            if (cls != null) return cls;
        }

        Class<?> stackHit = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
                .walk(frames -> frames
                        .map(StackWalker.StackFrame::getDeclaringClass)
                        .filter(cls -> {
                            String n = cls.getName();
                            return !n.startsWith("com.retromod.")
                                    && !n.startsWith("java.")
                                    && !n.startsWith("jdk.")
                                    && !n.startsWith("sun.")
                                    && cls.getClassLoader() != null;
                        })
                        .map(cls -> tryLoadAny(cls.getClassLoader(), names))
                        .filter(c -> c != null)
                        .findFirst()
                        .orElse(null));
        if (stackHit != null) {
            anchorClassLoader = stackHit.getClassLoader();
            return stackHit;
        }

        ClassLoader[] candidates = {
            Thread.currentThread().getContextClassLoader(),
            RegistryRefLookup.class.getClassLoader(),
            ClassLoader.getSystemClassLoader()
        };
        for (ClassLoader cl : candidates) {
            Class<?> cls = tryLoadAny(cl, names);
            if (cls != null) {
                anchorClassLoader = cl;
                return cls;
            }
        }
        return null;
    }

    /** Try each name on the given loader, return the first that resolves. */
    private static Class<?> tryLoadAny(ClassLoader cl, String... names) {
        if (cl == null) return null;
        for (String name : names) {
            Class<?> cls = tryLoad(name, cl);
            if (cls != null) return cls;
        }
        return null;
    }

    private static Class<?> tryLoad(String name, ClassLoader cl) {
        if (cl == null) return null;
        try {
            return Class.forName(name, true, cl);
        } catch (ClassNotFoundException ignored) {
            return null;
        } catch (Throwable t) {
            LOGGER.debug("tryLoad({}) via {} threw {}: {}",
                    name, cl.getClass().getSimpleName(),
                    t.getClass().getSimpleName(), t.getMessage());
            return null;
        }
    }

    // enchantments: return Object, CHECKCAST'd to Enchantment by the redirect.

    public static Object SHARPNESS()           { return enchant("sharpness"); }
    public static Object SMITE()               { return enchant("smite"); }
    public static Object BANE_OF_ARTHROPODS()  { return enchant("bane_of_arthropods"); }
    public static Object KNOCKBACK()           { return enchant("knockback"); }
    public static Object FIRE_ASPECT()         { return enchant("fire_aspect"); }
    public static Object LOOTING()             { return enchant("looting"); }
    public static Object SWEEPING_EDGE()       { return enchant("sweeping_edge"); }
    public static Object PROTECTION()          { return enchant("protection"); }
    public static Object FIRE_PROTECTION()     { return enchant("fire_protection"); }
    public static Object FEATHER_FALLING()     { return enchant("feather_falling"); }
    public static Object BLAST_PROTECTION()    { return enchant("blast_protection"); }
    public static Object PROJECTILE_PROTECTION() { return enchant("projectile_protection"); }
    public static Object RESPIRATION()         { return enchant("respiration"); }
    public static Object AQUA_AFFINITY()       { return enchant("aqua_affinity"); }
    public static Object THORNS()              { return enchant("thorns"); }
    public static Object DEPTH_STRIDER()       { return enchant("depth_strider"); }
    public static Object FROST_WALKER()        { return enchant("frost_walker"); }
    public static Object BINDING_CURSE()       { return enchant("binding_curse"); }
    public static Object SOUL_SPEED()          { return enchant("soul_speed"); }
    public static Object SWIFT_SNEAK()         { return enchant("swift_sneak"); }
    public static Object EFFICIENCY()          { return enchant("efficiency"); }
    public static Object SILK_TOUCH()          { return enchant("silk_touch"); }
    public static Object UNBREAKING()          { return enchant("unbreaking"); }
    public static Object FORTUNE()             { return enchant("fortune"); }
    public static Object POWER()               { return enchant("power"); }
    public static Object PUNCH()               { return enchant("punch"); }
    public static Object FLAME()               { return enchant("flame"); }
    public static Object INFINITY()            { return enchant("infinity"); }
    public static Object LUCK_OF_THE_SEA()     { return enchant("luck_of_the_sea"); }
    public static Object LURE()                { return enchant("lure"); }
    public static Object LOYALTY()             { return enchant("loyalty"); }
    public static Object IMPALING()            { return enchant("impaling"); }
    public static Object RIPTIDE()             { return enchant("riptide"); }
    public static Object CHANNELING()          { return enchant("channeling"); }
    public static Object MULTISHOT()           { return enchant("multishot"); }
    public static Object QUICK_CHARGE()        { return enchant("quick_charge"); }
    public static Object PIERCING()            { return enchant("piercing"); }
    public static Object MENDING()             { return enchant("mending"); }
    public static Object VANISHING_CURSE()     { return enchant("vanishing_curse"); }

    // status effects / mob effects

    public static Object SPEED()               { return mobEffect("speed"); }              // yarn SPEED
    public static Object SLOWNESS()            { return mobEffect("slowness"); }
    public static Object HASTE()               { return mobEffect("haste"); }              // yarn HASTE
    public static Object MINING_FATIGUE()      { return mobEffect("mining_fatigue"); }
    public static Object STRENGTH()            { return mobEffect("strength"); }           // yarn STRENGTH
    public static Object INSTANT_HEALTH()      { return mobEffect("instant_health"); }
    public static Object INSTANT_DAMAGE()      { return mobEffect("instant_damage"); }
    public static Object JUMP_BOOST()          { return mobEffect("jump_boost"); }
    public static Object NAUSEA()              { return mobEffect("nausea"); }
    public static Object REGENERATION()        { return mobEffect("regeneration"); }
    public static Object RESISTANCE()          { return mobEffect("resistance"); }
    public static Object FIRE_RESISTANCE()     { return mobEffect("fire_resistance"); }
    public static Object WATER_BREATHING()     { return mobEffect("water_breathing"); }
    public static Object INVISIBILITY()        { return mobEffect("invisibility"); }
    public static Object BLINDNESS()           { return mobEffect("blindness"); }
    public static Object NIGHT_VISION()        { return mobEffect("night_vision"); }
    public static Object HUNGER()              { return mobEffect("hunger"); }
    public static Object WEAKNESS()            { return mobEffect("weakness"); }
    public static Object POISON()              { return mobEffect("poison"); }
    public static Object WITHER()              { return mobEffect("wither"); }
    public static Object HEALTH_BOOST()        { return mobEffect("health_boost"); }
    public static Object ABSORPTION()          { return mobEffect("absorption"); }
    public static Object SATURATION()          { return mobEffect("saturation"); }
    public static Object GLOWING()             { return mobEffect("glowing"); }
    public static Object LEVITATION()          { return mobEffect("levitation"); }
    public static Object LUCK()                { return mobEffect("luck"); }
    public static Object UNLUCK()              { return mobEffect("unluck"); }
    public static Object SLOW_FALLING()        { return mobEffect("slow_falling"); }
    public static Object CONDUIT_POWER()       { return mobEffect("conduit_power"); }
    public static Object DOLPHINS_GRACE()      { return mobEffect("dolphins_grace"); }
    public static Object BAD_OMEN()            { return mobEffect("bad_omen"); }
    public static Object HERO_OF_THE_VILLAGE() { return mobEffect("hero_of_the_village"); }
    public static Object DARKNESS()            { return mobEffect("darkness"); }

    private static Object enchant(String entryName) {
        return lookup(ENCHANTMENT_REGISTRY, entryName);
    }

    private static Object mobEffect(String entryName) {
        return lookup(MOB_EFFECT_REGISTRY, entryName);
    }

    /** Cached registry lookup; returns null on miss (mirrors {@code Registry.get}). */
    private static Object lookup(String registryName, String entryName) {
        String cacheKey = registryName + ":" + entryName;
        Object cached = CACHE.get(cacheKey);
        if (cached == NULL_SENTINEL) {
            LOGGER.debug("[diag {}.{}] Cached result is empty", registryName, entryName);
            return null;
        }
        if (cached != null) {
            return cached;
        }
        LOGGER.debug("[diag {}.{}] No cached result, running lookup", registryName, entryName);

        Object value = doLookup(registryName, entryName);
        CACHE.put(cacheKey, value != null ? value : NULL_SENTINEL);
        if (value != null && RESOLVED_REGISTRIES.add(registryName)) {
            LOGGER.info("Polyfill resolved {} registry - first hit on {} returned {}",
                    registryName, entryName, value.getClass().getName());
        }
        return value;
    }

    private static Object doLookup(String registryName, String entryName) {
        Class<?> rl;
        try {
            rl = resourceLocationClass;
            if (rl == null) {
                rl = loadMcClass(
                        "net.minecraft.resources.ResourceLocation",   // mojang
                        "net.minecraft.resources.Identifier",          // fabric-yarn-on-mojang-package
                        "net.minecraft.util.Identifier",                // pure yarn
                        "net.minecraft.class_2960"                      // intermediary
                );
                resourceLocationClass = rl;
            }
        } catch (Throwable t) {
            LOGGER.debug("[diag {}.{}] ResourceLocation lookup failed with {}: {}",
                    registryName, entryName, t.getClass().getSimpleName(), t.getMessage());
            return null;
        }
        if (rl == null) {
            LOGGER.debug("[diag {}.{}] loadMcClass(ResourceLocation) returned null",
                    registryName, entryName);
            return null;
        }

        Object id = buildResourceLocation(rl, entryName);
        if (id == null) {
            LOGGER.debug("[diag {}.{}] buildResourceLocation returned null",
                    registryName, entryName);
            return null;
        }
        LOGGER.debug("[diag {}.{}] built id: {}", registryName, entryName, id);

        // path 1: static registry on BuiltInRegistries
        Object value = lookupViaBuiltInRegistries(registryName, rl, id);
        if (value != null) {
            LOGGER.debug("[diag {}.{}] BuiltInRegistries returned: {}",
                    registryName, entryName, value.getClass().getName());
            return value;
        }
        LOGGER.debug("[diag {}.{}] BuiltInRegistries miss, trying dynamic registry",
                registryName, entryName);

        // path 2: dynamic registry via the client's RegistryAccess
        return lookupViaDynamicRegistry(registryName, rl, id);
    }

    private static Object lookupViaBuiltInRegistries(String registryName, Class<?> rl, Object id) {
        try {
            Class<?> bir = builtInRegistriesClass;
            if (bir == null) {
                bir = loadMcClass(
                        "net.minecraft.core.registries.BuiltInRegistries",  // mojang
                        "net.minecraft.registry.Registries"                  // yarn
                );
                if (bir == null) return null;
                builtInRegistriesClass = bir;
            }
            Object registry = REGISTRIES.computeIfAbsent("BIR:" + registryName, k -> {
                try {
                    return builtInRegistriesClass.getField(registryName).get(null);
                } catch (Throwable t) {
                    return NULL_SENTINEL;
                }
            });
            if (registry == NULL_SENTINEL || registry == null) return null;

            Method getter = findRegistryGetter(registry.getClass(), rl);
            if (getter == null) return null;
            return getter.invoke(registry, id);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object lookupViaDynamicRegistry(String registryName, Class<?> rl, Object id) {
        try {
            Object registryAccess = resolveRegistryAccess();
            if (registryAccess == null) {
                LOGGER.debug("[diag {}] resolveRegistryAccess returned null", registryName);
                return null;
            }
            LOGGER.debug("[diag {}] registryAccess={}", registryName, registryAccess.getClass().getName());

            // resolve the parent registry key (Registries.ENCHANTMENT etc.)
            Class<?> registriesCls = loadMcClass(
                    "net.minecraft.core.registries.Registries",        // mojang (the keys holder)
                    "net.minecraft.registry.RegistryKeys"               // yarn equivalent
            );
            if (registriesCls == null) {
                LOGGER.debug("[diag {}] could not load Registries class", registryName);
                return null;
            }
            Object registryKey;
            try {
                registryKey = registriesCls.getField(registryName).get(null);
            } catch (Throwable t) {
                LOGGER.debug("[diag {}] Registries.{} field lookup failed: {}",
                        registryName, registryName, t.getMessage());
                return null;
            }
            if (registryKey == null) {
                LOGGER.debug("[diag {}] Registries.{} returned null value", registryName, registryName);
                return null;
            }
            LOGGER.debug("[diag {}] registryKey={} ({})",
                    registryName, registryKey, registryKey.getClass().getName());

            // path A (MC 1.21+): registryAccess.lookup(ResourceKey) returns
            // Optional<HolderLookup.RegistryLookup<T>>, keyed by a child
            // ResourceKey (not a ResourceLocation), so build one below.
            Object holderLookup = invokeAndUnwrapOptional(registryAccess, "lookup", registryKey);
            LOGGER.debug("[diag {}] holderLookup={}", registryName,
                    holderLookup == null ? "null" : holderLookup.getClass().getName());
            if (holderLookup != null) {
                // child ResourceKey<T> via ResourceKey.create(parent, id)
                Class<?> resourceKeyCls = loadMcClass(
                        "net.minecraft.resources.ResourceKey",   // mojang
                        "net.minecraft.registry.RegistryKey"      // yarn
                );
                if (resourceKeyCls == null) {
                    LOGGER.debug("[diag {}] could not load ResourceKey class", registryName);
                    return null;
                }
                Object entryKey;
                try {
                    Method createMethod = resourceKeyCls.getMethod(
                            "create", resourceKeyCls, rl);
                    entryKey = createMethod.invoke(null, registryKey, id);
                } catch (Throwable t) {
                    LOGGER.debug("[diag {}] ResourceKey.create failed: {}", registryName, t.getMessage());
                    return null;
                }
                if (entryKey == null) {
                    LOGGER.debug("[diag {}] entryKey is null", registryName);
                    return null;
                }
                LOGGER.debug("[diag {}] entryKey={}", registryName, entryKey);

                // holderLookup.get(ResourceKey<T>) -> Optional<Holder.Reference<T>>
                Object holderOpt = null;
                Method matchedGetMethod = null;
                for (Method m : holderLookup.getClass().getMethods()) {
                    if (!"get".equals(m.getName())) continue;
                    if (m.getParameterCount() != 1) continue;
                    if (m.getParameterTypes()[0] != resourceKeyCls
                            && !m.getParameterTypes()[0].isAssignableFrom(resourceKeyCls)) continue;
                    try {
                        holderOpt = m.invoke(holderLookup, entryKey);
                        matchedGetMethod = m;
                        break;
                    } catch (Throwable t) {
                        LOGGER.debug("[diag {}] holderLookup.get invoke failed: {}",
                                registryName, t.getMessage());
                    }
                }
                LOGGER.debug("[diag {}] matchedGetMethod={} holderOpt={}",
                        registryName,
                        matchedGetMethod == null ? "none" : matchedGetMethod.toString(),
                        holderOpt == null ? "null" : holderOpt.getClass().getName());
                Object holder = (holderOpt instanceof java.util.Optional<?> opt)
                        ? (opt.isPresent() ? opt.get() : null)
                        : holderOpt;
                LOGGER.debug("[diag {}] unwrapped holder={}", registryName,
                        holder == null ? "null" : holder.getClass().getName());
                if (holder != null) {
                    Object value = unwrapHolder(holder);
                    LOGGER.debug("[diag {}] final value={}", registryName,
                            value == null ? "null" : value.getClass().getName());
                    return value;
                }
            }

            // path B (older RegistryAccess): registryOrThrow returns Registry<T>
            // directly, which has get(ResourceLocation)
            Object registry = invokeUnwrap(registryAccess, "registryOrThrow", registryKey);
            LOGGER.debug("[diag {}] registryOrThrow result={}", registryName,
                    registry == null ? "null" : registry.getClass().getName());
            if (registry != null) {
                Method getter = findRegistryGetter(registry.getClass(), rl);
                if (getter != null) {
                    return unwrapHolder(getter.invoke(registry, id));
                }
            }

            return null;
        } catch (Throwable t) {
            LOGGER.debug("[diag {}] Dynamic-registry lookup threw {}: {}",
                    registryName, t.getClass().getSimpleName(), t.getMessage());
            return null;
        }
    }

    /**
     * Find a RegistryAccess on the client, trying the world then the connection.
     * Returns null before the client connects (init/title-screen time).
     */
    private static Object resolveRegistryAccess() {
        try {
            Class<?> mcCls = loadMcClass(
                    "net.minecraft.client.Minecraft",           // mojang
                    "net.minecraft.client.MinecraftClient"      // yarn
            );
            if (mcCls == null) return null;
            Object mc = mcCls.getMethod("getInstance").invoke(null);
            if (mc == null) return null;

            try {
                Object level = mcCls.getMethod("level").invoke(mc);
                if (level != null) {
                    return level.getClass().getMethod("registryAccess").invoke(level);
                }
            } catch (Throwable ignored) {}

            try {
                Object conn = mcCls.getMethod("getConnection").invoke(mc);
                if (conn != null) {
                    return conn.getClass().getMethod("registryAccess").invoke(conn);
                }
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * Invoke the first matching {@code name(arg)} method and unwrap its
     * {@code Optional<T>} result to {@code T} (null on empty).
     */
    private static Object invokeAndUnwrapOptional(Object receiver, String name, Object arg) {
        try {
            for (Method m : receiver.getClass().getMethods()) {
                if (!name.equals(m.getName())) continue;
                if (m.getParameterCount() != 1) continue;
                if (!m.getParameterTypes()[0].isInstance(arg)) continue;
                Object r = m.invoke(receiver, arg);
                if (r instanceof java.util.Optional<?> opt) {
                    return opt.isPresent() ? opt.get() : null;
                }
                return r;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** Try a method that returns T directly (or throws if absent). */
    private static Object invokeUnwrap(Object receiver, String name, Object arg) {
        try {
            for (Method m : receiver.getClass().getMethods()) {
                if (!name.equals(m.getName())) continue;
                if (m.getParameterCount() != 1) continue;
                if (!m.getParameterTypes()[0].isInstance(arg)) continue;
                return m.invoke(receiver, arg);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** If {@code value} is a {@code Holder} or {@code Holder.Reference}, return its value. */
    private static Object unwrapHolder(Object value) {
        if (value == null) return null;
        try {
            Method valueMethod = value.getClass().getMethod("value");
            return valueMethod.invoke(value);
        } catch (Throwable ignored) {
            return value;
        }
    }

    private static Object buildResourceLocation(Class<?> rl, String path) {
        // newer MC: ResourceLocation.parse("minecraft:<path>")
        try {
            Method parse = rl.getMethod("parse", String.class);
            return parse.invoke(null, "minecraft:" + path);
        } catch (Throwable ignored) {}
        // older MC: ResourceLocation.fromNamespaceAndPath("minecraft", path)
        try {
            Method fromNs = rl.getMethod("fromNamespaceAndPath", String.class, String.class);
            return fromNs.invoke(null, "minecraft", path);
        } catch (Throwable ignored) {}
        // oldest: the public constructor (deprecated in newer MC)
        try {
            return rl.getConstructor(String.class, String.class)
                     .newInstance("minecraft", path);
        } catch (Throwable ignored) {}
        return null;
    }

    private static Method findRegistryGetter(Class<?> registryClass, Class<?> rl) {
        // newer MC: getValue(ResourceLocation) -> T
        for (Method m : registryClass.getMethods()) {
            if ("getValue".equals(m.getName())
                    && m.getParameterCount() == 1
                    && m.getParameterTypes()[0] == rl) {
                return m;
            }
        }
        // older MC: get(ResourceLocation) -> T (or Optional<T> in some versions)
        for (Method m : registryClass.getMethods()) {
            if ("get".equals(m.getName())
                    && m.getParameterCount() == 1
                    && m.getParameterTypes()[0] == rl) {
                return m;
            }
        }
        return null;
    }
}
