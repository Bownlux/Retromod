/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.common.RebuiltApiClasses;
import com.retromod.shim.common.embedded.LegacyLog;

import java.util.List;

/** Gives old mods Retromod's rebuilt event base on Forge hosts whose EventBus 7 removed Forge 1.20.1's {@code Event}. */
public final class LegacyEventBaseBridge {

    /** Turns on {@link LegacyEventBaseAdapter} before the remap of every class. */
    public static final String FLAG = "forge-legacy-event-base";

    static final String OLD_EVENT = LegacyEventBaseAdapter.EVENT;
    static final String OLD_RESULT = OLD_EVENT + "$Result";
    static final String OLD_HAS_RESULT = OLD_EVENT + "$HasResult";
    static final String RESULT = "net/minecraftforge/common/util/Result";
    static final String HAS_RESULT = "net/minecraftforge/common/util/HasResult";
    static final String BUS_GROUP = "net/minecraftforge/eventbus/api/bus/BusGroup";
    static final String LEGACY_HAS_RESULT = RebuiltApiClasses.ROOT + "forge/eventbus/LegacyHasResult";
    static final String LEGACY_SUBSCRIBE = RebuiltApiClasses.ROOT + "forge/eventbus/LegacySubscribe";
    /** The dispatcher the bus bridge hands old events, handlers and listeners to. */
    static final String LEGACY_EVENTS = RebuiltApiClasses.ROOT + "forge/eventbus/LegacyEvents";
    private static final String LEGACY_LOG = LegacyLog.class.getName().replace('.', '/');

    private LegacyEventBaseBridge() {}

    /** Registers the rebuilt base, its helpers and the old result names. */
    public static void register(RetromodTransformer t) {
        if (!hostLacksEventBase()) return;
        // The rebuilt base names the LegacyEventPriority stand-in, so ForgeEventBusSynthetics.register runs first.
        // Both enums name ALLOW, DEFAULT and DENY, and javac's switch maps read ordinal() when they load.
        t.registerClassRedirect(OLD_RESULT, RESULT);
        // The marker LegacyEvent.hasResult() looks for, kept at run time where the old one is missing.
        t.registerClassRedirect(OLD_HAS_RESULT, LEGACY_HAS_RESULT);
        for (String root : List.of(LegacyEventBaseAdapter.LEGACY_EVENT, LegacyEventBaseAdapter.LEGACY_GENERIC_EVENT,
                LegacyEventBaseAdapter.ACCESS, LEGACY_HAS_RESULT, LEGACY_SUBSCRIBE, LEGACY_EVENTS)) {
            RebuiltApiClasses.registerTree(t, root);
        }
        // LegacyEventAccess notes calls it cannot answer yet through the game log.
        SyntheticEmbedder.registerClassResource(t, LEGACY_LOG, LegacyLog.class);
        t.enablePostProcess(FLAG);
    }

    /** Whether the Forge host runs EventBus 7 without the old base; offline, a Forge target from 26.2. */
    static boolean hostLacksEventBase() {
        return ForgeHostShape.matches(List.of(BUS_GROUP, RESULT, HAS_RESULT), List.of(OLD_EVENT, OLD_RESULT));
    }

    /** The result enum old {@code Event.Result} code reaches on this host: Forge 26.2's own where the old one is gone. */
    public static String resultEnum() {
        return hostLacksEventBase() ? RESULT : OLD_RESULT;
    }

    /** A class Retromod generates on the old base, moved onto the rebuilt one where the host lacks the old base. */
    public static byte[] adaptGenerated(byte[] classBytes) {
        return hostLacksEventBase() ? LegacyEventBaseAdapter.apply(classBytes) : classBytes;
    }
}
