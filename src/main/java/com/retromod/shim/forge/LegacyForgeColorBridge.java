/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.common.LegacyColorClasses;
import com.retromod.shim.common.RebuiltApiClasses;
import com.retromod.shim.common.embedded.LegacyLog;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.util.List;
import java.util.function.Function;

/** Rebuilds Forge 1.20.1's block and item colors inside each old mod, on Forge hosts that dropped the item color event. */
public final class LegacyForgeColorBridge {

    static final String OLD_BLOCK_COLOR = LegacyColorClasses.OLD_BLOCK_COLOR;
    static final String OLD_ITEM_COLOR = LegacyColorClasses.OLD_ITEM_COLOR;
    static final String OLD_ITEM_COLORS = LegacyColorClasses.OLD_ITEM_COLORS;
    static final String OLD_ITEM_EVENT = "net/minecraftforge/client/event/RegisterColorHandlersEvent$Item";
    /** The host's block color event, which Forge 26.2 kept as a record taking tint sources. */
    static final String BLOCK_EVENT = "net/minecraftforge/client/event/RegisterColorHandlersEvent$Block";
    static final String BLOCK_COLORS = LegacyColorClasses.BLOCK_COLORS;
    /** Where Minecraft keeps its item tint type ids, in a private static field the install adds to. */
    static final String ITEM_TINT_SOURCES = "net/minecraft/client/color/item/ItemTintSources";
    static final String ID_MAPPER = "net/minecraft/util/ExtraCodecs$LateBoundIdMapper";
    private static final String ID_MAPPER_DESC = "L" + ID_MAPPER + ";";
    private static final String PUT_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)" + ID_MAPPER_DESC;

    static final String ROOT = RebuiltApiClasses.ROOT + "forge/client/color/";
    /** The helper that takes the old register call on the host's block color event. */
    static final String BLOCK_HELPERS = ROOT + "LegacyBlockColors";
    static final String ITEM_EVENT = RebuiltApiClasses.ROOT + "forge/client/event/LegacyItemColorsEvent";
    /** The rebuilt class whose {@code install()} sets up item colors; Retromod's item definitions name its tint type only where it runs. */
    public static final String INSTALLER = ROOT + "LegacyItemColors";

    private static final String BLOCK = "[Lnet/minecraft/world/level/block/Block;";
    private static final String LEGACY_LOG = LegacyLog.class.getName().replace('.', '/');

    private LegacyForgeColorBridge() {}

    /** Registers the rebuilt colors where the Forge host has the block color event and no item one; offline, a Forge target from 26.2. */
    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read);
    }

    static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        // Forge 26.2 keeps only the block color event and takes tint sources there; a host with the item event needs nothing.
        if (!ForgeHostShape.matches(List.of(BLOCK_EVENT), List.of(OLD_ITEM_EVENT), ForgeHostShape.FIRST_CHECKED_VERSION, host)) {
            return;
        }
        RebuiltApiClasses.registerTree(t, BLOCK_HELPERS);
        RebuiltApiClasses.registerTree(t, ITEM_EVENT);
        // The rebuilt classes note what they could not do through the game log.
        SyntheticEmbedder.registerClassResource(t, LEGACY_LOG, LegacyLog.class);

        // The color classes, BlockColors' old calls and Minecraft.getItemColors() are rebuilt the same way for NeoForge.
        LegacyColorClasses.register(t, ForgeHostShape.FIRST_CHECKED_VERSION);
        t.registerClassRedirect(OLD_ITEM_EVENT, ITEM_EVENT);
        t.limitClassRedirectToModsBefore(OLD_ITEM_EVENT, ForgeHostShape.FIRST_CHECKED_VERSION);
        LegacyColorClasses.registerItemColorsOwner(t, ITEM_EVENT);
        // The block color event stays on the host and now takes one tint source per tint index.
        String register = "(L" + OLD_BLOCK_COLOR + ";" + BLOCK + ")V";
        t.registerMethodRedirect(BLOCK_EVENT, "register", register, BLOCK_HELPERS, "register",
                "(L" + BLOCK_EVENT + ";L" + LegacyColorClasses.BLOCK_COLOR + ";" + BLOCK + ")V", true);
        t.limitRedirectToModsBefore(BLOCK_EVENT, "register", register, ForgeHostShape.FIRST_CHECKED_VERSION);

        // The item color event keeps Forge 1.20.1's priority and listener order rules in Retromod's dispatch.
        LegacyEventBaseAdapter.dispatchAsLegacy(t, List.of(OLD_ITEM_EVENT));
        // An item definition naming a tint type nobody registered fails whole, and the item shows the missing model.
        // A host whose tint type ids sit elsewhere gets no install, so Retromod writes no definition naming one.
        if (!itemTintTypesReachable(host)) return;
        // Each jar's copy lists itself for the item color post and adds its mods' tint types while they construct.
        // The item color name also matches ItemColors, which Minecraft.getItemColors() callers name.
        LegacyClientRenderBootstrapInjector.addPart(t, new LegacyClientRenderBootstrapInjector.Part("item-colors",
                List.of(OLD_ITEM_EVENT, OLD_ITEM_COLOR), INSTALLER));
    }

    /** Whether the install can add tint types: one static id mapper on ItemTintSources with a put(Object, Object). */
    static boolean itemTintTypesReachable(Function<String, ClassNode> host) {
        return lateBoundIdsReachable(host, ITEM_TINT_SOURCES);
    }

    /** Whether an install can add type ids to {@code owner}: one static id mapper on it with a put(Object, Object). */
    static boolean lateBoundIdsReachable(Function<String, ClassNode> host, String owner) {
        ClassNode types = host.apply(owner);
        // Offline and on a dedicated server no client class can be read, so the bridge's version rule stands.
        if (types == null) return true;
        long mappers = types.fields == null ? 0 : types.fields.stream()
                .filter(field -> (field.access & Opcodes.ACC_STATIC) != 0 && field.desc.equals(ID_MAPPER_DESC)).count();
        if (mappers != 1) return false;
        ClassNode mapper = host.apply(ID_MAPPER);
        return mapper != null && mapper.methods != null
                && mapper.methods.stream().anyMatch(method -> method.name.equals("put") && method.desc.equals(PUT_DESC));
    }
}
