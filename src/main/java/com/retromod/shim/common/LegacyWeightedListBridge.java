/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.tree.ClassNode;

import java.util.List;
import java.util.function.Function;

/** Answers the members of the two weighted lists 1.21.5 folded into {@code WeightedList}, where their names or results changed. */
public final class LegacyWeightedListBridge {

    static final String OLD = "net/minecraft/util/random/SimpleWeightedRandomList";
    static final String ENTRIES = "net/minecraft/util/random/WeightedRandomList";
    static final String HELPER = RebuiltApiClasses.ROOT + "minecraft/util/random/LegacyWeightedLists";
    private static final String CODEC = "Lcom/mojang/serialization/Codec;";
    static final String NEW = "net/minecraft/util/random/WeightedList";
    static final String RANDOM = "Lnet/minecraft/util/RandomSource;";
    /** The release that replaced the old list. */
    static final String VERSION = "1.21.5";

    private LegacyWeightedListBridge() {}

    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read);
    }

    static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        if (!hostReplacedIt(host)) return;
        String list = "L" + NEW + ";";
        // The class move to WeightedList may run before or after these, so both the old and new owner and return type match.
        for (String owner : List.of(OLD, NEW)) {
            String returned = "L" + owner + ";";
            t.registerMethodRedirect(owner, "empty", "()" + returned, NEW, "of", "()" + list);
            t.limitRedirectToModsBefore(owner, "empty", "()" + returned, VERSION);
            t.registerMethodRedirect(owner, "single", "(Ljava/lang/Object;)" + returned, NEW, "of", "(Ljava/lang/Object;)" + list);
            t.limitRedirectToModsBefore(owner, "single", "(Ljava/lang/Object;)" + returned, VERSION);
            // getRandomValue returned the drawn value itself, as getRandom does now.
            t.registerMethodRedirect(owner, "getRandomValue", "(" + RANDOM + ")Ljava/util/Optional;", NEW, "getRandom",
                    "(" + RANDOM + ")Ljava/util/Optional;");
            t.limitRedirectToModsBefore(owner, "getRandomValue", "(" + RANDOM + ")Ljava/util/Optional;", VERSION);
            // The same {data, weight} entries, read by the host's codec.
            redirect(t, owner, "wrappedCodec", "(" + CODEC + ")" + CODEC, NEW, "codec", "(" + CODEC + ")" + CODEC);
        }
        RebuiltApiClasses.registerTree(t, HELPER);
        // getRandom, unwrap and codec meant different things on the two old lists, which both become WeightedList,
        // so they are rewritten by their original owner before the class move (LegacyWeightedListPrePass).
        t.enablePostProcess(LegacyWeightedListPrePass.FLAG);
        // A list of entries kept each entry's own weight; the host takes the weight beside each value.
        for (String owner : List.of(OLD, ENTRIES)) {
            String returned = "Lnet/minecraft/util/random/WeightedRandomList;";
            redirect(t, owner, "create", "(Ljava/util/List;)" + returned, HELPER, "fromEntries", "(Ljava/util/List;)" + list);
            redirect(t, owner, "create", "([Lnet/minecraft/util/random/WeightedEntry;)" + returned, HELPER, "fromEntries",
                    "([Ljava/lang/Object;)" + list);
            redirect(t, owner, "create", "()" + returned, HELPER, "noEntries", "()" + list);
        }
    }

    /** A call to the old {@code owner.name} becomes a static call to {@code target}, for mods built before 1.21.5. */
    private static void redirect(RetromodTransformer t, String owner, String name, String desc, String target, String newName,
            String newDesc) {
        boolean instance = !target.equals(NEW) && newDesc.startsWith("(L" + NEW + ";");
        t.registerMethodRedirect(owner, name, desc, target, newName, newDesc, instance);
        t.limitRedirectToModsBefore(owner, name, desc, VERSION);
    }

    /** Whether the host's list has {@code of()} and no {@code empty()}; offline or unreadable, a target from 1.21.5. */
    static boolean hostReplacedIt(Function<String, ClassNode> host) {
        ClassNode weighted = host.apply(NEW);
        if (weighted == null) return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, VERSION) >= 0;
        return weighted.methods.stream().anyMatch(m -> m.name.equals("of") && m.desc.equals("()L" + NEW + ";"))
                && weighted.methods.stream().noneMatch(m -> m.name.equals("empty"));
    }
}
