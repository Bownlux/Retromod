/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.common.embedded.LegacyLog;
import org.objectweb.asm.commons.Remapper;

import java.util.List;
import java.util.function.UnaryOperator;

/** Rebuilds Minecraft 1.20.1's item renderer (BlockEntityWithoutLevelRenderer) inside each old mod, for the Forge and NeoForge bridges. */
public final class LegacyItemRendererBridge {

    public static final String OLD_RENDERER = "net/minecraft/client/renderer/BlockEntityWithoutLevelRenderer";
    public static final String GET_CUSTOM_RENDERER = "getCustomRenderer";
    public static final String OLD_GETTER_DESC = "()L" + OLD_RENDERER + ";";

    public static final String ROOT = RebuiltApiClasses.ROOT + "minecraft/client/renderer/";
    public static final String RENDERER = ROOT + "LegacyItemRenderer";
    public static final String CALLS = ROOT + "LegacyItemRendererCalls";
    public static final String MODEL = ROOT + "LegacyItemRendererModel";
    /** The path of the item model type each installing mod's namespace gets; Retromod's item definitions name it. */
    public static final String MODEL_TYPE_PATH = "retromod_legacy_item";
    /** The getter helper takes the receiver as Object, so Forge's and NeoForge's IClientItemExtensions both pass. */
    public static final String HELPER_DESC = "(Ljava/lang/Object;)L" + RENDERER + ";";

    private static final String LEGACY_LOG = LegacyLog.class.getName().replace('.', '/');

    private LegacyItemRendererBridge() {}

    /**
     * Registers the rebuilt base, item model and getter helper. {@code extensionOwners} are the IClientItemExtensions names
     * old getter calls use; {@code names} rewrites the rebuilt classes for a host, or is null.
     */
    public static void register(RetromodTransformer t, List<String> extensionOwners, String firstVersionWithout, Remapper names) {
        // The model's tree reaches every vanilla-shaped class, so a loader's installer registered later finds them all here.
        for (String root : List.of(RENDERER, CALLS, MODEL)) RebuiltApiClasses.registerTree(t, root, names);
        // The rebuilt classes note what they could not do through the game log.
        SyntheticEmbedder.registerClassResource(t, LEGACY_LOG, LegacyLog.class);

        // Class redirect first, so the getter redirects below also match calls once the class is renamed. The rebuilt
        // base keeps the old constructor, so a subclass's super(dispatcher, models) still links.
        t.registerClassRedirect(OLD_RENDERER, RENDERER);
        // A mod built for a host that has the name again keeps it.
        t.limitClassRedirectToModsBefore(OLD_RENDERER, firstVersionWithout);
        for (String owner : extensionOwners) {
            // The host interface lost getCustomRenderer, so a call on it finds the mod's own method by name.
            t.registerMethodRedirect(owner, GET_CUSTOM_RENDERER, OLD_GETTER_DESC, CALLS, GET_CUSTOM_RENDERER, HELPER_DESC, true);
            t.limitRedirectToModsBefore(owner, GET_CUSTOM_RENDERER, OLD_GETTER_DESC, firstVersionWithout);
        }
        // An override's super call gets the old default, not the override again.
        t.registerSuperCallHelper(CALLS, GET_CUSTOM_RENDERER, HELPER_DESC, "superGetCustomRenderer");
    }

    /** A mixin target that is the removed item renderer, which the redirect would land on the mod's own rebuilt copy; null for none. */
    public static String removedMixinTarget(List<String> targets, UnaryOperator<String> redirectForCurrentMod) {
        // Vanilla draws no item through that copy, so a mixin into it could only fail or do nothing.
        for (String target : targets) {
            if (target.equals(OLD_RENDERER) && redirectForCurrentMod.apply(target).equals(RENDERER)) return target;
        }
        return null;
    }
}
