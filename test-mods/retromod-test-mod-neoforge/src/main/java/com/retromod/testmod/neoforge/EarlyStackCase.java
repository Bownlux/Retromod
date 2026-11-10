/*
 * Retromod Test Mod (NeoForge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.neoforge;

import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Item stacks a mod builds while it constructs, which 26.x cannot build until a world's data binds item components. */
final class EarlyStackCase {

    // Kept from construction for the server-started half.
    private static ItemStack diamonds;
    private static ItemStack pickaxe;
    private static ItemStack defaultApple;
    private static ItemStack copied;

    private EarlyStackCase() {}

    /** A static field, the holder constructor and getDefaultInstance, all while the mod constructs. */
    static boolean buildWhileConstructing() {
        diamonds = Icons.DIAMONDS;
        pickaxe = new ItemStack(Items.DIAMOND_PICKAXE.builtInRegistryHolder());
        defaultApple = Items.APPLE.getDefaultInstance();
        copied = diamonds.copyWithCount(5);
        return diamonds.getItem() == Items.DIAMOND && diamonds.getCount() == 3 && !diamonds.isEmpty()
                && pickaxe.getItem() == Items.DIAMOND_PICKAXE && defaultApple.getItem() == Items.APPLE;
    }

    /** Once a server has loaded its data, the kept stacks read their defaults, stack with new stacks both ways, and save as them. */
    static String matchOnServer(HolderLookup.Provider registries) {
        if (diamonds == null) return "fail: the construction half did not run";
        ItemStack now = new ItemStack(Items.DIAMOND, 3);
        if (diamonds.getMaxStackSize() != 64) return "fail: max stack size reads " + diamonds.getMaxStackSize();
        if (!ItemStack.isSameItemSameComponents(diamonds, now) || !ItemStack.isSameItemSameComponents(now, diamonds)) {
            return "fail: the stack built while constructing does not stack with a new one";
        }
        if (!ItemStack.matches(copied, new ItemStack(Items.DIAMOND, 5))) return "fail: the copy does not match a stack of five";
        if (!pickaxe.isDamageableItem() || !ItemStack.isSameItemSameComponents(pickaxe, new ItemStack(Items.DIAMOND_PICKAXE))) {
            return "fail: the pickaxe built from its holder lost its defaults";
        }
        if (!ItemStack.isSameItemSameComponents(defaultApple, new ItemStack(Items.APPLE))) {
            return "fail: getDefaultInstance does not stack with an apple";
        }
        // Saved and read back with the 1.21.1 calls, it comes back as the stack it was.
        ItemStack loaded = ItemStack.parse(registries, diamonds.save(registries)).orElse(ItemStack.EMPTY);
        return ItemStack.matches(loaded, now) ? "success" : "fail: the saved stack read back as " + loaded;
    }

    /** A static field set in a class initializer, as a mod's icon holder does. */
    private static final class Icons {
        static final ItemStack DIAMONDS = new ItemStack(Items.DIAMOND, 3);
    }
}
