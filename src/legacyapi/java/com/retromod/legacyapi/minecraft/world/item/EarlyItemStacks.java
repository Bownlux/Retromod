/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.item;

import com.retromod.shim.common.embedded.LegacyLog;
import it.unimi.dsi.fastutil.objects.AbstractReference2ObjectMap;
import it.unimi.dsi.fastutil.objects.ObjectCollection;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import it.unimi.dsi.fastutil.objects.Reference2ObjectArrayMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectMaps;
import it.unimi.dsi.fastutil.objects.ReferenceSet;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.PatchedDataComponentMap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Builds the item stacks an old mod asks for before a world has bound item components. */
public final class EarlyItemStacks {

    private static final String SIMPLE_MAP = "net.minecraft.core.component.DataComponentMap$Builder$SimpleMap";
    private static final String UNBOUND = "Components not bound yet";

    // One late prototype per item, so early stacks of one item share it as bound stacks share theirs.
    private static final Map<Holder.Reference<Item>, DataComponentMap> PROTOTYPES = new ConcurrentHashMap<>();
    private static final ClassValue<Boolean> OWN_DEFAULT = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            try {
                return type.getMethod("getDefaultInstance").getDeclaringClass() != Item.class;
            } catch (NoSuchMethodException | SecurityException e) {
                return false;
            } catch (LinkageError clientOnlySignature) {
                // A dedicated server cannot list the methods of an item that names client classes; calling it decides.
                return true;
            }
        }
    };
    private static volatile Object shape;
    private static volatile boolean failureLogged;

    private EarlyItemStacks() {}

    /** {@code new ItemStack(item)}. */
    public static ItemStack of(ItemLike item) {
        ItemStack early = deferred(unbound(item), 1, null);
        return early != null ? early : new ItemStack(item);
    }

    /** {@code new ItemStack(item, count)}. */
    public static ItemStack of(ItemLike item, int count) {
        ItemStack early = deferred(unbound(item), count, null);
        return early != null ? early : new ItemStack(item, count);
    }

    /** {@code new ItemStack(holder)}. */
    public static ItemStack of(Holder<Item> holder) {
        ItemStack early = deferred(unbound(holder), 1, null);
        return early != null ? early : new ItemStack(holder);
    }

    /** {@code new ItemStack(holder, count)}. */
    public static ItemStack of(Holder<Item> holder, int count) {
        ItemStack early = deferred(unbound(holder), count, null);
        return early != null ? early : new ItemStack(holder, count);
    }

    /** {@code new ItemStack(holder, count, patch)}. */
    public static ItemStack of(Holder<Item> holder, int count, DataComponentPatch patch) {
        ItemStack early = patch == null ? null : deferred(unbound(holder), count, patch);
        return early != null ? early : new ItemStack(holder, count, patch);
    }

    /** {@code item.getDefaultInstance()}: before binding, the plain stack of the item rather than the empty stack. */
    public static ItemStack defaultInstance(Item item) {
        Holder.Reference<Item> holder = unbound(item);
        if (holder == null) return item.getDefaultInstance();
        // An item's own default may build its stack in mod code, which works early; the host's cannot.
        if (OWN_DEFAULT.get(item.getClass())) {
            try {
                return item.getDefaultInstance();
            } catch (NullPointerException hostStack) {
                if (!UNBOUND.equals(hostStack.getMessage())) throw hostStack;
            }
        }
        ItemStack early = deferred(holder, 1, null);
        return early != null ? early : ItemStack.EMPTY;
    }

    private static Holder.Reference<Item> unbound(ItemLike like) {
        Item item = like == null ? null : like.asItem();
        return item == null ? null : unbound(item.builtInRegistryHolder());
    }

    private static Holder.Reference<Item> unbound(Holder<Item> holder) {
        // Only a registry reference binds late; a direct holder carries its components.
        if (holder instanceof Holder.Reference<Item> reference && !reference.areComponentsBound()) return reference;
        return null;
    }

    /** A stack on the item's late prototype, or null to let the host's constructor answer. */
    private static ItemStack deferred(Holder.Reference<Item> holder, int count, DataComponentPatch patch) {
        if (holder == null) return null;
        Shape host = shape();
        if (host == null) return null;
        try {
            DataComponentMap prototype = PROTOTYPES.computeIfAbsent(holder, host::latePrototype);
            PatchedDataComponentMap components = patch == null
                    ? new PatchedDataComponentMap(prototype) : PatchedDataComponentMap.fromPatch(prototype, patch);
            // The constructor every public one ends in, so loader patches to it still run.
            return host.stack.newInstance(holder, count, components);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            if (!failureLogged) {
                failureLogged = true;
                LegacyLog.debug("Built an item stack with Minecraft's own constructor because the early stack failed: " + failure);
            }
            return null;
        }
    }

    private static Shape shape() {
        Object found = shape;
        if (found == null) {
            synchronized (EarlyItemStacks.class) {
                found = shape;
                if (found == null) {
                    found = Shape.find();
                    shape = found;
                }
            }
        }
        return found instanceof Shape host ? host : null;
    }

    /** The host members an early stack is built from, found once. */
    private static final class Shape {
        final Constructor<ItemStack> stack;
        final Constructor<?> simpleMap;
        final Method innerMap;
        final Class<?> simpleMapType;

        private Shape(Constructor<ItemStack> stack, Class<?> simpleMapType, RecordComponent inner)
                throws NoSuchMethodException {
            this.stack = stack;
            this.simpleMapType = simpleMapType;
            this.simpleMap = simpleMapType.getDeclaredConstructor(inner.getType());
            this.innerMap = inner.getAccessor();
        }

        /** The shape, or a failure string when the host is not shaped as 26.1 to 26.3 are. */
        static Object find() {
            try {
                Constructor<ItemStack> stack = ItemStack.class.getDeclaredConstructor(
                        Holder.class, int.class, PatchedDataComponentMap.class);
                // A bound item's prototype is this record around a fastutil map; the late one must be the same record.
                Class<?> simpleMapType = Class.forName(SIMPLE_MAP, false, DataComponentMap.class.getClassLoader());
                RecordComponent[] parts = simpleMapType.getRecordComponents();
                if (parts == null || parts.length != 1 || !parts[0].getType().isAssignableFrom(LateEntries.class)) {
                    throw new IllegalStateException(SIMPLE_MAP + " is not a record of one component map");
                }
                Shape host = new Shape(stack, simpleMapType, parts[0]);
                stack.setAccessible(true);
                host.simpleMap.setAccessible(true);
                host.innerMap.setAccessible(true);
                return host;
            } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
                LegacyLog.debug("Left item stacks built before a world loads to Minecraft's own constructor: " + failure);
                return failure.toString();
            }
        }

        /** A prototype that reads the item's components once they are bound and reads as empty before. */
        DataComponentMap latePrototype(Holder.Reference<Item> holder) {
            try {
                return (DataComponentMap) simpleMap.newInstance(new LateEntries(holder, this));
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("could not build the late prototype of " + holder, e);
            }
        }

        /** The map inside a bound prototype, or a copy of its entries when the prototype has another shape. */
        @SuppressWarnings("unchecked")
        Reference2ObjectMap<Object, Object> entriesOf(DataComponentMap components) {
            if (components.getClass() == simpleMapType) {
                try {
                    return (Reference2ObjectMap<Object, Object>) innerMap.invoke(components);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("could not read the bound components of an item", e);
                }
            }
            Reference2ObjectArrayMap<Object, Object> copy = new Reference2ObjectArrayMap<>();
            for (DataComponentType<?> type : components.keySet()) copy.put(type, components.get(type));
            return copy;
        }
    }

    /** The item's bound component map, read through on each call, so the late prototype equals and hashes as it. */
    private static final class LateEntries extends AbstractReference2ObjectMap<Object, Object> {
        private final Holder.Reference<Item> holder;
        private final Shape shape;
        private volatile Bound bound;

        private record Bound(DataComponentMap components, Reference2ObjectMap<Object, Object> entries) {}

        LateEntries(Holder.Reference<Item> holder, Shape shape) {
            this.holder = holder;
            this.shape = shape;
        }

        /** The bound entries, read again after each rebind, as a new world or a reload binds new ones. */
        private Reference2ObjectMap<Object, Object> entries() {
            Bound last = bound;
            // Components never unbind, so once seen bound the holder answers directly.
            if (last == null && !holder.areComponentsBound()) return Reference2ObjectMaps.emptyMap();
            DataComponentMap components = holder.components();
            if (last != null && last.components() == components) return last.entries();
            Reference2ObjectMap<Object, Object> entries = shape.entriesOf(components);
            bound = new Bound(components, entries);
            return entries;
        }

        @Override
        public Object get(Object key) {
            return entries().get(key);
        }

        @Override
        public boolean containsKey(Object key) {
            return entries().containsKey(key);
        }

        @Override
        public int size() {
            return entries().size();
        }

        @Override
        public boolean isEmpty() {
            return entries().isEmpty();
        }

        @Override
        public ReferenceSet<Object> keySet() {
            return entries().keySet();
        }

        @Override
        public ObjectCollection<Object> values() {
            return entries().values();
        }

        @Override
        public ObjectSet<Reference2ObjectMap.Entry<Object, Object>> reference2ObjectEntrySet() {
            return entries().reference2ObjectEntrySet();
        }

        @Override
        public int hashCode() {
            return entries().hashCode();
        }

        @Override
        public boolean equals(Object other) {
            if (other == this) return true;
            // Another late map compares by the entries it stands for.
            return entries().equals(other instanceof LateEntries late ? late.entries() : other);
        }

        @Override
        public String toString() {
            return entries().toString();
        }
    }
}
