/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.item;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;

/** The {@code ItemStack} NBT members 1.21.5 and 1.21.6 removed, rebuilt on the item codec they wrapped. */
public final class ItemStackMembers {

    // Retromod's own runtime is not on a mod's class path, so the log goes through the JDK.
    private static final System.Logger LOG = System.getLogger("Retromod");

    private ItemStackMembers() {}

    /** {@code ItemStack.parse(registries, tag)}: the stack, or empty with a logged error when the tag is not a valid item. */
    public static Optional<ItemStack> parse(HolderLookup.Provider registries, Tag tag) {
        return ItemStack.CODEC.parse(registries.createSerializationContext(NbtOps.INSTANCE), tag)
                .resultOrPartial(error -> LOG.log(System.Logger.Level.ERROR, () -> "Tried to load invalid item: '" + error + "'"));
    }

    /** {@code ItemStack.parseOptional(registries, tag)}: an empty tag or an invalid item reads as the empty stack. */
    public static ItemStack parseOptional(HolderLookup.Provider registries, CompoundTag tag) {
        return tag.isEmpty() ? ItemStack.EMPTY : parse(registries, tag).orElse(ItemStack.EMPTY);
    }

    /** {@code stack.save(registries)}: a new tag for the stack; an empty stack throws, as before. */
    public static Tag save(ItemStack stack, HolderLookup.Provider registries) {
        requireNotEmpty(stack);
        return ItemStack.CODEC.encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), stack).getOrThrow();
    }

    /** {@code stack.save(registries, prefix)}: the stack's fields added to a copy of {@code prefix}, such as one holding a slot. */
    public static Tag save(ItemStack stack, HolderLookup.Provider registries, Tag prefix) {
        requireNotEmpty(stack);
        return ItemStack.CODEC.encode(stack, registries.createSerializationContext(NbtOps.INSTANCE), prefix).getOrThrow();
    }

    /** {@code stack.saveOptional(registries)}: an empty stack saves as an empty compound instead of throwing. */
    public static Tag saveOptional(ItemStack stack, HolderLookup.Provider registries) {
        return stack.isEmpty() ? new CompoundTag() : save(stack, registries, new CompoundTag());
    }

    private static void requireNotEmpty(ItemStack stack) {
        if (stack.isEmpty()) throw new IllegalStateException("Cannot encode empty ItemStack");
    }
}
