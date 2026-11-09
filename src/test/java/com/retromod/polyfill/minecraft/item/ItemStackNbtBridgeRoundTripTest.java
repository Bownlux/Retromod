/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.polyfill.minecraft.item;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Writes through the old item tag calls stay on the stack, on a host with the custom data accessor and one without. */
class ItemStackNbtBridgeRoundTripTest {

    private static final String BRIDGE = "com/retromod/polyfill/minecraft/item/embedded/ItemStackNbtBridge";

    @TempDir
    Path dir;

    @ParameterizedTest(name = "custom data {0}")
    @ValueSource(strings = {"with getUnsafe", "without getUnsafe"})
    void aWriteToANewTagIsKept(String host) throws Throwable {
        Game game = new Game(compileHost(host.startsWith("with ")));
        Object stack = game.stack();

        // The first tag a stack gets used to be lost: the component stored a copy of it.
        game.put(game.call("getOrCreateTag", stack), "count", game.intTag(3));

        Object stored = game.call("getTag", stack);
        assertNotNull(stored, "the stack keeps its tag");
        assertNotNull(game.get(stored, "count"), "the write to the new tag reached the stack");
        assertSame(stored, game.call("getOrCreateTag", stack), "later calls answer the same stored tag");
    }

    @ParameterizedTest(name = "custom data {0}")
    @ValueSource(strings = {"with getUnsafe", "without getUnsafe"})
    void tagElementsReadAndWriteTheStoredTag(String host) throws Throwable {
        Game game = new Game(compileHost(host.startsWith("with ")));
        Object stack = game.stack();

        Object display = game.call("getOrCreateTagElement", stack, "display");
        game.put(display, "Name", game.intTag(1));
        game.call("addTagElement", stack, "Lore", game.intTag(2));

        Object read = game.call("getTagElement", stack, "display");
        assertSame(display, read, "the element is the one stored in the stack's tag");
        assertNotNull(game.get(read, "Name"), "a write into the element is kept");
        assertNotNull(game.get(game.call("getTag", stack), "Lore"), "an added element is kept");
        assertNull(game.call("getTagElement", stack, "Lore"), "an element that is not a compound reads as none");

        // Removing every key leaves no tag, as before 1.20.5.
        game.call("removeTagKey", stack, "display");
        game.call("removeTagKey", stack, "Lore");
        assertNull(game.call("getTag", stack), "an emptied tag goes away");
    }

    @ParameterizedTest(name = "custom data {0}")
    @ValueSource(strings = {"with getUnsafe", "without getUnsafe"})
    void aCopyKeepsItsOwnTag(String host) throws Throwable {
        Game game = new Game(compileHost(host.startsWith("with ")));
        Object stack = game.stack();
        game.put(game.call("getOrCreateTag", stack), "charge", game.intTag(1));
        assertSame(game.call("getTag", stack), game.call("getTag", stack), "two reads answer the same tag");

        // A menu keeps a copy of each slot and resends when the stack no longer matches it.
        Object lastSent = game.copy(stack);
        Object split = game.copy(stack);
        game.put(game.call("getTag", stack), "charge", game.intTag(2));

        assertEquals("1", game.charge(lastSent), "the copy keeps the value it was taken with");
        assertEquals("1", game.charge(split), "a split stack keeps its own value");
        assertEquals("2", game.charge(stack), "the write reached the stack");
        assertFalse(game.matches(lastSent, stack), "the menu sees the change");

        // A write to the copy reaches only the copy.
        game.put(game.call("getTag", lastSent), "charge", game.intTag(3));
        assertEquals("2", game.charge(stack), "the source keeps its value");
    }

    @ParameterizedTest(name = "custom data {0}")
    @ValueSource(strings = {"with getUnsafe", "without getUnsafe"})
    void aStackWithoutCustomDataGetsNone(String host) throws Throwable {
        Game game = new Game(compileHost(host.startsWith("with ")));
        Object stack = game.stack();

        assertNull(game.call("getTag", stack), "no tag is made by reading");
        assertEquals(false, game.call("hasTag", stack), "the stack still has no custom data");
    }

    /** Compiles stand-ins for the host's tag, component and stack classes, with or without the custom data accessor. */
    private Map<String, byte[]> compileHost(boolean accessor) throws Exception {
        Map<String, String> sources = new HashMap<>();
        sources.put("net/minecraft/nbt/Tag.java", "package net.minecraft.nbt; public interface Tag {}");
        sources.put("net/minecraft/nbt/IntTag.java",
                "package net.minecraft.nbt; public final class IntTag implements Tag { public final int value;"
                        + " public IntTag(int value) { this.value = value; } }");
        sources.put("net/minecraft/nbt/CompoundTag.java", "package net.minecraft.nbt; import java.util.*;"
                + " public final class CompoundTag implements Tag { private final Map<String, Tag> tags = new HashMap<>();"
                + " public CompoundTag copy() { CompoundTag copy = new CompoundTag(); copy.tags.putAll(tags); return copy; }"
                + " public Tag put(String key, Tag value) { return tags.put(key, value); }"
                + " public Tag get(String key) { return tags.get(key); }"
                + " public void remove(String key) { tags.remove(key); }"
                + " public boolean isEmpty() { return tags.isEmpty(); }"
                + " public boolean equals(Object o) { return o instanceof CompoundTag t && t.tags.equals(tags); }"
                + " public int hashCode() { return tags.hashCode(); } }");
        sources.put("net/minecraft/core/component/DataComponentType.java",
                "package net.minecraft.core.component; public final class DataComponentType<T> {}");
        sources.put("net/minecraft/core/component/DataComponents.java", "package net.minecraft.core.component;"
                + " public final class DataComponents { public static final DataComponentType<Object> CUSTOM_DATA"
                + " = new DataComponentType<>(); }");
        // The host's copy on write: a copy shares the patch and marks both maps, and the next write takes a new patch.
        sources.put("net/minecraft/core/component/PatchedDataComponentMap.java", "package net.minecraft.core.component;"
                + " import java.util.*; @SuppressWarnings(\"unchecked\") public final class PatchedDataComponentMap {"
                + " private Map<Object, Object> patch; private boolean copyOnWrite;"
                + " public PatchedDataComponentMap() { this(new HashMap<>(), false); }"
                + " private PatchedDataComponentMap(Map<Object, Object> patch, boolean copyOnWrite) { this.patch = patch;"
                + " this.copyOnWrite = copyOnWrite; }"
                + " public <T> T get(DataComponentType<? extends T> type) { return (T) patch.get(type); }"
                + " public <T> T set(DataComponentType<? super T> type, T value) { own(); return (T) patch.put(type, value); }"
                + " public <T> T remove(DataComponentType<? extends T> type) { own(); return (T) patch.remove(type); }"
                + " private void own() { if (copyOnWrite) { patch = new HashMap<>(patch); copyOnWrite = false; } }"
                + " public PatchedDataComponentMap copy() { copyOnWrite = true; return new PatchedDataComponentMap(patch, true); }"
                + " public boolean equals(Object o) { return o instanceof PatchedDataComponentMap m && m.patch.equals(patch); } }");
        sources.put("net/minecraft/world/item/component/CustomData.java", "package net.minecraft.world.item.component;"
                + " import net.minecraft.nbt.CompoundTag; public final class CustomData { private final CompoundTag tag;"
                + " private CustomData(CompoundTag tag) { this.tag = tag; }"
                + " public static CustomData of(CompoundTag tag) { return new CustomData(tag.copy()); }"
                + " public CompoundTag copyTag() { return tag.copy(); }"
                + " public boolean equals(Object o) { return o instanceof CustomData d && d.tag.equals(tag); }"
                + " public int hashCode() { return tag.hashCode(); }"
                + (accessor ? " public CompoundTag getUnsafe() { return tag; }" : "") + " }");
        sources.put("net/minecraft/world/item/ItemStack.java", "package net.minecraft.world.item;"
                + " import net.minecraft.core.component.*; public final class ItemStack {"
                + " private final PatchedDataComponentMap components;"
                + " public ItemStack() { this(new PatchedDataComponentMap()); }"
                + " private ItemStack(PatchedDataComponentMap components) { this.components = components; }"
                + " public <T> T get(DataComponentType<? extends T> type) { return components.get(type); }"
                + " public <T> T set(DataComponentType<? super T> type, T value) { return components.set(type, value); }"
                + " public boolean has(DataComponentType<?> type) { return components.get(type) != null; }"
                + " public <T> T remove(DataComponentType<? extends T> type) { return components.remove(type); }"
                + " public ItemStack copy() { return new ItemStack(components.copy()); }"
                + " public static boolean matches(ItemStack a, ItemStack b) { return a.components.equals(b.components); } }");
        Path src = dir.resolve(accessor ? "src-accessor" : "src-field");
        Path out = dir.resolve(accessor ? "out-accessor" : "out-field");
        List<String> args = new ArrayList<>(List.of("-d", out.toString(), "--release", "17", "-proc:none"));
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
        try (Stream<Path> files = Files.walk(out)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".class")).toList()) {
                String name = out.relativize(file).toString().replace(File.separatorChar, '/');
                classes.put(name.substring(0, name.length() - ".class".length()), Files.readAllBytes(file));
            }
        }
        // The bridge joins them, so its own lookups of host classes find the stand-ins.
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(BRIDGE + ".class")) {
            assertNotNull(in, "the bridge is on the test class path");
            classes.put(BRIDGE, in.readAllBytes());
        }
        return classes;
    }

    /** The bridge and the stand-ins, called by reflection. */
    private static final class Game {
        private final ClassLoader loader;

        Game(Map<String, byte[]> classes) {
            this.loader = new ClassLoader(ItemStackNbtBridgeRoundTripTest.class.getClassLoader()) {
                @Override
                protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                    synchronized (getClassLoadingLock(name)) {
                        Class<?> loaded = findLoadedClass(name);
                        byte[] bytes = classes.get(name.replace('.', '/'));
                        if (loaded == null && bytes != null) loaded = defineClass(name, bytes, 0, bytes.length);
                        if (loaded == null) return super.loadClass(name, resolve);
                        if (resolve) resolveClass(loaded);
                        return loaded;
                    }
                }
            };
        }

        Object stack() throws Exception {
            return type("net/minecraft/world/item/ItemStack").getConstructor().newInstance();
        }

        Object intTag(int value) throws Exception {
            return type("net/minecraft/nbt/IntTag").getConstructor(int.class).newInstance(value);
        }

        void put(Object tag, String key, Object value) throws Exception {
            tag.getClass().getMethod("put", String.class, type("net/minecraft/nbt/Tag")).invoke(tag, key, value);
        }

        Object get(Object tag, String key) throws Exception {
            return tag.getClass().getMethod("get", String.class).invoke(tag, key);
        }

        Object copy(Object stack) throws Exception {
            return stack.getClass().getMethod("copy").invoke(stack);
        }

        boolean matches(Object a, Object b) throws Exception {
            Class<?> stack = type("net/minecraft/world/item/ItemStack");
            return (Boolean) stack.getMethod("matches", stack, stack).invoke(null, a, b);
        }

        /** The stored charge of {@code stack}, read from its custom data without the bridge. */
        String charge(Object stack) throws Exception {
            Object type = type("net/minecraft/core/component/DataComponents").getField("CUSTOM_DATA").get(null);
            Object data = stack.getClass().getMethod("get", type("net/minecraft/core/component/DataComponentType")).invoke(stack, type);
            Object tag = data.getClass().getMethod("copyTag").invoke(data);
            Object value = get(tag, "charge");
            return value == null ? null : String.valueOf(value.getClass().getField("value").get(value));
        }

        /** The bridge's static method {@code name} that takes {@code args}. */
        Object call(String name, Object... args) throws Throwable {
            for (Method method : type(BRIDGE).getMethods()) {
                if (!method.getName().equals(name) || method.getParameterCount() != args.length) continue;
                try {
                    return method.invoke(null, args);
                } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                }
            }
            throw new AssertionError("the bridge has no " + name + " for " + args.length + " arguments");
        }

        private Class<?> type(String internalName) throws ClassNotFoundException {
            return Class.forName(internalName.replace('/', '.'), true, loader);
        }
    }
}
