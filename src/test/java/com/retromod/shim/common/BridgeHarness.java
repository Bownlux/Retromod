/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Compiles old mod code against one host shape, transforms it, and runs it against another. */
final class BridgeHarness {

    private BridgeHarness() {}

    /** Compiles Java sources keyed by file path, such as {@code mod/Calls.java}, into classes keyed by internal name. */
    static Map<String, byte[]> compile(Path dir, Map<String, String> sources) throws Exception {
        return compile(dir, sources, null);
    }

    /** The same, against the jars of {@code classpath} too, such as a real DataFixerUpper. */
    static Map<String, byte[]> compile(Path dir, Map<String, String> sources, String classpath) throws Exception {
        Path src = dir.resolve("src");
        Path out = dir.resolve("out");
        List<String> args = new ArrayList<>(List.of("-d", out.toString(), "--release", "17", "-nowarn"));
        if (classpath != null) args.addAll(List.of("-cp", classpath));
        for (Map.Entry<String, String> source : sources.entrySet()) {
            Path file = src.resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
            args.add(file.toString());
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "the tests need a JDK to compile the stand-ins");
        assertEquals(0, compiler.run(null, null, null, args.toArray(new String[0])), "the stand-ins must compile");
        Map<String, byte[]> classes = new HashMap<>();
        try (var files = Files.walk(out)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".class"))::iterator) {
                String name = out.relativize(file).toString().replace(java.io.File.separatorChar, '/');
                classes.put(name.substring(0, name.length() - ".class".length()), Files.readAllBytes(file));
            }
        }
        return classes;
    }

    /** The classes of {@code compiled} whose internal names start with {@code prefix}. */
    static Map<String, byte[]> only(Map<String, byte[]> compiled, String prefix) {
        Map<String, byte[]> selected = new HashMap<>();
        compiled.forEach((name, bytes) -> {
            if (name.startsWith(prefix)) selected.put(name, bytes);
        });
        return selected;
    }

    /** {@code mod}, transformed for a host of {@code target} after {@code register} registered its rules. */
    static Map<String, byte[]> transform(String target, Consumer<RetromodTransformer> register,
            Map<String, byte[]> mod) {
        return transform(target, register, mod, Map.of());
    }

    /** The same, with {@code host} classes visible to hierarchy proofs as an installed game's are. */
    static Map<String, byte[]> transform(String target, Consumer<RetromodTransformer> register,
            Map<String, byte[]> mod, Map<String, byte[]> host) {
        RetromodVersion.TARGET_MC_VERSION = target;
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        register.accept(transformer);
        Map<String, byte[]> transformed = new HashMap<>();
        Map<String, byte[]> visible = new HashMap<>(host);
        visible.putAll(mod);
        try (var scope = transformer.pushJarClassBytesProvider(visible::get)) {
            mod.forEach((name, bytes) -> transformed.put(name, transformer.transformClass(bytes, name)));
        }
        return transformed;
    }

    /** Each call in {@code method}, as {@code owner.name(desc)}. */
    static List<String> calls(byte[] bytes, String method) {
        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode insn : method(bytes, method).instructions.toArray()) {
            if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name + call.desc);
        }
        return calls;
    }

    /** Each field access in {@code method}, as {@code owner.name:desc}. */
    static List<String> fields(byte[] bytes, String method) {
        List<String> fields = new ArrayList<>();
        for (AbstractInsnNode insn : method(bytes, method).instructions.toArray()) {
            if (insn instanceof FieldInsnNode field) fields.add(field.owner + "." + field.name + ":" + field.desc);
        }
        return fields;
    }

    static MethodNode method(byte[] bytes, String name) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }

    /** One loader over a host's stand-ins, the mod and Retromod's helpers, which wins over the test's own classes. */
    static final class Run extends ClassLoader {
        private final Map<String, byte[]> classes = new HashMap<>();

        Run(Map<String, byte[]> host, Map<String, byte[]> mod) {
            super(BridgeHarness.class.getClassLoader());
            host.forEach((name, bytes) -> classes.put(name.replace('/', '.'), bytes));
            mod.forEach((name, bytes) -> classes.put(name.replace('/', '.'), bytes));
            RetromodTransformer.getInstance().getSyntheticClasses()
                    .forEach((name, bytes) -> classes.put(name.replace('/', '.'), bytes));
        }

        @Override
        protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            Class<?> loaded = findLoadedClass(name);
            if (loaded != null) return loaded;
            byte[] bytes = classes.get(name);
            if (bytes == null) return super.loadClass(name, resolve);
            return defineClass(name, bytes, 0, bytes.length);
        }

        /** Calls the static method {@code owner.name}, rethrowing what it throws. */
        Object call(String owner, String name, Object... args) throws Throwable {
            for (Method method : loadClass(owner.replace('/', '.')).getMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == args.length) {
                    try {
                        return method.invoke(null, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                }
            }
            throw new AssertionError("no method " + owner + "." + name);
        }
    }
}
