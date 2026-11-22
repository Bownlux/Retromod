/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.core;

import com.retromod.shim.common.embedded.LegacyLog;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Lets an old mod build a block or item before it knows the registry id, and gives it the real id when the mod registers it. */
public final class LegacyDeferredIds {

    /** Placeholder ids are {@code retromod:deferred_id_<n>}, so every id-derived string carries {@code retromod.deferred_id_<n>}. */
    static final String NAMESPACE = "retromod";
    static final String PREFIX = "deferred_id_";
    private static final String MARKER = NAMESPACE + "." + PREFIX;
    private static final String LOOT_MARKER = "blocks/" + PREFIX;

    // Each mod embeds its own copy of this class, and the mod that builds an item is often not the one that registers it,
    // so the pending properties live in one map every copy reaches through the system properties.
    private static final String SHARED_KEY = "retromod.deferred-ids";
    private static final String SHARED_ITEMS_KEY = "retromod.deferred-ids.items";
    private static final String COUNTER = "#next";
    // A settled block's placeholder description, for the block item that copied it as its own.
    private static final String BLOCK_DESCRIPTION = "#block:";
    private static final Map<String, Object> LOCAL = new ConcurrentHashMap<>();
    private static final Map<Object, String> LOCAL_ITEMS = Collections.synchronizedMap(new WeakHashMap<>());
    // The placeholder the item constructor running on this thread was given, until builtItem ties it to the item.
    private static final ThreadLocal<String> BUILDING = new ThreadLocal<>();

    private static volatile Field itemPropertiesId;
    private static volatile Field blockPropertiesId;
    // Set once a lookup failed, so a host without the member fails once rather than on every constructor.
    private static volatile boolean itemIdMissing;
    private static volatile boolean blockIdMissing;
    private static final Map<String, Field> FIELDS = new ConcurrentHashMap<>();
    private static volatile Method itemDescription;
    private static volatile Method blockDescription;
    private static volatile Method blockDrops;

    private LegacyDeferredIds() {}

    /** Gives item properties with no id a fresh placeholder right before Minecraft's item constructor reads it. */
    public static Item.Properties deferItem(Item.Properties properties) {
        BUILDING.remove();
        try {
            if (properties == null || !needsPlaceholder(properties, itemIdField())) return properties;
            String path = nextPath();
            properties.setId(itemKey(Identifier.fromNamespaceAndPath(NAMESPACE, path)));
            // Items keep no reference to their properties, so registration finds them here by the placeholder.
            pending().put(path, properties);
            BUILDING.set(path);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unreadable) {
            // Without the field the constructor fails as it did before, with Minecraft's own message.
        }
        return properties;
    }

    /** Ties the item Minecraft's constructor just built to the placeholder {@link #deferItem} gave it, if any. */
    public static void builtItem(Object item) {
        String path = BUILDING.get();
        BUILDING.remove();
        // A description can be overridden, as a block item's is with its block's, so the item itself is the reliable key.
        if (path != null && item instanceof Item) items().put(item, path);
    }

    /** Gives block properties with no id a fresh placeholder right before Minecraft's block constructor reads it. */
    public static BlockBehaviour.Properties deferBlock(BlockBehaviour.Properties properties) {
        try {
            if (properties == null || !needsPlaceholder(properties, blockIdField())) return properties;
            // A block keeps its properties, so registration reaches them through the block.
            properties.setId(blockKey(Identifier.fromNamespaceAndPath(NAMESPACE, nextPath())));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unreadable) {
            // As above.
        }
        return properties;
    }

    /** Gives a waiting block or item its id right before the mod's own registration call registers it under {@code id}. */
    public static void settle(Object id, Object value) {
        if (!(value instanceof Item) && !(value instanceof Block)) return;
        if (id instanceof Identifier location) {
            settleAt(value, location);
        } else if (id instanceof ResourceKey<?> key) {
            settleAt(value, key.identifier());
        } else if (id instanceof String name) {
            settleByName(value, name);
        }
    }

    private static void settleByName(Object value, String name) {
        Identifier id = name == null ? null : Identifier.tryParse(name);
        // A malformed name fails in Minecraft's register right after, with its own message.
        if (id != null) settleAt(value, id);
    }

    private static void settleAt(Object value, Identifier id) {
        if (id == null) return;
        try {
            if (value instanceof Item item) {
                settleItem(item, id);
            } else if (value instanceof Block block) {
                settleBlock(block, id);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            LegacyLog.warnOnce("deferred-id:" + failure.getClass().getName(), "Retromod could not give " + id
                    + " its registry id after the mod built it before registering it, so its name, model, or loot table"
                    + " may still name a retromod:" + PREFIX + " placeholder: " + failure);
        }
    }

    private static void settleItem(Item item, Identifier id) throws ReflectiveOperationException {
        String path = items().remove(item);
        if (path == null) path = placeholderPath(item.getDescriptionId());
        if (path == null) return;
        if (!(pending().remove(path) instanceof Item.Properties properties)) return;
        ResourceKey<Item> key = itemKey(id);
        properties.setId(key);
        String description = item.getDescriptionId();
        // A description the mod or a block gave the item stays; only one made from a placeholder is redone.
        if (description.contains(MARKER)) {
            // The host's own rules give the description, so a block item keeps its block. prefix.
            description = settledBlockDescription((String) itemDescriptionMethod().invoke(properties));
            setField(Item.class, "descriptionId", item, description);
        }
        ResourceKey<Item> placeholder = itemKey(Identifier.fromNamespaceAndPath(NAMESPACE, path));
        LegacyDeferredItemComponents.settle(item, placeholder, key, description, properties.effectiveModel());
        // Properties a mod reuses for its next item would otherwise hand that item this id.
        itemIdField().set(properties, null);
    }

    private static void settleBlock(Block block, Identifier id) throws ReflectiveOperationException {
        if (!waitingBlock(block)) return;
        String placeholder = block.getDescriptionId();
        BlockBehaviour.Properties properties = block.properties();
        properties.setId(blockKey(id));
        setField(BlockBehaviour.class, "drops", block, blockDropsMethod().invoke(properties));
        String description = (String) blockDescriptionMethod().invoke(properties);
        setField(BlockBehaviour.class, "descriptionId", block, description);
        // As for items: reused properties must not pass this id to the next block built from them.
        blockIdField().set(properties, null);
        // A block item built beside the block copied the placeholder description as its own.
        if (placeholder != null && placeholder.contains(MARKER)) pending().put(BLOCK_DESCRIPTION + placeholder, description);
    }

    /** A block's placeholder description, as a block item copies it, replaced by the one its block settled on. */
    private static String settledBlockDescription(String description) {
        return pending().get(BLOCK_DESCRIPTION + description) instanceof String settled ? settled : description;
    }

    /** A block is waiting while its description or default loot table still came from a placeholder. */
    private static boolean waitingBlock(Block block) {
        if (placeholderPath(block.getDescriptionId()) != null) return true;
        Optional<ResourceKey<net.minecraft.world.level.storage.loot.LootTable>> loot = block.getLootTable();
        if (loot == null || loot.isEmpty()) return false;
        Identifier table = loot.get().identifier();
        return NAMESPACE.equals(table.getNamespace()) && table.getPath().startsWith(LOOT_MARKER);
    }

    /** The {@code deferred_id_<n>} path named in a description id, or null. */
    static String placeholderPath(String description) {
        if (description == null) return null;
        int start = description.indexOf(MARKER);
        if (start < 0) return null;
        int digits = start + MARKER.length();
        int end = digits;
        while (end < description.length() && Character.isDigit(description.charAt(end))) end++;
        if (end == digits) return null;
        return PREFIX + description.substring(digits, end);
    }

    private static boolean needsPlaceholder(Object properties, Field idField) throws IllegalAccessException {
        if (!(idField.get(properties) instanceof ResourceKey<?> id)) return true;
        // Properties shared by several objects are given a fresh placeholder for each one.
        Identifier location = id.identifier();
        return NAMESPACE.equals(location.getNamespace()) && location.getPath().startsWith(PREFIX);
    }

    private static String nextPath() {
        Object counter = pending().computeIfAbsent(COUNTER, unused -> new AtomicInteger());
        int next = counter instanceof AtomicInteger shared ? shared.incrementAndGet() : LOCAL.size() + 1;
        return PREFIX + next;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> pending() {
        Properties system = System.getProperties();
        Object shared = system.get(SHARED_KEY);
        if (shared == null) {
            system.putIfAbsent(SHARED_KEY, new ConcurrentHashMap<String, Object>());
            shared = system.get(SHARED_KEY);
        }
        // Anything else under the key keeps this copy working on its own.
        return shared instanceof ConcurrentHashMap<?, ?> map ? (Map<String, Object>) map : LOCAL;
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, String> items() {
        Properties system = System.getProperties();
        Object shared = system.get(SHARED_ITEMS_KEY);
        if (shared == null) {
            system.putIfAbsent(SHARED_ITEMS_KEY, Collections.synchronizedMap(new WeakHashMap<Object, String>()));
            shared = system.get(SHARED_ITEMS_KEY);
        }
        return shared instanceof Map<?, ?> map ? (Map<Object, String>) map : LOCAL_ITEMS;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static ResourceKey<Item> itemKey(Identifier id) {
        return ResourceKey.create((ResourceKey) Registries.ITEM, id);
    }

    private static ResourceKey<Block> blockKey(Identifier id) {
        return ResourceKey.create(Registries.BLOCK, id);
    }

    private static Field itemIdField() throws NoSuchFieldException {
        Field field = itemPropertiesId;
        if (field != null) return field;
        if (itemIdMissing) throw new NoSuchFieldException("Item.Properties.id");
        try {
            return itemPropertiesId = accessible(Item.Properties.class.getDeclaredField("id"));
        } catch (NoSuchFieldException | RuntimeException missing) {
            itemIdMissing = true;
            throw missing;
        }
    }

    private static Field blockIdField() throws NoSuchFieldException {
        Field field = blockPropertiesId;
        if (field != null) return field;
        if (blockIdMissing) throw new NoSuchFieldException("BlockBehaviour.Properties.id");
        try {
            return blockPropertiesId = accessible(BlockBehaviour.Properties.class.getDeclaredField("id"));
        } catch (NoSuchFieldException | RuntimeException missing) {
            blockIdMissing = true;
            throw missing;
        }
    }

    private static Method itemDescriptionMethod() throws NoSuchMethodException {
        Method method = itemDescription;
        if (method == null) itemDescription = method = accessible(Item.Properties.class.getDeclaredMethod("effectiveDescriptionId"));
        return method;
    }

    private static Method blockDescriptionMethod() throws NoSuchMethodException {
        Method method = blockDescription;
        if (method == null) {
            blockDescription = method = accessible(BlockBehaviour.Properties.class.getDeclaredMethod("effectiveDescriptionId"));
        }
        return method;
    }

    private static Method blockDropsMethod() throws NoSuchMethodException {
        Method method = blockDrops;
        if (method == null) blockDrops = method = accessible(BlockBehaviour.Properties.class.getDeclaredMethod("effectiveDrops"));
        return method;
    }

    private static void setField(Class<?> owner, String name, Object target, Object value) throws ReflectiveOperationException {
        String key = owner.getName() + "." + name;
        Field field = FIELDS.get(key);
        if (field == null) FIELDS.put(key, field = accessible(owner.getDeclaredField(name)));
        field.set(target, value);
    }

    private static <A extends java.lang.reflect.AccessibleObject> A accessible(A member) {
        member.setAccessible(true);
        return member;
    }
}
