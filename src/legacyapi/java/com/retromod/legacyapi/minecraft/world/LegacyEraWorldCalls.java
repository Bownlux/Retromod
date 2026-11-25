/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.util.BlockUtil;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.villager.VillagerData;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.WoolCarpetBlock;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.portal.PortalForcer;
import net.minecraft.world.level.storage.LevelStorageSource;

/** World, item and command calls that mods for 1.19.4 and older made, each answered by the member that replaced it. */
public final class LegacyEraWorldCalls {

    private LegacyEraWorldCalls() {}

    /** {@code source.sendSuccess(message, broadcast)}: 1.20 takes the message as a supplier. */
    public static void sendSuccess(CommandSourceStack source, Component message, boolean broadcast) {
        source.sendSuccess(() -> message, broadcast);
    }

    /** {@code new Vec3i(x, y, z)} from doubles, which 1.19.4 removed: each coordinate rounded down, as it was. */
    public static Vec3i vec3i(double x, double y, double z) {
        return new Vec3i((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
    }

    /** {@code EnchantmentHelper.getEnchantments(stack)}: each enchantment on the stack, or stored in a book, with its level. */
    public static Map<Enchantment, Integer> getEnchantments(ItemStack stack) {
        Map<Enchantment, Integer> levels = new LinkedHashMap<>();
        for (Object2IntMap.Entry<Holder<Enchantment>> entry : EnchantmentHelper.getEnchantmentsForCrafting(stack).entrySet()) {
            levels.put(entry.getKey().value(), entry.getIntValue());
        }
        return levels;
    }

    /** {@code enchantment.getDescriptionId()}: the translation key of the enchantment's name, which 1.21 keeps in its description. */
    public static String enchantmentDescriptionId(Enchantment enchantment) {
        Component description = enchantment.description();
        return description.getContents() instanceof TranslatableContents key ? key.getKey() : description.getString();
    }

    /**
     * {@code biome.getPrecipitation()}: none for a dry biome, else snow where the biome's base temperature freezes and rain
     * elsewhere. 1.19.4 decides snow per position, which this one answer for the whole biome cannot.
     */
    public static Biome.Precipitation getPrecipitation(Biome biome) {
        if (!biome.hasPrecipitation()) return Biome.Precipitation.NONE;
        return biome.getBaseTemperature() < 0.15F ? Biome.Precipitation.SNOW : Biome.Precipitation.RAIN;
    }

    /** {@code inventory.getCarried()}: the stack on the cursor, which 1.17 moved to the open menu. */
    public static ItemStack getCarried(Inventory inventory) {
        return inventory.player.containerMenu.getCarried();
    }

    /** {@code inventory.setCarried(stack)}: the same move. */
    public static void setCarried(Inventory inventory, ItemStack stack) {
        inventory.player.containerMenu.setCarried(stack);
    }

    /** {@code data.getType()}: the villager type, which 1.21.5 holds as a registry holder. */
    public static VillagerType getType(VillagerData data) {
        return data.type().value();
    }

    /** {@code data.getProfession()}: the same. */
    public static VillagerProfession getProfession(VillagerData data) {
        return data.profession().value();
    }

    /** {@code llama.getSwag()}: the color of the carpet the llama wears, or null without one. */
    public static DyeColor getSwag(Mob llama) {
        ItemStack worn = llama.getBodyArmorItem();
        return worn.getItem() instanceof BlockItem item && item.getBlock() instanceof WoolCarpetBlock carpet
                ? carpet.getColor() : null;
    }

    /**
     * {@code forcer.findPortalAround(pos, toNether, border)}: the closest portal block, which 1.21 returns alone, as a
     * one-block area at that block.
     */
    public static Optional<BlockUtil.FoundRectangle> findPortalAround(PortalForcer forcer, BlockPos pos, boolean toNether,
            WorldBorder border) {
        return forcer.findClosestPortalPosition(pos, toNether, border).map(found -> new BlockUtil.FoundRectangle(found, 1, 1));
    }

    /** {@code pack.getResource(type, id)}: the resource's stream, which 1.19.3 hands out through a supplier; a missing one fails as before. */
    public static InputStream getResource(PackResources pack, PackType type, Identifier id) throws IOException {
        IoSupplier<InputStream> resource = pack.getResource(type, id);
        if (resource == null) throw new FileNotFoundException(type + " " + id);
        return resource.get();
    }

    /** {@code source.findLevelCandidates()}: the world folders, which 1.20 wrapped in a record. */
    public static List<LevelStorageSource.LevelDirectory> findLevelCandidates(LevelStorageSource source) {
        return source.findLevelCandidates().levels();
    }
}
