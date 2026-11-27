/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.fabric.Pre1_20_5ScreenHandlerBridge;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The rebuilt fluid stack tag, container screen event, shader callback and fluid reads, run against stand-in hosts. */
class LegacyClientApiRuntimeTest {

    private static final Path COMPILED = Path.of("target", "legacyapi-classes");
    private static final String ROOT = "com.retromod.legacyapi.";

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("A write to an old fluid stack tag reaches the stack, and a copy made earlier keeps its own tag")
    void fluidStackTagsWriteThroughAndStayIsolated(@TempDir Path dir) throws Exception {
        Host host = new Host(dir, Map.of(
                "net/minecraft/nbt/Tag.java", "package net.minecraft.nbt; public interface Tag {}",
                "net/minecraft/nbt/CompoundTag.java", """
                        package net.minecraft.nbt;
                        import java.util.*;
                        public final class CompoundTag implements Tag {
                            public final Map<String, Object> values = new HashMap<>();
                            public Tag put(String key, Tag value) { values.put(key, value); return null; }
                            public void putInt(String key, int value) { values.put(key, value); }
                            public Optional<CompoundTag> getCompound(String key) { return Optional.ofNullable((CompoundTag) values.get(key)); }
                            public Tag remove(String key) { values.remove(key); return null; }
                            public boolean isEmpty() { return values.isEmpty(); }
                            public CompoundTag copy() { CompoundTag copy = new CompoundTag(); copy.values.putAll(values); return copy; }
                        }""",
                "net/minecraft/world/item/component/CustomData.java", """
                        package net.minecraft.world.item.component;
                        import net.minecraft.nbt.CompoundTag;
                        public final class CustomData {
                            private final CompoundTag tag;
                            private CustomData(CompoundTag tag) { this.tag = tag; }
                            public static CustomData of(CompoundTag tag) { return new CustomData(tag.copy()); }
                            public CompoundTag copyTag() { return tag.copy(); }
                        }""",
                "net/minecraft/core/component/DataComponentType.java", "package net.minecraft.core.component; public final class DataComponentType<T> {}",
                "net/minecraft/core/component/DataComponents.java", """
                        package net.minecraft.core.component;
                        public final class DataComponents {
                            public static final DataComponentType<net.minecraft.world.item.component.CustomData> CUSTOM_DATA = new DataComponentType<>();
                        }""",
                "net/neoforged/neoforge/fluids/FluidStack.java", """
                        package net.neoforged.neoforge.fluids;
                        import java.util.*;
                        public final class FluidStack {
                            private final Map<Object, Object> components = new HashMap<>();
                            public boolean isEmpty() { return false; }
                            public Object get(net.minecraft.core.component.DataComponentType type) { return components.get(type); }
                            public Object set(net.minecraft.core.component.DataComponentType type, Object value) { return components.put(type, value); }
                            public Object remove(net.minecraft.core.component.DataComponentType type) { return components.remove(type); }
                            // A copy shares its components until one side sets a new value, as the host's copy on write does.
                            public FluidStack copy() { FluidStack copy = new FluidStack(); copy.components.putAll(components); return copy; }
                        }"""));
        Class<?> tags = host.type(ROOT + "neoforge.common.LegacyFluidStackTags");
        Class<?> stackType = host.type("net.neoforged.neoforge.fluids.FluidStack");
        Object stack = stackType.getConstructor().newInstance();
        Method putInt = host.type("net.minecraft.nbt.CompoundTag").getMethod("putInt", String.class, int.class);

        putInt.invoke(call(tags, "getOrCreateTag", stack), "a", 1);
        assertEquals(1, values(call(tags, "getTag", stack)).get("a"), "the write reached the stack's custom data");
        Object copy = stackType.getMethod("copy").invoke(stack);
        putInt.invoke(call(tags, "getTag", stack), "b", 2);
        assertEquals(2, values(call(tags, "getTag", stack)).get("b"));
        assertNull(values(call(tags, "getTag", copy)).get("b"), "the earlier copy keeps its own tag");
        assertEquals(Boolean.TRUE, call(tags, "hasTag", stack));
    }

    @Test
    @DisplayName("A container screen that builds the old render event itself is marked, so Retromod's post skips it that frame")
    void selfPostedContainerEventsAreSkipped(@TempDir Path dir) throws Exception {
        Host host = new Host(dir, Map.of(
                "net/neoforged/bus/api/Event.java", "package net.neoforged.bus.api; public abstract class Event {}",
                "net/minecraft/client/gui/screens/Screen.java", "package net.minecraft.client.gui.screens; public class Screen {}",
                "net/minecraft/client/gui/screens/inventory/AbstractContainerScreen.java",
                "package net.minecraft.client.gui.screens.inventory; public class AbstractContainerScreen<T> extends net.minecraft.client.gui.screens.Screen {}",
                "net/minecraft/client/gui/GuiGraphicsExtractor.java", "package net.minecraft.client.gui; public class GuiGraphicsExtractor {}"));
        String event = ROOT + "neoforge.client.event.LegacyContainerScreenEvent";
        Class<?> base = host.type(event);
        Class<?> screenType = host.type("net.minecraft.client.gui.screens.inventory.AbstractContainerScreen");
        Object screen = screenType.getConstructor().newInstance();
        Class<?> graphics = host.type("net.minecraft.client.gui.GuiGraphicsExtractor");
        Class<?> background = host.type(event + "$Render$Background");
        Method postedBy = privateMethod(base, "postedBy", Object.class);
        Method frameStarted = privateMethod(base, "frameStarted", Object.class);
        Method posting = privateMethod(host.type(event + "$Posting"), "set", boolean.class);

        frameStarted.invoke(null, screen);
        background.getConstructor(screenType, graphics, int.class, int.class).newInstance(screen, null, 1, 2);
        assertEquals(Boolean.TRUE, postedBy.invoke(null, screen), "the screen's own post is noted");
        frameStarted.invoke(null, screen);
        assertEquals(Boolean.FALSE, postedBy.invoke(null, screen), "a new frame clears the note");
        posting.invoke(null, true);
        background.getConstructor(screenType, graphics, int.class, int.class).newInstance(screen, null, 1, 2);
        posting.invoke(null, false);
        assertEquals(Boolean.FALSE, postedBy.invoke(null, screen), "Retromod's own post does not count");
    }

    @Test
    @DisplayName("The old core shader callback fires once the client starts, and a callback that fails to link does not stop the client")
    void shaderCallbackFailuresAreContained(@TempDir Path dir) throws Exception {
        Host host = new Host(dir, Map.of(
                "net/minecraft/client/Minecraft.java", "package net.minecraft.client; public class Minecraft {}",
                "net/minecraft/resources/Identifier.java", "package net.minecraft.resources; public class Identifier {}",
                "net/fabricmc/fabric/api/event/Event.java", """
                        package net.fabricmc.fabric.api.event;
                        public abstract class Event<T> {
                            protected volatile T invoker;
                            public final T invoker() { return invoker; }
                            public abstract void register(T listener);
                        }""",
                "net/fabricmc/fabric/api/event/EventFactory.java", """
                        package net.fabricmc.fabric.api.event;
                        import java.util.*;
                        import java.util.function.Function;
                        public final class EventFactory {
                            @SuppressWarnings("unchecked")
                            public static <T> Event<T> createArrayBacked(Class<? super T> type, Function<T[], T> factory) {
                                List<T> listeners = new ArrayList<>();
                                Event<T> event = new Event<T>() {
                                    public void register(T listener) {
                                        listeners.add(listener);
                                        invoker = factory.apply(listeners.toArray((T[]) java.lang.reflect.Array.newInstance(type, 0)));
                                    }
                                };
                                event.invoker = factory.apply((T[]) java.lang.reflect.Array.newInstance(type, 0));
                                return event;
                            }
                        }""",
                "net/fabricmc/fabric/api/client/event/lifecycle/v1/ClientLifecycleEvents.java", """
                        package net.fabricmc.fabric.api.client.event.lifecycle.v1;
                        import net.fabricmc.fabric.api.event.*;
                        public final class ClientLifecycleEvents {
                            public interface ClientStarted { void onClientStarted(net.minecraft.client.Minecraft client); }
                            public static final java.util.List<ClientStarted> STARTED = new java.util.ArrayList<>();
                            public static final Event<ClientStarted> CLIENT_STARTED = new Event<>() {
                                public void register(ClientStarted listener) { STARTED.add(listener); }
                            };
                        }"""));
        String callback = ROOT + "fabric.client.rendering.LegacyCoreShaderRegistrationCallback";
        Class<?> callbackType = host.type(callback);
        Class<?> contextType = host.type(callback + "$RegistrationContext");
        Object event = callbackType.getField("EVENT").get(null);
        List<Object> handedOut = new ArrayList<>();
        Method register = contextType.getMethod("register", host.type("net.minecraft.resources.Identifier"), Object.class,
                java.util.function.Consumer.class);
        Object records = Proxy.newProxyInstance(host.loader, new Class<?>[]{callbackType}, (proxy, method, args) -> {
            java.util.function.Consumer<Object> onLoaded = handedOut::add;
            return register.invoke(args[0], null, null, onLoaded);
        });
        Object breaks = Proxy.newProxyInstance(host.loader, new Class<?>[]{callbackType}, (proxy, method, args) -> {
            throw new NoClassDefFoundError("net/minecraft/client/renderer/ShaderInstance");
        });
        Method add = host.type("net.fabricmc.fabric.api.event.Event").getMethod("register", Object.class);
        add.invoke(event, records);
        add.invoke(event, breaks);

        @SuppressWarnings("unchecked")
        List<Object> started = (List<Object>) host.type("net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents")
                .getField("STARTED").get(null);
        assertEquals(1, started.size(), "the callback event listens for the client start once");
        Method onStarted = host.type("net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents$ClientStarted")
                .getMethod("onClientStarted", host.type("net.minecraft.client.Minecraft"));
        assertDoesNotThrow(() -> onStarted.invoke(started.get(0), (Object) null));
        assertEquals(java.util.Collections.singletonList(null), handedOut, "the shader 26.x cannot build is handed out as null");
    }

    @Test
    @DisplayName("An old fluid read asks the extension's own override, falls back to its plain read when the positional one fails, then to the default")
    void fluidReadsFallBackInOrder(@TempDir Path dir) throws Exception {
        Map<String, String> sources = new HashMap<>(Map.of(
                "net/minecraft/resources/Identifier.java", """
                        package net.minecraft.resources;
                        public class Identifier { public final String path; public Identifier(String path) { this.path = path; } }""",
                "net/minecraft/world/level/material/Fluid.java", "package net.minecraft.world.level.material; public abstract class Fluid {}",
                "net/minecraft/world/level/material/FluidState.java", "package net.minecraft.world.level.material; public final class FluidState {}",
                "net/minecraft/world/level/block/state/BlockState.java", """
                        package net.minecraft.world.level.block.state;
                        public class BlockState { public net.minecraft.world.level.material.FluidState getFluidState() { return null; } }""",
                "net/minecraft/client/renderer/block/BlockAndTintGetter.java", "package net.minecraft.client.renderer.block; public interface BlockAndTintGetter {}",
                "net/minecraft/client/color/block/BlockTintSource.java", """
                        package net.minecraft.client.color.block;
                        public interface BlockTintSource { int color(net.minecraft.world.level.block.state.BlockState state); }""",
                "net/neoforged/neoforge/client/fluid/FluidTintSource.java", """
                        package net.neoforged.neoforge.client.fluid;
                        public interface FluidTintSource extends net.minecraft.client.color.block.BlockTintSource {
                            int color(net.minecraft.world.level.material.FluidState state);
                        }""",
                "net/minecraft/client/Minecraft.java", """
                        package net.minecraft.client;
                        public class Minecraft { public static Minecraft getInstance() { return null; } }""",
                "net/neoforged/neoforge/client/extensions/common/IClientFluidTypeExtensions.java", """
                        package net.neoforged.neoforge.client.extensions.common;
                        public interface IClientFluidTypeExtensions {
                            static IClientFluidTypeExtensions of(net.minecraft.world.level.material.Fluid fluid) { return null; }
                        }"""));
        sources.put("mod/Exts.java", """
                package mod;
                import net.minecraft.resources.Identifier;
                import net.minecraft.world.level.material.FluidState;
                import net.minecraft.client.renderer.block.BlockAndTintGetter;
                import net.minecraft.core.BlockPos;
                import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
                public class Exts {
                    public static class Throwing implements IClientFluidTypeExtensions {
                        public Identifier getStillTexture() { return new Identifier("plain"); }
                        public Identifier getStillTexture(FluidState s, BlockAndTintGetter l, BlockPos p) { throw new IllegalStateException("no level"); }
                        public int getTintColor() { return 9; }
                        public int getTintColor(FluidState s, BlockAndTintGetter l, BlockPos p) { throw new IllegalStateException("no level"); }
                    }
                    public static class Plain implements IClientFluidTypeExtensions {
                        public Identifier getStillTexture() { return new Identifier("plain"); }
                        public int getTintColor() { return 7; }
                    }
                    public static class Positional implements IClientFluidTypeExtensions {
                        public Identifier getStillTexture(FluidState s, BlockAndTintGetter l, BlockPos p) { return new Identifier("there"); }
                    }
                    public static class Neither implements IClientFluidTypeExtensions {}
                }""");
        sources.put("net/minecraft/core/BlockPos.java", "package net.minecraft.core; public class BlockPos {}");
        Host host = new Host(dir, sources);
        Class<?> reads = host.type(ROOT + "neoforge.client.extensions.LegacyFluidTypeReads");
        Class<?> ext = host.type("net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions");
        Class<?> state = host.type("net.minecraft.world.level.material.FluidState");
        Method still = reads.getMethod("getStillTexture", ext, state, host.type("net.minecraft.client.renderer.block.BlockAndTintGetter"),
                host.type("net.minecraft.core.BlockPos"));
        Method tint = reads.getMethod("getTintColor", ext, state, host.type("net.minecraft.client.renderer.block.BlockAndTintGetter"),
                host.type("net.minecraft.core.BlockPos"));

        assertEquals("plain", path(still.invoke(null, host.make("mod.Exts$Throwing"), null, null, null)), "a failing positional read falls back");
        assertEquals("plain", path(still.invoke(null, host.make("mod.Exts$Plain"), null, null, null)), "the old default asked the plain read");
        assertEquals("there", path(still.invoke(null, host.make("mod.Exts$Positional"), null, null, null)));
        assertNull(still.invoke(null, host.make("mod.Exts$Neither"), null, null, null), "no override and no model: nothing");
        assertEquals(9, tint.invoke(null, host.make("mod.Exts$Throwing"), null, null, null));
        assertEquals(7, tint.invoke(null, host.make("mod.Exts$Plain"), null, null, null));
        assertEquals(0xFFFFFFFF, tint.invoke(null, host.make("mod.Exts$Neither"), null, null, null), "white, as the old default was");
    }

    @Test
    @DisplayName("On a 1.20.5 to 1.21.11 Fabric host a mod's own menu type subclass hands its super constructor the opening data codec")
    void menuTypeSubclassGetsTheCodec(@TempDir Path dir) throws Exception {
        String api = "net/fabricmc/fabric/api/screenhandler/v1/";
        Map<String, byte[]> mod = BridgeHarness.only(BridgeHarness.compile(dir.resolve("old"), Map.of(
                api + "ExtendedScreenHandlerType.java", """
                        package net.fabricmc.fabric.api.screenhandler.v1;
                        public class ExtendedScreenHandlerType<T extends net.minecraft.class_1703> {
                            public interface ExtendedFactory<T extends net.minecraft.class_1703> {
                                T create(int id, net.minecraft.class_1661 inventory, net.minecraft.class_2540 buf);
                            }
                            public ExtendedScreenHandlerType(ExtendedFactory<T> factory) {}
                        }""",
                "net/minecraft/class_1661.java", "package net.minecraft; public class class_1661 {}",
                "net/minecraft/class_2540.java", "package net.minecraft; public class class_2540 {}",
                "net/minecraft/class_1703.java", "package net.minecraft; public class class_1703 {}",
                "mod/MenuType.java", """
                        package mod;
                        public class MenuType extends net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerType<net.minecraft.class_1703> {
                            public MenuType() { super((id, inventory, buf) -> null); }
                        }""")), "mod/");
        Map<String, byte[]> host = BridgeHarness.compile(dir.resolve("host"), Map.of(
                api + "ExtendedScreenHandlerType.java", """
                        package net.fabricmc.fabric.api.screenhandler.v1;
                        public class ExtendedScreenHandlerType<T extends net.minecraft.class_1703, D> {
                            public interface ExtendedFactory<T extends net.minecraft.class_1703, D> { T create(int id, net.minecraft.class_1661 inventory, D data); }
                            public final Object codec;
                            public ExtendedScreenHandlerType(ExtendedFactory<T, D> factory, net.minecraft.class_9139<?, D> codec) { this.codec = codec; }
                        }""",
                "net/minecraft/class_9139.java", """
                        package net.minecraft;
                        public interface class_9139<B, V> { V decode(Object buf); void encode(Object buf, Object value); }""",
                "net/minecraft/class_1661.java", "package net.minecraft; public class class_1661 {}",
                "net/minecraft/class_1703.java", "package net.minecraft; public class class_1703 {}",
                "io/netty/buffer/ByteBuf.java", "package io.netty.buffer; public abstract class ByteBuf {}",
                "io/netty/buffer/Unpooled.java", "package io.netty.buffer; public final class Unpooled {}",
                "net/minecraft/class_2540.java", "package net.minecraft; public abstract class class_2540 extends io.netty.buffer.ByteBuf {}",
                "net/minecraft/class_9129.java", "package net.minecraft; public abstract class class_9129 extends class_2540 {}",
                "net/minecraft/class_5455.java", "package net.minecraft; public interface class_5455 {}"));
        Map<String, byte[]> transformed = BridgeHarness.transform("1.21.1", Pre1_20_5ScreenHandlerBridge::registerRedirects, mod);
        BridgeHarness.Run run = new BridgeHarness.Run(host, transformed);
        Object type = run.loadClass("mod.MenuType").getConstructor().newInstance();
        Field codec = type.getClass().getSuperclass().getField("codec");
        assertEquals("com.retromod.generated.LegacyScreenOpeningDataCodec", codec.get(type).getClass().getName());
    }

    // ---- the stand-in host ----

    /** Stand-in host classes compiled from source, beside the rebuilt classes, in a loader of their own. */
    private static final class Host {
        final ClassLoader loader;
        private final Map<String, byte[]> classes = new HashMap<>();

        Host(Path dir, Map<String, String> sources) throws Exception {
            BridgeHarness.compile(dir, sources).forEach((name, bytes) -> classes.put(name.replace('/', '.'), bytes));
            loader = new ClassLoader(LegacyClientApiRuntimeTest.class.getClassLoader()) {
                @Override
                protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded != null) return loaded;
                    byte[] bytes = classes.get(name);
                    try {
                        // The rebuilt classes come from their own build output, not the copies on the test classpath.
                        if (bytes == null && name.startsWith(ROOT)) bytes = Files.readAllBytes(COMPILED.resolve(name.replace('.', '/') + ".class"));
                    } catch (java.io.IOException absent) {
                        throw new ClassNotFoundException(name, absent);
                    }
                    // Host types a method names but this test never runs only need to exist for the verifier.
                    if (bytes == null && isHost(name)) bytes = empty(name.replace('.', '/'));
                    if (bytes == null) return super.loadClass(name, resolve);
                    return defineClass(name, bytes, 0, bytes.length);
                }
            };
        }

        Class<?> type(String name) throws ClassNotFoundException {
            return Class.forName(name, true, loader);
        }

        Object make(String name) throws Exception {
            return type(name).getConstructor().newInstance();
        }
    }

    private static boolean isHost(String name) {
        return name.startsWith("net.minecraft.") || name.startsWith("net.neoforged.") || name.startsWith("net.fabricmc.")
                || name.startsWith("com.mojang.") || name.startsWith("org.joml.");
    }

    private static byte[] empty(String name) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static Object call(Class<?> owner, String name, Object argument) throws Exception {
        for (Method method : owner.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == 1) return method.invoke(null, argument);
        }
        throw new NoSuchMethodException(name);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> values(Object compound) throws Exception {
        return (Map<String, Object>) compound.getClass().getField("values").get(compound);
    }

    private static String path(Object identifier) throws Exception {
        return identifier == null ? null : (String) identifier.getClass().getField("path").get(identifier);
    }

    private static Method privateMethod(Class<?> owner, String name, Class<?>... params) throws Exception {
        Method method = owner.getDeclaredMethod(name, params);
        method.setAccessible(true);
        return method;
    }
}
