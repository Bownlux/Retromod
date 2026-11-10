/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.shim.common.LegacyItemRendererBridge;
import com.retromod.shim.common.RebuiltApiClasses;
import org.objectweb.asm.tree.ClassNode;

import java.util.List;
import java.util.function.Function;

/** Registers the rebuilt item renderers (BlockEntityWithoutLevelRenderer) on Forge hosts that draw items through item models. */
public final class LegacyForgeItemRendererBridge {

    static final String OLD_RENDERER = LegacyItemRendererBridge.OLD_RENDERER;
    static final String CLIENT_ITEM_EXTENSIONS = "net/minecraftforge/client/extensions/common/IClientItemExtensions";
    static final String GET_CUSTOM_RENDERER = LegacyItemRendererBridge.GET_CUSTOM_RENDERER;
    static final String OLD_GETTER_DESC = LegacyItemRendererBridge.OLD_GETTER_DESC;
    /** Where Minecraft keeps its item model type ids, in a private static field the install adds to. */
    static final String ITEM_MODELS = "net/minecraft/client/renderer/item/ItemModels";

    static final String ROOT = RebuiltApiClasses.ROOT + "forge/client/renderer/";
    /** The rebuilt class whose {@code install()} adds each mod's item model type; Retromod's item definitions name it only where it runs. */
    public static final String INSTALLER = ROOT + "LegacyItemRenderers";

    private LegacyForgeItemRendererBridge() {}

    /** Registers the rebuilt item renderers where the Forge host lacks the old base and getCustomRenderer; offline, a Forge target from 26.2. */
    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read);
    }

    static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        // Forge 26.2 draws items through item models and dropped getCustomRenderer; a host with either needs nothing.
        if (!ForgeHostShape.matches(List.of(CLIENT_ITEM_EXTENSIONS), List.of(OLD_RENDERER), ForgeHostShape.FIRST_CHECKED_VERSION, host)
                || !ForgeHostMembers.lacksMethod(CLIENT_ITEM_EXTENSIONS, GET_CUSTOM_RENDERER, OLD_GETTER_DESC,
                        ForgeHostShape.FIRST_CHECKED_VERSION, host)) {
            return;
        }
        LegacyItemRendererBridge.register(t, List.of(CLIENT_ITEM_EXTENSIONS), ForgeHostShape.FIRST_CHECKED_VERSION, null);

        // An item definition naming a model type nobody registered fails whole, and the item shows the missing model.
        // A host whose type ids sit elsewhere gets no install, so Retromod writes no definition naming one.
        if (!LegacyForgeColorBridge.lateBoundIdsReachable(host, ITEM_MODELS)) return;
        // Each jar's copy adds its mods' item model type while they construct.
        LegacyClientRenderBootstrapInjector.addPart(t, new LegacyClientRenderBootstrapInjector.Part("item-renderers",
                List.of(OLD_RENDERER), INSTALLER));
    }
}
