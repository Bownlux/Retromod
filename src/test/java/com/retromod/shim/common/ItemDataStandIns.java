/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/** Stand-ins for the NBT, data fixer and text classes the rebuilt item and name upgrades call, shaped like the host's. */
final class ItemDataStandIns {

    private ItemDataStandIns() {}

    public interface Tag {
        Tag copy();
    }

    public static class StringTag implements Tag {
        public final String value;

        public StringTag(String value) {
            this.value = value;
        }

        @Override
        public StringTag copy() {
            return new StringTag(value);
        }
    }

    public static class ListTag extends ArrayList<Tag> implements Tag {
        @Override
        public Tag get(int index) {
            return super.get(index);
        }

        @Override
        public ListTag copy() {
            ListTag copy = new ListTag();
            for (Tag tag : this) copy.add(tag.copy());
            return copy;
        }
    }

    /** Ints, bytes, strings, lists and compounds by key. */
    public static class CompoundTag implements Tag {
        public final Map<String, Object> values = new LinkedHashMap<>();

        public boolean contains(String key) {
            return values.containsKey(key);
        }

        public int getIntOr(String key, int fallback) {
            return values.get(key) instanceof Integer value ? value : fallback;
        }

        public byte getByteOr(String key, byte fallback) {
            return values.get(key) instanceof Byte value ? value : fallback;
        }

        public void putInt(String key, int value) {
            values.put(key, value);
        }

        public Tag put(String key, Tag value) {
            Object previous = values.put(key, value);
            return previous instanceof Tag tag ? tag : null;
        }

        public Tag get(String key) {
            return values.get(key) instanceof Tag tag ? tag : null;
        }

        public Optional<String> getString(String key) {
            return values.get(key) instanceof StringTag text ? Optional.of(text.value) : Optional.empty();
        }

        public ListTag getListOrEmpty(String key) {
            return values.get(key) instanceof ListTag list ? list : new ListTag();
        }

        public Set<String> keySet() {
            return values.keySet();
        }

        /** A deep copy, as the host's is. */
        @Override
        public CompoundTag copy() {
            CompoundTag copy = new CompoundTag();
            values.forEach((key, value) -> copy.values.put(key, value instanceof Tag tag ? tag.copy() : value));
            return copy;
        }
    }

    public interface DynamicOps {}

    public static class NbtOps implements DynamicOps {
        public static final NbtOps INSTANCE = new NbtOps();
    }

    public static class JsonOps implements DynamicOps {
        public static final JsonOps INSTANCE = new JsonOps();
    }

    public static class Dynamic {
        public final DynamicOps ops;
        public final Object value;

        public Dynamic(DynamicOps ops, Object value) {
            this.ops = ops;
            this.value = value;
        }

        public Object getValue() {
            return value;
        }
    }

    public interface TypeReference {}

    public interface DataFixer {
        Dynamic update(TypeReference type, Dynamic input, int version, int newVersion);
    }

    /** Records each update and does what the host's item fixes do to the fields the tests read. */
    public static class RecordingFixer implements DataFixer {
        public static final List<int[]> UPDATES = new ArrayList<>();

        @Override
        public Dynamic update(TypeReference type, Dynamic input, int version, int newVersion) {
            UPDATES.add(new int[]{version, newVersion});
            CompoundTag stack = ((CompoundTag) input.getValue()).copy();
            stack.values.put("count", (int) (Byte) stack.values.remove("Count"));
            // grass_path became dirt_path in 1.17 (data version 2690), so only an older stack is renamed.
            if (version < 2690 && stack.getString("id").orElse("").equals("minecraft:grass_path")) {
                stack.values.put("id", new StringTag("minecraft:dirt_path"));
            }
            return new Dynamic(input.ops, stack);
        }
    }

    public static class DataFixers {
        public static DataFixer getDataFixer() {
            return new RecordingFixer();
        }
    }

    public static class References {
        public static final TypeReference ITEM_STACK = null;
    }

    public static class DataVersion {
        public int version() {
            return 4700;
        }
    }

    public interface WorldVersion {
        DataVersion dataVersion();
    }

    public static class CurrentVersion implements WorldVersion {
        @Override
        public DataVersion dataVersion() {
            return new DataVersion();
        }
    }

    public static class SharedConstants {
        public static WorldVersion getCurrentVersion() {
            return new CurrentVersion();
        }
    }

    public interface Component {
        MutableComponent copy();
    }

    public static class MutableComponent implements Component {
        public final String text;

        public MutableComponent(String text) {
            this.text = text;
        }

        @Override
        public MutableComponent copy() {
            return this;
        }
    }

    public interface DataResult {
        Object getOrThrow(Function<String, ? extends RuntimeException> failure);

        Optional<Object> result();
    }

    public static class Result implements DataResult {
        private final Object value;

        public Result(Object value) {
            this.value = value;
        }

        @Override
        public Object getOrThrow(Function<String, ? extends RuntimeException> failure) {
            if (value == null) throw failure.apply("not a text component");
            return value;
        }

        @Override
        public Optional<Object> result() {
            return Optional.ofNullable(value);
        }
    }

    public interface Codec {
        DataResult parse(DynamicOps ops, Object input);

        DataResult encodeStart(DynamicOps ops, Object input);
    }

    /** Reads text the way the host's codec does, from a string, an array or a {"text": ...} object, and writes it as a compound. */
    public static class TextCodec implements Codec {
        @Override
        public DataResult parse(DynamicOps ops, Object input) {
            return new Result(input instanceof JsonElement json ? text(json) : null);
        }

        private static MutableComponent text(JsonElement json) {
            if (json instanceof JsonPrimitive primitive && primitive.isString()) return new MutableComponent(primitive.getAsString());
            if (json instanceof JsonObject object && object.has("text")) return new MutableComponent(object.get("text").getAsString());
            if (!(json instanceof JsonArray array)) return null;
            StringBuilder joined = new StringBuilder();
            for (JsonElement part : array) {
                MutableComponent piece = text(part);
                if (piece == null) return null;
                joined.append(piece.text);
            }
            return new MutableComponent(joined.toString());
        }

        @Override
        public DataResult encodeStart(DynamicOps ops, Object input) {
            CompoundTag encoded = new CompoundTag();
            encoded.values.put("text", new StringTag(((MutableComponent) input).text));
            return new Result(encoded);
        }
    }

    public static class ComponentSerialization {
        public static final Codec CODEC = new TextCodec();
    }
}
