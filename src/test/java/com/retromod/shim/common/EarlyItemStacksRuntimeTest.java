/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An early stack built before item components are bound works once they are: it reads the item's defaults, stacks
 * with a normal stack both ways, and saves and loads as one. The rebuilt helper runs against stand-ins shaped like
 * 26.x's stack, component maps and holder, with a prototype record around a map as the host's is.
 */
class EarlyItemStacksRuntimeTest {

    private static final String STACK = "net.minecraft.world.item.ItemStack";

    @TempDir
    static Path stubDir;

    /** 26.x-shaped stand-ins, by internal name. */
    private static Map<String, byte[]> host;

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private Run run;

    @BeforeAll
    static void compileHost() throws Exception {
        host = compile(Map.ofEntries(
                // fastutil's map types as the rebuilt map names them; equality and hashing follow java.util.Map's rules.
                Map.entry("it/unimi/dsi/fastutil/objects/ReferenceSet.java", """
                        package it.unimi.dsi.fastutil.objects;
                        public interface ReferenceSet<K> extends java.util.Set<K> {}"""),
                Map.entry("it/unimi/dsi/fastutil/objects/ObjectSet.java", """
                        package it.unimi.dsi.fastutil.objects;
                        public interface ObjectSet<K> extends java.util.Set<K> {}"""),
                Map.entry("it/unimi/dsi/fastutil/objects/ObjectCollection.java", """
                        package it.unimi.dsi.fastutil.objects;
                        public interface ObjectCollection<K> extends java.util.Collection<K> {}"""),
                Map.entry("it/unimi/dsi/fastutil/objects/Reference2ObjectMap.java", """
                        package it.unimi.dsi.fastutil.objects;
                        public interface Reference2ObjectMap<K, V> extends java.util.Map<K, V> {
                            ReferenceSet<K> keySet();
                            ObjectCollection<V> values();
                            ObjectSet<Entry<K, V>> reference2ObjectEntrySet();
                            @SuppressWarnings({"unchecked", "rawtypes"})
                            default java.util.Set<java.util.Map.Entry<K, V>> entrySet() { return (java.util.Set) reference2ObjectEntrySet(); }
                            interface Entry<K, V> extends java.util.Map.Entry<K, V> {}
                        }"""),
                Map.entry("it/unimi/dsi/fastutil/objects/AbstractReference2ObjectMap.java", """
                        package it.unimi.dsi.fastutil.objects;
                        import java.util.*;
                        public abstract class AbstractReference2ObjectMap<K, V> implements Reference2ObjectMap<K, V> {
                            protected AbstractReference2ObjectMap() {}
                            public boolean isEmpty() { return size() == 0; }
                            public boolean containsKey(Object key) { return keySet().contains(key); }
                            public boolean containsValue(Object value) { return values().contains(value); }
                            public V put(K key, V value) { throw new UnsupportedOperationException(); }
                            public V remove(Object key) { throw new UnsupportedOperationException(); }
                            public void putAll(Map<? extends K, ? extends V> from) { from.forEach(this::put); }
                            public void clear() { throw new UnsupportedOperationException(); }
                            public boolean equals(Object o) {
                                if (o == this) return true;
                                if (!(o instanceof Map<?, ?> other) || other.size() != size()) return false;
                                for (Map.Entry<K, V> entry : entrySet()) {
                                    if (!Objects.equals(other.get(entry.getKey()), entry.getValue())) return false;
                                }
                                return true;
                            }
                            public int hashCode() {
                                int hash = 0;
                                for (Map.Entry<K, V> entry : entrySet()) {
                                    hash += System.identityHashCode(entry.getKey()) ^ Objects.hashCode(entry.getValue());
                                }
                                return hash;
                            }
                        }"""),
                Map.entry("it/unimi/dsi/fastutil/objects/Reference2ObjectArrayMap.java", """
                        package it.unimi.dsi.fastutil.objects;
                        import java.util.*;
                        public class Reference2ObjectArrayMap<K, V> extends AbstractReference2ObjectMap<K, V> {
                            private final Map<K, V> values = new LinkedHashMap<>();
                            public Reference2ObjectArrayMap() {}
                            public Reference2ObjectArrayMap(Map<K, V> from) { values.putAll(from); }
                            public V get(Object key) { return values.get(key); }
                            public int size() { return values.size(); }
                            public boolean containsKey(Object key) { return values.containsKey(key); }
                            public V put(K key, V value) { return values.put(key, value); }
                            public ReferenceSet<K> keySet() {
                                class Keys extends AbstractSet<K> implements ReferenceSet<K> {
                                    public Iterator<K> iterator() { return values.keySet().iterator(); }
                                    public int size() { return values.size(); }
                                }
                                return new Keys();
                            }
                            public ObjectCollection<V> values() {
                                class Values extends AbstractCollection<V> implements ObjectCollection<V> {
                                    public Iterator<V> iterator() { return values.values().iterator(); }
                                    public int size() { return values.size(); }
                                }
                                return new Values();
                            }
                            public ObjectSet<Reference2ObjectMap.Entry<K, V>> reference2ObjectEntrySet() {
                                class Item extends AbstractMap.SimpleImmutableEntry<K, V> implements Reference2ObjectMap.Entry<K, V> {
                                    Item(Map.Entry<K, V> from) { super(from); }
                                }
                                class Entries extends AbstractSet<Reference2ObjectMap.Entry<K, V>>
                                        implements ObjectSet<Reference2ObjectMap.Entry<K, V>> {
                                    public Iterator<Reference2ObjectMap.Entry<K, V>> iterator() {
                                        Iterator<Map.Entry<K, V>> all = values.entrySet().iterator();
                                        return new Iterator<>() {
                                            public boolean hasNext() { return all.hasNext(); }
                                            public Reference2ObjectMap.Entry<K, V> next() { return new Item(all.next()); }
                                        };
                                    }
                                    public int size() { return values.size(); }
                                }
                                return new Entries();
                            }
                        }"""),
                Map.entry("it/unimi/dsi/fastutil/objects/Reference2ObjectMaps.java", """
                        package it.unimi.dsi.fastutil.objects;
                        public final class Reference2ObjectMaps {
                            private static final Reference2ObjectMap<Object, Object> EMPTY = new Reference2ObjectArrayMap<>();
                            @SuppressWarnings("unchecked")
                            public static <K, V> Reference2ObjectMap<K, V> emptyMap() { return (Reference2ObjectMap<K, V>) EMPTY; }
                        }"""),
                Map.entry("net/minecraft/core/component/DataComponentType.java", """
                        package net.minecraft.core.component;
                        public final class DataComponentType<T> {
                            private final String name;
                            public DataComponentType(String name) { this.name = name; }
                            public String toString() { return name; }
                        }"""),
                Map.entry("net/minecraft/core/component/DataComponents.java", """
                        package net.minecraft.core.component;
                        public final class DataComponents {
                            public static final DataComponentType<Integer> MAX_STACK_SIZE = new DataComponentType<>("max_stack_size");
                            public static final DataComponentType<String> CUSTOM_NAME = new DataComponentType<>("custom_name");
                            public static final DataComponentType<String> POTION_CONTENTS = new DataComponentType<>("potion_contents");
                        }"""),
                Map.entry("net/minecraft/core/component/DataComponentGetter.java", """
                        package net.minecraft.core.component;
                        public interface DataComponentGetter {
                            <T> T get(DataComponentType<? extends T> type);
                            default <T> T getOrDefault(DataComponentType<? extends T> type, T fallback) {
                                T value = get(type);
                                return value != null ? value : fallback;
                            }
                        }"""),
                // The bound prototype is a private record around a map, which compares by its map.
                Map.entry("net/minecraft/core/component/DataComponentMap.java", """
                        package net.minecraft.core.component;
                        import it.unimi.dsi.fastutil.objects.*;
                        import java.util.Set;
                        public interface DataComponentMap extends DataComponentGetter {
                            DataComponentMap EMPTY = new DataComponentMap() {
                                public <T> T get(DataComponentType<? extends T> type) { return null; }
                                public Set<DataComponentType<?>> keySet() { return Set.of(); }
                            };
                            Set<DataComponentType<?>> keySet();
                            static Builder builder() { return new Builder(); }
                            final class Builder {
                                private final Reference2ObjectArrayMap<DataComponentType<?>, Object> values = new Reference2ObjectArrayMap<>();
                                public <T> Builder set(DataComponentType<T> type, T value) { values.put(type, value); return this; }
                                public DataComponentMap build() { return new SimpleMap(new Reference2ObjectArrayMap<>(values)); }
                                private record SimpleMap(Reference2ObjectMap<DataComponentType<?>, Object> map) implements DataComponentMap {
                                    @SuppressWarnings("unchecked")
                                    public <T> T get(DataComponentType<? extends T> type) { return (T) map.get(type); }
                                    public Set<DataComponentType<?>> keySet() { return map.keySet(); }
                                }
                            }
                        }"""),
                Map.entry("net/minecraft/core/component/DataComponentPatch.java", """
                        package net.minecraft.core.component;
                        import java.util.*;
                        public final class DataComponentPatch {
                            final Map<DataComponentType<?>, Optional<?>> changes;
                            public DataComponentPatch(Map<DataComponentType<?>, Optional<?>> changes) { this.changes = Map.copyOf(changes); }
                            public boolean equals(Object o) { return o instanceof DataComponentPatch p && changes.equals(p.changes); }
                            public int hashCode() { return changes.hashCode(); }
                            public String toString() { return changes.toString(); }
                        }"""),
                // Values that match the prototype are dropped from the patch, a missing value is an empty Optional.
                Map.entry("net/minecraft/core/component/PatchedDataComponentMap.java", """
                        package net.minecraft.core.component;
                        import java.util.*;
                        public final class PatchedDataComponentMap implements DataComponentMap {
                            private final DataComponentMap prototype;
                            private final Map<DataComponentType<?>, Optional<?>> patch;
                            public PatchedDataComponentMap(DataComponentMap prototype) { this(prototype, new LinkedHashMap<>()); }
                            private PatchedDataComponentMap(DataComponentMap prototype, Map<DataComponentType<?>, Optional<?>> patch) {
                                this.prototype = prototype;
                                this.patch = patch;
                            }
                            public static PatchedDataComponentMap fromPatch(DataComponentMap prototype, DataComponentPatch patch) {
                                PatchedDataComponentMap map = new PatchedDataComponentMap(prototype);
                                patch.changes.forEach((type, value) -> map.put(type, value.orElse(null)));
                                return map;
                            }
                            @SuppressWarnings("unchecked")
                            public <T> T get(DataComponentType<? extends T> type) {
                                Optional<?> changed = patch.get(type);
                                return changed != null ? (T) changed.orElse(null) : prototype.get(type);
                            }
                            public <T> T set(DataComponentType<T> type, T value) {
                                T old = get(type);
                                put(type, value);
                                return old;
                            }
                            private void put(DataComponentType<?> type, Object value) {
                                if (Objects.equals(value, prototype.get(type))) patch.remove(type);
                                else patch.put(type, Optional.ofNullable(value));
                            }
                            public Set<DataComponentType<?>> keySet() {
                                Set<DataComponentType<?>> keys = new LinkedHashSet<>(prototype.keySet());
                                patch.forEach((type, value) -> { if (value.isPresent()) keys.add(type); else keys.remove(type); });
                                return keys;
                            }
                            public DataComponentPatch asPatch() { return new DataComponentPatch(patch); }
                            public PatchedDataComponentMap copy() { return new PatchedDataComponentMap(prototype, new LinkedHashMap<>(patch)); }
                            public boolean equals(Object o) {
                                return o instanceof PatchedDataComponentMap other && prototype.equals(other.prototype) && patch.equals(other.patch);
                            }
                            public int hashCode() { return prototype.hashCode() + 31 * patch.hashCode(); }
                        }"""),
                Map.entry("net/minecraft/core/Holder.java", """
                        package net.minecraft.core;
                        import net.minecraft.core.component.DataComponentMap;
                        public interface Holder<T> {
                            T value();
                            DataComponentMap components();
                            record Direct<T>(T value, DataComponentMap components) implements Holder<T> {}
                            class Reference<T> implements Holder<T> {
                                private final T value;
                                private volatile DataComponentMap components;
                                public Reference(T value) { this.value = value; }
                                public T value() { return value; }
                                public boolean areComponentsBound() { return components != null; }
                                public void bindComponents(DataComponentMap components) { this.components = components; }
                                public DataComponentMap components() {
                                    return java.util.Objects.requireNonNull(components, "Components not bound yet");
                                }
                            }
                        }"""),
                Map.entry("net/minecraft/world/level/ItemLike.java", """
                        package net.minecraft.world.level;
                        public interface ItemLike { net.minecraft.world.item.Item asItem(); }"""),
                Map.entry("net/minecraft/world/item/Item.java", """
                        package net.minecraft.world.item;
                        import net.minecraft.core.Holder;
                        public class Item implements net.minecraft.world.level.ItemLike {
                            private final Holder.Reference<Item> holder = new Holder.Reference<>(this);
                            public Item asItem() { return this; }
                            public Holder.Reference<Item> builtInRegistryHolder() { return holder; }
                            public ItemStack getDefaultInstance() { return new ItemStack(this); }
                        }"""),
                // A vanilla item whose default adds data to Minecraft's own stack, so it fails before binding.
                Map.entry("net/minecraft/world/item/PotionItem.java", """
                        package net.minecraft.world.item;
                        import net.minecraft.core.component.DataComponents;
                        public class PotionItem extends Item {
                            public ItemStack getDefaultInstance() {
                                ItemStack stack = super.getDefaultInstance();
                                stack.set(DataComponents.POTION_CONTENTS, "water");
                                return stack;
                            }
                        }"""),
                // An item with a method that names a client class, which a dedicated server does not have.
                Map.entry("net/minecraft/client/ClientThing.java", """
                        package net.minecraft.client;
                        public class ClientThing {}"""),
                Map.entry("net/minecraft/world/item/ClientOnlyItem.java", """
                        package net.minecraft.world.item;
                        public class ClientOnlyItem extends Item {
                            public void draw(net.minecraft.client.ClientThing thing) {}
                        }"""),
                // A mod item whose own default was translated, so it builds its stack without the host.
                Map.entry("net/minecraft/world/item/ModItem.java", """
                        package net.minecraft.world.item;
                        public class ModItem extends Item {
                            public static java.util.function.Supplier<ItemStack> own;
                            public ItemStack getDefaultInstance() { return own.get(); }
                        }"""),
                Map.entry("net/minecraft/world/item/ItemStack.java", """
                        package net.minecraft.world.item;
                        import java.util.*;
                        import net.minecraft.core.Holder;
                        import net.minecraft.core.component.*;
                        import net.minecraft.world.level.ItemLike;
                        public final class ItemStack {
                            public static final ItemStack EMPTY = new ItemStack((Void) null);
                            private final Holder<Item> item;
                            private int count;
                            private final PatchedDataComponentMap components;
                            public ItemStack(ItemLike item) { this(item, 1); }
                            public ItemStack(ItemLike item, int count) { this(item.asItem().builtInRegistryHolder(), count); }
                            public ItemStack(Holder<Item> item) { this(item, 1); }
                            public ItemStack(Holder<Item> item, int count) { this(item, count, new PatchedDataComponentMap(item.components())); }
                            public ItemStack(Holder<Item> item, int count, DataComponentPatch patch) {
                                this(item, count, PatchedDataComponentMap.fromPatch(item.components(), patch));
                            }
                            private ItemStack(Holder<Item> item, int count, PatchedDataComponentMap components) {
                                this.item = item;
                                this.count = count;
                                this.components = components;
                            }
                            private ItemStack(Void empty) {
                                this.item = null;
                                this.components = new PatchedDataComponentMap(DataComponentMap.EMPTY);
                            }
                            public boolean isEmpty() { return this == EMPTY || count <= 0; }
                            public Item getItem() { return item.value(); }
                            public int getCount() { return count; }
                            public void setCount(int count) { this.count = count; }
                            public <T> T get(DataComponentType<? extends T> type) { return components.get(type); }
                            public <T> T set(DataComponentType<T> type, T value) { return components.set(type, value); }
                            public int getMaxStackSize() { return components.getOrDefault(DataComponents.MAX_STACK_SIZE, 1); }
                            public ItemStack copy() { return new ItemStack(item, count, components.copy()); }
                            public static boolean isSameItemSameComponents(ItemStack a, ItemStack b) {
                                return a.getItem() == b.getItem() && Objects.equals(a.components, b.components);
                            }
                            public static int hashItemAndComponents(ItemStack stack) {
                                return 31 * stack.getItem().hashCode() + stack.components.hashCode();
                            }
                            public List<Object> save() { return List.of(item.value(), count, components.asPatch()); }
                            public static ItemStack load(List<Object> saved) {
                                Item item = (Item) saved.get(0);
                                return new ItemStack(item.builtInRegistryHolder(), (Integer) saved.get(1), (DataComponentPatch) saved.get(2));
                            }
                        }""")));
        // As on a dedicated server, the client class is missing at run time.
        host.remove("net/minecraft/client/ClientThing");
    }

    @BeforeEach
    void load() {
        transformer.clearRedirectsForTesting();
        LegacyEarlyItemStackBridge.registerRedirects(transformer);
        run = new Run(transformer.getSyntheticClasses());
    }

    @AfterEach
    void reset() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("Minecraft's own constructor throws before binding, as 26.x does")
    void standInMatchesTheHost() throws Exception {
        Object diamond = run.item("Item");
        InvocationTargetException error = assertThrows(InvocationTargetException.class,
                () -> run.load(STACK).getConstructor(run.load("net.minecraft.world.level.ItemLike")).newInstance(diamond));
        assertEquals("Components not bound yet", error.getCause().getMessage());
    }

    @Test
    @DisplayName("before binding the stack holds its item and count, takes changes, and reads no defaults")
    void beforeBinding() throws Exception {
        Object diamond = run.item("Item");

        Object stack = run.of(diamond, 3);
        run.call(stack, "set", run.component("CUSTOM_NAME"), "Shiny");

        assertSame(diamond, run.call(stack, "getItem"));
        assertEquals(3, run.call(stack, "getCount"));
        assertEquals(false, run.call(stack, "isEmpty"));
        assertEquals("Shiny", run.call(stack, "get", run.component("CUSTOM_NAME")));
        assertEquals(1, run.call(stack, "getMaxStackSize"), "an unbound stack reads as having no default components");
    }

    @Test
    @DisplayName("after binding it reads the defaults, stacks with a normal stack both ways, and hashes the same")
    void afterBindingItActsAsANormalStack() throws Exception {
        Object diamond = run.item("Item");
        Object early = run.of(diamond, 3);
        Object copiedEarly = run.call(early, "copy");
        Object fromHolder = run.ofHolder(run.call(diamond, "builtInRegistryHolder"));

        run.bind(diamond, 64);
        Object normal = run.newStack(diamond, 5);

        assertEquals(64, run.call(early, "getMaxStackSize"));
        for (Object stack : List.of(early, copiedEarly, fromHolder)) {
            assertTrue(run.same(stack, normal), "early equals normal");
            assertTrue(run.same(normal, stack), "normal equals early");
            assertEquals(run.hash(normal), run.hash(stack));
        }
    }

    @Test
    @DisplayName("a change made before binding stays a change, and a default value set after binding is no change")
    void changesMadeEarlyCompareAsTheyWould() throws Exception {
        Object diamond = run.item("Item");
        Object named = run.of(diamond, 1);
        run.call(named, "set", run.component("CUSTOM_NAME"), "Shiny");

        run.bind(diamond, 64);
        Object normal = run.newStack(diamond, 1);
        Object namedNormal = run.newStack(diamond, 1);
        run.call(namedNormal, "set", run.component("CUSTOM_NAME"), "Shiny");
        Object early = run.of(diamond, 1);
        run.call(early, "set", run.component("MAX_STACK_SIZE"), 64);

        assertFalse(run.same(named, normal));
        assertFalse(run.same(normal, named));
        assertTrue(run.same(named, namedNormal) && run.same(namedNormal, named));
        assertTrue(run.same(early, normal), "a default value set after binding is compared with the bound default");
    }

    @Test
    @DisplayName("a value set before binding that turns out to be the default stays a change until the stack is saved and loaded")
    void defaultSetBeforeBindingStaysAChange() throws Exception {
        Object diamond = run.item("Item");
        Object early = run.of(diamond, 1);
        // Before binding the prototype is empty, so the stack keeps the value as a change.
        run.call(early, "set", run.component("MAX_STACK_SIZE"), 64);

        run.bind(diamond, 64);
        Object normal = run.newStack(diamond, 1);
        Object loaded = run.load(STACK).getMethod("load", List.class).invoke(null, run.call(early, "save"));

        assertFalse(run.same(early, normal), "documented: the change stays in the patch");
        assertFalse(run.same(normal, early));
        assertTrue(run.same(loaded, normal) && run.same(normal, loaded), "loading drops a change equal to the default");
    }

    @Test
    @DisplayName("an early stack saves as a normal one and loads equal to it")
    void savesAndLoads() throws Exception {
        Object diamond = run.item("Item");
        Object named = run.of(diamond, 2);
        run.call(named, "set", run.component("CUSTOM_NAME"), "Shiny");
        Object plain = run.of(diamond, 2);

        run.bind(diamond, 64);
        Object normal = run.newStack(diamond, 2);

        assertEquals(run.call(normal, "save"), run.call(plain, "save"));
        Object loaded = run.load(STACK).getMethod("load", List.class).invoke(null, run.call(named, "save"));
        assertTrue(run.same(loaded, named) && run.same(named, loaded));
        assertEquals("Shiny", run.call(loaded, "get", run.component("CUSTOM_NAME")));
    }

    @Test
    @DisplayName("a second binding, as a new world or a reload makes, is read by stacks built before the first")
    void followsARebind() throws Exception {
        Object diamond = run.item("Item");
        Object early = run.of(diamond, 1);
        run.bind(diamond, 64);
        assertEquals(64, run.call(early, "getMaxStackSize"));

        run.bind(diamond, 16);

        assertEquals(16, run.call(early, "getMaxStackSize"));
        assertTrue(run.same(early, run.newStack(diamond, 1)));
    }

    @Test
    @DisplayName("once bound, every factory answers with Minecraft's own stack")
    void boundFactoriesBuildTheHostsStack() throws Exception {
        Object diamond = run.item("Item");
        run.bind(diamond, 64);
        Object normalPrototype = run.prototype(run.newStack(diamond, 1));

        assertSame(normalPrototype, run.prototype(run.of(diamond, 1)), "a bound stack shares the bound prototype");
        assertSame(normalPrototype, run.prototype(run.ofHolder(run.call(diamond, "builtInRegistryHolder"))));
    }

    @Test
    @DisplayName("a holder that is not a registry reference keeps Minecraft's constructor")
    void directHolderIsTheHosts() throws Exception {
        Object diamond = run.item("Item");
        Object components = run.load("net.minecraft.core.component.DataComponentMap").getField("EMPTY").get(null);
        Object direct = run.load("net.minecraft.core.Holder$Direct").getConstructors()[0].newInstance(diamond, components);

        assertSame(components, run.prototype(run.ofHolder(direct)));
    }

    @Test
    @DisplayName("getDefaultInstance before binding: the plain stack for Minecraft's items, the mod's own where it builds one")
    void defaultInstanceBeforeBinding() throws Exception {
        Object diamond = run.item("Item");
        Object potion = run.item("PotionItem");
        Object modItem = run.item("ModItem");
        Object own = run.of(diamond, 7);
        run.load("net.minecraft.world.item.ModItem").getField("own").set(null, (java.util.function.Supplier<Object>) () -> own);

        Object plain = run.defaultInstance(diamond);
        Object plainPotion = run.defaultInstance(potion);

        assertSame(diamond, run.call(plain, "getItem"));
        assertSame(potion, run.call(plainPotion, "getItem"), "the potion's own default needs bound components");
        assertNull(run.call(plainPotion, "get", run.component("POTION_CONTENTS")));
        assertSame(own, run.defaultInstance(modItem));
    }

    @Test
    @DisplayName("getDefaultInstance before binding on an item whose methods name a missing client class")
    void defaultInstanceWithClientOnlySignature() throws Exception {
        Object item = run.item("ClientOnlyItem");

        assertSame(item, run.call(run.defaultInstance(item), "getItem"));
    }

    @Test
    @DisplayName("getDefaultInstance after binding is the item's own")
    void defaultInstanceAfterBinding() throws Exception {
        Object potion = run.item("PotionItem");
        run.bind(potion, 1);

        assertEquals("water", run.call(run.defaultInstance(potion), "get", run.component("POTION_CONTENTS")));
    }

    @Test
    @DisplayName("stacks built on many threads while the world binds all stack with a normal stack afterwards")
    void concurrentBuildsDuringBinding() throws Exception {
        Object diamond = run.item("Item");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<List<Object>>> built = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                built.add(pool.submit(() -> {
                    start.await();
                    List<Object> stacks = new ArrayList<>();
                    for (int n = 0; n < 200; n++) stacks.add(run.of(diamond, 1));
                    return stacks;
                }));
            }
            start.countDown();
            run.bind(diamond, 64);
            Object normal = run.newStack(diamond, 1);
            for (Future<List<Object>> stacks : built) {
                for (Object stack : stacks.get(30, TimeUnit.SECONDS)) {
                    assertTrue(run.same(stack, normal) && run.same(normal, stack));
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** The registered helper and the stand-ins in one isolated loader. */
    private static final class Run extends ClassLoader {
        private final Map<String, byte[]> classes = new HashMap<>();

        Run(Map<String, byte[]> synthetics) {
            super(EarlyItemStacksRuntimeTest.class.getClassLoader());
            host.forEach((name, bytes) -> classes.put(name.replace('/', '.'), bytes));
            synthetics.forEach((name, bytes) -> classes.put(name.replace('/', '.'), bytes));
        }

        @Override
        protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            Class<?> loaded = findLoadedClass(name);
            if (loaded != null) return loaded;
            byte[] bytes = classes.get(name);
            if (bytes == null) return super.loadClass(name, resolve);
            return defineClass(name, bytes, 0, bytes.length);
        }

        Class<?> load(String name) throws ClassNotFoundException {
            return loadClass(name);
        }

        Object item(String type) throws Exception {
            return load("net.minecraft.world.item." + type).getConstructor().newInstance();
        }

        Object component(String name) throws Exception {
            return load("net.minecraft.core.component.DataComponents").getField(name).get(null);
        }

        /** Binds the item's components as a world's data load does. */
        void bind(Object item, int maxStackSize) throws Exception {
            Object builder = load("net.minecraft.core.component.DataComponentMap").getMethod("builder").invoke(null);
            call(builder, "set", component("MAX_STACK_SIZE"), maxStackSize);
            Object holder = call(item, "builtInRegistryHolder");
            call(holder, "bindComponents", call(builder, "build"));
        }

        Object of(Object item, int count) throws Exception {
            return helper("of", load("net.minecraft.world.level.ItemLike"), int.class).invoke(null, item, count);
        }

        Object ofHolder(Object holder) throws Exception {
            return helper("of", load("net.minecraft.core.Holder")).invoke(null, holder);
        }

        Object defaultInstance(Object item) throws Exception {
            return helper("defaultInstance", load("net.minecraft.world.item.Item")).invoke(null, item);
        }

        Object newStack(Object item, int count) throws Exception {
            return load(STACK).getConstructor(load("net.minecraft.world.level.ItemLike"), int.class).newInstance(item, count);
        }

        boolean same(Object first, Object second) throws Exception {
            Class<?> stack = load(STACK);
            return (boolean) stack.getMethod("isSameItemSameComponents", stack, stack).invoke(null, first, second);
        }

        int hash(Object stack) throws Exception {
            return (int) load(STACK).getMethod("hashItemAndComponents", load(STACK)).invoke(null, stack);
        }

        /** The prototype inside a stack's component map. */
        Object prototype(Object stack) throws Exception {
            var components = load(STACK).getDeclaredField("components");
            components.setAccessible(true);
            var prototype = load("net.minecraft.core.component.PatchedDataComponentMap").getDeclaredField("prototype");
            prototype.setAccessible(true);
            return prototype.get(components.get(stack));
        }

        private Method helper(String name, Class<?>... parameters) throws Exception {
            return load(LegacyEarlyItemStackBridge.HELPER.replace('/', '.')).getMethod(name, parameters);
        }

        Object call(Object target, String name, Object... args) throws Exception {
            for (Method method : target.getClass().getMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == args.length) {
                    try {
                        return method.invoke(target, args);
                    } catch (InvocationTargetException e) {
                        if (e.getCause() instanceof Exception cause) throw cause;
                        throw e;
                    }
                }
            }
            throw new AssertionError("no " + name + " on " + target.getClass());
        }
    }

    private static Map<String, byte[]> compile(Map<String, String> sources) throws Exception {
        Path src = stubDir.resolve("src");
        Path out = stubDir.resolve("out");
        List<String> args = new ArrayList<>(List.of("-d", out.toString(), "--release", "17", "-nowarn",
                "-implicit:none", "-sourcepath", src.toString()));
        for (Map.Entry<String, String> source : sources.entrySet()) {
            Path file = src.resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
            args.add(file.toString());
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "the tests need a JDK to compile the 26.x stand-ins");
        assertEquals(0, compiler.run(null, null, null, args.toArray(new String[0])), "the 26.x stand-ins must compile");
        Map<String, byte[]> classes = new HashMap<>();
        try (var files = Files.walk(out)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".class"))::iterator) {
                String name = out.relativize(file).toString().replace(java.io.File.separatorChar, '/');
                classes.put(name.substring(0, name.length() - ".class".length()), Files.readAllBytes(file));
            }
        }
        return classes;
    }
}
