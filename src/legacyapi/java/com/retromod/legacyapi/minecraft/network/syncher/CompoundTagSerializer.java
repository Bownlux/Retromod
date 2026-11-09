/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.network.syncher;

import com.retromod.legacyapi.forge.LegacySharedSlots;
import com.retromod.shim.common.embedded.LegacyLog;
import com.retromod.shim.common.embedded.LegacyPublicMethods;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.syncher.EntityDataSerializer;

/** The {@code EntityDataSerializers.COMPOUND_TAG} 1.21.9 removed: synced entity data that holds a compound tag. */
public final class CompoundTagSerializer implements EntityDataSerializer<CompoundTag> {

    // Every translated jar's copy meets in these slots, which hold only JDK and host types.
    private static final String SLOT = "entity_data.compound_tag";
    private static final String REGISTERED = "entity_data.compound_tag.registered";
    private static final int PROTOCOL = 1;
    static final String NAMESPACE = "retromod";
    static final String PATH = "compound_tag";
    private static final String[] REGISTRY_GETTERS = {"getRegistry", "getForgeRegistry", "getVanillaRegistry"};

    private static boolean listening;

    private CompoundTagSerializer() {}

    /** The one serializer every translated mod shares, as the old field held one. */
    @SuppressWarnings("unchecked")
    public static EntityDataSerializer<CompoundTag> get() {
        return LegacySharedSlots.get(SLOT, PROTOCOL, EntityDataSerializer.class, CompoundTagSerializer::new);
    }

    /** Called from each {@code @Mod} constructor of a jar that names the old field, before any registry fills. */
    public static synchronized void watch() {
        if (listening) return;
        listening = true;
        // The loader keeps modded serializers in its own registry, filled while mods load on the server and on
        // every client, so the serializer's number does not depend on when a mod first reads the field.
        try {
            if (listenOnForge() || listenOnNeoForge()) return;
            LegacyLog.warnOnce("compound tag serializer bus", "Retromod found no mod bus to register the old compound"
                    + " tag entity data serializer from, so entities that sync one cannot be sent to clients.");
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            LegacyLog.warnOnce("compound tag serializer listener", "Retromod could not listen for the loader's entity"
                    + " data serializer registration, so entities that sync a compound tag cannot be sent: " + failure);
        }
    }

    /** Forge 26.2 and newer: the registration event on the constructing mod's bus group. */
    private static boolean listenOnForge() throws ReflectiveOperationException {
        Class<?> context = find("net.minecraftforge.fml.ModLoadingContext");
        Class<?> busGroup = find("net.minecraftforge.eventbus.api.bus.BusGroup");
        if (context == null || busGroup == null) return false;
        Object container = context.getMethod("getContainer").invoke(context.getMethod("get").invoke(null));
        Object group = find("net.minecraftforge.fml.ModContainer").getMethod("getModBusGroup").invoke(container);
        Class<?> event = find("net.minecraftforge.registries.RegisterEvent");
        Object bus = event.getMethod("getBus", busGroup).invoke(null, group);
        Object key = find("net.minecraftforge.registries.ForgeRegistries$Keys").getField("ENTITY_DATA_SERIALIZERS").get(null);
        Consumer<Object> listener = registration -> onRegister(registration, key);
        find("net.minecraftforge.eventbus.api.bus.EventBus").getMethod("addListener", Consumer.class).invoke(bus, listener);
        return true;
    }

    /** NeoForge: the registration event on the constructing mod's event bus. */
    private static boolean listenOnNeoForge() throws ReflectiveOperationException {
        Class<?> context = find("net.neoforged.fml.ModLoadingContext");
        if (context == null) return false;
        Object container = context.getMethod("getActiveContainer").invoke(context.getMethod("get").invoke(null));
        Object bus = find("net.neoforged.fml.ModContainer").getMethod("getEventBus").invoke(container);
        Class<?> event = find("net.neoforged.neoforge.registries.RegisterEvent");
        Object key = find("net.neoforged.neoforge.registries.NeoForgeRegistries$Keys")
                .getField("ENTITY_DATA_SERIALIZERS").get(null);
        Consumer<Object> listener = registration -> onRegister(registration, key);
        find("net.neoforged.bus.api.IEventBus").getMethod("addListener", Class.class, Consumer.class)
                .invoke(bus, event, listener);
        return true;
    }

    /** Registers the shared serializer once, under one name every copy uses, when the serializer registry fills. */
    static void onRegister(Object registration, Object key) {
        try {
            if (!key.equals(LegacyPublicMethods.method(registration.getClass(), "getRegistryKey").invoke(registration))) return;
            // A copy from another Retromod build shares no slot with this one, so the registry itself is asked first.
            Object id = identifier();
            if (alreadyRegistered(registration, id)) return;
            // The loader posts the event to every mod's bus, and only the first copy to see it registers.
            AtomicBoolean done = LegacySharedSlots.get(REGISTERED, PROTOCOL, AtomicBoolean.class, AtomicBoolean::new);
            if (!done.compareAndSet(false, true)) return;
            EntityDataSerializer<CompoundTag> serializer = get();
            Supplier<Object> value = () -> serializer;
            for (Method method : LegacyPublicMethods.methods(registration.getClass())) {
                if (method.getName().equals("register") && method.getParameterCount() == 3
                        && method.getParameterTypes()[2] == Supplier.class) {
                    method.invoke(registration, key, id, value);
                    return;
                }
            }
            throw new NoSuchMethodException("RegisterEvent.register(key, id, supplier)");
        } catch (ReflectiveOperationException | RuntimeException failure) {
            LegacyLog.warnOnce("compound tag serializer register", "Retromod could not register the old compound"
                    + " tag entity data serializer, so entities that sync one cannot be sent: " + failure);
        }
    }

    /** Whether the registry the event fills already holds {@code id}. */
    private static boolean alreadyRegistered(Object registration, Object id) throws ReflectiveOperationException {
        // NeoForge's event names the registry it fills; Forge's names its own registry, or the vanilla one.
        for (String name : REGISTRY_GETTERS) {
            Method getter = publicMethod(registration.getClass(), name);
            Object registry = getter == null ? null : getter.invoke(registration);
            if (registry == null) continue;
            // The getter's declared type is public, which the registry class behind it may not be.
            return (boolean) getter.getReturnType().getMethod("containsKey", id.getClass()).invoke(registry, id);
        }
        return false;
    }

    private static Method publicMethod(Class<?> owner, String name) {
        try {
            return owner.getMethod(name);
        } catch (NoSuchMethodException absent) {
            return null;
        }
    }

    /** {@code retromod:compound_tag} in the host's id class, Identifier from 1.21.11 and ResourceLocation before. */
    private static Object identifier() throws ReflectiveOperationException {
        Class<?> id = find("net.minecraft.resources.Identifier");
        if (id == null) id = find("net.minecraft.resources.ResourceLocation");
        if (id == null) throw new ClassNotFoundException("net.minecraft.resources.Identifier");
        return id.getMethod("fromNamespaceAndPath", String.class, String.class).invoke(null, NAMESPACE, PATH);
    }

    private static Class<?> find(String name) {
        try {
            return Class.forName(name, false, CompoundTagSerializer.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError absent) {
            return null;
        }
    }

    /** The tag's network form, with the same size limit the old serializer's buffer read applied. */
    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public StreamCodec<? super RegistryFriendlyByteBuf, CompoundTag> codec() {
        return (StreamCodec) ByteBufCodecs.COMPOUND_TAG;
    }

    /** A copy, as the old serializer made, so a packet holds the tag as it was when it was sent. */
    @Override
    public CompoundTag copy(CompoundTag value) {
        return value.copy();
    }
}
