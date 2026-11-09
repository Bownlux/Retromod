/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.forge.ModConstructorHooks;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static com.retromod.shim.common.BridgeHarness.*;
import static org.junit.jupiter.api.Assertions.*;

/** Carry On's {@code COMPOUND_TAG} on hosts that removed it: one shared serializer in the loader's own registry. */
class LegacyEntityDataSerializerBridgeTest {

    private static final List<String> SLOTS = List.of("retromod.shared.entity_data.compound_tag.v1",
            "retromod.shared.entity_data.compound_tag.registered.v1");
    private static final String FORGE_BUS = "net.minecraftforge.eventbus.api.bus.EventBus";
    private static final String FORGE_EVENT = "net.minecraftforge.registries.RegisterEvent";
    private static final String NEOFORGE_BUS = "net.neoforged.bus.api.ModBus";
    private static final String NEOFORGE_EVENT = "net.neoforged.neoforge.registries.RegisterEvent";

    @TempDir
    Path dir;

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        RetromodTransformer.getInstance().clearRedirectsForTesting();
        SLOTS.forEach(System.getProperties()::remove);
    }

    @Test
    @DisplayName("on Forge 26.2 the mod's constructor registers the serializer once, before any read of the field")
    void forgeRegistersAtConstruction() throws Throwable {
        McReflect.setForceForge(true);
        Run run = constructed("net.minecraftforge.fml.common.Mod", forgeLoader());
        assertRegisteredOnce(run, FORGE_BUS, FORGE_EVENT, "forge:entity_data_serializers");
    }

    @Test
    @DisplayName("on NeoForge the mod's constructor registers the serializer in NeoForge's registry")
    void neoForgeRegistersAtConstruction() throws Throwable {
        McReflect.setForceNeoForge(true);
        Run run = constructed("net.neoforged.fml.common.Mod", neoForgeLoader());
        assertRegisteredOnce(run, NEOFORGE_BUS, NEOFORGE_EVENT, "neoforge:entity_data_serializers");
    }

    @Test
    @DisplayName("a name another Retromod build already registered is left alone, so the registry sees no duplicate")
    void anotherBuildRegisteredFirst() throws Throwable {
        McReflect.setForceForge(true);
        assertLeftToOtherBuild(constructed("net.minecraftforge.fml.common.Mod", forgeLoader()),
                FORGE_BUS, FORGE_EVENT, "forge:entity_data_serializers");
        McReflect.setForceForge(false);
        McReflect.setForceNeoForge(true);
        assertLeftToOtherBuild(constructed("net.neoforged.fml.common.Mod", neoForgeLoader()),
                NEOFORGE_BUS, NEOFORGE_EVENT, "neoforge:entity_data_serializers");
    }

    @Test
    @DisplayName("Fabric and Forge before 26.2 keep the old read, since no mod constructor can register it there")
    void otherHostsKeepTheRead() throws Exception {
        Map<String, byte[]> mod = only(compile(dir.resolve("old"), oldMod("net.minecraftforge.fml.common.Mod")), "mod/");
        Map<String, byte[]> fabric = transform("26.2", LegacyEntityDataSerializerBridge::register, mod);
        assertTrue(fields(fabric.get("mod/CarryMod"), "serializer").get(0).contains("EntityDataSerializers.COMPOUND_TAG"));
        McReflect.setForceForge(true);
        Map<String, byte[]> forge1219 = transform("1.21.11", LegacyEntityDataSerializerBridge::register, mod);
        assertTrue(fields(forge1219.get("mod/CarryMod"), "serializer").get(0).contains("EntityDataSerializers.COMPOUND_TAG"));
    }

    /** The old mod, translated for 26.2 with its constructor hooks, then constructed against {@code loader} stand-ins. */
    private Run constructed(String modAnnotation, Map<String, String> loader) throws Exception {
        // Each construction compiles into its own folder, so one loader's stand-ins never reach another's run.
        Path work = Files.createTempDirectory(dir, "run");
        Map<String, byte[]> mod = only(compile(work.resolve("old"), oldMod(modAnnotation)), "mod/");
        Map<String, String> hostSources = new HashMap<>(loader);
        hostSources.putAll(hostVanilla());
        Map<String, byte[]> host = compile(work.resolve("host"), hostSources);
        Map<String, byte[]> out = transform("26.2", LegacyEntityDataSerializerBridge::register, mod);
        ModConstructorHooks.JarHooks hooks = ModConstructorHooks.forJar(RetromodTransformer.getInstance(), mod.values());
        assertNotNull(hooks, "a jar that names COMPOUND_TAG gets the registration hook");
        Map<String, byte[]> hooked = new HashMap<>();
        out.forEach((name, bytes) -> hooked.put(name, hooks.apply(bytes)));
        Run run = new Run(host, hooked);
        run.loadClass("mod.CarryMod").getConstructor().newInstance();
        return run;
    }

    /** Fires the registration event on every listener twice, as two mods' buses would, and checks one registration. */
    private static void assertRegisteredOnce(Run run, String busClass, String eventClass, String key) throws Throwable {
        @SuppressWarnings("unchecked")
        List<Consumer<Object>> listeners = (List<Consumer<Object>>) run.loadClass(busClass).getField("LISTENERS").get(null);
        assertEquals(1, listeners.size(), "the constructor added one listener");
        Class<?> event = run.loadClass(eventClass);
        for (Object registry : List.of("minecraft:block", key, key)) {
            Object posted = event.getConstructor(Object.class).newInstance(registry);
            listeners.forEach(listener -> listener.accept(posted));
        }
        @SuppressWarnings("unchecked")
        List<Object[]> registered = (List<Object[]>) event.getField("REGISTERED").get(null);
        assertEquals(1, registered.size(), "registered once, into the serializer registry only");
        assertEquals("retromod:compound_tag", registered.get(0)[1].toString());
        // Only now does the mod read the field, and it gets the serializer the loader already numbered.
        assertSame(registered.get(0)[2], run.call("mod/CarryMod", "serializer"));
    }

    /** Seeds the registry with another build's entry under the shared name, then fires the event on every listener. */
    private static void assertLeftToOtherBuild(Run run, String busClass, String eventClass, String key) throws Throwable {
        Class<?> event = run.loadClass(eventClass);
        @SuppressWarnings("unchecked")
        List<Object[]> registered = (List<Object[]>) event.getField("REGISTERED").get(null);
        Object id = run.loadClass("net.minecraft.resources.Identifier").getMethod("fromNamespaceAndPath", String.class, String.class)
                .invoke(null, "retromod", "compound_tag");
        registered.add(new Object[] {key, id, "another build's serializer"});
        @SuppressWarnings("unchecked")
        List<Consumer<Object>> listeners = (List<Consumer<Object>>) run.loadClass(busClass).getField("LISTENERS").get(null);
        Object posted = event.getConstructor(Object.class).newInstance(key);
        listeners.forEach(listener -> listener.accept(posted));
        assertEquals(1, registered.size(), "the name is already taken, so this build registers nothing");
        assertEquals("another build's serializer", registered.get(0)[2]);
    }

    /** Forge 26.2's mod bus group and registration event, whose Forge registry answers what it holds. */
    private static Map<String, String> forgeLoader() {
        return Map.of(
                "net/minecraftforge/fml/ModLoadingContext.java", """
                        package net.minecraftforge.fml;
                        public class ModLoadingContext {
                            public static ModLoadingContext get() { return new ModLoadingContext(); }
                            public ModContainer getContainer() { return new ModContainer(); }
                        }""",
                "net/minecraftforge/fml/ModContainer.java", """
                        package net.minecraftforge.fml;
                        public class ModContainer {
                            public net.minecraftforge.eventbus.api.bus.BusGroup getModBusGroup() { return new net.minecraftforge.eventbus.api.bus.BusGroup(); }
                        }""",
                "net/minecraftforge/eventbus/api/bus/BusGroup.java", "package net.minecraftforge.eventbus.api.bus; public class BusGroup {}",
                "net/minecraftforge/eventbus/api/bus/EventBus.java", """
                        package net.minecraftforge.eventbus.api.bus;
                        public class EventBus<T> {
                            public static final java.util.List<java.util.function.Consumer<Object>> LISTENERS = new java.util.ArrayList<>();
                            @SuppressWarnings("unchecked")
                            public Object addListener(java.util.function.Consumer<T> listener) { LISTENERS.add((java.util.function.Consumer<Object>) listener); return listener; }
                        }""",
                "net/minecraftforge/registries/ForgeRegistries.java", """
                        package net.minecraftforge.registries;
                        public class ForgeRegistries { public static class Keys { public static final Object ENTITY_DATA_SERIALIZERS = "forge:entity_data_serializers"; } }""",
                "net/minecraftforge/registries/RegisterEvent.java", """
                        package net.minecraftforge.registries;
                        public class RegisterEvent {
                            public static final java.util.List<Object[]> REGISTERED = new java.util.ArrayList<>();
                            private final Object key;
                            public RegisterEvent(Object key) { this.key = key; }
                            public static net.minecraftforge.eventbus.api.bus.EventBus<RegisterEvent> getBus(net.minecraftforge.eventbus.api.bus.BusGroup group) { return new net.minecraftforge.eventbus.api.bus.EventBus<>(); }
                            public Object getRegistryKey() { return key; }
                            public IForgeRegistry<Object> getForgeRegistry() { return id -> REGISTERED.stream().anyMatch(entry -> entry[1].toString().equals(id.toString())); }
                            public net.minecraft.core.Registry<Object> getVanillaRegistry() { return null; }
                            public <T> void register(Object key, net.minecraft.resources.Identifier id, java.util.function.Supplier<T> value) { REGISTERED.add(new Object[] {key, id, value.get()}); }
                        }""",
                "net/minecraftforge/registries/IForgeRegistry.java", """
                        package net.minecraftforge.registries;
                        public interface IForgeRegistry<T> { boolean containsKey(net.minecraft.resources.Identifier key); }""");
    }

    /** NeoForge's mod event bus and registration event, whose registry answers what it holds. */
    private static Map<String, String> neoForgeLoader() {
        return Map.of(
                "net/neoforged/fml/ModLoadingContext.java", """
                        package net.neoforged.fml;
                        public class ModLoadingContext {
                            public static ModLoadingContext get() { return new ModLoadingContext(); }
                            public ModContainer getActiveContainer() { return new ModContainer(); }
                        }""",
                "net/neoforged/fml/ModContainer.java", """
                        package net.neoforged.fml;
                        public class ModContainer {
                            public net.neoforged.bus.api.IEventBus getEventBus() { return new net.neoforged.bus.api.ModBus(); }
                        }""",
                "net/neoforged/bus/api/IEventBus.java", """
                        package net.neoforged.bus.api;
                        public interface IEventBus { <T> void addListener(Class<T> type, java.util.function.Consumer<T> listener); }""",
                "net/neoforged/bus/api/ModBus.java", """
                        package net.neoforged.bus.api;
                        public class ModBus implements IEventBus {
                            public static final java.util.List<java.util.function.Consumer<Object>> LISTENERS = new java.util.ArrayList<>();
                            @SuppressWarnings("unchecked")
                            public <T> void addListener(Class<T> type, java.util.function.Consumer<T> listener) { LISTENERS.add((java.util.function.Consumer<Object>) listener); }
                        }""",
                "net/neoforged/neoforge/registries/NeoForgeRegistries.java", """
                        package net.neoforged.neoforge.registries;
                        public class NeoForgeRegistries { public static class Keys { public static final Object ENTITY_DATA_SERIALIZERS = "neoforge:entity_data_serializers"; } }""",
                "net/neoforged/neoforge/registries/RegisterEvent.java", """
                        package net.neoforged.neoforge.registries;
                        public class RegisterEvent {
                            public static final java.util.List<Object[]> REGISTERED = new java.util.ArrayList<>();
                            private final Object key;
                            public RegisterEvent(Object key) { this.key = key; }
                            public Object getRegistryKey() { return key; }
                            public net.minecraft.core.Registry<?> getRegistry() { return id -> REGISTERED.stream().anyMatch(entry -> entry[1].toString().equals(id.toString())); }
                            public <T> void register(Object key, net.minecraft.resources.Identifier id, java.util.function.Supplier<T> value) { REGISTERED.add(new Object[] {key, id, value.get()}); }
                        }""");
    }

    private static Map<String, String> oldMod(String modAnnotation) {
        String annotationPackage = modAnnotation.substring(0, modAnnotation.lastIndexOf('.'));
        return Map.of(
                modAnnotation.replace('.', '/') + ".java", "package " + annotationPackage + ";\n"
                        + "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)\n"
                        + "public @interface Mod { String value(); }",
                "net/minecraft/network/syncher/EntityDataSerializer.java", """
                        package net.minecraft.network.syncher;
                        public interface EntityDataSerializer<T> { T copy(T value); }""",
                "net/minecraft/network/syncher/EntityDataSerializers.java", """
                        package net.minecraft.network.syncher;
                        public class EntityDataSerializers { public static final EntityDataSerializer<Object> COMPOUND_TAG = null; }""",
                "mod/CarryMod.java", "package mod;\n@" + modAnnotation + "(\"carryon\")\n" + """
                        public class CarryMod {
                            public CarryMod() {}
                            public static Object serializer() { return net.minecraft.network.syncher.EntityDataSerializers.COMPOUND_TAG; }
                        }""");
    }

    private static Map<String, String> hostVanilla() {
        return Map.of(
                "net/minecraft/resources/Identifier.java", """
                        package net.minecraft.resources;
                        public final class Identifier {
                            private final String id;
                            private Identifier(String id) { this.id = id; }
                            public static Identifier fromNamespaceAndPath(String namespace, String path) { return new Identifier(namespace + ":" + path); }
                            @Override public String toString() { return id; }
                        }""",
                "io/netty/buffer/ByteBuf.java", "package io.netty.buffer; public abstract class ByteBuf {}",
                "net/minecraft/network/FriendlyByteBuf.java", "package net.minecraft.network; public class FriendlyByteBuf extends io.netty.buffer.ByteBuf {}",
                "net/minecraft/network/RegistryFriendlyByteBuf.java", "package net.minecraft.network; public class RegistryFriendlyByteBuf extends FriendlyByteBuf {}",
                "net/minecraft/network/codec/StreamCodec.java", "package net.minecraft.network.codec; public interface StreamCodec<B, V> {}",
                "net/minecraft/network/codec/ByteBufCodecs.java", """
                        package net.minecraft.network.codec;
                        public interface ByteBufCodecs { StreamCodec<io.netty.buffer.ByteBuf, net.minecraft.nbt.CompoundTag> COMPOUND_TAG = new StreamCodec<>() {}; }""",
                "net/minecraft/nbt/CompoundTag.java", """
                        package net.minecraft.nbt;
                        public final class CompoundTag { public CompoundTag copy() { return new CompoundTag(); } }""",
                "net/minecraft/network/syncher/EntityDataSerializer.java", """
                        package net.minecraft.network.syncher;
                        public interface EntityDataSerializer<T> {
                            net.minecraft.network.codec.StreamCodec<? super net.minecraft.network.RegistryFriendlyByteBuf, T> codec();
                            T copy(T value);
                        }""",
                "net/minecraft/network/syncher/EntityDataSerializers.java",
                "package net.minecraft.network.syncher; public class EntityDataSerializers {}",
                "net/minecraft/core/Registry.java", """
                        package net.minecraft.core;
                        public interface Registry<T> { boolean containsKey(net.minecraft.resources.Identifier id); }""");
    }
}
