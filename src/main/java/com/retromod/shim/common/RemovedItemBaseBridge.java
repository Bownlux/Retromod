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

    /**
     * Item subclasses 26.x removed that a mod extends directly, all of which sat on {@code Item}.
     *
     * <p>The tool classes were a chain, {@code SwordItem} and {@code DiggerItem} on
     * {@code TieredItem} on {@code Item}, and every link of it is gone: material, mining level and
     * attack damage are components now. The rest each held one behaviour that became a component.
     * Only classes already absent on 26.1 belong in this list, because the bridge that registers it
     * applies from 26.1 up and a rebase is not conditional on the class being missing.
     * Checked against the 1.20.1 Mojang-mapped jar for what they extended, and against 26.3 for
     * which of those bases survived, so each lands on a real class rather than the nearest name.
     *
     * <p>A mod with custom tools or armour is most of what people translate, and every one of them
     * stopped at the {@code extends}. Rebasing keeps the item registering with its name, texture,
     * recipe and tab. What the old base did with its other constructor arguments does not come
     * back: a rebased sword is an ordinary item until its mod sets the components itself.
     */
    private static final String[] REMOVED_ITEM_BASES = {
        // Tools and weapons. AxeItem, ShovelItem and HoeItem are NOT here: they survived into
        // 26.1 and 26.2 and only went at 26.3, so they are registered by the 26.3 shim, which
        // only applies on a host that actually lost them.
        "SwordItem", "PickaxeItem", "DiggerItem", "TieredItem",
        // Wearables that were plain Item subclasses.
        "ArmorItem", "AnimalArmorItem", "ElytraItem",
        // One behaviour each, all of it now a component.
        "BookItem", "BannerPatternItem", "ChorusFruitItem", "HoneyBottleItem", "MilkBucketItem",
        "SuspiciousStewItem", "OminousBottleItem", "FireworkStarItem", "SaddleItem", "ComplexItem",
    };

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

        for (String removed : REMOVED_ITEM_BASES) {
            transformer.registerGeneratedLegacyBase(
                    "net/minecraft/world/item/" + removed,
                    "com/retromod/generated/Legacy" + removed,
                    ITEM, ITEM_CTOR);
        }

        // Not bridged here: BedItem and ItemNameBlockItem sat on BlockItem, and SignItem on
        // StandingAndWallBlockItem. Both of those bases survive, so the right base is one of them
        // rather than Item, and their constructors take the block before the properties while the
        // old ones took it after. Rebasing those needs argument reordering, which the constructor
        // absorber refuses on purpose, so they still report rather than guess.
    }
}
