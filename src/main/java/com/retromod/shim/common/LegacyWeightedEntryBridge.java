/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.commons.SimpleRemapper;

import java.util.Map;

/** Rebuilds the weighted entry API 1.21.5 removed, for mods built for 1.21.4 or older. */
public final class LegacyWeightedEntryBridge {

    static final String RANDOM = "net/minecraft/util/random/";
    static final String OLD_ENTRY = RANDOM + "WeightedEntry";
    static final String OLD_WRAPPER = OLD_ENTRY + "$Wrapper";
    static final String OLD_BASE = OLD_ENTRY + "$IntrusiveBase";
    static final String OLD_WEIGHT = RANDOM + "Weight";
    static final String WEIGHTED = RANDOM + "Weighted";
    static final String WEIGHTED_RANDOM = RANDOM + "WeightedRandom";
    static final String RANDOM_SOURCE = "net/minecraft/util/RandomSource";

    static final String REBUILT = RebuiltApiClasses.ROOT + "minecraft/util/random/";
    static final String ENTRY = REBUILT + "WeightedEntry";
    static final String BASE = ENTRY + "$IntrusiveBase";
    static final String WEIGHT = REBUILT + "Weight";
    static final String MEMBERS = REBUILT + "WeightedEntryMembers";
    static final String INTERMEDIARY_REBUILT = RebuiltApiClasses.ROOT + "intermediary/minecraft/util/random/";

    private static final String L_WEIGHT = "L" + WEIGHT + ";";
    private static final String L_WEIGHTED = "L" + WEIGHTED + ";";
    private static final String L_OLD_WEIGHT = "L" + OLD_WEIGHT + ";";
    private static final String L_SOURCE = "L" + RANDOM_SOURCE + ";";
    static final String TOTAL = "(Ljava/util/List;)I";
    static final String PICK = "(" + L_SOURCE + "Ljava/util/List;)Ljava/util/Optional;";
    static final String PICK_FROM_TOTAL = "(" + L_SOURCE + "Ljava/util/List;I)Ljava/util/Optional;";
    static final String AT_INDEX = "(Ljava/util/List;I)Ljava/util/Optional;";

    // A Fabric 1.21.5 to 1.21.11 host runs intermediary names, the same ones in each of those
    // versions (composed from the 1.21.4, 1.21.5 and 1.21.11 tables). The rebuilt classes are
    // copied into those names and answer the old ids, so a mod's own entry still overrides getWeight.
    // The one-argument constructor: NeoForge 1.21.11 ships ASM 9.8, which has no api-level one.
    static final SimpleRemapper INTERMEDIARY = new SimpleRemapper(Map.ofEntries(
            Map.entry(OLD_ENTRY, "net/minecraft/class_6008"),
            Map.entry(OLD_WRAPPER, "net/minecraft/class_6008$class_6010"),
            Map.entry(OLD_BASE, "net/minecraft/class_6008$class_6009"),
            Map.entry(OLD_WEIGHT, "net/minecraft/class_6007"),
            Map.entry(WEIGHTED, "net/minecraft/class_6010"),
            Map.entry(WEIGHTED_RANDOM, "net/minecraft/class_6011"),
            Map.entry(RANDOM_SOURCE, "net/minecraft/class_5819"),
            Map.entry(ENTRY, INTERMEDIARY_REBUILT + "WeightedEntry"),
            Map.entry(BASE, INTERMEDIARY_REBUILT + "WeightedEntry$IntrusiveBase"),
            Map.entry(WEIGHT, INTERMEDIARY_REBUILT + "Weight"),
            Map.entry(MEMBERS, INTERMEDIARY_REBUILT + "WeightedEntryMembers"),
            // What the rebuilt classes call on the host.
            Map.entry(WEIGHTED + ".value()Ljava/lang/Object;", "comp_2542"),
            Map.entry(WEIGHTED + ".weight()I", "comp_2543"),
            Map.entry(RANDOM_SOURCE + ".nextInt(I)I", "method_43048"),
            // The rebuilt members a Fabric mod names by its old id.
            Map.entry(ENTRY + ".getWeight()" + L_WEIGHT, "method_34979"),
            Map.entry(BASE + ".getWeight()" + L_WEIGHT, "method_34979"),
            Map.entry(ENTRY + ".wrap(Ljava/lang/Object;I)" + L_WEIGHTED, "method_34980"),
            Map.entry(WEIGHT + ".of(I)" + L_WEIGHT, "method_34977"),
            Map.entry(WEIGHT + ".asInt()I", "method_34976"),
            Map.entry(WEIGHT + ".ONE", "field_29928"),
            // The old members redirected to the helpers.
            Map.entry(WEIGHTED_RANDOM + ".getTotalWeight" + TOTAL, "method_34984"),
            Map.entry(WEIGHTED_RANDOM + ".getWeightedItem" + AT_INDEX, "method_34985"),
            Map.entry(WEIGHTED_RANDOM + ".getRandomItem" + PICK, "method_34986"),
            Map.entry(WEIGHTED_RANDOM + ".getRandomItem" + PICK_FROM_TOTAL, "method_34987"),
            Map.entry(OLD_WRAPPER + ".getData()Ljava/lang/Object;", "method_34983"),
            Map.entry(OLD_WRAPPER + ".getWeight()" + L_OLD_WEIGHT, "method_34979"),
            Map.entry(OLD_WRAPPER + ".weight()" + L_OLD_WEIGHT, "comp_2543"),
            Map.entry(OLD_ENTRY + ".getWeight()" + L_OLD_WEIGHT, "method_34979")));

    private LegacyWeightedEntryBridge() {}

    /** Mojang-named mods, on hosts that lost the weighted entry API. */
    public static void register(RetromodTransformer t) {
        if (hostKeepsWeightedEntry(OLD_ENTRY, WEIGHTED_RANDOM)) return;
        registerRedirects(t, null);
    }

    /** Fabric mods on a 1.21.5 to 1.21.11 host, which runs intermediary names. */
    public static void registerIntermediary(RetromodTransformer t) {
        if (hostKeepsWeightedEntry(INTERMEDIARY.mapType(OLD_ENTRY), INTERMEDIARY.mapType(WEIGHTED_RANDOM))) return;
        registerRedirects(t, INTERMEDIARY);
    }

    /** Whether the host still has the old interface; offline, where no host class is readable, the version decides. */
    static boolean hostKeepsWeightedEntry(String entry, String weightedRandom) {
        if (ClassResourceInspector.read(weightedRandom) != null) return ClassResourceInspector.read(entry) != null;
        return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "1.21.5") < 0;
    }

    /** Unconditional registration, in Mojang names or in {@code names}. */
    static void registerRedirects(RetromodTransformer t, Remapper names) {
        RebuiltApiClasses.registerTree(t, MEMBERS, names);
        // The class moves come first, so each member redirect below also matches a call whose
        // owner or descriptor an earlier pass already rewrote.
        classRedirect(t, names, OLD_ENTRY, ENTRY);
        classRedirect(t, names, OLD_BASE, BASE);
        classRedirect(t, names, OLD_WEIGHT, WEIGHT);
        // 1.21.5 turned the wrapper into the top-level Weighted record that host lists hold.
        classRedirect(t, names, OLD_WRAPPER, WEIGHTED);
        // The random picks took a weight function; the helpers keep the old sums and picks.
        redirect(t, names, WEIGHTED_RANDOM, "getTotalWeight", TOTAL, "getTotalWeight", TOTAL, false);
        redirect(t, names, WEIGHTED_RANDOM, "getWeightedItem", AT_INDEX, "getWeightedItem", AT_INDEX, false);
        redirect(t, names, WEIGHTED_RANDOM, "getRandomItem", PICK, "getRandomItem", PICK, false);
        redirect(t, names, WEIGHTED_RANDOM, "getRandomItem", PICK_FROM_TOTAL, "getRandomItem", PICK_FROM_TOTAL, false);
        // The wrapper's getters: data is value now, and the weight is a plain int.
        redirect(t, names, OLD_WRAPPER, "getData", "()Ljava/lang/Object;",
                "getData", "(" + L_WEIGHTED + ")Ljava/lang/Object;", true);
        redirect(t, names, OLD_WRAPPER, "getWeight", "()" + L_OLD_WEIGHT,
                "wrapperWeight", "(" + L_WEIGHTED + ")" + L_WEIGHT, true);
        redirect(t, names, OLD_WRAPPER, "weight", "()" + L_OLD_WEIGHT,
                "wrapperWeight", "(" + L_WEIGHTED + ")" + L_WEIGHT, true);
        // A call through the interface may reach a wrapped value, which is a host Weighted now.
        redirect(t, names, OLD_ENTRY, "getWeight", "()" + L_OLD_WEIGHT,
                "weightOf", "(Ljava/lang/Object;)" + L_WEIGHT, true);
        String newWrapper = "(Ljava/lang/Object;" + L_WEIGHT + ")" + L_WEIGHTED;
        if (names == null) {
            // 1.21 to 1.21.4 made the wrapper a record whose data() is value() on Weighted.
            t.registerMethodRedirect(OLD_WRAPPER, "data", "()Ljava/lang/Object;", WEIGHTED, "value", "()Ljava/lang/Object;");
            t.registerConstructorRedirect(OLD_WRAPPER, "(Ljava/lang/Object;" + L_OLD_WEIGHT + ")V",
                    MEMBERS, "newWrapper", newWrapper);
        } else {
            t.registerConstructorRedirect(names.mapType(OLD_WRAPPER), names.mapMethodDesc("(Ljava/lang/Object;" + L_OLD_WEIGHT + ")V"),
                    names.mapType(MEMBERS), "newWrapper", names.mapMethodDesc(newWrapper));
        }
    }

    private static void classRedirect(RetromodTransformer t, Remapper names, String from, String to) {
        t.registerClassRedirect(names == null ? from : names.mapType(from), names == null ? to : names.mapType(to));
    }

    /** Points an old member at a helper of {@link #MEMBERS}, in Mojang names or in {@code names}. */
    private static void redirect(RetromodTransformer t, Remapper names, String owner, String name, String desc,
            String helperName, String helperDesc, boolean instance) {
        if (names == null) {
            t.registerMethodRedirect(owner, name, desc, MEMBERS, helperName, helperDesc, instance);
            return;
        }
        t.registerMethodRedirect(names.mapType(owner), names.mapMethodName(owner, name, desc), names.mapMethodDesc(desc),
                names.mapType(MEMBERS), helperName, names.mapMethodDesc(helperDesc), instance);
    }
}
