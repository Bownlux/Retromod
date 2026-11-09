/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.item;

import com.mojang.serialization.Dynamic;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;

/** Item stacks a mod saved before 1.20.5, brought to the current shape with the host's own data fixer. */
public final class LegacyItemData {

    /** The data version a stack is read from when the mod's own Minecraft version is unknown: 1.20.1's. */
    public static final int DEFAULT_DATA_VERSION = 3465;
    /** The highest count the item codec reads and writes. */
    public static final int MAX_COUNT = 99;
    /** 1.20.4's data version, the last release that saved a stack with {@code Count} and {@code tag}. */
    public static final int LAST_OLD_SHAPE_DATA_VERSION = 3700;
    private static final String ITEMS = "Items";

    private LegacyItemData() {}

    /** A stack in the 1.20.4 shape, with {@code Count} and {@code tag}, in the current shape; any other tag as it is. */
    public static CompoundTag upgradeStack(CompoundTag stack) {
        return upgradeStack(stack, DEFAULT_DATA_VERSION);
    }

    /** The same, read as data the mod saved at {@code dataVersion}: a mod's own data is never upgraded with the world. */
    public static CompoundTag upgradeStack(CompoundTag stack, int dataVersion) {
        if (stack.contains("count") || !stack.contains("Count")) return stack;
        // A stack in this shape was saved by 1.20.4 at the latest, whatever version the mod's jar names.
        int from = Math.min(dataVersion, LAST_OLD_SHAPE_DATA_VERSION);
        Tag fixed = DataFixers.getDataFixer().update(References.ITEM_STACK, new Dynamic<>(NbtOps.INSTANCE, (Tag) stack.copy()),
                from, SharedConstants.getCurrentVersion().dataVersion().version()).getValue();
        CompoundTag upgraded = fixed instanceof CompoundTag compound ? compound : stack.copy();
        // A byte Count past 127 wrapped, and the mod kept the real count beside it.
        int count = upgraded.getIntOr("count", stack.getByteOr("Count", (byte) 1));
        upgraded.putInt("count", repairCount(count));
        return upgraded;
    }

    /** A count read from an old byte {@code Count}: past 127 it wrapped, so it is read unsigned, and it is capped. */
    public static int repairCount(int count) {
        return Math.max(1, Math.min(MAX_COUNT, count < 0 ? count & 0xFF : count));
    }

    /**
     * The tag with each 1.20.4-shaped stack of its {@code Items} list upgraded, or the same tag when it has none. A new
     * tag holds copies only, so a caller that edits it changes neither the mod's tag nor what the next caller reads.
     */
    public static CompoundTag upgradeItems(CompoundTag tag, int dataVersion) {
        ListTag items = tag.getListOrEmpty(ITEMS);
        if (!holdsOldStack(items)) return tag;
        ListTag upgraded = new ListTag();
        for (int i = 0; i < items.size(); i++) {
            Tag item = items.get(i);
            Tag fixed = item instanceof CompoundTag entry ? upgradeStack(entry, dataVersion) : item;
            // upgradeStack builds each upgraded stack anew, and an entry it leaves alone is copied here.
            upgraded.add(fixed == item ? item.copy() : fixed);
        }
        CompoundTag copy = tag.copy();
        copy.put(ITEMS, upgraded);
        return copy;
    }

    private static boolean holdsOldStack(ListTag items) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i) instanceof CompoundTag entry && !entry.contains("count") && entry.contains("Count")) return true;
        }
        return false;
    }
}
