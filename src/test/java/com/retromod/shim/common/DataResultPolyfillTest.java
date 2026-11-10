/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.polyfill.minecraft.DataResultPolyfill;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Balm's LoadedTableConfig and 22 other Forge 1.20.1 jars call DataResult.getOrThrow(boolean, Consumer), which 1.20.5 removed. */
class DataResultPolyfillTest {

    private static final Path DFU = Path.of(System.getProperty("user.home"),
            "Library/Application Support/PrismLauncher/libraries/com/mojang/datafixerupper");

    @Test
    @DisplayName("DataFixerUpper 6.0.8 has the method; 9.0.19 and 10.0.21 do not")
    void realDataFixerUpperJars() throws Exception {
        ClassNode dfu6 = readDataResult("6.0.8");
        ClassNode dfu9 = readDataResult("9.0.19");
        ClassNode dfu10 = readDataResult("10.0.21");
        assertFalse(LegacyDfuRenames.lacksOldGetOrThrow(name -> dfu6, true));
        assertTrue(LegacyDfuRenames.lacksOldGetOrThrow(name -> dfu9, false));
        assertTrue(LegacyDfuRenames.lacksOldGetOrThrow(name -> dfu10, false));
    }

    @Test
    @DisplayName("the helper on DataFixerUpper 9 and 10 answers exactly as DataFixerUpper 6's own method did")
    void helperMatchesDataFixerUpper6() throws Exception {
        try (URLClassLoader old = dfu("6.0.8"); URLClassLoader v9 = dfu("9.0.19"); URLClassLoader v10 = dfu("10.0.21")) {
            for (String scenario : List.of("success", "null success", "error", "error with partial", "throwing consumer")) {
                for (boolean allowPartial : new boolean[]{true, false}) {
                    String expected = viaOldMethod(old, scenario, allowPartial);
                    String label = scenario + ", allowPartial " + allowPartial;
                    assertEquals(expected, viaHelper(v9, scenario, allowPartial), "DFU 9: " + label);
                    assertEquals(expected, viaHelper(v10, scenario, allowPartial), "DFU 10: " + label);
                }
            }
        }
    }

    @Test
    @DisplayName("without a real DataFixerUpper the helper still keeps DFU 6's order: report once, then partial or throw")
    void helperOnAStandIn() {
        List<String> reported = new ArrayList<>();
        assertEquals("value", DataResultPolyfill.getOrThrow(StandInResult.success("value"), false, reported::add));
        assertEquals(List.of(), reported, "a success never reports");

        assertEquals("partial", DataResultPolyfill.getOrThrow(StandInResult.error("bad", "partial"), true, reported::add));
        assertEquals(List.of("bad"), reported);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> DataResultPolyfill.getOrThrow(StandInResult.error("worse", "partial"), false, reported::add));
        assertEquals("worse", thrown.getMessage());
        assertEquals(RuntimeException.class, thrown.getClass());
        assertEquals(List.of("bad", "worse"), reported);
    }

    /** A result shaped like DataFixerUpper 9's: the helper only calls these three methods. */
    public record StandInResult(Object value, String message, Object partial, boolean ok) {
        static StandInResult success(Object value) {
            return new StandInResult(value, null, null, true);
        }

        static StandInResult error(String message, Object partial) {
            return new StandInResult(null, message, partial, false);
        }

        public boolean isSuccess() {
            return ok;
        }

        public Object getOrThrow(Function<String, ? extends RuntimeException> onError) {
            if (ok) return value;
            throw onError.apply(message);
        }

        public Optional<Object> resultOrPartial(Consumer<String> onError) {
            if (ok) return Optional.ofNullable(value);
            onError.accept(message);
            return Optional.ofNullable(partial);
        }
    }

    /** One scenario through DataFixerUpper 6's own getOrThrow(boolean, Consumer). */
    private static String viaOldMethod(ClassLoader dfu, String scenario, boolean allowPartial) throws Exception {
        Recorder recorder = new Recorder(scenario);
        Object result = build(dfu, scenario, recorder);
        Method method = result.getClass().getMethod("getOrThrow", boolean.class, Consumer.class);
        return recorder.outcome(() -> method.invoke(result, allowPartial, recorder.consumer()));
    }

    /** The same scenario through Retromod's helper on a newer DataFixerUpper. */
    private static String viaHelper(ClassLoader dfu, String scenario, boolean allowPartial) throws Exception {
        Recorder recorder = new Recorder(scenario);
        Object result = build(dfu, scenario, recorder);
        return recorder.outcome(() -> DataResultPolyfill.getOrThrow(result, allowPartial, recorder.consumer()));
    }

    private static Object build(ClassLoader dfu, String scenario, Recorder recorder) throws Exception {
        Class<?> type = Class.forName("com.mojang.serialization.DataResult", true, dfu);
        Supplier<String> message = () -> {
            recorder.messageReads.incrementAndGet();
            return "bad input";
        };
        return switch (scenario) {
            case "success" -> type.getMethod("success", Object.class).invoke(null, "value");
            case "null success" -> type.getMethod("success", Object.class).invoke(null, (Object) null);
            case "error with partial" -> type.getMethod("error", Supplier.class, Object.class).invoke(null, message, "partial");
            default -> type.getMethod("error", Supplier.class).invoke(null, message);
        };
    }

    /** Collects what a call did: its return or exception, what the consumer saw, and how often the message was built. */
    private static final class Recorder {
        final String scenario;
        final List<String> reported = new ArrayList<>();
        final AtomicInteger messageReads = new AtomicInteger();

        Recorder(String scenario) {
            this.scenario = scenario;
        }

        Consumer<String> consumer() {
            return text -> {
                reported.add(text);
                if (scenario.equals("throwing consumer")) throw new IllegalArgumentException("consumer: " + text);
            };
        }

        String outcome(Call call) {
            String result;
            try {
                result = "returned " + call.run();
            } catch (InvocationTargetException wrapped) {
                result = "threw " + wrapped.getCause();
            } catch (Throwable thrown) {
                result = "threw " + thrown;
            }
            return result + ", reported " + reported + ", message built " + messageReads.get() + "x";
        }
    }

    @FunctionalInterface
    private interface Call {
        Object run() throws Exception;
    }

    private static URLClassLoader dfu(String version) throws Exception {
        Path jar = DFU.resolve(version).resolve("datafixerupper-" + version + ".jar");
        assumeTrue(Files.isRegularFile(jar), "DataFixerUpper " + version + " is not installed in PrismLauncher");
        return new URLClassLoader(new URL[]{jar.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
    }

    private static ClassNode readDataResult(String version) throws Exception {
        Path jar = DFU.resolve(version).resolve("datafixerupper-" + version + ".jar");
        assumeTrue(Files.isRegularFile(jar), "DataFixerUpper " + version + " is not installed in PrismLauncher");
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ClassNode node = new ClassNode();
            new ClassReader(zip.getInputStream(zip.getEntry("com/mojang/serialization/DataResult.class")).readAllBytes())
                    .accept(node, ClassReader.SKIP_CODE);
            return node;
        }
    }
}
