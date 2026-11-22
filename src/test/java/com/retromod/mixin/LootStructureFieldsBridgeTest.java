/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mixin;

import com.retromod.mixin.runtime.LootStructureFieldsBridge;
import com.retromod.mixin.runtime.RecipeManagerFieldsBridge;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The bridges read 26.3's loot holders and recipe map back in the shapes old accessors returned, on stand-in classes. */
class LootStructureFieldsBridgeTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("functions, conditions, tag, contents and byType come back in their old shapes")
    void oldShapesFromTheNewFields() throws Exception {
        try (URLClassLoader host = host()) {
            Object fn = new Object();
            Object holder = host.loadClass("net.minecraft.core.Holder").getMethod("direct", Object.class).invoke(null, fn);
            Object pool = host.loadClass("stand.Pool").getConstructor(Object.class).newInstance(holder);
            assertEquals(List.of(fn), LootStructureFieldsBridge.functions(pool), "the one modifier, as a list");
            assertEquals(List.of(fn), LootStructureFieldsBridge.conditions(pool), "the one condition, as a list");
            assertEquals(List.of(), LootStructureFieldsBridge.functions(host.loadClass("stand.Pool").getConstructor(Object.class)
                    .newInstance((Object) null)), "no modifier is an empty list");

            Object tagEntry = host.loadClass("stand.Tag").getConstructor(String.class).newInstance("minecraft:logs");
            assertEquals("minecraft:logs", LootStructureFieldsBridge.tag(tagEntry));

            Object keyed = host.loadClass("stand.Nested").getConstructor(Object.class, String.class).newInstance("table", "minecraft:chests/x");
            assertEquals("left:minecraft:chests/x", String.valueOf(LootStructureFieldsBridge.contents(keyed)));
            Object inline = host.loadClass("stand.Nested").getConstructor(Object.class, String.class).newInstance("table", null);
            assertEquals("right:table", String.valueOf(LootStructureFieldsBridge.contents(inline)));

            Object manager = host.loadClass("net.minecraft.world.item.crafting.RecipeManager").getConstructor().newInstance();
            assertEquals("multimap", RecipeManagerFieldsBridge.byType(manager));
        }
    }

    /** Stand-ins for Holder, HolderSet, Either, a pool with a modifier and condition, tag and nested entries, and RecipeManager. */
    private URLClassLoader host() throws Exception {
        Map<String, String> sources = Map.of(
                "net/minecraft/core/Holder.java", """
                        package net.minecraft.core;
                        import java.util.Optional;
                        public interface Holder<T> {
                            T value();
                            Optional<String> unwrapKey();
                            static <T> Holder<T> direct(T value) { return keyed(value, null); }
                            static <T> Holder<T> keyed(T value, String key) {
                                return new Holder<T>() {
                                    public T value() { return value; }
                                    public Optional<String> unwrapKey() { return Optional.ofNullable(key); }
                                };
                            }
                        }""",
                "net/minecraft/core/HolderSet.java", """
                        package net.minecraft.core;
                        import java.util.List;
                        import java.util.Optional;
                        public interface HolderSet<T> {
                            int size();
                            Holder<T> get(int index);
                            Optional<String> unwrapKey();
                            static <T> HolderSet<T> of(List<Holder<T>> holders, String tag) {
                                return new HolderSet<T>() {
                                    public int size() { return holders.size(); }
                                    public Holder<T> get(int index) { return holders.get(index); }
                                    public Optional<String> unwrapKey() { return Optional.ofNullable(tag); }
                                };
                            }
                        }""",
                "com/mojang/datafixers/util/Either.java", """
                        package com.mojang.datafixers.util;
                        public final class Either<L, R> {
                            private final String text;
                            private Either(String text) { this.text = text; }
                            public static <L, R> Either<L, R> left(L value) { return new Either<>("left:" + value); }
                            public static <L, R> Either<L, R> right(R value) { return new Either<>("right:" + value); }
                            public String toString() { return text; }
                        }""",
                "stand/Pool.java", """
                        package stand;
                        import java.util.Optional;
                        public class Pool {
                            private final Optional<Object> modifier;
                            private final Optional<Object> condition;
                            public Pool(Object holder) { modifier = Optional.ofNullable(holder); condition = Optional.ofNullable(holder); }
                        }""",
                "stand/Tag.java", """
                        package stand;
                        import java.util.List;
                        import net.minecraft.core.HolderSet;
                        public class Tag {
                            private final HolderSet<Object> tag;
                            public Tag(String key) { tag = HolderSet.of(List.of(), key); }
                        }""",
                "stand/Nested.java", """
                        package stand;
                        import java.util.List;
                        import net.minecraft.core.Holder;
                        import net.minecraft.core.HolderSet;
                        public class Nested {
                            private final HolderSet<Object> value;
                            public Nested(Object table, String key) { value = HolderSet.of(List.of(Holder.keyed(table, key)), null); }
                        }""",
                "net/minecraft/world/item/crafting/RecipeManager.java", """
                        package net.minecraft.world.item.crafting;
                        public class RecipeManager {
                            private final RecipeMap recipes = new RecipeMap();
                            public RecipeManager() {}
                        }""",
                "net/minecraft/world/item/crafting/RecipeMap.java", """
                        package net.minecraft.world.item.crafting;
                        public class RecipeMap { private Object byType = "multimap"; }""");
        Path src = dir.resolve("src");
        Path out = Files.createDirectories(dir.resolve("out"));
        List<String> args = new ArrayList<>(List.of("-d", out.toString(), "--release", "17", "-nowarn"));
        for (Map.Entry<String, String> source : sources.entrySet()) {
            Path file = src.resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
            args.add(file.toString());
        }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(new String[0])));
        return new URLClassLoader(new java.net.URL[] {out.toUri().toURL()}, getClass().getClassLoader());
    }
}
