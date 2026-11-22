/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.neoforge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.shim.common.RebuiltApiClasses;
import org.objectweb.asm.tree.ClassNode;

import java.util.function.Function;

/** Keeps NeoForge 1.21.1's {@code ModifyDefaultComponentsEvent} calls, which took a patch consumer, working on 26.1 and newer. */
public final class LegacyDefaultComponentsBridge {

    static final String EVENT = "net/neoforged/neoforge/event/ModifyDefaultComponentsEvent";
    static final String REBUILT = RebuiltApiClasses.ROOT + "neoforge/event/LegacyDefaultComponents";
    static final String OLD_MODIFY = "(Lnet/minecraft/world/level/ItemLike;Ljava/util/function/Consumer;)V";
    static final String OLD_MATCHING = "(Ljava/util/function/Predicate;Ljava/util/function/Consumer;)V";
    static final String NEW_MODIFY = "(Lnet/minecraft/world/level/ItemLike;L" + EVENT + "$Initializer;)V";

    private LegacyDefaultComponentsBridge() {}

    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read);
    }

    /**
     * Only where the event takes an initializer. 26.1 and 26.2 still have a {@code modify(item, consumer)},
     * but its consumer edits the component map builder, not the old patch builder, so the redirects apply
     * only to mods built before 26.1, whose consumer is the patch kind.
     */
    static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        ClassNode event = host.apply(EVENT);
        if (event == null || event.methods.stream().noneMatch(m -> m.name.equals("modify") && m.desc.equals(NEW_MODIFY))) return;
        RebuiltApiClasses.registerTree(t, REBUILT);
        t.registerMethodRedirect(EVENT, "modify", OLD_MODIFY, REBUILT, "modify",
                "(L" + EVENT + ";Lnet/minecraft/world/level/ItemLike;Ljava/util/function/Consumer;)V");
        t.registerMethodRedirect(EVENT, "modifyMatching", OLD_MATCHING, REBUILT, "modifyMatching",
                "(L" + EVENT + ";Ljava/util/function/Predicate;Ljava/util/function/Consumer;)V");
        t.limitRedirectToModsBefore(EVENT, "modify", OLD_MODIFY, "26.1");
        t.limitRedirectToModsBefore(EVENT, "modifyMatching", OLD_MATCHING, "26.1");
    }
}
