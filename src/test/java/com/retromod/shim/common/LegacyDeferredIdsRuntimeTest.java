/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.shim.common.embedded.LegacyLog;
import com.retromod.testsupport.fakemc.deferredids.FakeBlock;
import com.retromod.testsupport.fakemc.deferredids.FakeBlockBehaviour;
import com.retromod.testsupport.fakemc.deferredids.FakeBlockProperties;
import com.retromod.testsupport.fakemc.deferredids.FakeBuiltInRegistries;
import com.retromod.testsupport.fakemc.deferredids.FakeComponent;
import com.retromod.testsupport.fakemc.deferredids.FakeComponentInitializers;
import com.retromod.testsupport.fakemc.deferredids.FakeComponentItem;
import com.retromod.testsupport.fakemc.deferredids.FakeComponentMap;
import com.retromod.testsupport.fakemc.deferredids.FakeComponentMapBuilder;
import com.retromod.testsupport.fakemc.deferredids.FakeComponentType;
import com.retromod.testsupport.fakemc.deferredids.FakeDataComponents;
import com.retromod.testsupport.fakemc.deferredids.FakeIdentifier;
import com.retromod.testsupport.fakemc.deferredids.FakeInitializer;
import com.retromod.testsupport.fakemc.deferredids.FakeItem;
import com.retromod.testsupport.fakemc.deferredids.FakeItemProperties;
import com.retromod.testsupport.fakemc.deferredids.FakeLookupProvider;
import com.retromod.testsupport.fakemc.deferredids.FakeMutableComponent;
import com.retromod.testsupport.fakemc.deferredids.FakeRegistries;
import com.retromod.testsupport.fakemc.deferredids.FakeResourceKey;
import com.retromod.testsupport.fakemc.deferredids.FakeSimpleComponentMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The rebuilt deferred-id runtime, run against stand-ins shaped like the 1.21.11 and 26.1 to 26.3 item and block classes. */
class LegacyDeferredIdsRuntimeTest {

    private static final Path COMPILED = Path.of("target", "legacyapi-classes");
    private static final String RUNTIME = "com/retromod/legacyapi/minecraft/core/LegacyDeferredIds";
    private static final String COMPONENTS = "com/retromod/legacyapi/minecraft/core/LegacyDeferredItemComponents";
    private static final String SHARED_KEY = "retromod.deferred-ids";
    private static final String SHARED_ITEMS_KEY = "retromod.deferred-ids.items";
    private static final String ITEM = "net/minecraft/world/item/Item";
    private static final String ITEM_PROPERTIES = "net/minecraft/world/item/Item$Properties";
    private static final String BLOCK = "net/minecraft/world/level/block/Block";
    private static final String BLOCK_PROPERTIES = "net/minecraft/world/level/block/state/BlockBehaviour$Properties";
    private static final String COMPONENT = "net/minecraft/core/component/";

    private ClassLoader game;

    // The pending map lives in the system properties, so each test starts without the previous one's.
    @BeforeEach
    @AfterEach
    void clearSharedState() {
        System.getProperties().remove(SHARED_KEY);
        System.getProperties().remove(SHARED_ITEMS_KEY);
    }

    @Test
    @DisplayName("an item built before its id is known is named, modeled and queued under the id it is registered with")
    void itemTakesItsRegisteredId() throws Exception {
        game = load(FakeItem.class);
        Object item = newItem(call(itemProperties(), "stacksTo", 16));
        assertTrue(description(item).startsWith("item.retromod.deferred_id_"), description(item));

        settle(id("supplementaries", "way_sign_oak"), item);

        assertEquals("item.supplementaries.way_sign_oak", description(item));
        FakeComponentMapLike components = built(itemKey("supplementaries", "way_sign_oak"));
        assertEquals(translatable("item.supplementaries.way_sign_oak"), components.get("ITEM_NAME"));
        assertEquals(id("supplementaries", "way_sign_oak"), components.get("ITEM_MODEL"));
        assertEquals(16, components.get("MAX_STACK_SIZE"), "the item's own components move with it");
    }

    @Test
    @DisplayName("an item whose properties already carry an id is built and named as before")
    void itemWithItsOwnIdIsLeftAlone() throws Exception {
        game = load(FakeItem.class);
        Object properties = call(itemProperties(), "setId", itemKey("mod", "own"));
        Object item = newItem(properties);

        settle(id("mod", "other"), item);

        assertEquals("item.mod.own", description(item));
        built(itemKey("mod", "own"));
    }

    @Test
    @DisplayName("properties shared by two items give each the id it is registered under")
    void sharedPropertiesSettleEachItem() throws Exception {
        game = load(FakeItem.class);
        Object shared = itemProperties();
        Object first = newItem(shared);
        Object second = newItem(shared);
        assertFalse(description(first).equals(description(second)), "each item gets its own placeholder");

        settle(id("mod", "first"), first);
        settle(id("mod", "second"), second);

        assertEquals("item.mod.first", description(first));
        assertEquals("item.mod.second", description(second));
        Map<?, ?> all = buildAll(itemKey("mod", "first"), itemKey("mod", "second"));
        assertEquals(translatable("item.mod.second"), new FakeComponentMapLike(all.get(itemKey("mod", "second"))).get("ITEM_NAME"));
    }

    @Test
    @DisplayName("properties reused after one item registered do not hand the next item that item's id")
    void reusedPropertiesBuildRegisterBuildRegister() throws Exception {
        game = load(FakeItem.class);
        Object shared = itemProperties();
        Object first = newItem(shared);
        settle(id("mod", "first"), first);
        Object second = newItem(shared);
        settle(id("mod", "second"), second);

        assertEquals("item.mod.first", description(first));
        assertEquals("item.mod.second", description(second));
        buildAll(itemKey("mod", "first"), itemKey("mod", "second"));
    }

    @Test
    @DisplayName("block properties reused after one block registered do not hand the next block that block's id")
    void reusedBlockPropertiesBuildRegisterBuildRegister() throws Exception {
        game = load(FakeItem.class);
        Object shared = blockProperties();
        Object first = newBlock(shared);
        settle(id("mod", "first"), first);
        Object second = newBlock(shared);
        settle(id("mod", "second"), second);

        assertEquals("block.mod.first", call(first, "getDescriptionId"));
        assertEquals("block.mod.second", call(second, "getDescriptionId"));
        Optional<?> loot = (Optional<?>) call(second, "getLootTable");
        assertEquals(id("mod", "blocks/second"), call(loot.orElseThrow(), "identifier"));
    }

    @Test
    @DisplayName("a block item keeps the block description prefix, under a ResourceKey or a String id")
    void blockPrefixAndOtherIdForms() throws Exception {
        game = load(FakeItem.class);
        Object blockItem = newItem(call(itemProperties(), "useBlockDescriptionPrefix"));
        Object named = newItem(itemProperties());

        settle(itemKey("mod", "lamp"), blockItem);
        settle("mod:lantern", named);

        assertEquals("block.mod.lamp", description(blockItem));
        assertEquals("item.mod.lantern", description(named));
        buildAll(itemKey("mod", "lamp"), itemKey("mod", "lantern"));
    }

    @Test
    @DisplayName("a block item that copied its block's placeholder description takes the block's settled one")
    void blockItemFollowsItsBlock() throws Exception {
        game = load(FakeItem.class);
        Object block = newBlock(blockProperties());
        // Retromod's old BlockItem shape overrides the item's description with its block's, as 1.21.1 named block items.
        Object properties = call(itemProperties(), "overrideDescription", call(block, "getDescriptionId"));
        Object blockItem = newItem(properties);

        settle(id("mod", "sign_post"), block);
        settle(id("mod", "sign_post"), blockItem);

        assertEquals("block.mod.sign_post", description(blockItem));
        FakeComponentMapLike components = built(itemKey("mod", "sign_post"));
        assertEquals(translatable("block.mod.sign_post"), components.get("ITEM_NAME"));
        assertEquals(id("mod", "sign_post"), components.get("ITEM_MODEL"));
    }

    @Test
    @DisplayName("an item whose description was set by name keeps it, and its components still move to the real id")
    void overriddenDescriptionStays() throws Exception {
        game = load(FakeItem.class);
        Object item = newItem(call(itemProperties(), "overrideDescription", "block.waystones.waystone"));

        settle(id("waystones", "waystone"), item);

        assertEquals("block.waystones.waystone", description(item));
        FakeComponentMapLike components = built(itemKey("waystones", "waystone"));
        assertEquals(translatable("block.waystones.waystone"), components.get("ITEM_NAME"));
        assertEquals(id("waystones", "waystone"), components.get("ITEM_MODEL"));
    }

    @Test
    @DisplayName("a block built before its id is known takes its description and loot table from the registered id")
    void blockTakesItsRegisteredId() throws Exception {
        game = load(FakeItem.class);
        Object block = newBlock(blockProperties());
        Object empty = newBlock(call(blockProperties(), "noLootTable"));

        settle(id("mod", "sign_post"), block);
        settle(id("mod", "air_pocket"), empty);

        assertEquals("block.mod.sign_post", call(block, "getDescriptionId"));
        Optional<?> loot = (Optional<?>) call(block, "getLootTable");
        assertEquals(id("mod", "blocks/sign_post"), call(loot.orElseThrow(), "identifier"));
        assertEquals("block.mod.air_pocket", call(empty, "getDescriptionId"));
        assertEquals(Optional.empty(), call(empty, "getLootTable"), "a block with no loot table keeps none");
    }

    @Test
    @DisplayName("on 1.21.11 the item's own component map takes the registered name and model")
    void componentMapItemTakesItsRegisteredId() throws Exception {
        game = load(FakeComponentItem.class);
        Object item = newItem(call(itemProperties(), "stacksTo", 8));

        settle(id("mod", "lamp"), item);

        assertEquals("item.mod.lamp", description(item));
        FakeComponentMapLike components = new FakeComponentMapLike(call(item, "components"));
        assertEquals(translatable("item.mod.lamp"), components.get("ITEM_NAME"));
        assertEquals(id("mod", "lamp"), components.get("ITEM_MODEL"));
        assertEquals(8, components.get("MAX_STACK_SIZE"));
    }

    @Test
    @DisplayName("a value that is not a block or item, and a null id, are passed through untouched")
    void otherValuesAreIgnored() throws Exception {
        game = load(FakeItem.class);
        Object item = newItem(itemProperties());
        String before = description(item);

        settle(id("mod", "thing"), "not an item");
        settle(null, item);

        assertEquals(before, description(item));
    }

    private Object itemProperties() throws Exception {
        return type(ITEM_PROPERTIES).getConstructor().newInstance();
    }

    private Object blockProperties() throws Exception {
        return type(BLOCK_PROPERTIES).getMethod("of").invoke(null);
    }

    /** {@code new Item(LegacyDeferredIds.deferItem(properties))} then {@code builtItem(item)}, as the stamper rewrites it. */
    private Object newItem(Object properties) throws Exception {
        Object stamped = deferred("deferItem", ITEM_PROPERTIES, properties);
        Object item = type(ITEM).getConstructor(type(ITEM_PROPERTIES)).newInstance(stamped);
        type(RUNTIME).getMethod("builtItem", Object.class).invoke(null, item);
        return item;
    }

    private Object newBlock(Object properties) throws Exception {
        Object stamped = deferred("deferBlock", BLOCK_PROPERTIES, properties);
        return type(BLOCK).getConstructor(type(BLOCK_PROPERTIES)).newInstance(stamped);
    }

    private Object deferred(String method, String propertiesType, Object properties) throws Exception {
        return type(RUNTIME).getMethod(method, type(propertiesType)).invoke(null, properties);
    }

    private void settle(Object id, Object value) throws Exception {
        type(RUNTIME).getMethod("settle", Object.class, Object.class).invoke(null, id, value);
    }

    private String description(Object item) throws Exception {
        return (String) call(item, "getDescriptionId");
    }

    private Object id(String namespace, String path) throws Exception {
        return type("net/minecraft/resources/Identifier").getMethod("fromNamespaceAndPath", String.class, String.class)
                .invoke(null, namespace, path);
    }

    private Object itemKey(String namespace, String path) throws Exception {
        Class<?> key = type("net/minecraft/resources/ResourceKey");
        Object registry = type("net/minecraft/core/registries/Registries").getField("ITEM").get(null);
        return key.getMethod("create", key, type("net/minecraft/resources/Identifier")).invoke(null, registry, id(namespace, path));
    }

    private Object translatable(String key) throws Exception {
        return type("net/minecraft/network/chat/Component").getMethod("translatable", String.class).invoke(null, key);
    }

    /** The component build the host runs once registries load; it fails on a key no item was registered under. */
    private Map<?, ?> buildAll(Object... registered) throws Exception {
        Object queue = type("net/minecraft/core/registries/BuiltInRegistries").getField("DATA_COMPONENT_INITIALIZERS").get(null);
        return (Map<?, ?>) queue.getClass().getMethod("build", Set.class).invoke(queue, Set.of(registered));
    }

    private FakeComponentMapLike built(Object key) throws Exception {
        return new FakeComponentMapLike(buildAll(key).get(key));
    }

    /** Reads a stand-in component map loaded in the game loader by the name of its component constant. */
    private final class FakeComponentMapLike {
        private final Object map;

        FakeComponentMapLike(Object map) {
            this.map = map;
        }

        Object get(String component) throws Exception {
            Class<?> types = type(COMPONENT + "DataComponents");
            Object type = types.getField(component).get(null);
            return map.getClass().getMethod("get", type(COMPONENT + "DataComponentType")).invoke(map, type);
        }
    }

    private static Object call(Object target, String name, Object... arguments) throws Exception {
        for (Method method : target.getClass().getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == arguments.length) {
                return method.invoke(target, arguments);
            }
        }
        throw new AssertionError("no " + name + " on " + target.getClass().getName());
    }

    private Class<?> type(String internalName) throws ClassNotFoundException {
        return Class.forName(internalName.replace('/', '.'), true, game);
    }

    /** A fresh game loader with the stand-ins under the host's names and {@code item} as its Item class. */
    private static ClassLoader load(Class<?> item) throws IOException {
        Map<Class<?>, String> names = new HashMap<>(Map.ofEntries(
                Map.entry(FakeIdentifier.class, "net/minecraft/resources/Identifier"),
                Map.entry(FakeResourceKey.class, "net/minecraft/resources/ResourceKey"),
                Map.entry(FakeRegistries.class, "net/minecraft/core/registries/Registries"),
                Map.entry(FakeBuiltInRegistries.class, "net/minecraft/core/registries/BuiltInRegistries"),
                Map.entry(FakeComponent.class, "net/minecraft/network/chat/Component"),
                Map.entry(FakeMutableComponent.class, "net/minecraft/network/chat/MutableComponent"),
                Map.entry(FakeComponentType.class, COMPONENT + "DataComponentType"),
                Map.entry(FakeDataComponents.class, COMPONENT + "DataComponents"),
                Map.entry(FakeComponentMap.class, COMPONENT + "DataComponentMap"),
                Map.entry(FakeComponentMapBuilder.class, COMPONENT + "DataComponentMap$Builder"),
                Map.entry(FakeSimpleComponentMap.class, COMPONENT + "SimpleComponentMap"),
                Map.entry(FakeLookupProvider.class, "net/minecraft/core/HolderLookup$Provider"),
                Map.entry(FakeInitializer.class, COMPONENT + "DataComponentInitializers$Initializer"),
                Map.entry(FakeComponentInitializers.class, COMPONENT + "DataComponentInitializers"),
                Map.entry(FakeItemProperties.class, ITEM_PROPERTIES),
                Map.entry(item, ITEM),
                Map.entry(FakeBlockProperties.class, BLOCK_PROPERTIES),
                Map.entry(FakeBlockBehaviour.class, "net/minecraft/world/level/block/state/BlockBehaviour"),
                Map.entry(FakeBlock.class, BLOCK)));
        Map<String, String> rename = new HashMap<>();
        names.forEach((fake, name) -> rename.put(internal(fake), name));
        // The entry record is package-private, so it is named here rather than through a class literal.
        String entry = "com/retromod/testsupport/fakemc/deferredids/FakeInitializerEntry";
        rename.put(entry, COMPONENT + "DataComponentInitializers$InitializerEntry");
        SimpleRemapper remapper = new SimpleRemapper(rename);

        Map<String, byte[]> classes = new HashMap<>();
        for (Map.Entry<String, String> name : rename.entrySet()) {
            classes.put(name.getValue().replace('/', '.'), remap(bytesOf(name.getKey()), remapper));
        }
        for (String rebuilt : new String[] {RUNTIME, COMPONENTS}) {
            classes.put(rebuilt.replace('/', '.'), Files.readAllBytes(COMPILED.resolve(rebuilt + ".class")));
        }
        classes.put(LegacyLog.class.getName(), bytesOf(internal(LegacyLog.class)));
        return new ClassLoader(ClassLoader.getPlatformClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classes.get(name);
                if (bytes == null) throw new ClassNotFoundException(name);
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
    }

    private static String internal(Class<?> type) {
        return type.getName().replace('.', '/');
    }

    private static byte[] remap(byte[] bytes, SimpleRemapper remapper) {
        ClassWriter writer = new ClassWriter(0);
        new ClassReader(bytes).accept(new ClassRemapper(writer, remapper), 0);
        return writer.toByteArray();
    }

    private static byte[] bytesOf(String internalName) throws IOException {
        try (InputStream in = LegacyDeferredIdsRuntimeTest.class.getClassLoader().getResourceAsStream(internalName + ".class")) {
            if (in == null) throw new IOException("missing " + internalName);
            return in.readAllBytes();
        }
    }
}
