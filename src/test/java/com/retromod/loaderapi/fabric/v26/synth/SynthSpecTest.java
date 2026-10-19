/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi.fabric.v26.synth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The spec generators produce verifiable classes that behave as their specs say. */
class SynthSpecTest {

    private static final String PKG = "com/retromod/loaderapi/fabric/v26/synth/";

    /** The host shape a bridge adapts to. */
    public interface HostCallback {
        String handle(Object first, String second, int count);
    }

    /** A host class holding an event field. */
    public static final class HostEvents {
        public static final net.fabricmc.fabric.api.event.Event<Runnable> EVENT =
                new net.fabricmc.fabric.api.event.Event<>() {
                    @Override
                    public void register(Runnable listener) {
                    }

                    @Override
                    public Runnable invoker() {
                        return () -> { };
                    }
                };

        private HostEvents() {}
    }

    /** A helper taking Object, as compiled helpers do. */
    public static final class Helper {
        private Helper() {}

        public static Object upper(Object text) {
            return ((String) text).toUpperCase(java.util.Locale.ROOT);
        }

        public static String describe(Object value) {
            return "converted:" + value;
        }
    }

    @Test
    @DisplayName("A SAM bridge reorders, converts and fills arguments for the old method")
    void samBridgeAdaptsArguments() throws Throwable {
        String name = PKG + "TestOldCallback";
        SamBridgeSpec spec = new SamBridgeSpec(name,
                List.of(internalName(HostCallback.class)), "oldHandle",
                "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;I)Ljava/lang/String;",
                "handle", "(Ljava/lang/Object;Ljava/lang/String;I)Ljava/lang/String;",
                List.of(new SamBridgeSpec.HostArg(1),
                        new SamBridgeSpec.ConvertedArg(0, internalName(Helper.class), "describe",
                                "(Ljava/lang/Object;)Ljava/lang/String;", false),
                        new SamBridgeSpec.NullArg(),
                        new SamBridgeSpec.HostArg(2)),
                SamBridgeSpec.Result.SAME);
        byte[] bytes = spec.generate();
        verify(bytes);

        Class<?> bridge = define(name, bytes);
        InvocationHandler handler = (proxy, method, args) -> method.getName().equals("oldHandle")
                ? args[0] + "|" + args[1] + "|" + args[2] + "|" + args[3]
                : InvocationHandler.invokeDefault(proxy, method, args);
        HostCallback callback = (HostCallback) Proxy.newProxyInstance(bridge.getClassLoader(),
                new Class<?>[]{bridge}, handler);
        assertEquals("b|converted:a|null|3", callback.handle("a", "b", 3));
    }

    @Test
    @DisplayName("An event holder hands out the host's own event object")
    void eventHolderCopiesHostField() throws Exception {
        String name = PKG + "TestHolder";
        byte[] bytes = new EventHolderSpec(name, List.of(
                new EventHolderSpec.EventField("EVENT", HostEvents.class.getName(), "EVENT")))
                .generate();
        verifyStructure(bytes);
        Class<?> holder = define(name, bytes);
        assertSame(HostEvents.EVENT, holder.getField("EVENT").get(null),
                "mods must register into the host event, not a copy");
    }

    @Test
    @DisplayName("A static forward casts typed arguments to a helper's Object signature")
    void staticForwardCasts() throws Exception {
        String name = PKG + "TestForward";
        byte[] bytes = new StaticForwardSpec(name, List.of(new StaticForwardSpec.Forward("upper",
                "(Ljava/lang/String;)Ljava/lang/String;", internalName(Helper.class), "upper",
                "(Ljava/lang/Object;)Ljava/lang/Object;", false))).generate();
        verify(bytes);
        Class<?> forward = define(name, bytes);
        assertEquals("ABC", forward.getMethod("upper", String.class).invoke(null, "abc"));
    }

    @Test
    @DisplayName("A boundary type refuses shared names outside a shared package, and an erased method named like the old one")
    void boundarySpecGuardsNames() {
        assertThrows(IllegalArgumentException.class, () -> new BoundaryTypeSpec("old/Color",
                "com/retromod/loaderapi/fabric/v26/Color", "getColor", "()I", "colorOf", "()I",
                PKG + "Bridge"));
        assertThrows(IllegalArgumentException.class, () -> new BoundaryTypeSpec("old/Color",
                "com/retromod/loaderapi/shared/TestColor", "getColor", "()I", "getColor", "()I",
                PKG + "Bridge"));
        BoundaryTypeSpec spec = new BoundaryTypeSpec("old/Color",
                "com/retromod/loaderapi/shared/TestColor", "getColor",
                "(Ljava/lang/String;Ljava/lang/Integer;Ljava/lang/Long;I)I", "colorOf",
                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;I)I", PKG + "TestColorBridge2");
        verify(spec.generateBridge());
    }

    @Test
    @DisplayName("An event holder leaves a missing host field null and says so")
    void eventHolderMissingHost() throws Exception {
        String name = PKG + "TestHolderMissing";
        byte[] bytes = new EventHolderSpec(name, List.of(
                new EventHolderSpec.EventField("GONE", "com.retromod.missing.Host", "EVENT"))).generate();
        Class<?> holder = define(name, bytes);
        PrintStream previous = System.err;
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        System.setErr(new PrintStream(err, true));
        try {
            assertNull(holder.getField("GONE").get(null));
        } finally {
            System.setErr(previous);
        }
        assertTrue(err.toString().contains("Could not connect TestHolderMissing.GONE"), err.toString());
    }

    private static String internalName(Class<?> type) {
        return org.objectweb.asm.Type.getInternalName(type);
    }

    private static void verify(byte[] bytes) {
        StringWriter out = new StringWriter();
        CheckClassAdapter.verify(new ClassReader(bytes), SynthSpecTest.class.getClassLoader(), false,
                new PrintWriter(out));
        assertEquals("", out.toString(), "generated class failed verification");
    }

    private static void verifyStructure(byte[] bytes) {
        new ClassReader(bytes).accept(new CheckClassAdapter(null, false), 0);
    }

    private static Class<?> define(String internalName, byte[] bytes) {
        return new ClassLoader(SynthSpecTest.class.getClassLoader()) {
            Class<?> load() {
                return defineClass(internalName.replace('/', '.'), bytes, 0, bytes.length);
            }
        }.load();
    }
}
