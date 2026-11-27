/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import net.minecraft.CrashReport;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegisterEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Single vanilla members of 1.20.1 that NeoForge and Forge hosts from 1.20.5 to 1.21.11 lack, as old content mods call them. */
public final class VanillaGapsCases {

    private static final Logger LOG = LoggerFactory.getLogger("Retromod-Test-Forge");
    private static final String PREFIX = "[Retromod-Test-Forge]";

    private static volatile Block cloneBlock;
    private static volatile int cloneOverrides;

    private VanillaGapsCases() {}

    /** A block whose 1.20.1 clone stack override asks its parent's, as many mods' blocks do. */
    static final class CloneOverrideBlock extends Block {
        CloneOverrideBlock(BlockBehaviour.Properties properties) {
            super(properties);
        }

        @Override
        public ItemStack getCloneItemStack(BlockGetter level, BlockPos pos, BlockState state) {
            cloneOverrides++;
            return super.getCloneItemStack(level, pos, state);
        }
    }

    /** Builds the block into a local and registers it by literal id, a shape every host gives its id. */
    static void registerBlocks(RegisterEvent event) {
        event.register(ForgeRegistries.Keys.BLOCKS, helper -> {
            Block block = new CloneOverrideBlock(BlockBehaviour.Properties.of());
            helper.register(new ResourceLocation("retromod_test_mod_forge", "clone_override_block"), block);
            cloneBlock = block;
        });
    }

    /** Logs the checks that need no world and listens for the ones that need a server. */
    static void register() {
        LOG.info("{} vanilla gaps: ItemStack.getUseDuration() of a bow after 1.21 (startup): {}", PREFIX, run(() ->
                new ItemStack(Items.BOW).getUseDuration() == 72000));
        LOG.info("{} vanilla gaps: CrashReport.getFriendlyReport() after 1.20.5 (startup): {}", PREFIX, run(() ->
                new CrashReport("Retromod test", new RuntimeException()).getFriendlyReport().contains("Retromod test")));
        MinecraftForge.EVENT_BUS.addListener(VanillaGapsCases::onServerStarted);
    }

    private static void onServerStarted(ServerStartedEvent event) {
        ServerLevel overworld = event.getServer().overworld();
        // An MCreator mob asks its parent this way; The Box of Horrors does it from Monster.
        LOG.info("{} vanilla gaps: a zombie's getPassengersRidingOffset() after 1.20.5 (server started): {}", PREFIX, run(() ->
                new Zombie(EntityType.ZOMBIE, overworld).getPassengersRidingOffset() > 0));
        LOG.info("{} vanilla gaps: ItemStack.getAttributeModifiers(MAINHAND) of a diamond sword after 1.20.5 (server started): {}", PREFIX,
                run(() -> !new ItemStack(Items.DIAMOND_SWORD).getAttributeModifiers(EquipmentSlot.MAINHAND).isEmpty()));
        // A spawn egg's tag carries the entity's data under EntityTag.
        LOG.info("{} vanilla gaps: EntityType.updateCustomEntityTag applies the item's EntityTag after 1.20.5 (server started): {}", PREFIX,
                run(() -> {
                    Zombie zombie = new Zombie(EntityType.ZOMBIE, overworld);
                    CompoundTag data = new CompoundTag();
                    data.putBoolean("NoAI", true);
                    CompoundTag itemTag = new CompoundTag();
                    itemTag.put("EntityTag", data);
                    EntityType.updateCustomEntityTag(overworld, null, zombie, itemTag);
                    return zombie.isNoAi();
                }));
        LOG.info("{} vanilla gaps: Block.getCloneItemStack(getter, pos, state) after 1.21 (server started): {}", PREFIX, run(() ->
                Blocks.STONE.getCloneItemStack(overworld, BlockPos.ZERO, Blocks.STONE.defaultBlockState()).is(Items.STONE)));
        // Called as a Block, the host asks the block's own override, whose parent call must not come back to it.
        LOG.info("{} vanilla gaps: a block override of the old clone stack that asks its parent's (server started): {}", PREFIX, run(() -> {
            Block block = cloneBlock;
            ItemStack stack = block.getCloneItemStack(overworld, BlockPos.ZERO, block.defaultBlockState());
            LOG.info("{} vanilla gaps: the clone stack override ran {} times", PREFIX, cloneOverrides);
            return stack != null;
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
}
