/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.shim.common.embedded.LegacyLog;
import com.retromod.testsupport.fakemc.compost.FakeBuiltInRegistries;
import com.retromod.testsupport.fakemc.compost.FakeComponentInitializers;
import com.retromod.testsupport.fakemc.compost.FakeComponentMap;
import com.retromod.testsupport.fakemc.compost.FakeComponentMapBuilder;
import com.retromod.testsupport.fakemc.compost.FakeComponentType;
import com.retromod.testsupport.fakemc.compost.FakeComponentTypeImpl;
import com.retromod.testsupport.fakemc.compost.FakeComponents;
import com.retromod.testsupport.fakemc.compost.FakeCompostable;
import com.retromod.testsupport.fakemc.compost.FakeFloatHashMap;
import com.retromod.testsupport.fakemc.compost.FakeFloatMap;
import com.retromod.testsupport.fakemc.compost.FakeHolderReference;
import com.retromod.testsupport.fakemc.compost.FakeIdentifier;
import com.retromod.testsupport.fakemc.compost.FakeInitializer;
import com.retromod.testsupport.fakemc.compost.FakeItem;
import com.retromod.testsupport.fakemc.compost.FakeItemLike;
import com.retromod.testsupport.fakemc.compost.FakeLookupProvider;
import com.retromod.testsupport.fakemc.compost.FakeRegistries;
import com.retromod.testsupport.fakemc.compost.FakeResolvableInt;
import com.retromod.testsupport.fakemc.compost.FakeResolvableIntReference;
import com.retromod.testsupport.fakemc.compost.FakeResourceKey;
import com.retromod.testsupport.fakemc.compost.FakeSimpleComponentMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The rebuilt composter table, run against small stand-ins for items, their component build, and fastutil's map. */
class LegacyCompostablesRuntimeTest {

    private static final Path COMPILED = Path.of("target", "legacyapi-classes");
    private static final String TABLE = "com/retromod/legacyapi/minecraft/world/level/block/LegacyCompostables";
    private static final String INTS = "net/minecraft/world/level/storage/loot/providers/number/ints/";
    private static final Map<Class<?>, String> NAMES = Map.ofEntries(
            Map.entry(FakeFloatMap.class, "it/unimi/dsi/fastutil/objects/Object2FloatMap"),
            Map.entry(FakeFloatHashMap.class, "it/unimi/dsi/fastutil/objects/Object2FloatOpenHashMap"),
            Map.entry(FakeItemLike.class, "net/minecraft/world/level/ItemLike"),
            Map.entry(FakeItem.class, "net/minecraft/world/item/Item"),
            Map.entry(FakeHolderReference.class, "net/minecraft/core/Holder$Reference"),
            Map.entry(FakeComponentMap.class, "net/minecraft/core/component/DataComponentMap"),
            Map.entry(FakeComponentMapBuilder.class, "net/minecraft/core/component/DataComponentMap$Builder"),
            Map.entry(FakeSimpleComponentMap.class, "net/minecraft/core/component/SimpleComponentMap"),
            Map.entry(FakeComponentType.class, "net/minecraft/core/component/DataComponentType"),
            Map.entry(FakeComponentTypeImpl.class, "net/minecraft/core/component/SimpleComponentType"),
            Map.entry(FakeComponents.class, "net/minecraft/core/component/DataComponents"),
            Map.entry(FakeComponentInitializers.class, "net/minecraft/core/component/DataComponentInitializers"),
            Map.entry(FakeInitializer.class, "net/minecraft/core/component/DataComponentInitializers$Initializer"),
            Map.entry(FakeLookupProvider.class, "net/minecraft/core/HolderLookup$Provider"),
            Map.entry(FakeBuiltInRegistries.class, "net/minecraft/core/registries/BuiltInRegistries"),
            Map.entry(FakeCompostable.class, "net/minecraft/world/item/component/Compostable"),
            Map.entry(FakeResolvableInt.class, INTS + "ResolvableInt"),
            Map.entry(FakeResolvableIntReference.class, INTS + "ResolvableInt$Reference"),
            Map.entry(FakeResourceKey.class, "net/minecraft/resources/ResourceKey"),
            Map.entry(FakeIdentifier.class, "net/minecraft/resources/Identifier"),
            Map.entry(FakeRegistries.class, "net/minecraft/core/registries/Registries"));

    private ClassLoader game;

    // Each test gets a fresh loader, since the table and the initializer list keep what was added.
    @BeforeEach
    void loadGame() throws IOException {
        Map<String, String> rename = new HashMap<>();
        NAMES.forEach((fake, name) -> rename.put(fake.getName().replace('.', '/'), name));
        SimpleRemapper remapper = new SimpleRemapper(rename);
        Map<String, byte[]> classes = new HashMap<>();
        for (Map.Entry<Class<?>, String> fake : NAMES.entrySet()) {
            classes.put(fake.getValue().replace('/', '.'), remap(bytesOf(fake.getKey()), remapper));
        }
        classes.put(TABLE.replace('/', '.'), Files.readAllBytes(COMPILED.resolve(TABLE + ".class")));
        classes.put(LegacyLog.class.getName(), bytesOf(LegacyLog.class));
        game = new ClassLoader(ClassLoader.getPlatformClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classes.get(name);
                if (bytes == null) throw new ClassNotFoundException(name);
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
    }

    @Test
    @DisplayName("put gives the item vanilla's nearest chance on the next component build")
    void putSetsTheNearestPreset() throws Exception {
        Object berries = item("glow_berries");
        Object roots = item("roots");

        put(berries, 0.3f);
        put(roots, 0.62f);
        build(berries, roots);

        assertEquals("compostable/low", presetOf(berries));
        assertEquals("compostable/medium", presetOf(roots));
        assertEquals(0.3f, getFloat(berries), "an added chance reads back as added");
    }

    @Test
    @DisplayName("an item added before binding has the component after a build, and still after a second one")
    void addAtSetupSurvivesEveryBuild() throws Exception {
        Object sapling = item("modded_sapling");

        put(sapling, 0.3f);
        assertFalse((Boolean) call(holder(sapling), "areComponentsBound"));

        build(sapling);
        assertEquals("compostable/low", presetOf(sapling));
        build(sapling);
        assertEquals("compostable/low", presetOf(sapling), "a reload builds every component again");
    }

    @Test
    @DisplayName("get reads an item's own component, and an item with none is absent")
    void getReadsTheHostComponent() throws Exception {
        Object melon = item("melon");
        Object stone = item("stone");
        vanillaPreset(melon, "compostable/medium_high");
        build(melon, stone);

        assertEquals(0.85f, getFloat(melon));
        assertTrue(containsKey(melon));
        assertEquals(0f, getFloat(stone));
        assertFalse(containsKey(stone));
        assertEquals(0.7f, (Float) table().getClass().getMethod("getOrDefault", Object.class, float.class)
                .invoke(table(), stone, 0.7f));
    }

    @Test
    @DisplayName("an unbound item reads 0, and putting that 0 back leaves the item's own chance in place")
    void copyingAnUnboundChanceRemovesNothing() throws Exception {
        Object cake = item("cake");
        vanillaPreset(cake, "compostable/always_add_one");
        assertEquals(0f, getFloat(cake), "the game has not bound item components yet");

        put(cake, getFloat(cake));
        build(cake);

        assertEquals("compostable/always_add_one", presetOf(cake));
    }

    @Test
    @DisplayName("removeFloat takes away even the item's own component on the next build")
    void removeTakesTheComponentAway() throws Exception {
        Object leaves = item("oak_leaves");
        vanillaPreset(leaves, "compostable/low");

        table().getClass().getMethod("removeFloat", Object.class).invoke(table(), leaves);
        build(leaves);

        assertNull(compostable(leaves));
        assertFalse(containsKey(leaves));
    }

    @Test
    @DisplayName("putIfAbsent leaves an item that already composts as it is")
    void putIfAbsentKeepsAnExistingChance() throws Exception {
        Object melon = item("melon");
        vanillaPreset(melon, "compostable/medium_high");
        build(melon);

        table().getClass().getMethod("putIfAbsent", Object.class, float.class).invoke(table(), melon, 0.3f);
        build(melon);

        assertEquals("compostable/medium_high", presetOf(melon));
    }

    private Object table() throws Exception {
        return type(TABLE).getMethod("compostables").invoke(null);
    }

    private void put(Object item, float chance) throws Exception {
        table().getClass().getMethod("put", Object.class, float.class).invoke(table(), item, chance);
    }

    private float getFloat(Object item) throws Exception {
        return (Float) table().getClass().getMethod("getFloat", Object.class).invoke(table(), item);
    }

    private boolean containsKey(Object item) throws Exception {
        return (Boolean) table().getClass().getMethod("containsKey", Object.class).invoke(table(), item);
    }

    private Object item(String id) throws Exception {
        return type("net/minecraft/world/item/Item").getConstructor(String.class).newInstance(id);
    }

    private Object initializers() throws Exception {
        return type("net/minecraft/core/registries/BuiltInRegistries").getField("DATA_COMPONENT_INITIALIZERS").get(null);
    }

    private void vanillaPreset(Object item, String path) throws Exception {
        call(initializers(), "addPreset", call(holder(item), "key"), path);
    }

    /** A world load: every item's initializers run on a new builder, and the result is bound. */
    private void build(Object... items) throws Exception {
        call(initializers(), "buildAndApply", List.of(items));
    }

    private String presetOf(Object item) throws Exception {
        Object compostable = compostable(item);
        assertNotNull(compostable, "the item has the compostable component");
        Object key = call(call(compostable, "layers"), "key");
        return (String) call(call(key, "identifier"), "getPath");
    }

    private Object compostable(Object item) throws Exception {
        Object components = call(holder(item), "components");
        Object type = type("net/minecraft/core/component/DataComponents").getField("COMPOSTABLE").get(null);
        return components.getClass().getMethod("get", type("net/minecraft/core/component/DataComponentType"))
                .invoke(components, type);
    }

    private Object holder(Object item) throws Exception {
        return call(item, "builtInRegistryHolder");
    }

    private static Object call(Object target, String name, Object... arguments) throws Exception {
        for (var method : target.getClass().getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == arguments.length) {
                return method.invoke(target, arguments);
            }
        }
        throw new AssertionError("no " + name + " on " + target.getClass().getName());
    }

    private Class<?> type(String internalName) throws ClassNotFoundException {
        return Class.forName(internalName.replace('/', '.'), true, game);
    }

    private static byte[] remap(byte[] bytes, SimpleRemapper remapper) {
        ClassWriter writer = new ClassWriter(0);
        new ClassReader(bytes).accept(new ClassRemapper(writer, remapper), 0);
        return writer.toByteArray();
    }

    private static byte[] bytesOf(Class<?> type) throws IOException {
        try (InputStream in = type.getClassLoader().getResourceAsStream(type.getName().replace('.', '/') + ".class")) {
            return in.readAllBytes();
        }
    }
}
