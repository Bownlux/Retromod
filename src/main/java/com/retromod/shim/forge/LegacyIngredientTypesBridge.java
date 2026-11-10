/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.shim.common.RebuiltApiClasses;
import org.objectweb.asm.tree.ClassNode;

import java.util.List;
import java.util.function.Function;

/** Keeps Forge 1.20.1's compound, difference, intersection and NBT ingredients working on Forge 26.2 and newer. */
public final class LegacyIngredientTypesBridge {

    static final String OLD = "net/minecraftforge/common/crafting/";
    static final String HOST = "net/minecraftforge/common/crafting/ingredients/";
    static final String OLD_COMPOUND = OLD + "CompoundIngredient";
    static final String OLD_STRICT = OLD + "StrictNBTIngredient";
    /** The rebuilt compound an old mod's subclass extends. */
    public static final String COMPOUND = RebuiltApiClasses.ROOT + "forge/crafting/LegacyCompoundIngredient";
    /** The rebuilt strict NBT ingredient, which compares every component. */
    public static final String STRICT = RebuiltApiClasses.ROOT + "forge/crafting/LegacyStrictNBTIngredient";

    private LegacyIngredientTypesBridge() {}

    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read);
    }

    static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        // Forge 26.2 moved these types into the ingredients package with codecs and private constructors.
        List<String> moved = List.of(HOST + "CompoundIngredient", HOST + "DifferenceIngredient",
                HOST + "IntersectionIngredient", HOST + "NBTIngredient");
        if (!ForgeHostShape.matches(moved, List.of(OLD_COMPOUND), ForgeHostShape.FIRST_CHECKED_VERSION, host)) {
            return;
        }
        // Mods extend the old compound through its protected constructor, which the host's compound lacks.
        RebuiltApiClasses.registerTree(t, COMPOUND);
        t.registerClassRedirect(OLD_COMPOUND, COMPOUND);
        // The host's NBT ingredient compares custom data only, and the old strict one compared all item data.
        RebuiltApiClasses.registerTree(t, STRICT);
        t.registerClassRedirect(OLD_STRICT, STRICT);
        // The host's own types keep the old factories with the same arguments, and partial NBT is its default.
        t.registerClassRedirect(OLD + "DifferenceIngredient", HOST + "DifferenceIngredient");
        t.registerClassRedirect(OLD + "IntersectionIngredient", HOST + "IntersectionIngredient");
        t.registerClassRedirect(OLD + "PartialNBTIngredient", HOST + "NBTIngredient");
    }
}
