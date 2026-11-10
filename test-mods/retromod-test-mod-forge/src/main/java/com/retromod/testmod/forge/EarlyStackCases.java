/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Item stacks a mod builds while it constructs, which 26.x cannot build until a world's data binds item components. */
final class EarlyStackCases {

    // Kept from construction for the server-started half.
    private static ItemStack diamonds;
    private static ItemStack pickaxe;
    private static ItemStack defaultApple;
    private static ItemStack copied;

    private EarlyStackCases() {}

    /** A static field, the holder constructor and getDefaultInstance, all while the mod constructs. */
    static boolean buildWhileConstructing() {
        diamonds = Icons.DIAMONDS;
        pickaxe = new ItemStack(Items.DIAMOND_PICKAXE.builtInRegistryHolder());
        defaultApple = Items.APPLE.getDefaultInstance();
        copied = diamonds.copy();
        copied.setCount(5);
        return diamonds.getItem() == Items.DIAMOND && diamonds.getCount() == 3 && !diamonds.isEmpty()
                && pickaxe.getItem() == Items.DIAMOND_PICKAXE && defaultApple.getItem() == Items.APPLE;
    }

    /** Once a server has loaded its data, the kept stacks read their defaults, match new stacks both ways, and save as them. */
    static String matchOnServer() {
        if (diamonds == null) return "fail: the construction half did not run";
        ItemStack now = new ItemStack(Items.DIAMOND, 3);
        if (diamonds.getMaxStackSize() != 64) return "fail: max stack size reads " + diamonds.getMaxStackSize();
        if (!ItemStack.matches(diamonds, now) || !ItemStack.matches(now, diamonds)) {
            return "fail: the stack built while constructing does not match a new one";
        }
        if (!ItemStack.matches(copied, new ItemStack(Items.DIAMOND, 5))) return "fail: the copy does not match a stack of five";
        if (!pickaxe.isDamageableItem() || !ItemStack.matches(pickaxe, new ItemStack(Items.DIAMOND_PICKAXE))) {
            return "fail: the pickaxe built from its holder lost its defaults";
        }
        if (!ItemStack.matches(defaultApple, new ItemStack(Items.APPLE))) return "fail: getDefaultInstance does not match an apple";
        // Saved and read back with the 1.20.1 calls, it comes back as the stack it was.
        ItemStack loaded = ItemStack.of(diamonds.save(new CompoundTag()));
        return ItemStack.matches(loaded, now) ? "success" : "fail: the saved stack read back as " + loaded;
    }

    /** A static field set in a class initializer, as a mod's icon holder does. */
    private static final class Icons {
        static final ItemStack DIAMONDS = new ItemStack(Items.DIAMOND, 3);
    }
}
