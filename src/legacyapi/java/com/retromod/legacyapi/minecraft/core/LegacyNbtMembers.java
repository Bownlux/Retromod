/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.core;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.retromod.legacyapi.minecraft.network.chat.LegacyComponentJson;
import com.retromod.legacyapi.minecraft.world.ContainerHelperMembers;
import com.retromod.legacyapi.minecraft.world.item.ItemStackMembers;
import com.retromod.legacyapi.minecraft.world.item.LegacyItemData;
import com.retromod.legacyapi.minecraft.world.level.block.entity.LegacyLootTables;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.TagValueOutput;

import java.io.IOException;
import java.io.StringReader;
import java.util.function.Function;

/** The NBT members that took no registries before 1.20.5, on hosts that save through ValueOutput, with the save's registries. */
public final class LegacyNbtMembers {

    // The keys a container's loot table has always been saved under.
    private static final String LOOT_TABLE = "LootTable";
    private static final String LOOT_SEED = "LootTableSeed";
    private static final String ITEMS = "Items";
    private static final String SLOT = "Slot";
    private static final String CUSTOM_NAME = "CustomName";
    /** 1.20.3's data version: from it until 1.21.5 a plain name was saved as a JSON string. */
    private static final int FIRST_JSON_STRING_NAME = 3698;
    /** 1.21.5's data version: from it a name is saved as a text component, not as JSON. */
    private static final int FIRST_NBT_NAME = 4325;
    // Gson's own element reader, on a reader left strict: JsonParser would read the text leniently.
    private static final TypeAdapter<JsonElement> JSON = new Gson().getAdapter(JsonElement.class);
    // The keys a stack wrote into a tag, from 1.20.5 and before.
    private static final String[] STACK_KEYS = {"id", "count", "components", "Count", "tag"};

    private LegacyNbtMembers() {}

    /** {@code ContainerHelper.loadAllItems(tag, items)}, which also reads stacks a 1.20.1 world saved. */
    public static void loadAllItems(CompoundTag tag, NonNullList<ItemStack> items) {
        loadAllItems(tag, items, LegacyItemData.DEFAULT_DATA_VERSION);
    }

    /** The same, for a mod that saved its stacks at {@code dataVersion}; the transform passes the mod's own. */
    public static void loadAllItems(CompoundTag tag, NonNullList<ItemStack> items, int dataVersion) {
        ContainerHelperMembers.loadAllItems(LegacyItemData.upgradeItems(tag, dataVersion), items,
                registries("ContainerHelper.loadAllItems"));
    }

    /** {@code ContainerHelper.saveAllItems(tag, items)}. */
    public static CompoundTag saveAllItems(CompoundTag tag, NonNullList<ItemStack> items) {
        return saveAllItems(tag, items, true);
    }

    /** {@code ContainerHelper.saveAllItems(tag, items, alwaysPutTag)}: each filled slot as a stack with its byte {@code Slot}. */
    public static CompoundTag saveAllItems(CompoundTag tag, NonNullList<ItemStack> items, boolean alwaysPutTag) {
        ListTag list = new ListTag();
        for (int slot = 0; slot < items.size(); slot++) {
            ItemStack stack = items.get(slot);
            if (stack.isEmpty()) continue;
            CompoundTag entry = new CompoundTag();
            entry.putByte(SLOT, (byte) slot);
            list.add(save(stack, entry));
        }
        if (!list.isEmpty() || alwaysPutTag) tag.put(ITEMS, list);
        return tag;
    }

    /** {@code ItemStack.of(tag)}: a stack saved on 1.20.1 is upgraded; an empty or invalid tag reads as the empty stack. */
    public static ItemStack of(CompoundTag tag) {
        return of(tag, LegacyItemData.DEFAULT_DATA_VERSION);
    }

    /** The same, for a mod that saved its stacks at {@code dataVersion}; the transform passes the mod's own. */
    public static ItemStack of(CompoundTag tag, int dataVersion) {
        return ItemStackMembers.parseOptional(registries("ItemStack.of"), LegacyItemData.upgradeStack(tag, dataVersion));
    }

    /** {@code stack.save(tag)}: the stack's fields replace any the tag held, and the tag is returned. */
    public static CompoundTag save(ItemStack stack, CompoundTag tag) {
        // A mod that reuses one tag per slot must not keep the previous stack's fields. The key set is the tag's own,
        // and removing through it links on every host, where remove(key) returned nothing before 1.21.11.
        for (String key : STACK_KEYS) tag.keySet().remove(key);
        // The item codec refuses an empty stack, which had nothing worth saving.
        if (stack.isEmpty()) return tag;
        // 1.20.1 wrote any count into a byte and never failed; a mod with bigger stacks keeps the real count beside it.
        ItemStack encoded = stack.getCount() > LegacyItemData.MAX_COUNT ? stack.copyWithCount(LegacyItemData.MAX_COUNT) : stack;
        tag.merge((CompoundTag) ItemStackMembers.save(encoded, registries("ItemStack.save"), new CompoundTag()));
        return tag;
    }

    /** {@code data.save(tag)} on world data, which no longer declares it, through the data's own old method. */
    public static CompoundTag dataSave(Object data, CompoundTag tag) throws Throwable {
        // A method handle resolves only that method, where Class.getMethod would resolve every public signature.
        java.lang.invoke.MethodHandle save = java.lang.invoke.MethodHandles.publicLookup().findVirtual(data.getClass(), "save",
                java.lang.invoke.MethodType.methodType(CompoundTag.class, CompoundTag.class));
        return (CompoundTag) save.invoke(data, tag);
    }

    /** Forge's {@code stack.serializeNBT()}: the stack in a new tag; an empty stack saves as an empty tag. */
    public static CompoundTag serializeNBT(ItemStack stack) {
        return save(stack, new CompoundTag());
    }

    /** {@code container.tryLoadLootTable(tag)}: takes the table and its seed when the tag names one, as before. */
    public static boolean tryLoadLootTable(RandomizableContainer container, CompoundTag tag) {
        String id = tag.getString(LOOT_TABLE).orElse(null);
        if (id == null) return false;
        // The id class and the key's accessor changed in 1.21.11 and 26.1, so the table goes through the reflective helper.
        LegacyLootTables.hostSetLootTable(container, LegacyLootTables.parseId(container, id), tag.getLongOr(LOOT_SEED, 0L));
        return true;
    }

    /** {@code container.trySaveLootTable(tag)}: writes the table and a seed other than 0 while the loot is unopened. */
    public static boolean trySaveLootTable(RandomizableContainer container, CompoundTag tag) {
        Object table = LegacyLootTables.lootTableId(container);
        if (table == null) return false;
        tag.putString(LOOT_TABLE, table.toString());
        long seed = container.getLootTableSeed();
        if (seed != 0L) tag.putLong(LOOT_SEED, seed);
        return true;
    }

    /** {@code blockEntity.saveWithoutMetadata()}. */
    public static CompoundTag saveWithoutMetadata(BlockEntity blockEntity) {
        return blockEntity.saveWithoutMetadata(registries("BlockEntity.saveWithoutMetadata"));
    }

    /** {@code blockEntity.saveWithFullMetadata()}. */
    public static CompoundTag saveWithFullMetadata(BlockEntity blockEntity) {
        return blockEntity.saveWithFullMetadata(registries("BlockEntity.saveWithFullMetadata"));
    }

    /** {@code blockEntity.saveWithId()}: the block entity's data with its type id, in a new tag. */
    public static CompoundTag saveWithId(BlockEntity blockEntity) {
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING,
                registries("BlockEntity.saveWithId"));
        blockEntity.saveWithId(output);
        return output.buildResult();
    }

    /** {@code blockEntity.getUpdateTag()}, as a method reference names it. */
    public static CompoundTag getUpdateTag(BlockEntity blockEntity) {
        return blockEntity.getUpdateTag(registries("BlockEntity.getUpdateTag"));
    }

    /** {@code ClientboundBlockEntityDataPacket.create(blockEntity, tagFor)}: the old function runs with the packet's registries. */
    public static ClientboundBlockEntityDataPacket dataPacket(BlockEntity blockEntity,
            Function<BlockEntity, CompoundTag> tagFor) {
        return ClientboundBlockEntityDataPacket.create(blockEntity, (entity, registries) -> {
            Object previous = LegacyRegistryContext.enter(registries);
            try {
                return tagFor.apply(entity);
            } finally {
                LegacyRegistryContext.exit(previous);
            }
        });
    }

    /** The tag an old load hands to its Minecraft parent, for a mod whose Minecraft version is unknown. */
    public static CompoundTag forHost(CompoundTag tag) {
        return forHost(tag, LegacyItemData.DEFAULT_DATA_VERSION);
    }

    /**
     * The tag an old load hands to its Minecraft parent: old stacks in {@code Items} upgraded from the mod's data version,
     * and a {@code CustomName} saved as JSON before 1.21.5 read as the component it was, where the parent would show the JSON.
     */
    public static CompoundTag forHost(CompoundTag tag, int dataVersion) {
        CompoundTag upgraded = LegacyItemData.upgradeItems(tag, dataVersion);
        String text = tag.get(CUSTOM_NAME) instanceof StringTag ? tag.getString(CUSTOM_NAME).orElse(null) : null;
        JsonElement json = text == null ? null : legacyJsonName(text, dataVersion);
        if (json == null) return upgraded;
        MutableComponent name;
        try {
            name = LegacyComponentJson.fromJson(json);
        } catch (JsonParseException notText) {
            // An object that is no text component reads as the text itself, as the parent reads it.
            return upgraded;
        }
        HolderLookup.Provider registries = LegacyRegistryContext.available();
        Tag encoded = name == null ? null : ComponentSerialization.CODEC.encodeStart(registries == null ? NbtOps.INSTANCE
                : registries.createSerializationContext(NbtOps.INSTANCE), name).result().orElse(null);
        if (encoded == null) return upgraded;
        CompoundTag copy = upgraded == tag ? tag.copy() : upgraded;
        copy.put(CUSTOM_NAME, encoded);
        return copy;
    }

    /**
     * The JSON a name was saved as before 1.21.5, or null. From 1.21.5 a plain name is saved as the text itself, so a name
     * a player typed, such as {@code [Ores]} or {@code "Ores"}, is read only in a shape the mod's version wrote: a strict
     * JSON object, or from 1.20.3 to 1.21.4, which saved a plain name as a JSON string, a strict JSON string.
     */
    private static JsonElement legacyJsonName(String text, int dataVersion) {
        boolean jsonStrings = dataVersion >= FIRST_JSON_STRING_NAME && dataVersion < FIRST_NBT_NAME;
        if (!text.startsWith("{") && !(jsonStrings && text.startsWith("\""))) return null;
        try {
            JsonReader reader = new JsonReader(new StringReader(text));
            JsonElement element = JSON.read(reader);
            if (reader.peek() != JsonToken.END_DOCUMENT) return null;
            boolean string = element instanceof JsonPrimitive primitive && primitive.isString();
            return element instanceof JsonObject || string ? element : null;
        } catch (IOException | JsonParseException | IllegalStateException notJson) {
            return null;
        }
    }

    private static HolderLookup.Provider registries(String call) {
        return LegacyRegistryContext.provider(call);
    }
}
