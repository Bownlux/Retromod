/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.polyfill.minecraft;

/** Runs an old {@code EntityType$Builder.build(String)} call on a host that builds from a key. */
public final class RetroEntityTypeBuild {

    private RetroEntityTypeBuild() {}

    // On an intermediary host the members are reached by reflection (class_5321 = ResourceKey,
    // class_7924 = Registries, class_2960 = Identifier) and found by shape in resolve(), so
    // intermediary id drift between host versions does not break them.
    private static volatile java.lang.reflect.Method buildMethod;   // builder.method_5905(ResourceKey)
    private static volatile java.lang.reflect.Method keyFactory;    // ResourceKey.create(ResourceKey, Identifier)
    private static volatile java.lang.reflect.Method idFactory;     // Identifier.parse(String)
    private static volatile Object entityTypeRootKey;               // Registries.ENTITY_TYPE
    private static volatile boolean unresolvable;

    // Replaces builder.method_5905(String), with the builder as argument 0. 1.21.2 changed
    // build(String) to build(ResourceKey<EntityType<?>>) and kept the intermediary name, so a
    // 1.20.1-1.21.1 mod registering an entity died with NoSuchMethodError (#162).
    public static Object build(Object builder, String id) {
        return build(builder, id, null, null);
    }

    /** {@link #build(Object, String)} plus the call site's registered id and mod namespace, each nullable. */
    public static Object build(Object builder, String id, Object registeredId, String namespace) {
        try {
            if (builder == null || unresolvable) return null;
            if (isOfficialBuilder(builder)) {
                return buildOfficial(builder, id, registeredId, namespace);
            }
            if (buildMethod == null && !resolve(builder)) return null;
            Object identifier = idFactory.invoke(null, entityId(id, registeredId, namespace));
            Object key = keyFactory.invoke(null, entityTypeRootKey, identifier);
            return buildMethod.invoke(builder, key);
        } catch (Throwable t) {
            // Fail quietly with null. The mod's own registration line then fails visibly, which
            // is better than taking down the whole class load.
            return null;
        }
    }

    /** Official-name variant of the same String to ResourceKey adaptation, for 1.21.2 and newer. */
    public static Object buildOfficial(Object builder, String id) {
        return buildOfficial(builder, id, null, null);
    }

    /** {@link #buildOfficial(Object, String)} with what the transformer found at the call site. */
    public static Object buildOfficial(Object builder, String id, Object registeredId, String namespace) {
        try {
            if (builder == null) return null;
            ClassLoader cl = builder.getClass().getClassLoader();
            Class<?> keyClass = Class.forName("net.minecraft.resources.ResourceKey", false, cl);
            Class<?> idClass = officialIdClass(cl);
            Class<?> registriesClass = Class.forName(
                    "net.minecraft.core.registries.Registries", false, cl);
            Object root = registriesClass.getField("ENTITY_TYPE").get(null);
            // Before 1.21.2 the string was only a datafixer name. From 1.21.2 the builder takes the
            // translation key and default loot table from this key, so use the id being registered:
            // the deferred register's key while its supplier runs, else the id from the call site.
            Object key = registrationKey(keyClass, root);
            if (key == null) {
                Object identifier = idClass.getMethod("parse", String.class)
                        .invoke(null, entityId(id, registeredId, namespace));
                key = keyClass.getMethod("create", keyClass, idClass).invoke(null, root, identifier);
            }
            return builder.getClass().getMethod("build", keyClass).invoke(builder, key);
        } catch (Throwable t) {
            return null;
        }
    }

    /** The id to build under when no registration key is running. */
    static String entityId(String id, Object registeredId, String namespace) {
        // The registered id wins. A namespaced string stays as written. A bare string takes the
        // mod's namespace, because vanilla's parser would put it in Minecraft's namespace, where
        // the mob has no translation and no loot table. With no namespace known it goes as is.
        if (registeredId != null) return registeredId.toString();
        if (id == null || id.indexOf(':') >= 0 || namespace == null) return id;
        return namespace + ":" + id;
    }

    /** {@code Identifier}, or {@code ResourceLocation} on a host before 1.21.11 renamed it. */
    private static Class<?> officialIdClass(ClassLoader cl) throws ClassNotFoundException {
        try {
            return Class.forName("net.minecraft.resources.Identifier", false, cl);
        } catch (ClassNotFoundException olderHost) {
            return Class.forName("net.minecraft.resources.ResourceLocation", false, cl);
        }
    }

    // While its supplier runs, the NeoForge or Forge deferred register bridge puts the key of the
    // entry being built on this thread. Those bridges are generated per mod and embedded beside
    // this class under the same relocation prefix, so they are looked up by name, not linked.
    // The names copy RegistryIdBridgeSynthetic.INTERNAL and FORGE_INTERNAL, so an embedded copy
    // refers to no other Retromod class.
    private static final String UNRELOCATED_NAME = "com.retromod.polyfill.minecraft.RetroEntityTypeBuild";
    static final String NEOFORGE_REGISTRATION_BRIDGE = "com.retromod.shim.forge.embedded.RegistryIdBridge";
    static final String FORGE_REGISTRATION_BRIDGE = "com.retromod.shim.forge.embedded.ForgeRegistryIdBridge";
    private static volatile java.util.List<ThreadLocal<?>> registrationSlots;

    /** The key of the entity type registration running on this thread, or null. */
    private static Object registrationKey(Class<?> keyClass, Object entityTypeRoot) {
        try {
            java.lang.reflect.Method isFor = null;
            for (ThreadLocal<?> slot : registrationSlots()) {
                // A bare id with no registry, which a block or item static field stamps, is skipped.
                Object current = slot.get();
                if (!keyClass.isInstance(current)) continue;
                // A key for another registry means the entity is built inside some other entry,
                // such as a spawn egg's supplier, so it is not this entity's id.
                if (isFor == null) isFor = keyClass.getMethod("isFor", keyClass);
                if (Boolean.TRUE.equals(isFor.invoke(current, entityTypeRoot))) return current;
            }
        } catch (Throwable unreadable) {
            // fall back to the id the call site names
        }
        return null;
    }

    /** The key slots of the deferred register bridges embedded with this class, found once. */
    private static java.util.List<ThreadLocal<?>> registrationSlots() {
        java.util.List<ThreadLocal<?>> slots = registrationSlots;
        if (slots != null) return slots;
        java.util.List<ThreadLocal<?>> found = new java.util.ArrayList<>();
        // The bridges share this class's relocation prefix, which is empty when it is not relocated.
        String self = RetroEntityTypeBuild.class.getName();
        if (self.endsWith(UNRELOCATED_NAME)) {
            String prefix = self.substring(0, self.length() - UNRELOCATED_NAME.length());
            ClassLoader loader = RetroEntityTypeBuild.class.getClassLoader();
            for (String bridge : new String[]{NEOFORGE_REGISTRATION_BRIDGE, FORGE_REGISTRATION_BRIDGE}) {
                try {
                    Class<?> owner = Class.forName(prefix + bridge, true, loader);
                    if (owner.getField("CURRENT").get(null) instanceof ThreadLocal<?> slot) found.add(slot);
                } catch (Throwable absent) {
                    // this mod registers through no deferred register bridge
                }
            }
        }
        registrationSlots = java.util.List.copyOf(found);
        return registrationSlots;
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
