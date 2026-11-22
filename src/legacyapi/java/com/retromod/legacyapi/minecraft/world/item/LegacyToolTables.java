/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.item;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;
import java.util.function.Consumer;

/** The axe, shovel and hoe tables and helpers 26.3 removed with its tool items, kept for old mods that read and fill them. */
public final class LegacyToolTables {

    // Block update flags old HoeItem used: notify neighbors, send to clients, rerender at once.
    private static final int UPDATE_ALL_IMMEDIATE = 11;

    private LegacyToolTables() {}

    /** Old {@code AxeItem.STRIPPABLES}: log block to stripped block. */
    public static Map<Object, Object> strippables() {
        return LegacyToolTableSink.table(LegacyToolTableSink.STRIP);
    }

    /** A mod that replaced {@code AxeItem.STRIPPABLES} with a copy adds the copy's entries instead. */
    public static void setStrippables(Map<?, ?> replacement) {
        LegacyToolTableSink.absorb(LegacyToolTableSink.STRIP, replacement);
    }

    /** Old {@code ShovelItem.FLATTENABLES}: block to the path state it becomes. */
    public static Map<Object, Object> flattenables() {
        return LegacyToolTableSink.table(LegacyToolTableSink.FLATTEN);
    }

    public static void setFlattenables(Map<?, ?> replacement) {
        LegacyToolTableSink.absorb(LegacyToolTableSink.FLATTEN, replacement);
    }

    /** Old {@code HoeItem.TILLABLES}: block to a pair of a use check and the action that tills it. */
    public static Map<Object, Object> tillables() {
        return LegacyToolTableSink.table(LegacyToolTableSink.TILL);
    }

    public static void setTillables(Map<?, ?> replacement) {
        LegacyToolTableSink.absorb(LegacyToolTableSink.TILL, replacement);
    }

    /** Old {@code AxeItem.getAxeStrippingState}: the stripped state with the log's properties, or null. */
    public static BlockState getAxeStrippingState(BlockState state) {
        Object stripped = strippables().get(state.getBlock());
        return stripped instanceof Block block ? block.withPropertiesOf(state) : null;
    }

    /** Old {@code ShovelItem.getShovelPathingState}, or null. */
    public static BlockState getShovelPathingState(BlockState state) {
        Object flattened = flattenables().get(state.getBlock());
        return flattened instanceof BlockState path ? path : null;
    }

    /** Old {@code HoeItem.onlyIfAirAbove}: not the bottom face, and air above. */
    public static boolean onlyIfAirAbove(UseOnContext context) {
        return context.getClickedFace() != net.minecraft.core.Direction.DOWN
                && context.getLevel().getBlockState(context.getClickedPos().above()).isAir();
    }

    /** Old {@code HoeItem.changeIntoState}. */
    public static Consumer<UseOnContext> changeIntoState(BlockState state) {
        return new ChangeInto(state, null);
    }

    /** Old {@code HoeItem.changeIntoStateAndDropItem}. */
    public static Consumer<UseOnContext> changeIntoStateAndDropItem(BlockState state, ItemLike dropped) {
        return new ChangeInto(state, dropped);
    }

    /** What the old tilling helpers built; the sink reads the state back to register tilling with the host. */
    public record ChangeInto(BlockState legacyTilledState, ItemLike legacyDroppedItem) implements Consumer<UseOnContext> {
        @Override
        public void accept(UseOnContext context) {
            // Reached when a mod runs the action itself, as old HoeItem did on use.
            Level level = context.getLevel();
            BlockPos pos = context.getClickedPos();
            level.setBlock(pos, legacyTilledState, UPDATE_ALL_IMMEDIATE);
            if (legacyDroppedItem != null) {
                Block.popResourceFromFace(level, pos, context.getClickedFace(), new ItemStack(legacyDroppedItem));
            }
        }
    }
}
