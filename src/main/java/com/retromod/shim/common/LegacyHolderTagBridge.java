/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.tree.ClassNode;

/** Answers an old mod's tag checks and tag reads before the tags are bound, as 1.21.1 did, instead of throwing. */
public final class LegacyHolderTagBridge {

    static final String HOLDER = "net/minecraft/core/Holder";
    static final String REFERENCE = HOLDER + "$Reference";
    static final String TAGS = RebuiltApiClasses.ROOT + "minecraft/core/LegacyHolderTags";
    static final String IS_DESC = "(Lnet/minecraft/tags/TagKey;)Z";
    static final String HELPER_DESC = "(L" + HOLDER + ";Lnet/minecraft/tags/TagKey;)Z";
    static final String HOLDER_SET = "net/minecraft/core/HolderSet";
    static final String SETS = RebuiltApiClasses.ROOT + "minecraft/core/LegacyHolderSets";
    // The owners a call to a holder set's read can name: the interface, the tag's set, and their shared base.
    static final String[] SET_OWNERS = {HOLDER_SET, HOLDER_SET + "$Named", HOLDER_SET + "$ListBacked"};
    // Each read 1.21.1 answered from an empty list while the tag was unbound, by name and descriptor; contains asks the
    // holder's own tags instead, as a named set's contains always did.
    static final String[][] SET_READS = {
            {"iterator", "()Ljava/util/Iterator;"},
            {"stream", "()Ljava/util/stream/Stream;"},
            {"size", "()I"},
            {"get", "(I)L" + HOLDER + ";"},
            {"getRandomElement", "(Lnet/minecraft/util/RandomSource;)Ljava/util/Optional;"},
            {"contains", "(L" + HOLDER + ";)Z"},
            {"forEach", "(Ljava/util/function/Consumer;)V"}};

    private LegacyHolderTagBridge() {}

    /** Registers the wrapper only on hosts whose holders throw for unbound tags, which 1.21.2 started. */
    public static void register(RetromodTransformer transformer) {
        if (!throwsForUnboundTags()) return;
        RebuiltApiClasses.registerTree(transformer, TAGS);
        // Supplementaries checks every potion against its quiver blacklist during common setup, before any data loads.
        transformer.registerWrappingMethodRedirect(HOLDER, "is", IS_DESC, TAGS, "is", HELPER_DESC);
        transformer.registerWrappingMethodRedirect(REFERENCE, "is", IS_DESC, TAGS, "is", HELPER_DESC);
        // A mod built for 1.21.2 or newer expects the throw, so only older mods get the wrapper.
        transformer.limitRedirectToModsBefore(HOLDER, "is", IS_DESC, "1.21.2");
        transformer.limitRedirectToModsBefore(REFERENCE, "is", IS_DESC, "1.21.2");
        // Moonlight's Fabric burn times read an item tag's set in a reload listener, before 26.3 binds the tags.
        RebuiltApiClasses.registerTree(transformer, SETS);
        for (String owner : SET_OWNERS) {
            for (String[] read : SET_READS) {
                String helper = "(L" + HOLDER_SET + ";" + read[1].substring(1);
                transformer.registerWrappingMethodRedirect(owner, read[0], read[1], SETS, read[0], helper);
                transformer.limitRedirectToModsBefore(owner, read[0], read[1], "1.21.2");
            }
        }
    }

    /** 1.21.2 routed Reference.is(tag) through boundTags(), which throws while the tags are unset. */
    static boolean throwsForUnboundTags() {
        ClassNode reference = ClassResourceInspector.read(REFERENCE);
        // An unreadable host means an offline transform, so the host version decides.
        if (reference == null) {
            return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "1.21.2") >= 0;
        }
        return reference.methods.stream().anyMatch(method -> method.name.equals("boundTags"));
    }
}
