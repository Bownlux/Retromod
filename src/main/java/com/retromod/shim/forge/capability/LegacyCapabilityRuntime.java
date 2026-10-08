/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.capability;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The call targets for Forge's capability lookups on Minecraft objects, which NeoForge deleted.
 *
 * <p>Forge made every entity, item stack, block entity, level and chunk a capability provider:
 * {@code holder.getCapability(cap, side)} asked the holder's own override first, then every
 * provider attached through {@code AttachCapabilitiesEvent}. NeoForge has neither, so
 * {@link LegacyCapabilityCallAdapter} rewrites those calls to {@link #get}. The attach event is
 * posted to NeoForge's game bus the first time a holder is asked, and the providers are kept for
 * the holder's lifetime. A Forge built-in capability that nothing attached falls through to
 * NeoForge's own capability for the same handler.
 *
 * <p>This class is embedded per mod, so each mod sees the providers its own listeners attached.
 */
public final class LegacyCapabilityRuntime {

    private static final String[] ATTACHABLE_HOLDERS = {
            "net.minecraft.world.entity.Entity",
            "net.minecraft.world.item.ItemStack",
            "net.minecraft.world.level.block.entity.BlockEntity",
            "net.minecraft.world.level.Level",
            "net.minecraft.world.level.chunk.LevelChunk"
    };

    private static final Map<Object, List<Object>> ATTACHED =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Class<?>, Method> OVERRIDES = new ConcurrentHashMap<>();
    private static final Method NO_OVERRIDE = noOverrideMarker();
    private static final ThreadLocal<Boolean> GATHERING = ThreadLocal.withInitial(() -> false);

    private LegacyCapabilityRuntime() {}

    /** {@code holder.getCapability(cap)}. */
    public static LegacyLazyOptional<?> get(Object holder, LegacyCapability<?> capability) {
        return get(holder, capability, null);
    }

    /** {@code holder.getCapability(cap, side)}: the holder's own override first, then attachments. */
    public static LegacyLazyOptional<?> get(Object holder, LegacyCapability<?> capability, Object side) {
        if (holder == null || capability == null) return LegacyLazyOptional.empty();
        Method override = findOverride(holder.getClass(), capability.getClass());
        if (override != null) {
            Object result = invoke(override, holder, capability, side);
            return result instanceof LegacyLazyOptional<?> lazy ? lazy : LegacyLazyOptional.empty();
        }
        return getAttached(holder, capability, side);
    }

    /** {@code super.getCapability(cap)} from a mod override. */
    public static LegacyLazyOptional<?> getAttached(Object holder, LegacyCapability<?> capability) {
        return getAttached(holder, capability, null);
    }

    /** {@code super.getCapability(cap, side)} from a mod override: attachments and NeoForge only. */
    public static LegacyLazyOptional<?> getAttached(Object holder, LegacyCapability<?> capability, Object side) {
        if (holder == null || capability == null) return LegacyLazyOptional.empty();
        for (Object provider : providers(holder)) {
            Method method = findOverride(provider.getClass(), capability.getClass());
            if (method == null) continue;
            Object result = invoke(method, provider, capability, side);
            if (result instanceof LegacyLazyOptional<?> lazy && lazy.isPresent()) return lazy;
        }
        return NeoForgeCapabilities.query(holder, capability, side);
    }

    /** Forge's {@code RegisterCapabilitiesEvent.register(Class)}: tokens need no registration. */
    public static void registerType(Object event, Class<?> type) {
        // CapabilityManager.get(token) creates the capability on first use.
    }

    /** The providers attached to {@code holder}, posting the attach event on first use. */
    static List<Object> providers(Object holder) {
        if (!isAttachable(holder.getClass())) return List.of();
        List<Object> existing = ATTACHED.get(holder);
        if (existing != null) return existing;
        // A listener that asks the same holder for a capability while attaching would recurse.
        if (GATHERING.get()) return List.of();
        GATHERING.set(true);
        try {
            LegacyAttachCapabilitiesEvent<Object> event = new LegacyAttachCapabilitiesEvent<>(holder);
            GameBus.post(event);
            Persistence.restore(holder, event.getCapabilities());
            List<Object> gathered = new ArrayList<>(event.getCapabilities().values());
            List<Object> raced = ATTACHED.putIfAbsent(holder, gathered);
            return raced != null ? raced : gathered;
        } finally {
            GATHERING.set(false);
        }
    }

    static boolean isAttachable(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (String holder : ATTACHABLE_HOLDERS) {
                if (holder.equals(current.getName())) return true;
            }
        }
        return false;
    }

    /**
     * The mod-declared {@code getCapability(Capability, Direction)} on {@code type}, if any. Only a
     * public two-argument method whose first parameter is this mod's capability class counts, so
     * NeoForge's own {@code getCapability(EntityCapability, ...)} is never mistaken for it.
     */
    static Method findOverride(Class<?> type, Class<?> capabilityClass) {
        Method cached = OVERRIDES.computeIfAbsent(type, owner -> {
            for (Method method : owner.getMethods()) {
                if (!method.getName().equals("getCapability")) continue;
                if (Modifier.isStatic(method.getModifiers())) continue;
                Class<?>[] parameters = method.getParameterTypes();
                if (parameters.length == 2 && parameters[0] == capabilityClass) {
                    try {
                        method.setAccessible(true);
                    } catch (RuntimeException ignored) {
                        // Public members of exported packages stay callable without it.
                    }
                    return method;
                }
            }
            return NO_OVERRIDE;
        });
        return cached == NO_OVERRIDE ? null : cached;
    }

    private static Object invoke(Method method, Object target, Object capability, Object side) {
        try {
            return method.invoke(target, capability, side);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException(cause);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Retromod could not call " + method + " for a Forge capability", e);
        }
    }

    private static Method noOverrideMarker() {
        try {
            return Object.class.getMethod("hashCode");
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** NeoForge's game bus, reached reflectively because Retromod is not compiled against it. */
    static final class GameBus {
        private static volatile Object bus;
        private static volatile Method post;

        private GameBus() {}

        static void post(Object event) {
            try {
                if (post == null) {
                    ClassLoader loader = LegacyCapabilityRuntime.class.getClassLoader();
                    Class<?> neoForge = Class.forName("net.neoforged.neoforge.common.NeoForge", true, loader);
                    Class<?> eventType = Class.forName("net.neoforged.bus.api.Event", false, loader);
                    bus = neoForge.getField("EVENT_BUS").get(null);
                    post = bus.getClass().getMethod("post", eventType);
                }
                post.invoke(bus, event);
            } catch (java.lang.reflect.InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException runtime) throw runtime;
                if (cause instanceof Error error) throw error;
                throw new IllegalStateException(cause);
            } catch (ReflectiveOperationException | LinkageError e) {
                // No NeoForge game bus: nothing can attach, so every holder keeps no providers.
            }
        }
    }

    /**
     * Links attached providers to Retromod's {@code retromod:forge_capabilities} attachment, which
     * saves them with the holder. A provider gets back the tag it wrote last session, then takes its
     * place in the attachment so the next save asks it again.
     */
    static final class Persistence {
        private static volatile Object attachmentType;

        private Persistence() {}

        static void restore(Object holder, Map<Object, Object> providers) {
            if (providers.isEmpty()) return;
            try {
                Object type = attachmentType(holder.getClass().getClassLoader());
                if (type == null) return;
                Method getData = null;
                for (Method method : holder.getClass().getMethods()) {
                    if (method.getName().equals("getData") && method.getParameterCount() == 1
                            && method.getParameterTypes()[0].isInstance(type)) {
                        getData = method;
                        break;
                    }
                }
                if (getData == null) return; // item stacks have no attachments
                @SuppressWarnings("unchecked")
                Map<String, Object> stored = (Map<String, Object>) getData.invoke(holder, type);
                for (Map.Entry<Object, Object> entry : providers.entrySet()) {
                    String key = String.valueOf(entry.getKey());
                    Object saved = stored.get(key);
                    if (saved != null && saved != entry.getValue()) deserialize(entry.getValue(), saved);
                    stored.put(key, entry.getValue());
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                // Storage is unavailable: providers still work, they just start empty.
            }
        }

        private static void deserialize(Object provider, Object saved) throws ReflectiveOperationException {
            for (Method method : provider.getClass().getMethods()) {
                if (method.getName().equals("deserializeNBT") && method.getParameterCount() == 1
                        && method.getParameterTypes()[0].isInstance(saved)) {
                    method.setAccessible(true);
                    method.invoke(provider, saved);
                    return;
                }
            }
        }

        private static Object attachmentType(ClassLoader loader) throws ReflectiveOperationException {
            Object type = attachmentType;
            if (type != null) return type;
            Class<?> registries = Class.forName("net.neoforged.neoforge.registries.NeoForgeRegistries", true, loader);
            Object registry = registries.getField("ATTACHMENT_TYPES").get(null);
            Object id = null;
            for (String name : new String[]{"net.minecraft.resources.ResourceLocation", "net.minecraft.resources.Identifier"}) {
                try {
                    id = Class.forName(name, true, loader).getMethod("fromNamespaceAndPath", String.class, String.class)
                            .invoke(null, "retromod", "forge_capabilities");
                    break;
                } catch (ClassNotFoundException | NoSuchMethodException tryNext) {
                    // The other spelling belongs to a different Minecraft version.
                }
            }
            if (id == null) return null;
            for (String lookup : new String[]{"get", "getValue"}) {
                for (Method method : registry.getClass().getMethods()) {
                    if (method.getName().equals(lookup) && method.getParameterCount() == 1
                            && method.getParameterTypes()[0].isInstance(id)) {
                        type = method.invoke(registry, id);
                        if (type instanceof java.util.Optional<?> optional) type = optional.orElse(null);
                        if (type != null) {
                            attachmentType = type;
                            return type;
                        }
                    }
                }
            }
            return null;
        }
    }

    /** Fallback to NeoForge's capability system for Forge's built-in handler capabilities. */
    static final class NeoForgeCapabilities {
        private NeoForgeCapabilities() {}

        static LegacyLazyOptional<?> query(Object holder, LegacyCapability<?> capability, Object side) {
            Object result = null;
            try {
                if (capability.neoEntity() != null && isA(holder, "net.minecraft.world.entity.Entity")) {
                    result = call(holder, "getCapability", capability.neoEntity(), side);
                } else if (capability.neoItem() != null && isA(holder, "net.minecraft.world.item.ItemStack")) {
                    result = call(holder, "getCapability", capability.neoItem(), null);
                } else if (capability.neoBlock() != null
                        && isA(holder, "net.minecraft.world.level.block.entity.BlockEntity")) {
                    result = blockCapability(holder, capability.neoBlock(), side);
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                result = null;
            }
            if (result == null) return LegacyLazyOptional.empty();
            Object found = result;
            return LegacyLazyOptional.of((Supplier<Object>) () -> found);
        }

        private static Object blockCapability(Object blockEntity, Object neoCapability, Object side)
                throws ReflectiveOperationException {
            Object level = blockEntity.getClass().getMethod("getLevel").invoke(blockEntity);
            if (level == null) return null;
            Object pos = blockEntity.getClass().getMethod("getBlockPos").invoke(blockEntity);
            Object state = blockEntity.getClass().getMethod("getBlockState").invoke(blockEntity);
            for (Method method : level.getClass().getMethods()) {
                if (method.getName().equals("getCapability") && method.getParameterCount() == 5
                        && method.getParameterTypes()[0].isInstance(neoCapability)) {
                    return method.invoke(level, neoCapability, pos, state, blockEntity, side);
                }
            }
            return null;
        }

        private static Object call(Object holder, String name, Object neoCapability, Object context)
                throws ReflectiveOperationException {
            for (Method method : holder.getClass().getMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == 2
                        && method.getParameterTypes()[0].isInstance(neoCapability)) {
                    return method.invoke(holder, neoCapability, context);
                }
            }
            return null;
        }

        private static boolean isA(Object holder, String className) {
            for (Class<?> current = holder.getClass(); current != null; current = current.getSuperclass()) {
                if (className.equals(current.getName())) return true;
            }
            return false;
        }
    }
}
