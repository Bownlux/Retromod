/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.polyfill.minecraft;

/**
 * 1.3.0 (#162): runtime half of the pre-26.1 {@code EntityType$Builder.build(String)} bridge.
 * MC 1.21.2 changed {@code build(String)} to {@code build(ResourceKey<EntityType<?>>)} (the
 * intermediary name {@code method_5905} survived, only the descriptor flipped), so a 1.20.1-1.21.1
 * Fabric mod registering an entity dies {@code NoSuchMethodError} on a 1.21.2+ intermediary host
 * (ENGRAM's {@code HorrorMod129.<clinit>}). The bridge rewrites the old call to
 * {@link #build(Object, String)}, which reconstructs the key the way vanilla's own 1.21.2
 * migration did: {@code ResourceKey.create(Registries.ENTITY_TYPE, Identifier.parse(id))} (a bare
 * id like {@code "blueice129"} becomes {@code minecraft:blueice129}, matching what the old
 * String-form produced for datafixer lookup).
 *
 * <p>Intermediary-host reflective ({@code class_5321} = ResourceKey, {@code class_7924} =
 * Registries, {@code class_2960} = Identifier); member discovery is shape-based (the sole static
 * {@code (String)->Identifier} factory; the sole static {@code (ResourceKey,Identifier)->
 * ResourceKey} factory; the {@code ENTITY_TYPE} root key found by scanning Registries' static
 * ResourceKey fields for the one whose string mentions {@code entity_type}), so intermediary
 * member-id drift across host versions never breaks it. Fail-safe: any resolution failure logs
 * nothing and returns null (the registration line then NPEs visibly at the mod's own call site,
 * which is still strictly better than taking down the class load).
 */
public final class RetroEntityTypeBuild {

    private RetroEntityTypeBuild() {}

    private static volatile java.lang.reflect.Method buildMethod;   // builder.method_5905(ResourceKey)
    private static volatile java.lang.reflect.Method keyFactory;    // ResourceKey.create(ResourceKey, Identifier)
    private static volatile java.lang.reflect.Method idFactory;     // Identifier.parse(String)
    private static volatile Object entityTypeRootKey;               // Registries.ENTITY_TYPE
    private static volatile boolean unresolvable;

    /** Replacement for {@code builder.method_5905(String)}: receiver as arg 0. */
    public static Object build(Object builder, String id) {
        try {
            if (builder == null || unresolvable) return null;
            if (isOfficialBuilder(builder)) {
                return buildOfficial(builder, id);
            }
            if (buildMethod == null && !resolve(builder)) return null;
            Object identifier = idFactory.invoke(null, id);
            Object key = keyFactory.invoke(null, entityTypeRootKey, identifier);
            return buildMethod.invoke(builder, key);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 26.x official-name variant of the same String to ResourceKey adaptation. */
    public static Object buildOfficial(Object builder, String id) {
        try {
            if (builder == null) return null;
            ClassLoader cl = builder.getClass().getClassLoader();
            Class<?> keyClass = Class.forName("net.minecraft.resources.ResourceKey", false, cl);
            Class<?> idClass = Class.forName("net.minecraft.resources.Identifier", false, cl);
            Class<?> registriesClass = Class.forName(
                    "net.minecraft.core.registries.Registries", false, cl);
            Object root = registriesClass.getField("ENTITY_TYPE").get(null);
            Object identifier = idClass.getMethod("parse", String.class).invoke(null, id);
            Object key = keyClass.getMethod("create", keyClass, idClass)
                    .invoke(null, root, identifier);
            return builder.getClass().getMethod("build", keyClass).invoke(builder, key);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isOfficialBuilder(Object builder) {
        return builder.getClass().getName().equals(
                "net.minecraft.world.entity.EntityType$Builder");
    }

    private static synchronized boolean resolve(Object builder) {
        if (buildMethod != null) return true;
        if (unresolvable) return false;
        try {
            ClassLoader cl = builder.getClass().getClassLoader();
            Class<?> rkCls = Class.forName("net.minecraft.class_5321", false, cl);
            Class<?> idCls = Class.forName("net.minecraft.class_2960", false, cl);
            Class<?> regsCls = Class.forName("net.minecraft.class_7924", false, cl);

            // builder.method_5905(ResourceKey): the renamed-descriptor form this bridge exists for.
            java.lang.reflect.Method bm = null;
            for (java.lang.reflect.Method m : builder.getClass().getMethods()) {
                if (m.getName().equals("method_5905") && m.getParameterCount() == 1
                        && m.getParameterTypes()[0] == rkCls) {
                    bm = m; break;
                }
            }

            // Identifier.parse has a stable intermediary ID on 1.21.2-1.21.11. Prefer it
            // because Identifier also has withDefaultNamespace and tryParse with the same
            // erased shape.
            java.lang.reflect.Method idf = null;
            try {
                idf = idCls.getMethod("method_60654", String.class);
            } catch (NoSuchMethodException ignored) {
                // Unexpected intermediary drift: fail safely below.
            }

            // ResourceKey.create: the sole public static (ResourceKey, Identifier) -> ResourceKey.
            java.lang.reflect.Method kf = null;
            for (java.lang.reflect.Method m : rkCls.getMethods()) {
                if (java.lang.reflect.Modifier.isStatic(m.getModifiers())
                        && m.getParameterCount() == 2 && m.getParameterTypes()[0] == rkCls
                        && m.getParameterTypes()[1] == idCls && m.getReturnType() == rkCls) {
                    kf = m; break;
                }
            }

            // Registries.ENTITY_TYPE: the static ResourceKey whose identity mentions entity_type.
            Object root = null;
            for (java.lang.reflect.Field f : regsCls.getFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) && f.getType() == rkCls) {
                    Object v = f.get(null);
                    if (v != null && isEntityTypeRoot(String.valueOf(v))) {
                        root = v;
                        break;
                    }
                }
            }

            if (bm == null || idf == null || kf == null || root == null) {
                unresolvable = true;
                return false;
            }
            idFactory = idf;
            keyFactory = kf;
            entityTypeRootKey = root;
            buildMethod = bm; // publish last
            return true;
        } catch (Throwable t) {
            unresolvable = true;
            return false;
        }
    }

    static boolean isEntityTypeRoot(String value) {
        return value.endsWith("minecraft:entity_type")
                || value.endsWith("minecraft:entity_type]");
    }
}
