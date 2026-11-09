/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Stand-ins for the save classes of a 1.21.1 host and a 26.x host, and two mods built for 1.21.1. */
final class ValueIoStandIns {

    private ValueIoStandIns() {}

    // ---- the 26.x host: saves through ValueOutput and ValueInput ----

    /** Keeps the 1.21.1 getter, which answered 0 for a missing key, so the mod bodies run as written. */
    public static class CompoundTag {
        public static final Codec CODEC = new Codec();
        public final Map<String, Object> values = new LinkedHashMap<>();

        public void putInt(String key, int value) {
            values.put(key, value);
        }

        public int getInt(String key) {
            return values.get(key) instanceof Integer value ? value : 0;
        }

        public CompoundTag merge(CompoundTag other) {
            values.putAll(other.values);
            return this;
        }

        public CompoundTag copy() {
            CompoundTag copy = new CompoundTag();
            copy.values.putAll(values);
            return copy;
        }

        public Tag get(String key) {
            return values.get(key) instanceof Tag tag ? tag : null;
        }

        public ListTag getListOrEmpty(String key) {
            return values.get(key) instanceof ListTag list ? list : new ListTag();
        }

        public Optional<String> getString(String key) {
            return values.get(key) instanceof String text ? Optional.of(text) : Optional.empty();
        }
    }

    public static class Codec {}

    /** Minecraft's tag and text types are interfaces, which a verifier takes any reference for. */
    public interface Tag {}

    public interface Component {}

    public interface DynamicOps {}

    public static class ListTag extends java.util.ArrayList<Tag> implements Tag {}

    public static class StringTag implements Tag {}

    /** {@code MapCodec.assumeMapUnsafe(CompoundTag.CODEC)}: reads or writes every key of a map as one compound. */
    public static class MapCodec {
        public final Codec codec;

        public MapCodec(Codec codec) {
            this.codec = codec;
        }

        public static MapCodec assumeMapUnsafe(Codec codec) {
            return new MapCodec(codec);
        }
    }

    public interface Provider {}

    public interface RegistryAccess extends Provider {
        static Frozen fromRegistryOfRegistries(Registry registry) {
            return new Frozen(registry);
        }
    }

    /** The registries built from the built-in ones alone. */
    public static class Frozen implements RegistryAccess {
        public final Registry source;

        public Frozen(Registry source) {
            this.source = source;
        }
    }

    public static class Registry {}

    public static class BuiltInRegistries {
        public static final Registry REGISTRY = new Registry();
    }

    public static class Level {
        public final RegistryAccess registries;

        public Level(RegistryAccess registries) {
            this.registries = registries;
        }

        public RegistryAccess registryAccess() {
            return registries;
        }
    }

    public static class ProblemReporter {
        public static final ProblemReporter DISCARDING = new ProblemReporter();
    }

    public interface ValueOutput {
        void store(MapCodec codec, Object value);

        void store(String key, Codec codec, Object value);

        void putInt(String key, int value);
    }

    public interface ValueInput {
        Optional<Object> read(MapCodec codec);

        Optional<Object> read(String key, Codec codec);

        Provider lookup();

        int getIntOr(String key, int fallback);
    }

    public static class TagValueOutput implements ValueOutput {
        public final CompoundTag output = new CompoundTag();
        public final Provider registries;

        public TagValueOutput(Provider registries) {
            this.registries = registries;
        }

        public static TagValueOutput createWithContext(ProblemReporter reporter, Provider registries) {
            return new TagValueOutput(registries);
        }

        public static TagValueOutput createWithoutContext(ProblemReporter reporter) {
            return new TagValueOutput(null);
        }

        public CompoundTag buildResult() {
            return output;
        }

        @Override
        public void store(MapCodec codec, Object value) {
            output.merge((CompoundTag) value);
        }

        @Override
        public void store(String key, Codec codec, Object value) {
            output.values.put(key, value);
        }

        @Override
        public void putInt(String key, int value) {
            output.putInt(key, value);
        }
    }

    /** An output that is not backed by a compound, such as a loader's own, which only takes stores. */
    public static class StoringOutput implements ValueOutput {
        public final Map<String, Object> stored = new LinkedHashMap<>();

        @Override
        public void store(MapCodec codec, Object value) {
            stored.putAll(((CompoundTag) value).values);
        }

        @Override
        public void store(String key, Codec codec, Object value) {
            stored.put(key, value);
        }

        @Override
        public void putInt(String key, int value) {
            stored.put(key, value);
        }
    }

    public static class TagValueInput implements ValueInput {
        public final CompoundTag input;
        public final Provider registries;

        public TagValueInput(Provider registries, CompoundTag input) {
            this.registries = registries;
            this.input = input;
        }

        public static ValueInput create(ProblemReporter reporter, Provider registries, CompoundTag input) {
            return new TagValueInput(registries, input);
        }

        @Override
        public Optional<Object> read(MapCodec codec) {
            return Optional.of(input.copy());
        }

        @Override
        public Optional<Object> read(String key, Codec codec) {
            return Optional.ofNullable(input.values.get(key));
        }

        @Override
        public Provider lookup() {
            return registries;
        }

        @Override
        public int getIntOr(String key, int fallback) {
            return input.values.get(key) instanceof Integer value ? value : fallback;
        }
    }

    /** The host's own save and load entry points, which only call the new methods. */
    public static class BlockEntity {
        public Level level;

        public Level getLevel() {
            return level;
        }

        protected void saveAdditional(ValueOutput output) {}

        protected void loadAdditional(ValueInput input) {}

        public CompoundTag saveWithoutMetadata(Provider registries) {
            TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries);
            saveAdditional(output);
            return output.buildResult();
        }

        public void saveInto(ValueOutput output) {
            saveAdditional(output);
        }

        public void saveWithId(ValueOutput output) {
            output.putInt("Id", 1);
            saveAdditional(output);
        }

        public static void addEntityType(ValueOutput output, BlockEntityType type) {
            output.putInt("Id", type.id);
        }

        public final void loadWithComponents(ValueInput input) {
            loadAdditional(input);
        }
    }

    public static class BlockEntityType {
        public final int id;

        public BlockEntityType(int id) {
            this.id = id;
        }
    }

    /** A player's hunger, saved apart from any entity. */
    public static class FoodData {
        public int foodLevel;
        public Provider loadedWith;

        public void addAdditionalSaveData(ValueOutput output) {
            output.putInt("foodLevel", foodLevel);
        }

        public void readAdditionalSaveData(ValueInput input) {
            foodLevel = input.getIntOr("foodLevel", 20);
            loadedWith = input.lookup();
        }
    }

    /** A vanilla block entity with data of its own, which a mod's subclass saves through super. */
    public static class BaseContainerBlockEntity extends BlockEntity {
        public int items;

        @Override
        protected void saveAdditional(ValueOutput output) {
            output.putInt("Items", items);
        }

        @Override
        protected void loadAdditional(ValueInput input) {
            items = input.getIntOr("Items", 0);
        }
    }

    public abstract static class Entity {
        public Level level;

        public RegistryAccess registryAccess() {
            return level.registryAccess();
        }

        protected abstract void addAdditionalSaveData(ValueOutput output);

        protected abstract void readAdditionalSaveData(ValueInput input);

        public void saveWithoutId(ValueOutput output) {
            output.putInt("Air", 300);
            addAdditionalSaveData(output);
        }

        public boolean save(ValueOutput output) {
            saveWithoutId(output);
            return true;
        }

        public void load(ValueInput input) {
            readAdditionalSaveData(input);
        }
    }

    public static class Mob extends Entity {
        public int health;

        @Override
        protected void addAdditionalSaveData(ValueOutput output) {
            output.putInt("Health", health);
        }

        @Override
        protected void readAdditionalSaveData(ValueInput input) {
            health = input.getIntOr("Health", 0);
        }
    }

    // ---- the 1.21.1 host, only to compile the mods below ----

    public static class OldBlockEntity {
        protected void saveAdditional(CompoundTag tag, Provider registries) {}

        protected void loadAdditional(CompoundTag tag, Provider registries) {}

        public void loadWithComponents(CompoundTag tag, Provider registries) {}

        public CompoundTag saveWithId(Provider registries) {
            return new CompoundTag();
        }

        public static void addEntityType(CompoundTag tag, BlockEntityType type) {}
    }

    public static class OldFoodData {
        public void addAdditionalSaveData(CompoundTag tag) {}

        public void readAdditionalSaveData(CompoundTag tag) {}
    }

    public static class OldBaseContainerBlockEntity extends OldBlockEntity {}

    public abstract static class OldEntity {
        protected abstract void addAdditionalSaveData(CompoundTag tag);

        protected abstract void readAdditionalSaveData(CompoundTag tag);

        public CompoundTag saveWithoutId(CompoundTag tag) {
            return tag;
        }

        public boolean save(CompoundTag tag) {
            return true;
        }

        public void load(CompoundTag tag) {}
    }

    public static class OldMob extends OldEntity {
        @Override
        protected void addAdditionalSaveData(CompoundTag tag) {}

        @Override
        protected void readAdditionalSaveData(CompoundTag tag) {}
    }

    // ---- two mods built for 1.21.1 ----

    /** A chest that also saves a counter, after its container's own data. */
    public static class CounterChest extends OldBaseContainerBlockEntity {
        public int count;
        public Provider savedWith;
        public Provider loadedWith;

        @Override
        protected void saveAdditional(CompoundTag tag, Provider registries) {
            super.saveAdditional(tag, registries);
            tag.putInt("Count", count);
            savedWith = registries;
        }

        @Override
        protected void loadAdditional(CompoundTag tag, Provider registries) {
            super.loadAdditional(tag, registries);
            count = tag.getInt("Count");
            loadedWith = registries;
        }

        /** Copies another chest's saved data the 1.21.1 way. */
        public void copyFrom(CompoundTag tag, Provider registries) {
            loadWithComponents(tag, registries);
        }

        /** The 1.21.1 way to save a chest with its type, as a picked-up block does. */
        public CompoundTag saveForItem(Provider registries, BlockEntityType type) {
            CompoundTag tag = saveWithId(registries);
            addEntityType(tag, type);
            return tag;
        }

        /** A HUD mod's copy of the player's hunger, made the 1.21.1 way. */
        public static CompoundTag copyHunger(OldFoodData food, CompoundTag into) {
            food.addAdditionalSaveData(into);
            food.readAdditionalSaveData(into);
            return into;
        }
    }

    /** A bigger chest from the same mod, whose super call reaches the mod's own old method. */
    public static class BigCounterChest extends CounterChest {
        public int size;

        @Override
        protected void saveAdditional(CompoundTag tag, Provider registries) {
            super.saveAdditional(tag, registries);
            tag.putInt("Size", size);
        }
    }

    /** A mob that saves how angry it is beside the mob data. */
    public static class Golem extends OldMob {
        public int anger;

        @Override
        protected void addAdditionalSaveData(CompoundTag tag) {
            super.addAdditionalSaveData(tag);
            tag.putInt("Anger", anger);
        }

        @Override
        protected void readAdditionalSaveData(CompoundTag tag) {
            super.readAdditionalSaveData(tag);
            anger = tag.getInt("Anger");
        }

        /** The 1.21.1 way to snapshot an entity. */
        public CompoundTag snapshot() {
            return saveWithoutId(new CompoundTag());
        }

        public void restore(CompoundTag tag) {
            load(tag);
        }
    }

    // ---- the 1.20.1 host, only to compile the mod below ----

    /** A 1.20.1 block entity: it loaded and saved a tag and kept Forge's persistent data. */
    public static class LegacyBlockEntity {
        public void load(CompoundTag tag) {}

        protected void saveAdditional(CompoundTag tag) {}

        public CompoundTag getPersistentData() {
            return new CompoundTag();
        }
    }

    public static class LegacyContainerBlockEntity extends LegacyBlockEntity {}

    // ---- a mod built for Forge 1.20.1 ----

    /** A chest that saves a counter and marks itself in Forge's persistent data. */
    public static class MarkedChest extends LegacyContainerBlockEntity {
        public int count;

        @Override
        public void load(CompoundTag tag) {
            super.load(tag);
            count = tag.getInt("Count");
        }

        @Override
        protected void saveAdditional(CompoundTag tag) {
            super.saveAdditional(tag);
            tag.putInt("Count", count);
        }

        public void mark(int value) {
            getPersistentData().putInt("Marked", value);
        }

        public int marked() {
            return getPersistentData().getInt("Marked");
        }
    }

    /** A bigger chest from the same mod, which inherits its parent's saving. */
    public static class BigMarkedChest extends MarkedChest {}

    /** A plain block entity of the same mod with no save code of its own. */
    public static class Lamp extends LegacyBlockEntity {
        public void mark(int value) {
            getPersistentData().putInt("Marked", value);
        }
    }

    /** A block entity of a mod that compares its block entities by position, so any two are equal. */
    public static class TwinLamp extends LegacyBlockEntity {
        public void mark(int value) {
            getPersistentData().putInt("Marked", value);
        }

        public int marked() {
            return getPersistentData().getInt("Marked");
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof TwinLamp;
        }

        @Override
        public int hashCode() {
            return 1;
        }
    }

    // ---- a library mod and an addon in another jar, as Moonlight and Supplementaries ----

    /** The library's display block entity, which saves what it shows. */
    public static class LibraryShelf extends LegacyContainerBlockEntity {
        public int stored;

        @Override
        public void load(CompoundTag tag) {
            super.load(tag);
            stored = tag.getInt("Stored");
        }

        @Override
        protected void saveAdditional(CompoundTag tag) {
            super.saveAdditional(tag);
            tag.putInt("Stored", stored);
        }
    }

    /** The addon's block entity on top of it. */
    public static class BookPile extends LibraryShelf {
        public int books;

        @Override
        public void load(CompoundTag tag) {
            super.load(tag);
            books = tag.getInt("Books");
        }

        @Override
        protected void saveAdditional(CompoundTag tag) {
            super.saveAdditional(tag);
            tag.putInt("Books", books);
        }
    }
}
