/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi.fabric.v26.net;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** A fake 26.x host in its own class loader. */
public final class FakeHost implements AutoCloseable {

    // Mirrors of the Minecraft, netty and Fabric API classes by their 26.x names, with only the
    // members Retromod's helpers and the fixtures call. FakeWire stands in for the connection.
    private static final Path SOURCES = Path.of("src/test/resources/loaderapi/fakehost");
    private static Path compiled;

    private final URLClassLoader loader;

    private FakeHost(URLClassLoader loader) {
        this.loader = loader;
    }

    /** A fresh host: new class loader, so every static map starts empty. */
    public static FakeHost start() throws IOException {
        List<URL> urls = new ArrayList<>();
        urls.add(compiledHost().toUri().toURL());
        // Retromod's classes load again in this loader, so their host lookups find the fakes.
        urls.add(Path.of("target/classes").toUri().toURL());
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            if (entry.contains("/org/ow2/asm/") || entry.contains("/org/slf4j/")
                    || entry.contains("/com/google/code/gson/")) {
                urls.add(Path.of(entry).toUri().toURL());
            }
        }
        return new FakeHost(new URLClassLoader(urls.toArray(new URL[0]),
                ClassLoader.getPlatformClassLoader()));
    }

    /** Compiles the fake host once per JVM. */
    private static synchronized Path compiledHost() throws IOException {
        if (compiled != null) return compiled;
        // Compiled apart from the test class path, so no other test sees a net/minecraft class.
        Path out = Files.createTempDirectory("retromod-fakehost");
        List<String> args = new ArrayList<>(List.of("-d", out.toString(), "--release", "17",
                "-nowarn", "-Xlint:none"));
        try (Stream<Path> files = Files.walk(SOURCES)) {
            files.filter(path -> path.toString().endsWith(".java"))
                    .forEach(path -> args.add(path.toString()));
        }
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        int status = javac.run(null, null, errors, args.toArray(new String[0]));
        if (status != 0) throw new IllegalStateException("The fake host did not compile:\n" + errors);
        compiled = out;
        return out;
    }

    public Class<?> type(String binaryName) throws ClassNotFoundException {
        return Class.forName(binaryName, true, loader);
    }

    /** Calls a public static method by name and argument count. */
    Object call(String className, String method, Object... args) throws Throwable {
        for (Method candidate : type(className).getMethods()) {
            if (candidate.getName().equals(method) && candidate.getParameterCount() == args.length) {
                try {
                    return candidate.invoke(null, args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            }
        }
        throw new NoSuchMethodException(className + "." + method + " with " + args.length + " arguments");
    }

    /** Reads a public static field. */
    Object field(String className, String name) throws ReflectiveOperationException {
        Field field = type(className).getField(name);
        return field.get(null);
    }

    /** A fake {@code Identifier}. */
    Object id(String text) throws Throwable {
        String[] parts = text.split(":", 2);
        return call("net.minecraft.resources.Identifier", "fromNamespaceAndPath", parts[0], parts[1]);
    }

    /** What crossed the fake wire. */
    @SuppressWarnings("unchecked")
    List<String> wire() throws ReflectiveOperationException {
        return List.copyOf((List<String>) field("net.fabricmc.fabric.impl.networking.FakeWire", "LOG"));
    }

    /** What the fixtures' handlers recorded. */
    @SuppressWarnings("unchecked")
    List<String> events() throws ReflectiveOperationException {
        return List.copyOf((List<String>) field("fixture.Recorder", "EVENTS"));
    }

    /** Defines translated mod classes on top of the host, the way Knot loads a mod jar. */
    public ClassLoader defineMod(Map<String, byte[]> classes) {
        return new ClassLoader(loader) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classes.get(name.replace('.', '/'));
                if (bytes == null) throw new ClassNotFoundException(name);
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
    }

    public ClassLoader loader() {
        return loader;
    }

    @Override
    public void close() throws IOException {
        loader.close();
    }
}
