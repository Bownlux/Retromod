/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;

/**
 * Keeps mods that extend a deleted vanilla item class loadable on 26.x.
 *
 * <p>Minecraft has been folding hardcoded item subclasses into data components for several
 * versions. The class disappears, and every mod that extended it stops loading. There is nothing to
 * rename it to: the replacement is a component on {@code Item.Properties}, not another class.
 *
 * <p>The inheritance edge is rebased onto a generated {@code Item} subclass that carries the old
 * constructors, so the subclass links and the item still registers. What the old base did with its
 * extra constructor arguments is gone. A music disc keeps its name, texture, recipe, and creative
 * tab, and it no longer plays in a jukebox, because that now comes from the
 * {@code jukebox_playable} component rather than from the constructor.
 *
 * <p>Pointing these at the nearest surviving name is worse than leaving them alone, and the table
 * used to do exactly that. {@code JukeboxSong} is a final record, so an extending mod failed at
 * class load, and {@code Items} is a static holder with no constructor, so it failed at the
 * {@code super(...)} call instead.
 */
public final class RemovedItemBaseBridge {

    private static final String ITEM = "net/minecraft/world/item/Item";
    private static final String ITEM_CTOR = "(Lnet/minecraft/world/item/Item$Properties;)V";
    private static final String GENERATED_RECORD = "com/retromod/generated/LegacyRecordItem";
    private static final String GENERATED_ENCHANTED_BOOK =
            "com/retromod/generated/LegacyEnchantedBookItem";

    private RemovedItemBaseBridge() {}

    public static void register(RetromodTransformer transformer) {
        // RecordItem held the comparator output, the sound, and the track length. All three moved
        // into the jukebox_playable component and the JukeboxSong registry, and the class was
        // removed (verified absent on 26.1.2 and 26.2). Item is not final and still takes
        // Properties, so it is a working base for the subclass.
        transformer.registerGeneratedLegacyBase(
                "net/minecraft/world/item/RecordItem", GENERATED_RECORD, ITEM, ITEM_CTOR);

        // EnchantedBookItem went the same way when enchantments became a component. The table used
        // to point it at Items, a static holder with no constructor at all, so an extending mod
        // traded a missing class for a missing constructor (verified absent on 26.1.2 and 26.2).
        transformer.registerGeneratedLegacyBase(
                "net/minecraft/world/item/EnchantedBookItem", GENERATED_ENCHANTED_BOOK,
                ITEM, ITEM_CTOR);
    }
}
