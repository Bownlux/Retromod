/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.goal.FollowOwnerGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.common.world.ForgeChunkManager;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.minecraftforge.items.wrapper.SidedInvWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Forge 1.20.1 gameplay calls that NeoForge 1.21.1 and newer lost: #398 Epic Core API, #400 SoniCraft, #394 Recruits. */
public final class NeoForge121GameplayCases {

    private static final Logger LOG = LoggerFactory.getLogger("Retromod-Test-Forge");
    private static final String PREFIX = "[Retromod-Test-Forge]";
    private static final String MOD_ID = "retromod_test_mod_forge";

    private NeoForge121GameplayCases() {}

    /** Logs the checks that run while the mod constructs and listens for the ones that need a server. */
    static void register() {
        LOG.info("{} #398 EntityDataAccessor.getId() on the 1.20.5 record (startup): {}", PREFIX, run(() ->
                new EntityDataAccessor<>(7, EntityDataSerializers.INT).getId() == 7));
        // #398: Epic Core API sets its callback here, so NeoForge must take its ticket controller while loading.
        LOG.info("{} #398 ForgeChunkManager.setForcedChunkLoadingCallback while constructing (startup): {}", PREFIX, run(() -> {
            ForgeChunkManager.setForcedChunkLoadingCallback(MOD_ID, (level, helper) -> helper.getBlockTickets().size());
            return true;
        }));
        // #400: an MCreator block entity builds one lazy handler per side; a null container proves the call links.
        LOG.info("{} #400 SidedInvWrapper.create gives one lazy handler per side (startup): {}", PREFIX, run(() -> {
            LazyOptional<IItemHandlerModifiable>[] handlers = SidedInvWrapper.create(null, Direction.UP, Direction.DOWN);
            return handlers.length == 2 && handlers[0].isPresent();
        }));
        LOG.info("{} #400 EnchantmentHelper.hasVanishingCurse on a plain stack (startup): {}", PREFIX, run(() ->
                !EnchantmentHelper.hasVanishingCurse(new ItemStack(Items.STICK))));
        MinecraftForge.EVENT_BUS.addListener(NeoForge121GameplayCases::onServerStarted);
    }

    private static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        ServerLevel overworld = server.overworld();
        LOG.info("{} #400 #394 server.getLootData().getLootTable(id) after 1.20.5 (server started): {}", PREFIX, run(() ->
                server.getLootData().getLootTable(BuiltInLootTables.SIMPLE_DUNGEON) != LootTable.EMPTY));
        LOG.info("{} #400 new FollowOwnerGoal(mob, speed, start, stop, canFly) after 1.21 (server started): {}", PREFIX, run(() ->
                new FollowOwnerGoal(new Wolf(EntityType.WOLF, overworld), 1.0, 10.0F, 2.0F, false) != null));
        // The controller from construction forces a chunk and lets it go again.
        LOG.info("{} #398 ForgeChunkManager.forceChunk through the mod's controller (server started): {}", PREFIX, run(() ->
                ForgeChunkManager.forceChunk(overworld, MOD_ID, BlockPos.ZERO, 0, 0, true, false)
                        && ForgeChunkManager.forceChunk(overworld, MOD_ID, BlockPos.ZERO, 0, 0, false, false)));
        // #394: a recruit checks the biome it spawns in and the potions it carries these ways.
        LOG.info("{} #394 Holder.containsTag on the spawn biome (server started): {}", PREFIX, run(() ->
                overworld.getBiome(BlockPos.ZERO).containsTag(BiomeTags.IS_OVERWORLD)));
        LOG.info("{} #394 PotionUtils.getMobEffects on a swiftness potion (server started): {}", PREFIX, run(() ->
                !PotionUtils.getMobEffects(PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.SWIFTNESS)).isEmpty()));
        // A recruit's own ground navigation asks itself whether its mob is in a liquid; a new zombie is not.
        LOG.info("{} #394 a navigation's own isInLiquid() (server started): {}", PREFIX, run(() -> {
            var navigation = new GroundPathNavigation(new Zombie(EntityType.ZOMBIE, overworld), overworld) {
                boolean inLiquid() { return isInLiquid(); }
            };
            return !navigation.inLiquid();
        }));
    }

    /** A check's result as the log reads it. */
    private static String run(java.util.concurrent.Callable<Boolean> check) {
        try {
            return check.call() ? "success" : "fail: assertion was false";
        } catch (Throwable t) {
            return "fail: " + t;
        }
    }

    /** #392: Meulti's Backrooms adds a texture image button to the title screen. Client only. */
    static String imageButton() {
        return run(() -> new net.minecraft.client.gui.components.ImageButton(0, 0, 20, 20, 0, 0, 20,
                new ResourceLocation(MOD_ID, "textures/gui/button.png"), button -> {}).getWidth() == 20);
    }
}
