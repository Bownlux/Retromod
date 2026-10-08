/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.capability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Saves Forge capability data through one NeoForge data attachment, {@code retromod:forge_capabilities}.
 *
 * <p>Forge wrote every {@code ICapabilitySerializable} provider into the holder's NBT. NeoForge has
 * no capability NBT, so without this a migrated mod's per-player or per-entity data resets on every
 * restart. The attachment value is a map from the provider's attach key to either the provider
 * (once a mod has attached it this session) or the raw tag read from disk (until then). Writing
 * calls each provider's {@code serializeNBT}, and {@link LegacyCapabilityRuntime} hands a provider
 * its saved tag when it is attached. A tag nobody claims is written back unchanged, so data from a
 * mod that is not asked this session is never lost.
 *
 * <p>Item stacks are not covered: 1.21 stacks carry data components, not attachments.
 */
public final class ForgeCapabilityStorage {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");
    public static final String NAMESPACE = "retromod";
    public static final String PATH = "forge_capabilities";

    private static final java.util.concurrent.atomic.AtomicBoolean LISTENING =
            new java.util.concurrent.atomic.AtomicBoolean();

    private ForgeCapabilityStorage() {}

    /** Register the attachment type from Retromod's own mod bus, once per game. */
    public static void listen() {
        if (!LISTENING.compareAndSet(false, true)) return;
        try {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            Class<?> registerEvent = Class.forName("net.neoforged.neoforge.registries.RegisterEvent", false, loader);
            Class<?> loadingContext = Class.forName("net.neoforged.fml.ModLoadingContext", false, loader);
            Object context = loadingContext.getMethod("get").invoke(null);
            Object container = loadingContext.getMethod("getActiveContainer").invoke(context);
            Object bus = container.getClass().getMethod("getEventBus").invoke(container);
            Method addListener = Class.forName("net.neoforged.bus.api.IEventBus", false, loader)
                    .getMethod("addListener", Class.class, Consumer.class);
            Consumer<Object> listener = ForgeCapabilityStorage::onRegister;
            addListener.invoke(bus, registerEvent, listener);
        } catch (ClassNotFoundException e) {
            LOGGER.debug("No NeoForge registry events here; Forge capability data is not saved");
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("Retromod could not register Forge capability storage, so data that migrated "
                    + "Forge mods keep in capabilities will reset on restart: {}", e.toString());
        }
    }

    static void onRegister(Object event) {
        try {
            ClassLoader loader = event.getClass().getClassLoader();
            Class<?> keys = Class.forName("net.neoforged.neoforge.registries.NeoForgeRegistries$Keys", true, loader);
            Object attachmentKey = keys.getField("ATTACHMENT_TYPES").get(null);
            Object registryKey = event.getClass().getMethod("getRegistryKey").invoke(event);
            if (!attachmentKey.equals(registryKey)) return;
            Class<?> serializerType = Class.forName("net.neoforged.neoforge.attachment.IAttachmentSerializer", false, loader);
            if (!usesTagSerializer(serializerType)) {
                LOGGER.info("This NeoForge saves attachments through ValueOutput; Forge capability data "
                        + "from migrated mods is not saved across restarts");
                return;
            }

            Object id = resourceLocation(loader, NAMESPACE, PATH);
            Supplier<Object> type = () -> buildType(loader);
            for (Method register : event.getClass().getMethods()) {
                if (register.getName().equals("register") && register.getParameterCount() == 3) {
                    register.invoke(event, attachmentKey, id, type);
                    return;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("Retromod could not register Forge capability storage: {}", e.toString());
        }
    }

    /**
     * The serializer proxy below speaks NeoForge 21.1's {@code Tag write(T, HolderLookup.Provider)}.
     * NeoForge 21.6 and newer declare {@code boolean write(T, ValueOutput)} and read from a
     * {@code ValueInput}, so registering the proxy there would fail every save with a
     * {@code ClassCastException}. Only the tag shape is supported.
     */
    static boolean usesTagSerializer(Class<?> serializerType) {
        for (Method method : serializerType.getMethods()) {
            if (method.getName().equals("write") && method.getParameterCount() == 2
                    && method.getReturnType() != boolean.class
                    && method.getParameterTypes()[1].getName().endsWith("HolderLookup$Provider")) {
                return true;
            }
        }
        return false;
    }

    private static Object buildType(ClassLoader loader) {
        try {
            Class<?> attachmentType = Class.forName("net.neoforged.neoforge.attachment.AttachmentType", true, loader);
            Class<?> serializerType = Class.forName("net.neoforged.neoforge.attachment.IAttachmentSerializer", true, loader);
            Supplier<Object> fresh = ConcurrentHashMap::new;
            Object builder = attachmentType.getMethod("builder", Supplier.class).invoke(null, fresh);
            Object serializer = Proxy.newProxyInstance(loader, new Class<?>[]{serializerType},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "read" -> read(args[1], loader);
                        case "write" -> write(args[0], loader);
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> "ForgeCapabilityStorage";
                    });
            builder = builder.getClass().getMethod("serialize", serializerType).invoke(builder, serializer);
            return builder.getClass().getMethod("build").invoke(builder);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Retromod could not build the Forge capability attachment", e);
        }
    }

    static Map<String, Object> read(Object compound, ClassLoader loader) throws ReflectiveOperationException {
        Map<String, Object> stored = new ConcurrentHashMap<>();
        Iterable<?> keys = (Iterable<?>) firstMethod(compound, "getAllKeys", "keySet").invoke(compound);
        Method get = compound.getClass().getMethod("get", String.class);
        for (Object key : keys) {
            Object tag = get.invoke(compound, key);
            if (tag != null) stored.put((String) key, tag);
        }
        return stored;
    }

    static Object write(Object value, ClassLoader loader) throws ReflectiveOperationException {
        Map<?, ?> stored = (Map<?, ?>) value;
        if (stored.isEmpty()) return null;
        Class<?> tagType = Class.forName("net.minecraft.nbt.Tag", false, loader);
        Object compound = Class.forName("net.minecraft.nbt.CompoundTag", true, loader).getConstructor().newInstance();
        Method put = compound.getClass().getMethod("put", String.class, tagType);
        for (Map.Entry<?, ?> entry : stored.entrySet()) {
            Object tag = savedForm(entry.getValue(), tagType);
            if (tag != null) put.invoke(compound, entry.getKey(), tag);
        }
        return compound;
    }

    /** A provider's current {@code serializeNBT()}, or the unclaimed tag itself. */
    static Object savedForm(Object value, Class<?> tagType) throws ReflectiveOperationException {
        if (tagType.isInstance(value)) return value;
        Method serialize;
        try {
            serialize = value.getClass().getMethod("serializeNBT");
        } catch (NoSuchMethodException notSerializable) {
            return null;
        }
        serialize.setAccessible(true);
        Object tag = serialize.invoke(value);
        return tagType.isInstance(tag) ? tag : null;
    }

    static Object resourceLocation(ClassLoader loader, String namespace, String path)
            throws ReflectiveOperationException {
        for (String name : new String[]{"net.minecraft.resources.ResourceLocation", "net.minecraft.resources.Identifier"}) {
            try {
                return Class.forName(name, true, loader).getMethod("fromNamespaceAndPath", String.class, String.class)
                        .invoke(null, namespace, path);
            } catch (ClassNotFoundException | NoSuchMethodException tryNext) {
                // The other name or factory belongs to a different Minecraft version.
            }
        }
        throw new ClassNotFoundException("No ResourceLocation factory on this host");
    }

    private static Method firstMethod(Object target, String... names) throws NoSuchMethodException {
        for (String name : names) {
            try {
                return target.getClass().getMethod(name);
            } catch (NoSuchMethodException tryNext) {
                // Renamed between Minecraft versions.
            }
        }
        throw new NoSuchMethodException(String.join("/", names) + " on " + target.getClass().getName());
    }
}
