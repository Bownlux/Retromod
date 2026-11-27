/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** The rebuilt getOrCreateTag on a stand-in registry before tags bind, after they bind, and once frozen. */
class LegacyRegistryTagsRuntimeTest {

    private static final Path HELPER = Path.of("target", "legacyapi-classes", LegacyGameplayCallsBridge.REGISTRY_TAGS + ".class");

    /** Stand-ins for the 26.x registry types, with the tag behavior MappedRegistry has. */
    private static final Map<String, String> HOST = Map.of(
            "net/minecraft/tags/TagKey.java", "package net.minecraft.tags; public record TagKey<T>(String id) {}",
            "net/minecraft/core/HolderOwner.java", "package net.minecraft.core; public interface HolderOwner<T> {}",
            "net/minecraft/core/HolderSet.java", "package net.minecraft.core; public interface HolderSet<T> {"
                    + " static <T> Named<T> emptyNamed(HolderOwner<T> owner, net.minecraft.tags.TagKey<T> key) { return new Named<>(key, \"empty\"); }"
                    + " class Named<T> implements HolderSet<T> { public final net.minecraft.tags.TagKey<T> key; public final String origin;"
                    + "  public Named(net.minecraft.tags.TagKey<T> key, String origin) { this.key = key; this.origin = origin; } } }",
            "net/minecraft/core/HolderGetter.java", "package net.minecraft.core; public interface HolderGetter<T> extends HolderOwner<T> {"
                    + " java.util.Optional<HolderSet.Named<T>> get(net.minecraft.tags.TagKey<T> tag);"
                    + " default HolderSet.Named<T> getOrThrow(net.minecraft.tags.TagKey<T> tag) { return get(tag).orElseThrow(); } }",
            "net/minecraft/core/Registry.java", "package net.minecraft.core; public interface Registry<T> extends HolderGetter<T> {}",
            "net/minecraft/core/WritableRegistry.java", "package net.minecraft.core; public interface WritableRegistry<T> extends Registry<T> {"
                    + " HolderGetter<T> createRegistrationLookup(); }",
            // Unbound tags throw on lookup, registration keeps a requested tag, and a frozen registry refuses registration.
            "net/minecraft/core/StandInRegistry.java", "package net.minecraft.core; import net.minecraft.tags.TagKey;"
                    + " public class StandInRegistry<T> implements WritableRegistry<T> {"
                    + " public boolean bound, frozen; public final java.util.Map<TagKey<T>, HolderSet.Named<T>> tags = new java.util.HashMap<>();"
                    + " public java.util.Optional<HolderSet.Named<T>> get(TagKey<T> tag) {"
                    + "  if (!bound) throw new IllegalStateException(\"Tags not bound\"); return java.util.Optional.ofNullable(tags.get(tag)); }"
                    + " public HolderGetter<T> createRegistrationLookup() {"
                    + "  if (frozen) throw new IllegalStateException(\"Registry is already frozen\");"
                    + "  return tag -> java.util.Optional.of(tags.computeIfAbsent(tag, key -> new HolderSet.Named<>(key, \"registration\"))); } }");

    @Test
    @DisplayName("before tags bind, the tag is registered with the registry so the tag load fills it")
    void unboundRegistryRegistersTheTag(@TempDir Path dir) throws Throwable {
        Game game = new Game(dir);
        Object registry = game.registry(false, false);
        Object named = game.getOrCreateTag(registry, "c:ores");

        assertEquals("registration", game.origin(named));
        assertSame(named, game.tags(registry).values().iterator().next(), "the registry keeps the tag it handed out");
    }

    @Test
    @DisplayName("a bound tag is returned as it is")
    void boundTagIsReturned(@TempDir Path dir) throws Throwable {
        Game game = new Game(dir);
        Object registry = game.registry(true, true);
        Object named = game.getOrCreateTag(registry, "c:ores");
        assertEquals("empty", game.origin(named), "a frozen registry without the tag answers an empty set");

        Object bound = game.named("c:gems", "bound");
        game.tags(registry).put(game.key("c:gems"), bound);
        assertSame(bound, game.getOrCreateTag(registry, "c:gems"));
    }

    @Test
    @DisplayName("a bound but unfrozen registry still registers a missing tag")
    void unfrozenRegistryRegistersMissingTag(@TempDir Path dir) throws Throwable {
        Game game = new Game(dir);
        Object named = game.getOrCreateTag(game.registry(true, false), "c:ores");
        assertEquals("registration", game.origin(named));
    }

    /** The stand-in registry types and the rebuilt helper, in a class loader of their own. */
    private static final class Game {
        private final ClassLoader loader;

        Game(Path dir) throws Exception {
            Map<String, byte[]> classes = compile(dir);
            classes.put(LegacyGameplayCallsBridge.REGISTRY_TAGS, Files.readAllBytes(HELPER));
            loader = new ClassLoader(ClassLoader.getPlatformClassLoader()) {
                @Override
                protected Class<?> findClass(String name) throws ClassNotFoundException {
                    byte[] bytes = classes.get(name.replace('.', '/'));
                    if (bytes == null) throw new ClassNotFoundException(name);
                    return defineClass(name, bytes, 0, bytes.length);
                }
            };
        }

        Class<?> type(String name) throws ClassNotFoundException {
            return Class.forName(name.replace('/', '.'), true, loader);
        }

        Object registry(boolean bound, boolean frozen) throws Exception {
            Object registry = type("net/minecraft/core/StandInRegistry").getConstructor().newInstance();
            registry.getClass().getField("bound").setBoolean(registry, bound);
            registry.getClass().getField("frozen").setBoolean(registry, frozen);
            return registry;
        }

        Object key(String id) throws Exception {
            return type("net/minecraft/tags/TagKey").getConstructor(String.class).newInstance(id);
        }

        Object named(String id, String origin) throws Exception {
            return type("net/minecraft/core/HolderSet$Named").getConstructor(type("net/minecraft/tags/TagKey"), String.class)
                    .newInstance(key(id), origin);
        }

        @SuppressWarnings("unchecked")
        Map<Object, Object> tags(Object registry) throws Exception {
            return (Map<Object, Object>) registry.getClass().getField("tags").get(registry);
        }

        String origin(Object named) throws Exception {
            return (String) named.getClass().getField("origin").get(named);
        }

        Object getOrCreateTag(Object registry, String id) throws Throwable {
            try {
                return type(LegacyGameplayCallsBridge.REGISTRY_TAGS)
                        .getMethod("getOrCreateTag", type("net/minecraft/core/Registry"), type("net/minecraft/tags/TagKey"))
                        .invoke(null, registry, key(id));
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
        }

        private static Map<String, byte[]> compile(Path dir) throws Exception {
            Path src = dir.resolve("src");
            Path out = dir.resolve("out");
            List<String> args = new ArrayList<>(List.of("-d", out.toString(), "--release", "17", "-proc:none"));
            for (Map.Entry<String, String> source : HOST.entrySet()) {
                Path file = src.resolve(source.getKey());
                Files.createDirectories(file.getParent());
                Files.writeString(file, source.getValue());
                args.add(file.toString());
            }
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            assertNotNull(compiler, "the test needs a JDK to compile the stand-ins");
            assertEquals(0, compiler.run(null, null, null, args.toArray(new String[0])), "the stand-ins must compile");
            Map<String, byte[]> classes = new HashMap<>();
            try (Stream<Path> files = Files.walk(out)) {
                for (Path file : files.filter(f -> f.toString().endsWith(".class")).toList()) {
                    String name = out.relativize(file).toString().replace(File.separatorChar, '/');
                    classes.put(name.substring(0, name.length() - ".class".length()), Files.readAllBytes(file));
                }
            }
            return classes;
        }
    }
}
