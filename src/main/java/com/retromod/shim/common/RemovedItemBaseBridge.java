/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

/**
 * Keeps mods that extend a deleted vanilla item class loadable on every host that removed it.
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

    /**
     * Item subclasses Minecraft removed that a mod extends directly, all of which sat on
     * {@code Item}, with the version each disappeared in, read from Mojang's own client mappings.
     *
     * <p>A rebase is not conditional on the class being missing, so each base is registered by
     * the shim for the version that removed it and applies only on a host that lost it. They used
     * to be registered from 26.1 only, which left every NeoForge and Forge host from 1.21 through
     * 1.21.11 without them: a 1.20.1 music disc mod still failed on 1.21.1 with
     * {@code NoClassDefFoundError: RecordItem}.
     *
     * <p>The tool classes were a chain, {@code SwordItem} and {@code DiggerItem} on
     * {@code TieredItem} on {@code Item}, and every link of it is gone: material, mining level and
     * attack damage are components now. The rest each held one behaviour that became a component.
     * {@code AxeItem}, {@code ShovelItem} and {@code HoeItem} are not here: they survived until
     * 26.3, so the 26.3 shim registers them.
     *
     * <p>A mod with custom tools or armour is most of what people translate, and every one of them
     * stopped at the {@code extends}. Rebasing keeps the item registering with its name, texture,
     * recipe and tab. What the old base did with its other constructor arguments does not come
     * back: a rebased sword is an ordinary item until its mod sets the components itself, and a
     * rebased music disc no longer plays in a jukebox, because that now comes from the
     * {@code jukebox_playable} component.
     */
    private static final java.util.Map<String, String> REMOVED_IN = java.util.Map.ofEntries(
        java.util.Map.entry("SimpleFoiledItem", "1.20.5"),
        java.util.Map.entry("HorseArmorItem", "1.20.5"),
        java.util.Map.entry("RecordItem", "1.21"),
        java.util.Map.entry("BowlFoodItem", "1.21"),
        java.util.Map.entry("EnchantedBookItem", "1.21.2"),
        java.util.Map.entry("TieredItem", "1.21.2"),
        java.util.Map.entry("ElytraItem", "1.21.2"),
        java.util.Map.entry("BookItem", "1.21.2"),
        java.util.Map.entry("ChorusFruitItem", "1.21.2"),
        java.util.Map.entry("HoneyBottleItem", "1.21.2"),
        java.util.Map.entry("MilkBucketItem", "1.21.2"),
        java.util.Map.entry("SuspiciousStewItem", "1.21.2"),
        java.util.Map.entry("OminousBottleItem", "1.21.2"),
        java.util.Map.entry("ComplexItem", "1.21.2"),
        java.util.Map.entry("SwordItem", "1.21.5"),
        java.util.Map.entry("PickaxeItem", "1.21.5"),
        java.util.Map.entry("DiggerItem", "1.21.5"),
        java.util.Map.entry("ArmorItem", "1.21.5"),
        java.util.Map.entry("AnimalArmorItem", "1.21.5"),
        java.util.Map.entry("BannerPatternItem", "1.21.5"),
        java.util.Map.entry("FireworkStarItem", "1.21.5"),
        java.util.Map.entry("SaddleItem", "1.21.5"));

    /** Item marker interfaces Minecraft removed, with the version each went in; an empty stand-in lets the item load (#415). */
    private static final java.util.Map<String, String> REMOVED_MARKERS_IN = java.util.Map.of("Vanishable", "1.20.5");
    /** The same markers under their intermediary names, for Fabric hosts before 26.1. */
    private static final java.util.Map<String, String> INTERMEDIARY_MARKERS = java.util.Map.of("net/minecraft/class_5150", "Vanishable");

    private RemovedItemBaseBridge() {}

    /** Registers every base. The 26.1 shim calls this, since all of them are gone by then. */
    public static void register(RetromodTransformer transformer) {
        for (String removed : REMOVED_IN.keySet()) registerBase(transformer, removed);
        for (String removed : REMOVED_MARKERS_IN.keySet()) registerMarker(transformer, removed);
        LegacyEquipmentBridge.register(transformer);

        // Not bridged here: BedItem and ItemNameBlockItem sat on BlockItem, and SignItem on
        // StandingAndWallBlockItem. Both of those bases survive, so the right base is one of them
        // rather than Item, and their constructors take the block before the properties while the
        // old ones took it after. Rebasing those needs argument reordering, which the constructor
        // absorber refuses on purpose, so they still report rather than guess.
    }

    /** Registers only the bases Minecraft removed in exactly {@code mcVersion}. */
    public static void registerRemovedIn(RetromodTransformer transformer, String mcVersion) {
        REMOVED_IN.forEach((removed, version) -> {
            if (version.equals(mcVersion)) registerBase(transformer, removed);
        });
        REMOVED_MARKERS_IN.forEach((removed, version) -> {
            if (version.equals(mcVersion)) registerMarker(transformer, removed);
        });
        LegacyEquipmentBridge.register(transformer);
    }

    /** Registers the removed markers under their intermediary names, which Fabric hosts before 26.1 run. */
    public static void registerIntermediaryMarkers(RetromodTransformer transformer) {
        INTERMEDIARY_MARKERS.forEach((intermediary, removed) -> registerMarker(transformer, intermediary, removed));
    }

    /** The version {@code simpleName} was removed in, or null when this bridge does not cover it. */
    static String removedIn(String simpleName) {
        return REMOVED_IN.get(simpleName);
    }

    private static void registerMarker(RetromodTransformer transformer, String removed) {
        registerMarker(transformer, "net/minecraft/world/item/" + removed, removed);
    }

    /** Sends the removed marker to an empty interface of the same meaning, generated once. */
    private static void registerMarker(RetromodTransformer transformer, String hostName, String removed) {
        String generated = "com/retromod/generated/Legacy" + removed;
        if (!transformer.getSyntheticClasses().containsKey(generated)) {
            ClassWriter cw = new ClassWriter(0);
            cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, generated, null,
                    "java/lang/Object", null);
            cw.visitEnd();
            transformer.registerSyntheticClass(generated, cw.toByteArray());
        }
        transformer.registerClassRedirect(hostName, generated);
    }

    private static void registerBase(RetromodTransformer transformer, String removed) {
        String generated = "com/retromod/generated/Legacy" + removed;
        // Every shim from the removal version up to 26.1 can reach this; register once, so a base
        // that already absorbed constructors is not reset by a later shim.
        if (transformer.getSyntheticClasses().containsKey(generated)) return;
        transformer.registerGeneratedLegacyBase(
                "net/minecraft/world/item/" + removed, generated, ITEM, ITEM_CTOR);
    }
}
