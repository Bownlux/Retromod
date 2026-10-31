/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.neoforge.capabilities;

import com.retromod.legacyapi.neoforge.capabilities.LegacyCapabilities.Target;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;
import net.neoforged.neoforge.capabilities.EntityCapability;
import net.neoforged.neoforge.capabilities.IBlockCapabilityProvider;
import net.neoforged.neoforge.capabilities.ICapabilityProvider;
import net.neoforged.neoforge.capabilities.ItemCapability;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.transfer.access.ItemAccess;

/** Where an old mod's capability registrations and lookups go, so they reach the current capabilities too. */
@SuppressWarnings({"unchecked", "rawtypes"})
public final class CapabilityCalls {

    // Caches over an old capability, which fall back the same way their lookups do.
    private static final Map<BlockCapabilityCache, BlockCapability> CACHES = Collections.synchronizedMap(new WeakHashMap<>());

    private CapabilityCalls() {}

    // ---- registration ---------------------------------------------------------------------------
    // A registration on an old capability is also made on the current one with the handler
    // adapted, so current mods and vanilla machines see it.

    public static void registerBlockEntity(RegisterCapabilitiesEvent event, BlockCapability capability,
            BlockEntityType type, ICapabilityProvider provider) {
        event.registerBlockEntity(capability, type, provider);
        Target target = LegacyCapabilities.target(capability);
        if (target == null) return;
        event.registerBlockEntity((BlockCapability) target.current(), type,
                (blockEntity, context) -> LegacyCapabilities.toCurrent(target.kind(), provider.getCapability(blockEntity, context)));
    }

    public static void registerBlock(RegisterCapabilitiesEvent event, BlockCapability capability,
            IBlockCapabilityProvider provider, Block[] blocks) {
        event.registerBlock(capability, provider, blocks);
        Target target = LegacyCapabilities.target(capability);
        if (target == null) return;
        event.registerBlock((BlockCapability) target.current(), (level, pos, state, blockEntity, context) ->
                LegacyCapabilities.toCurrent(target.kind(), provider.getCapability(level, pos, state, blockEntity, context)), blocks);
    }

    public static void registerEntity(RegisterCapabilitiesEvent event, EntityCapability capability,
            EntityType type, ICapabilityProvider provider) {
        event.registerEntity(capability, type, provider);
        Target target = LegacyCapabilities.target(capability);
        if (target == null) return;
        event.registerEntity((EntityCapability) target.current(), type,
                (entity, context) -> LegacyCapabilities.toCurrent(target.kind(), provider.getCapability(entity, context)));
    }

    /** The current item capabilities pass an item access; the old ones had no context. */
    public static void registerItem(RegisterCapabilitiesEvent event, ItemCapability capability,
            ICapabilityProvider provider, ItemLike[] items) {
        event.registerItem(capability, provider, items);
        Target target = LegacyCapabilities.target(capability);
        if (target == null) return;
        event.registerItem((ItemCapability) target.current(),
                (stack, access) -> LegacyCapabilities.toCurrent(target.kind(), provider.getCapability(stack, null)), items);
    }

    // ---- lookups --------------------------------------------------------------------------------
    // A lookup that finds no handler of the old type asks the current capability and adapts what
    // it gets, which is how one old mod reaches another's handler: each has its own copy of the types.

    public static Object levelCapability(Object level, BlockCapability capability, BlockPos pos, Object context) {
        return block((Level) level, capability, pos, null, null, context, false);
    }

    public static Object levelCapability(Object level, BlockCapability capability, BlockPos pos) {
        return block((Level) level, capability, pos, null, null, null, false);
    }

    public static Object levelCapabilityAt(Object level, BlockCapability capability, BlockPos pos, BlockState state,
            BlockEntity blockEntity, Object context) {
        return block((Level) level, capability, pos, state, blockEntity, context, true);
    }

    public static Object levelCapabilityAt(Object level, BlockCapability capability, BlockPos pos, BlockState state,
            BlockEntity blockEntity) {
        return block((Level) level, capability, pos, state, blockEntity, null, true);
    }

    /** {@code capability.getCapability(level, pos, state, blockEntity, context)} called on the capability itself. */
    public static Object blockCapability(BlockCapability capability, Level level, BlockPos pos, BlockState state,
            BlockEntity blockEntity, Object context) {
        return block(level, capability, pos, state, blockEntity, context, true);
    }

    private static Object block(Level level, BlockCapability capability, BlockPos pos, BlockState state,
            BlockEntity blockEntity, Object context, boolean full) {
        Object found = full ? level.getCapability(capability, pos, state, blockEntity, context)
                : level.getCapability(capability, pos, context);
        Target target = LegacyCapabilities.target(capability);
        if (target == null || LegacyCapabilities.isOld(target.kind(), found)) return found;
        BlockCapability current = (BlockCapability) target.current();
        Object handler = full ? level.getCapability(current, pos, state, blockEntity, context)
                : level.getCapability(current, pos, context);
        return LegacyCapabilities.toOld(target.kind(), handler, null);
    }

    public static Object stackCapability(ItemStack stack, ItemCapability capability) {
        return stackCapability(stack, capability, null);
    }

    public static Object stackCapability(ItemStack stack, ItemCapability capability, Object context) {
        Object found = stack.getCapability(capability, context);
        Target target = LegacyCapabilities.target(capability);
        if (target == null || LegacyCapabilities.isOld(target.kind(), found)) return found;
        ItemAccess access = ItemAccess.forStack(stack);
        return LegacyCapabilities.toOld(target.kind(), stack.getCapability((ItemCapability) target.current(), access), access);
    }

    /** {@code capability.getCapability(stack, context)} called on the capability itself. */
    public static Object itemCapability(ItemCapability capability, ItemStack stack, Object context) {
        return stackCapability(stack, capability, context);
    }

    public static Object entityCapability(Object entity, EntityCapability capability) {
        return entityCapability(entity, capability, null);
    }

    public static Object entityCapability(Object entity, EntityCapability capability, Object context) {
        Object found = capability.getCapability((Entity) entity, context);
        Target target = LegacyCapabilities.target(capability);
        if (target == null || LegacyCapabilities.isOld(target.kind(), found)) return found;
        Object handler = ((EntityCapability) target.current()).getCapability((Entity) entity, context);
        return LegacyCapabilities.toOld(target.kind(), handler, null);
    }

    /** {@code capability.getCapability(entity, context)} called on the capability itself. */
    public static Object entityCapabilityOn(EntityCapability capability, Entity entity, Object context) {
        return entityCapability(entity, capability, context);
    }

    // ---- caches ---------------------------------------------------------------------------------

    public static BlockCapabilityCache cache(BlockCapability capability, ServerLevel level, BlockPos pos, Object context) {
        return remember(BlockCapabilityCache.create(capability, level, pos, context), capability);
    }

    public static BlockCapabilityCache cache(BlockCapability capability, ServerLevel level, BlockPos pos, Object context,
            BooleanSupplier isValid, Runnable invalidationListener) {
        return remember(BlockCapabilityCache.create(capability, level, pos, context, isValid, invalidationListener), capability);
    }

    private static BlockCapabilityCache remember(BlockCapabilityCache cache, BlockCapability capability) {
        if (LegacyCapabilities.target(capability) != null) CACHES.put(cache, capability);
        return cache;
    }

    /** {@code cache.getCapability()}: for an old capability, the current one at the same place when the old is absent. */
    public static Object cached(BlockCapabilityCache cache) {
        Object found = cache.getCapability();
        BlockCapability capability = CACHES.get(cache);
        if (capability == null) return found;
        Target target = LegacyCapabilities.target(capability);
        if (LegacyCapabilities.isOld(target.kind(), found)) return found;
        Object handler = cache.level().getCapability((BlockCapability) target.current(), cache.pos(), cache.context());
        return LegacyCapabilities.toOld(target.kind(), handler, null);
    }
}
