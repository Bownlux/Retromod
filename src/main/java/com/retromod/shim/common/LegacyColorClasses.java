/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.common.embedded.LegacyLog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/** Minecraft's old block and item color classes, rebuilt inside each old mod, with the old calls on the host's block colors. */
public final class LegacyColorClasses {

    public static final String OLD_BLOCK_COLOR = "net/minecraft/client/color/block/BlockColor";
    public static final String OLD_ITEM_COLOR = "net/minecraft/client/color/item/ItemColor";
    public static final String OLD_ITEM_COLORS = "net/minecraft/client/color/item/ItemColors";
    public static final String BLOCK_COLORS = "net/minecraft/client/color/block/BlockColors";
    public static final String MINECRAFT = "net/minecraft/client/Minecraft";

    public static final String ROOT = RebuiltApiClasses.ROOT + "minecraft/client/color/";
    public static final String BLOCK_COLOR = ROOT + "BlockColor";
    public static final String ITEM_COLOR = ROOT + "ItemColor";
    public static final String ITEM_COLORS = ROOT + "ItemColors";
    /** The helpers that take the old BlockColors calls, with the host's block colors first. */
    public static final String CALLS = ROOT + "LegacyBlockColorCalls";

    private static final String BLOCK = "[Lnet/minecraft/world/level/block/Block;";
    private static final String LEGACY_LOG = LegacyLog.class.getName().replace('.', '/');
    public static final String COLOR_ARGS = "Lnet/minecraft/world/level/block/state/BlockState;"
            + "Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;I";
    /** The first version whose item renderer drew a tint with the alpha its handler answered; 1.20.1's drew every tint opaque. */
    public static final String FIRST_ITEM_ALPHA_VERSION = "1.21";
    private static final String ITEM_REGISTER = "(L" + ITEM_COLOR + ";[Lnet/minecraft/world/level/ItemLike;)V";

    /** Each removed class an old mod names, and the class rebuilt in its place. */
    static final Map<String, String> REBUILT = rebuilt();

    private LegacyColorClasses() {}

    private static Map<String, String> rebuilt() {
        Map<String, String> names = new LinkedHashMap<>();
        names.put(OLD_BLOCK_COLOR, BLOCK_COLOR);
        names.put(OLD_ITEM_COLOR, ITEM_COLOR);
        names.put(OLD_ITEM_COLORS, ITEM_COLORS);
        return names;
    }

    /** Rebuilds the color classes and sends the old calls to them, for mods built before {@code firstVersionWithout}. */
    public static void register(RetromodTransformer t, String firstVersionWithout) {
        RebuiltApiClasses.registerTree(t, CALLS);
        RebuiltApiClasses.registerTree(t, ITEM_COLORS);
        // The rebuilt classes note what they could not do through the game log.
        SyntheticEmbedder.registerClassResource(t, LEGACY_LOG, LegacyLog.class);
        // Class redirects first, so the member redirects below also match the calls once the classes are renamed.
        REBUILT.forEach((old, rebuilt) -> {
            t.registerClassRedirect(old, rebuilt);
            // A mod built for a host that has these names again keeps them.
            t.limitClassRedirectToModsBefore(old, firstVersionWithout);
        });
        // BlockColors stays on the host and now takes one tint source per tint index.
        String colors = "L" + BLOCK_COLORS + ";";
        String register = "(L" + OLD_BLOCK_COLOR + ";" + BLOCK + ")V";
        t.registerMethodRedirect(BLOCK_COLORS, "register", register, CALLS, "register",
                "(" + colors + "L" + BLOCK_COLOR + ";" + BLOCK + ")V", true);
        t.limitRedirectToModsBefore(BLOCK_COLORS, "register", register, firstVersionWithout);
        // BlockColors.getColor became getTintSource, which answers null past a block's last tint index.
        String getColor = "(" + COLOR_ARGS + ")I";
        String callsGetColor = "(" + colors + COLOR_ARGS + ")I";
        t.registerMethodRedirect(BLOCK_COLORS, "getColor", getColor, CALLS, "getColor", callsGetColor, true);
        t.limitRedirectToModsBefore(BLOCK_COLORS, "getColor", getColor, firstVersionWithout);
        // Before 1.21 the call answered RGB. The transform repeats until nothing changes, so an older mod's call takes
        // this second step to the helper that answers it that way.
        t.registerMethodRedirect(CALLS, "getColor", callsGetColor, CALLS, "getColorRgb", callsGetColor);
        t.limitRedirectToModsBefore(CALLS, "getColor", callsGetColor, FIRST_ITEM_ALPHA_VERSION);
        // An older mod's item colors are drawn opaque, as its own item renderer drew them.
        registerItemColorsOwner(t, ITEM_COLORS);
        // Minecraft.getItemColors() went with ItemColors; item colors now live in the rebuilt table every copy shares.
        String getItemColors = "()L" + OLD_ITEM_COLORS + ";";
        t.registerMethodRedirect(MINECRAFT, "getItemColors", getItemColors, ITEM_COLORS, "legacyOf",
                "(L" + MINECRAFT + ";)L" + ITEM_COLORS + ";", true);
        t.limitRedirectToModsBefore(MINECRAFT, "getItemColors", getItemColors, firstVersionWithout);
    }

    /** Sends an older mod's {@code register(ItemColor, ItemLike...)} on {@code owner}, a rebuilt class, to {@code registerOpaque}. */
    public static void registerItemColorsOwner(RetromodTransformer t, String owner) {
        // Keyed on the rebuilt class, which the class redirect already put in the call, so the version limit holds.
        t.registerMethodRedirect(owner, "register", ITEM_REGISTER, owner, "registerOpaque", ITEM_REGISTER);
        t.limitRedirectToModsBefore(owner, "register", ITEM_REGISTER, FIRST_ITEM_ALPHA_VERSION);
    }

    /**
     * A mixin target among the removed color classes whose name now leads to the copy rebuilt inside the mod; null for none.
     * Such a mixin, like Zeta's accessor of the old item color map, would land on the mod's own copy, which has no such member.
     */
    public static String removedMixinTarget(List<String> targets, UnaryOperator<String> redirectForCurrentMod) {
        for (String target : targets) {
            if (REBUILT.containsKey(target) && redirectForCurrentMod.apply(target).startsWith(ROOT)) return target;
        }
        return null;
    }
}
