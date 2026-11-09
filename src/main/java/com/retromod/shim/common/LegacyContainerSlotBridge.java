/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;

/** Keeps a 1.21 mod's ItemContainerContents slots holding stacks after 26.1 made them hold templates. */
public final class LegacyContainerSlotBridge {

    static final String CONTENTS = "net/minecraft/world/item/component/ItemContainerContents";
    static final String SLOT = CONTENTS + "$Slot";
    static final String REBUILT = RebuiltApiClasses.ROOT + "minecraft/world/item/component/LegacyContainerSlot";
    static final String FIRST_HOST_WITHOUT = "26.1";

    private LegacyContainerSlotBridge() {}

    /** Registered for 26.1 and newer hosts, for mods built before 26.1. */
    public static void register(RetromodTransformer t) {
        RebuiltApiClasses.registerTree(t, REBUILT);
        // Sophisticated Core opens the slot record and its two list methods with its access transformer and
        // builds its large-stack codec from them while it registers its data components.
        t.registerClassRedirect(SLOT, REBUILT);
        t.limitClassRedirectToModsBefore(SLOT, FIRST_HOST_WITHOUT);
        t.registerMethodRedirect(CONTENTS, "asSlots", "()Ljava/util/List;", REBUILT, "asSlots",
                "(L" + CONTENTS + ";)Ljava/util/List;", true);
        t.limitRedirectToModsBefore(CONTENTS, "asSlots", "()Ljava/util/List;", FIRST_HOST_WITHOUT);
        t.registerMethodRedirect(CONTENTS, "fromSlots", "(Ljava/util/List;)L" + CONTENTS + ";", REBUILT, "fromSlots",
                "(Ljava/util/List;)L" + CONTENTS + ";");
        t.limitRedirectToModsBefore(CONTENTS, "fromSlots", "(Ljava/util/List;)L" + CONTENTS + ";", FIRST_HOST_WITHOUT);
    }
}
