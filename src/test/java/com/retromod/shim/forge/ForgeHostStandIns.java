/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Compiles small stand-ins for Forge 26.2 host classes and loads translated classes against them, apart from the test classpath. */
final class ForgeHostStandIns {

    private final Map<String, String> sources = new HashMap<>();
    private final List<Path> classpath = new ArrayList<>();

    /** Adds one stand-in, as {@code "net/minecraft/Foo.java" -> source}. */
    ForgeHostStandIns add(String path, String source) {
        sources.put(path, source);
        return this;
    }

    /** Puts jars or class folders on the compile classpath, for stand-ins that name real host types. */
    ForgeHostStandIns classpath(Path... entries) {
        classpath.addAll(List.of(entries));
        return this;
    }

    /** The internal names of the classes the sources declare at top level. */
    List<String> names() {
        return sources.keySet().stream().map(path -> path.substring(0, path.length() - ".java".length())).toList();
    }

    /** The compiled sources by internal name, without loading them. */
    Map<String, byte[]> compiled(Path workDir) throws Exception {
        Map<String, byte[]> classes = new HashMap<>();
        compile(workDir).forEach((name, bytes) -> classes.put(name.replace('.', '/'), bytes));
        return classes;
    }

    /**
     * A loader holding the compiled stand-ins, the given classes, and every synthetic the transformer
     * registered. Its parent is the platform loader, so no host class leaks in from the test classpath.
     */
    ClassLoader loader(Path workDir, RetromodTransformer transformer, Map<String, byte[]> classes) throws Exception {
        return loader(workDir, transformer, name -> true, classes);
    }

    /** As {@link #loader(Path, RetromodTransformer, Map)}, with only the synthetics {@code embedded} accepts, as a jar carries only those it names. */
    ClassLoader loader(Path workDir, RetromodTransformer transformer, java.util.function.Predicate<String> embedded,
            Map<String, byte[]> classes) throws Exception {
        Map<String, byte[]> all = new HashMap<>(compile(workDir));
        transformer.getSyntheticClasses().forEach((name, bytes) -> {
            if (embedded.test(name)) all.put(name.replace('/', '.'), bytes);
        });
        classes.forEach((name, bytes) -> all.put(name.replace('/', '.'), bytes));
        return new ClassLoader(ClassLoader.getPlatformClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = all.get(name);
                if (bytes == null) throw new ClassNotFoundException(name);
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
    }

    private Map<String, byte[]> compile(Path workDir) throws Exception {
        Path src = workDir.resolve("src");
        Path out = workDir.resolve("out");
        List<String> args = new ArrayList<>(List.of("-d", out.toString(), "--release", "17"));
        if (!classpath.isEmpty()) {
            args.add("-cp");
            args.add(String.join(File.pathSeparator, classpath.stream().map(Path::toString).toList()));
        }
        for (Map.Entry<String, String> source : sources.entrySet()) {
            Path file = src.resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
            args.add(file.toString());
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "the tests need a JDK to compile the Forge host stand-ins");
        assertEquals(0, compiler.run(null, null, null, args.toArray(new String[0])),
                "the Forge host stand-ins must compile");
        Map<String, byte[]> classes = new HashMap<>();
        try (var files = Files.walk(out)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".class"))::iterator) {
                String name = out.relativize(file).toString().replace(File.separatorChar, '.');
                classes.put(name.substring(0, name.length() - ".class".length()), Files.readAllBytes(file));
            }
        }
        return classes;
    }
}
