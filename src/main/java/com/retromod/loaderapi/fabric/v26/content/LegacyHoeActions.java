/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi.fabric.v26.content;

import com.retromod.loaderapi.fabric.v26.HostLink;
import com.retromod.loaderapi.fabric.v26.HostLink.Member;

import java.util.function.Consumer;

import static com.retromod.loaderapi.fabric.v26.content.LegacyContentRegistries.call;

/** Stands in for the HoeItem helpers 26.3 removed, which old tilling registrations pass along. */
public final class LegacyHoeActions {

    private static final String CONTEXT = "net/minecraft/world/item/context/UseOnContext";
    private static final String POS = "Lnet/minecraft/core/BlockPos;";
    private static final String DIRECTION = "Lnet/minecraft/core/Direction;";
    private static final String LEVEL = "Lnet/minecraft/world/level/Level;";
    private static final String STATE = "Lnet/minecraft/world/level/block/state/BlockState;";
    static final Member CLICKED_FACE = Member.virtual(CONTEXT, "getClickedFace", "()" + DIRECTION);
    static final Member CLICKED_POS = Member.virtual(CONTEXT, "getClickedPos", "()" + POS);
    static final Member CONTEXT_LEVEL = Member.virtual(CONTEXT, "getLevel", "()" + LEVEL);
    static final Member DOWN = new Member("net/minecraft/core/Direction", "DOWN", DIRECTION, true);
    static final Member ABOVE = Member.virtual("net/minecraft/core/BlockPos", "above", "()" + POS);
    static final Member BLOCK_STATE = Member.virtual("net/minecraft/world/level/Level", "getBlockState",
            "(" + POS + ")" + STATE);
    static final Member IS_AIR = Member.virtual("net/minecraft/world/level/block/state/BlockState", "isAir", "()Z");
    static final Member SET_BLOCK = Member.virtual("net/minecraft/world/level/Level", "setBlock",
            "(" + POS + STATE + "I)Z");
    static final Member NEW_STACK = Member.constructor("net/minecraft/world/item/ItemStack",
            "(Lnet/minecraft/world/level/ItemLike;)V");
    static final Member POP_FROM_FACE = Member.staticMethod("net/minecraft/world/level/block/Block",
            "popResourceFromFace", "(" + LEVEL + POS + DIRECTION + "Lnet/minecraft/world/item/ItemStack;)V");

    /** Block update flags old HoeItem used: notify neighbors, send to clients, rerender at once. */
    private static final int UPDATE_ALL_IMMEDIATE = 11;

    /**
     * What old {@code HoeItem.changeIntoState} built: the tilled state, and an item to drop or null.
     * The tilling registry reads the state back, because 26.3 tills through block transformers.
     */
    public record ChangeIntoState(Object state, Object droppedItem) implements Consumer<Object> {
        @Override
        public void accept(Object context) {
            // Reached only when a mod runs the action itself, as old HoeItem did on use.
            Object level = call(HostLink.require(CONTEXT_LEVEL), context);
            Object pos = call(HostLink.require(CLICKED_POS), context);
            call(HostLink.require(SET_BLOCK), level, pos, state, UPDATE_ALL_IMMEDIATE);
            if (droppedItem != null) {
                Object stack = call(HostLink.require(NEW_STACK), droppedItem);
                call(HostLink.require(POP_FROM_FACE), level, pos, call(HostLink.require(CLICKED_FACE), context), stack);
            }
        }
    }

    private LegacyHoeActions() {}

    /** Old {@code HoeItem.onlyIfAirAbove(UseOnContext)}: not the bottom face, and air above. */
    public static boolean onlyIfAirAbove(Object context) {
        if (call(HostLink.require(CLICKED_FACE), context) == call(HostLink.require(DOWN))) return false;
        Object level = call(HostLink.require(CONTEXT_LEVEL), context);
        Object above = call(HostLink.require(ABOVE), call(HostLink.require(CLICKED_POS), context));
        return (boolean) call(HostLink.require(IS_AIR), call(HostLink.require(BLOCK_STATE), level, above));
    }

    /** Old {@code HoeItem.changeIntoState(BlockState)}. */
    public static Consumer<Object> changeIntoState(Object state) {
        return new ChangeIntoState(state, null);
    }

    /** Old {@code HoeItem.changeIntoStateAndDropItem(BlockState, ItemLike)}. */
    public static Consumer<Object> changeIntoStateAndDropItem(Object state, Object droppedItem) {
        return new ChangeIntoState(state, droppedItem);
    }
}
