/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import java.util.List;

import net.minecraft.SystemReport;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataSerializer;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.random.WeightedEntry;
import net.minecraft.util.random.WeightedRandom;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.npc.VillagerData;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.gameevent.GameEvent;

/** Members TerraBlender, Lootr, Carry On, Patchouli and Farmer's Delight use while a world loads, a player joins, or a meal ends. */
final class WorldLoadMemberCases {

    private WorldLoadMemberCases() {}

    /** TerraBlender's region cache key: 26.1 named the packing helpers pack. */
    static boolean chunkKeys() {
        long expected = 3L & 0xFFFFFFFFL | ((long) -4 & 0xFFFFFFFFL) << 32;
        return ChunkPos.asLong(3, -4) == expected && ChunkPos.asLong(new BlockPos(48, 70, -64)) == expected;
    }

    /** TerraBlender's weighted region pick: 1.21.5 removed WeightedEntry and the weightless sums. */
    static boolean weightedPick() {
        List<WeightedEntry.Wrapper<String>> regions = List.of(WeightedEntry.wrap("overworld", 2), WeightedEntry.wrap("mod", 3));
        String picked = WeightedRandom.getWeightedItem(regions, 2).map(WeightedEntry.Wrapper::getData).orElse(null);
        return WeightedRandom.getTotalWeight(regions) == 5 && "mod".equals(picked)
                && regions.get(1).getWeight().asInt() == 3;
    }

    /** Carry On's synced carry data: 1.21.9 removed the compound tag serializer. */
    static boolean compoundTagSerializer() {
        EntityDataSerializer<CompoundTag> serializer = EntityDataSerializers.COMPOUND_TAG;
        CompoundTag tag = new CompoundTag();
        tag.putString("carrying", "minecraft:chest");
        CompoundTag copy = serializer.copy(tag);
        return serializer == EntityDataSerializers.COMPOUND_TAG && copy != tag && "minecraft:chest".equals(copy.getString("carrying"));
    }

    /** Patchouli's system report line: 26.2 takes a crash report detail where it took a supplier. */
    static boolean systemReportDetail() {
        SystemReport report = new SystemReport();
        report.setDetail("Retromod test detail", () -> "present");
        return report.toLineSeparatedString().contains("Retromod test detail: present");
    }

    /** Lootr's loot table reads and writes: 1.20.5 keeps the table as a registry key. */
    static boolean lootTableId() {
        LootProbe chest = new LootProbe();
        ResourceLocation table = new ResourceLocation("minecraft", "chests/simple_dungeon");
        chest.setLootTable(table, 5L);
        boolean set = table.equals(chest.table()) && table.equals(chest.saved);
        chest.clear();
        return set && chest.table() == null;
    }

    /** Sophisticated Core's allay radius, which needs no item stack. */
    static boolean gameEventRadius() {
        return GameEvent.JUKEBOX_PLAY.getNotificationRadius() > 0;
    }

    /** Farmer's Delight's soup checks. Item stacks need item components, which a server binds as its data loads. */
    static boolean foodAndRemainder() {
        return new ItemStack(Items.APPLE).isEdible() && !new ItemStack(Items.STONE).isEdible()
                && new ItemStack(Items.MILK_BUCKET).hasCraftingRemainingItem()
                && new ItemStack(Items.MILK_BUCKET).getCraftingRemainingItem().is(Items.BUCKET);
    }

    /** Farmer's Delight's farmer check: 1.21.5 holds the profession in a holder behind profession(). */
    static boolean profession() {
        JsonObject json = new JsonObject();
        json.addProperty("type", "minecraft:plains");
        json.addProperty("profession", "minecraft:farmer");
        json.addProperty("level", 1);
        VillagerData data = VillagerData.CODEC.parse(JsonOps.INSTANCE, json).result().orElse(null);
        return data != null && "minecraft:farmer".equals(BuiltInRegistries.VILLAGER_PROFESSION.getKey(data.getProfession()).toString());
    }

    /** A loot chest that records the table it is given, as Lootr's own chests do. */
    static final class LootProbe extends ChestBlockEntity {
        ResourceLocation saved;

        LootProbe() {
            super(BlockPos.ZERO, Blocks.CHEST.defaultBlockState());
        }

        @Override
        public void setLootTable(ResourceLocation table, long seed) {
            super.setLootTable(table, seed);
            saved = table;
        }

        ResourceLocation table() {
            return lootTable;
        }

        void clear() {
            lootTable = null;
        }
    }
}
